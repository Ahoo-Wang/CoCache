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

package me.ahoo.cocache.spring.tx

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.spring.cache.CoCacheSpringCache
import org.apache.commons.logging.LogFactory
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import org.springframework.cache.Cache as SpringCache

/**
 * CoCache 的事务感知装饰器（RFC 0005 §4.9 U3），取代 Spring 的 `TransactionAwareCacheDecorator`。
 * 事务同步激活时（`TransactionSynchronizationManager.isSynchronizationActive()`，与 Spring 的装饰器相同）：
 *
 * - [evict]（`@CacheEvict`）登记到当前事务（与 [TransactionalInvalidator] 同一份登记）：提交、回滚与回滚到 savepoint 时都失效。
 *   Spring 的装饰器只在提交之后失效：同一事务内的 `@Cacheable(sync = true)` 读到未提交的行时，回滚之后它留到 `ttl`。
 * - [evictIfPresent]（`@CacheEvict(beforeInvocation = true)`）立即失效，同时登记（[CoCacheSpringCache.evictIfPresent]）。
 * - [put]（`@CachePut`）推迟到提交之后；回滚、回滚到 savepoint（只丢弃 savepoint 之后的）时丢弃。之后对同一 key 登记的失效
 *   取代它。`put(key, null)` 映射为失效，按 [evict] 登记。
 * - [clear]（`@CacheEvict(allEntries = true)`）推迟到提交之后；[invalidate]（`allEntries` 且 `beforeInvocation = true`）按
 *   Spring 的契约立即执行。两者都只清空本节点的本地副本。
 * - 读取与 [putIfAbsent] 直接委托。
 *
 * 事务结束之后才执行的操作失败时交给 [errorHandler]（与非事务路径上 Spring 的缓存通知一致，S18），而不是在提交之后
 * 抛给以为操作失败、准备重试的调用方。处理器抛出（如默认的 `SimpleCacheErrorHandler` 重新抛出）时只记录日志：
 * 数据源已经提交，抛出还会让 Spring 跳过其余同步的 `afterCommit`。
 *
 * 没有事务同步时一律直接执行，失败照常抛给调用方（由缓存通知交给 `CacheErrorHandler`）。
 *
 * @param errorHandler 事务结束之后的失败的处理器，每次失败时取得：starter 传入缓存通知的处理器
 */
class TransactionAwareSpringCache(
    val targetCache: CoCacheSpringCache,
    private val errorHandler: Supplier<CacheErrorHandler>,
) : SpringCache {

    @Suppress("UNCHECKED_CAST")
    private val cache = targetCache.nativeCache as Cache<Any, Any>

    private val evictFailures = InvalidationFailureHandler { error, keys ->
        val exception = error.asRuntime()
        for (key in keys) {
            handle("evict") { it.handleCacheEvictError(exception, this, key) }
        }
    }

    private val nullPutFailures = InvalidationFailureHandler { error, keys ->
        val exception = error.asRuntime()
        for (key in keys) {
            handle("put") { it.handleCachePutError(exception, this, key, null) }
        }
    }

    override fun getName(): String = targetCache.name

    override fun getNativeCache(): Any = targetCache.nativeCache

    override fun get(key: Any): SpringCache.ValueWrapper? = targetCache.get(key)

    override fun <T : Any> get(key: Any, type: Class<T>?): T? = targetCache.get(key, type)

    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? = targetCache.get(key, valueLoader)

    override fun retrieve(key: Any): CompletableFuture<*>? = targetCache.retrieve(key)

    override fun <T : Any> retrieve(key: Any, valueLoader: Supplier<CompletableFuture<T>>): CompletableFuture<T> =
        targetCache.retrieve(key, valueLoader)

    override fun putIfAbsent(key: Any, value: Any?): SpringCache.ValueWrapper? = targetCache.putIfAbsent(key, value)

    @Suppress("TooGenericExceptionCaught")
    override fun put(key: Any, value: Any?) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            targetCache.put(key, value)
            return
        }
        if (value == null) {
            TransactionalOperations.current().invalidate(cache, listOf(key), nullPutFailures)
            return
        }
        TransactionalOperations.current().afterCommit(cache, key) {
            try {
                targetCache.put(key, value)
            } catch (e: Exception) {
                handle("put") { it.handleCachePutError(e.asRuntime(), this, key, value) }
            }
        }
    }

    override fun evict(key: Any) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            targetCache.evict(key)
            return
        }
        TransactionalOperations.current().invalidate(cache, listOf(key), evictFailures)
    }

    override fun evictIfPresent(key: Any): Boolean = targetCache.evictIfPresent(key, evictFailures)

    @Suppress("TooGenericExceptionCaught")
    override fun clear() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            targetCache.clear()
            return
        }
        TransactionalOperations.current().afterCommit(cache, null) {
            try {
                targetCache.clear()
            } catch (e: Exception) {
                handle("clear") { it.handleCacheClearError(e.asRuntime(), this) }
            }
        }
    }

    override fun invalidate(): Boolean = targetCache.invalidate()

    /**
     * 把事务结束之后的失败交给 [errorHandler]；处理器抛出时只记录日志（见类注释），不记录 key。
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun handle(operation: String, block: (CacheErrorHandler) -> Unit) {
        try {
            block(errorHandler.get())
        } catch (e: Exception) {
            log.error(
                "Cache[$name]: deferred $operation failed after the transaction ended, and the CacheErrorHandler rethrew it. " +
                    "Not rethrown: the data source has already committed or rolled back.",
                e
            )
        }
    }

    private fun Exception.asRuntime(): RuntimeException = this as? RuntimeException ?: IllegalStateException(this)

    private companion object {
        private val log = LogFactory.getLog(TransactionAwareSpringCache::class.java)
    }
}
