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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.core.CacheAdmin
import org.springframework.cache.support.SimpleValueWrapper
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.function.Supplier
import org.springframework.cache.Cache as SpringCache

/**
 * 把 6.0 的 [Cache] 适配为 Spring [SpringCache]（RFC 0003 §4.12）。
 *
 * - 负缓存视为未命中：否则 `@Cacheable` 遇到负缓存将永远不调用方法。
 * - `put(key, null)`（如 `@CachePut` 返回 null）映射为失效。
 * - [get] (key, valueLoader) 按 key 合并并发加载，满足 `@Cacheable(sync = true)`。
 * - [clear] 只清空本节点的本地副本：L1 是共享存储，不支持整体清空。
 * - [retrieve] 在 [executor] 上执行阻塞查找，不占用调用方线程。
 */
class CoCacheSpringCache(
    private val cache: Cache<Any, Any>,
    private val admin: CacheAdmin?,
    private val executor: Executor,
) : SpringCache {
    private val loads = ConcurrentHashMap<Any, CompletableFuture<Any?>>()

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

    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? {
        get(key)?.let { return it.get() as T? }
        val call = CompletableFuture<Any?>()
        val existing = loads.putIfAbsent(key, call)
        if (existing != null) {
            return try {
                existing.join() as T?
            } catch (e: java.util.concurrent.CompletionException) {
                throw e.cause ?: e
            }
        }
        try {
            val value = get(key)?.get() ?: try {
                valueLoader.call().also { put(key, it) }
            } catch (error: Throwable) {
                throw SpringCache.ValueRetrievalException(key, valueLoader, error)
            }
            loads.remove(key, call)
            call.complete(value)
            return value as T?
        } catch (error: Throwable) {
            loads.remove(key, call)
            call.completeExceptionally(error)
            throw error
        }
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

    override fun retrieve(key: Any): CompletableFuture<*>? {
        return CompletableFuture.supplyAsync({ get(key) }, executor)
    }

    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    override fun <T : Any> retrieve(key: Any, valueLoader: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        return CompletableFuture.supplyAsync({ get(key) }, executor).thenCompose { wrapper ->
            if (wrapper != null) {
                return@thenCompose CompletableFuture.completedFuture(wrapper.get() as T)
            }
            try {
                valueLoader.get().thenApply { value ->
                    put(key, value)
                    value
                }
            } catch (error: Throwable) {
                CompletableFuture.failedFuture(error)
            }
        }
    }
}
