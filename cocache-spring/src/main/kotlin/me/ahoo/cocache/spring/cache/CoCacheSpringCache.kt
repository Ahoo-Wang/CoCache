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
import org.springframework.cache.support.SimpleValueWrapper
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Supplier
import org.springframework.cache.Cache as SpringCache

/**
 * 把 [Cache] 适配为 Spring [SpringCache]（RFC 0003 §4.12）。
 *
 * - 负缓存视为未命中：否则 `@Cacheable` 遇到负缓存将永远不调用方法。
 * - `put(key, null)`（如 `@CachePut` 返回 null）映射为失效。
 * - [get] (key, valueLoader) 经引擎受 lease 保护地回源并按 key 合并并发加载，满足 `@Cacheable(sync = true)`。
 *   不带 `sync` 的 `@Cacheable` 由 Spring 执行“读未命中 → 调用方法 → put”，put 是无条件写入，
 *   回源期间发生的失效可能被覆盖；需要上述一致性保证时请使用 `sync = true`。
 * - [clear] 只清空本节点的本地副本：L1 是共享存储，不支持整体清空。
 * - [retrieve] 经缓存的异步视图（RFC 0004 §6）：L2 命中返回已完成的 future，未命中不占用任何线程等待。
 */
class CoCacheSpringCache(
    private val cache: Cache<Any, Any>,
    private val admin: CacheAdmin?,
) : SpringCache {
    /**
     * 6.3 起 [retrieve] 不再使用执行器（经异步视图，不阻塞任何线程）：[executor] 被忽略，7.0 删除此构造函数。
     */
    @Deprecated(
        "Since 6.3, retrieve uses the async view and no executor; the executor is ignored.",
        ReplaceWith("CoCacheSpringCache(cache, admin)")
    )
    @Suppress("UNUSED_PARAMETER")
    constructor(cache: Cache<Any, Any>, admin: CacheAdmin?, executor: Executor) : this(cache, admin)

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

    override fun clear() {
        admin?.clearLocal()
    }

    /**
     * 经异步视图读取：命中（含负缓存视为未命中，以 `null` 完成）不切换线程；未命中经引擎回源，不阻塞任何线程。
     */
    override fun retrieve(key: Any): CompletableFuture<*>? {
        return cache.async().lookup(key).thenApply { lookup ->
            (lookup as? Lookup.Present)?.let { SimpleValueWrapper(it.value) }
        }
    }

    /**
     * 响应式 / `CompletableFuture` 方法上的 `@Cacheable(sync = true)`：与 [get] (key, valueLoader) 一样经引擎受 lease
     * 与失效戳保护的路径回源，而不是“读不到就 put”。经异步视图，没有线程在等待 [valueLoader]：它在调用方线程或
     * CoCache 的异步执行器上被调用（看不到调用方的 ThreadLocal），返回的 future 以原始异常失败。
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> retrieve(key: Any, valueLoader: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        return cache.async().get(key, AsyncCacheLoader { _ -> valueLoader.get() }) as CompletableFuture<T>
    }
}
