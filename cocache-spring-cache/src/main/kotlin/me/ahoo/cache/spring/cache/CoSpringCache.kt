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

package me.ahoo.cache.spring.cache

import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.NamedCache
import me.ahoo.cache.concurrent.SingleFlight
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.join.SimpleJoinCache
import me.ahoo.cache.proxy.CacheDelegated
import org.springframework.cache.support.SimpleValueWrapper
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Supplier
import org.springframework.cache.Cache as SpringCache

/**
 * 将 CoCache 适配为 Spring [SpringCache]。
 *
 * - 负缓存视为未命中：CoCache 的负缓存由 CacheSource 驱动，`put(key, null)` 写入负缓存但不会作为“缓存的 null”返回。
 * - [get] (key, valueLoader) 按 key 合并并发加载（满足 `@Cacheable(sync = true)` 的同步语义）。
 * - [retrieve] 在 [asyncExecutor] 上执行阻塞查找，不占用调用方线程。
 */
class CoSpringCache(
    override val cacheName: String,
    override val delegate: Cache<Any, Any?>,
    private val asyncExecutor: Executor = CoCacheManager.DEFAULT_ASYNC_EXECUTOR
) : NamedCache, SpringCache, CacheDelegated<Cache<Any, Any?>> {
    private val loads = SingleFlight<Any, Any?>()

    override fun getName(): String = cacheName

    override fun getNativeCache(): Any = delegate

    override fun get(key: Any): SpringCache.ValueWrapper? {
        val cacheValue = delegate.getCache(key) ?: return null
        if (cacheValue.isExpired || cacheValue.isMissing) {
            return null
        }
        return SimpleValueWrapper(cacheValue.value)
    }

    override fun <T : Any> get(key: Any, type: Class<T>?): T? {
        val value = get(key)?.get() ?: return null
        check(type == null || type.isInstance(value)) {
            "Cached value is not of required type [${type?.name}]: $value"
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Suppress("TooGenericExceptionCaught")
    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? {
        get(key)?.let {
            @Suppress("UNCHECKED_CAST")
            return it.get() as T?
        }
        @Suppress("UNCHECKED_CAST")
        return loads.execute(key) {
            get(key)?.get() ?: try {
                valueLoader.call().also { delegate[key] = it }
            } catch (error: Throwable) {
                throw SpringCache.ValueRetrievalException(key, valueLoader, error)
            }
        } as T?
    }

    override fun put(key: Any, value: Any?) {
        delegate[key] = value
    }

    override fun evict(key: Any) {
        delegate.evict(key)
    }

    /**
     * 只能清空本实例的 L2；L1 是共享存储，不支持整体清空。
     */
    override fun clear() {
        clearClientSide(delegate)
    }

    private fun clearClientSide(cache: Cache<*, *>) {
        when (cache) {
            is CacheDelegated<*> -> clearClientSide(cache.delegate)
            is CoherentCache<*, *> -> cache.configuration.clientSideCache.clear()
            is SimpleJoinCache<*, *, *, *> -> {
                clearClientSide(cache.firstCache)
                clearClientSide(cache.joinCache)
            }
        }
    }

    override fun retrieve(key: Any): CompletableFuture<*>? {
        return CompletableFuture.supplyAsync({ get(key) }, asyncExecutor)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun <T : Any> retrieve(key: Any, valueLoader: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        return CompletableFuture.supplyAsync({ get(key) }, asyncExecutor).thenCompose { valueWrapper ->
            if (valueWrapper != null) {
                @Suppress("UNCHECKED_CAST")
                return@thenCompose CompletableFuture.completedFuture(valueWrapper.get() as T)
            }
            try {
                valueLoader.get().thenApply {
                    delegate[key] = it
                    it
                }
            } catch (error: Throwable) {
                CompletableFuture.failedFuture(error)
            }
        }
    }
}
