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
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.proxy.CacheDelegated
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import org.springframework.cache.Cache as SpringCache

@Suppress("UNCHECKED_CAST")
class CoSpringCache(
    override val cacheName: String,
    override val delegate: Cache<Any, Any?>
) : NamedCache, SpringCache, CacheDelegated<Cache<Any, Any?>> {
    override fun getName(): String {
        return cacheName
    }

    override fun getNativeCache(): Any {
        return delegate
    }

    override fun get(key: Any): SpringCache.ValueWrapper? {
        val cacheValue = delegate.getCache(key) ?: return null
        return SpringCacheValueWrapper(cacheValue)
    }

    override fun <T : Any> get(key: Any, type: Class<T>?): T? {
        return delegate.get(key) as T?
    }

    override fun <T : Any> get(key: Any, valueLoader: Callable<T>): T? {
        val value = delegate.get(key)
        if (value != null) {
            return value as T
        }
        val loadedValue = valueLoader.call()
        delegate.set(key, loadedValue)
        return loadedValue
    }

    override fun put(key: Any, value: Any?) {
        delegate.set(key, value)
    }

    override fun evict(key: Any) {
        delegate.evict(key)
    }

    override fun clear() {
        if (delegate is ClientSideCache) {
            delegate.clear()
            return
        }
        if (delegate is CoherentCache) {
            delegate.clientSideCache.clear()
        }
    }

    override fun retrieve(key: Any): CompletableFuture<*>? {
        return CompletableFuture.supplyAsync {
            delegate.get(key)
        }
    }

    override fun <T : Any> retrieve(key: Any, valueLoader: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        return CompletableFuture.supplyAsync {
            delegate.get(key) as T?
        }.thenCompose<T?> {
            if (it != null) {
                return@thenCompose CompletableFuture.completedFuture(it)
            }
            valueLoader.get().thenApply {
                delegate.set(key, it)
                it
            }
        }
    }
}
