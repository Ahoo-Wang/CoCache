/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cocache.spring.cache

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.spring.tx.InvalidationFailureHandler
import me.ahoo.cocache.spring.tx.TransactionalInvalidator
import org.apache.commons.logging.LogFactory
import org.springframework.cache.support.SimpleValueWrapper
import org.springframework.util.ClassUtils
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import org.springframework.cache.Cache as SpringCache

/**
 * 把 [Cache] 适配为 Spring [SpringCache]（RFC 0005 §4.9）。
 *
 * - 负缓存视为未命中：否则 `@Cacheable` 遇到负缓存将永远不调用方法。
 * - `put(key, null)`（如 `@CachePut` 返回 null）映射为失效。
 * - [get] (key, valueLoader) 经引擎受 lease 保护地回源并按 key 合并并发加载，满足 `@Cacheable(sync = true)`。
 *   不带 `sync` 的 `@Cacheable` 由 Spring 执行“读未命中 → 调用方法 → put”，put 是无条件写入，
 *   回源期间发生的失效可能被覆盖；需要上述一致性保证时请使用 `sync = true`。
 * - [evictIfPresent]（`@CacheEvict(beforeInvocation = true)`）立即失效；事务内还登记到 [TransactionalInvalidator]，事务结束之后再失效。
 * - [clear]（`@CacheEvict(allEntries = true)`）只清空本节点的本地副本，每个缓存第一次调用时告警：L1 是共享存储，不支持整体清空。
 * - 本类不推迟任何操作：事务内的 `put` / `evict` 由 [me.ahoo.cocache.spring.tx.TransactionAwareSpringCache] 推迟与登记。
 * - [retrieve] 经缓存的异步视图（RFC 0004 §6）：L2 命中返回已完成的 future，未命中不占用任何线程等待；
 *   `sync = true` 的方法体在 [executor] 上执行。
 */
class CoCacheSpringCache(
    private val cache: Cache<Any, Any>,
    private val admin: CacheAdmin?,
    /**
     * 执行 [retrieve] (key, valueLoader) 的方法体（`valueLoader.get()`）。方法体可能先阻塞再返回 future
     * （如 `completedFuture(repository.find(id))`），因此不能在 CoCache 的异步执行器上执行；
     * [CoCacheSpringCacheManager] 传入运行时的阻塞执行器（[me.ahoo.cocache.core.CacheRuntime.blockingExecutor]）。不应排队。
     */
    private val executor: Executor,
) : SpringCache {

    private val clearWarned = AtomicBoolean()

    override fun getName(): String = cache.name

    override fun getNativeCache(): Any = cache

    override fun get(key: Any): SpringCache.ValueWrapper? {
        return when (val lookup = cache.lookup(key)) {
            is Lookup.Present -> SimpleValueWrapper(lookup.value)
            is Lookup.Absent -> null
        }
    }

    override fun <T : Any> get(key: Any, type: Class<T>?): T? {
        val value = get(key)?.get() ?: return null
        check(type == null || type.isInstance(value)) { "Cached value is not of required type [${type?.name}]: $value" }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /**
     * `@Cacheable(sync = true)`：经引擎的 [Cache.get] (key, loader) 回源，受 lease 与失效戳保护，并按 key 合并并发加载
     * （本节点经 SingleFlight，跨节点经 lease）。回源期间发生的失效不会被这次加载的结果覆盖。
     */
    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? {
        return cache.get(key) { _ ->
            try {
                valueLoader.call()
            } catch (error: Throwable) {
                throw SpringCache.ValueRetrievalException(key, valueLoader, error)
            }
        } as T?
    }

    override fun put(key: Any, value: Any?) {
        if (value == null) {
            cache.invalidate(key)
            return
        }
        cache.put(key, value)
    }

    override fun evict(key: Any) {
        cache.invalidate(key)
    }

    /**
     * `@CacheEvict(beforeInvocation = true)` 调用它，事务装饰器（[me.ahoo.cocache.spring.tx.TransactionAwareSpringCache]）也委托它：
     * 按 Spring 的契约立即失效。事务内只在提交之前失效会丢失失效：提交之前并发的读穿透把已提交的旧值写回 L1，
     * 提交之后再没有失效（G2）。因此事务内同时登记到 [TransactionalInvalidator]，提交、回滚与回滚到 savepoint 时再失效；
     * 多余的失效无害。先登记再失效：立即失效抛出、被 `CacheErrorHandler` 吞掉时，事务结束之后的失效照常执行。
     * 不确定之前是否存在，返回 `false`。
     */
    override fun evictIfPresent(key: Any): Boolean = evictIfPresent(key, null)

    /**
     * 同 [evictIfPresent]；事务结束之后再失效的失败交给 [onFailure]（为 `null` 时按 [TransactionalInvalidator] 的规则抛出）。
     */
    internal fun evictIfPresent(key: Any, onFailure: InvalidationFailureHandler?): Boolean {
        AFTER_TRANSACTION(cache, key, onFailure)
        cache.invalidate(key)
        return false
    }

    /**
     * 只清空本节点的本地副本（L2）：L1 是共享存储，8.0 不支持整体清空，Redis 与其它节点的副本保持不变。
     * `@CacheEvict(allEntries = true)` 很容易被当作全集群清空，所以每个缓存第一次调用时记录一次告警（U19）。
     * namespace 重置加入之后（RFC 0005 §4.9、Q12）改为全集群清空。
     */
    override fun clear() {
        if (clearWarned.compareAndSet(false, true)) {
            log.warn(
                "Cache[${cache.name}]: clear() (@CacheEvict(allEntries = true)) clears this instance's local copy (L2) only; " +
                    "Redis and the local copies on other instances keep their entries. Evict keys instead. " +
                    "This warning is logged once per cache."
            )
        }
        admin?.clearLocal()
    }

    /**
     * 经异步视图读取：命中（含负缓存视为未命中，以 `null` 完成）不切换线程；未命中经引擎回源，不阻塞任何线程。
     */
    override fun retrieve(key: Any): CompletableFuture<*>? {
        // 不用 thenApply：它会把失败包成 CompletionException，调用方应看到原始异常
        val result = CompletableFuture<SpringCache.ValueWrapper?>()
        attempt { cache.async().lookup(key) }.whenComplete { lookup, error ->
            if (error != null) {
                result.completeExceptionally(unwrap(error))
            } else {
                result.complete((lookup as? Lookup.Present)?.let { SimpleValueWrapper(it.value) })
            }
        }
        return result
    }

    /**
     * 响应式 / `CompletableFuture` 方法上的 `@Cacheable(sync = true)`：与 [get] (key, valueLoader) 一样经引擎受 lease
     * 与失效戳保护的路径回源，而不是“读不到就 put”。没有线程在等待 [valueLoader] 返回的 future；
     * 方法体本身（`valueLoader.get()`）派发到 [executor] 上执行（与 6.1 一样不在调用方线程上，看不到调用方的 ThreadLocal），
     * 先阻塞再返回的方法体因此不会占住异步执行器。返回的 future 以原始异常失败；[executor] 拒绝时以 RejectedExecutionException 失败。
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> retrieve(key: Any, valueLoader: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        val loader = AsyncCacheLoader<Any, Any> { _ -> dispatch(valueLoader) }
        return attempt { cache.async().get(key, loader) } as CompletableFuture<T>
    }

    /**
     * 不同步抛出：`cache.async()`（如自定义 [Cache] 的默认实现抛出 UnsupportedOperationException）或其方法同步抛出时，
     * 转为以该异常失败的 future。
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <R> attempt(block: () -> CompletableFuture<R>): CompletableFuture<R> {
        return try {
            block()
        } catch (error: Throwable) {
            CompletableFuture.failedFuture(error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> dispatch(valueLoader: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        executor.execute {
            try {
                valueLoader.get().whenComplete { value, error ->
                    if (error != null) {
                        result.completeExceptionally(unwrap(error))
                    } else {
                        result.complete(value)
                    }
                }
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result
    }

    private fun unwrap(error: Throwable): Throwable {
        return if (error is CompletionException) error.cause ?: error else error
    }

    internal companion object {
        private val log = LogFactory.getLog(CoCacheSpringCache::class.java)

        private const val TRANSACTION_SYNCHRONIZATION =
            "org.springframework.transaction.support.TransactionSynchronizationManager"

        private val AFTER_TRANSACTION = afterTransaction(CoCacheSpringCache::class.java.classLoader)

        /**
         * 事务内登记到事务结束之后再失效（没有事务时什么也不做）。spring-tx 是可选依赖（`compileOnly`）：
         * 不在 [classLoader] 中时返回空操作，不创建 [TransactionalInvalidator]。
         */
        internal fun afterTransaction(
            classLoader: ClassLoader?
        ): (Cache<Any, Any>, Any, InvalidationFailureHandler?) -> Unit {
            if (!ClassUtils.isPresent(TRANSACTION_SYNCHRONIZATION, classLoader)) {
                return { _, _, _ -> }
            }
            val invalidator = TransactionalInvalidator()
            return { cache, key, onFailure -> invalidator.invalidateAfterTransaction(cache, key, onFailure) }
        }
    }
}
