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

package me.ahoo.cache

import me.ahoo.cache.client.ClientSideCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.filter.NoOpKeyFilter
import java.util.concurrent.ConcurrentHashMap

class CacheManager(private val cacheEvictedEventBus: CacheEvictedEventBus) {

    private val caches = ConcurrentHashMap<String, CoherentCache<*, *>>()

    fun <K, V> getCache(cacheName: String): CoherentCache<K, V>? {
        @Suppress("UNCHECKED_CAST")
        return caches[cacheName] as CoherentCache<K, V>?
    }

    fun <K, V> createCache(cacheConfig: CacheConfig<K, V>): CoherentCache<K, V> {
        val cache = CoherentCache(
            cacheConfig.cacheName,
            cacheConfig.clientId,
            cacheConfig.keyConverter,
            cacheConfig.distributedCaching,
            cacheConfig.clientSideCaching,
            cacheEvictedEventBus,
            cacheConfig.cacheSource,
            cacheConfig.keyFilter
        )
        cacheEvictedEventBus.register(cache)
        return cache
    }

    fun <K, V> getOrCreateCache(cacheConfig: CacheConfig<K, V>): CoherentCache<K, V> {
        @Suppress("UNCHECKED_CAST")
        return caches.computeIfAbsent(cacheConfig.cacheName) {
            createCache(cacheConfig)
        } as CoherentCache<K, V>
    }
}

data class CacheConfig<K, V>(
    val cacheName: String,
    var clientId: String,
    var keyConverter: KeyConverter<K>,
    var distributedCaching: DistributedCache<V>,
    var clientSideCaching: ClientSideCache<V> = MapClientSideCache(),
    var cacheSource: CacheSource<K, V> = CacheSource.noOp(),
    var keyFilter: KeyFilter = NoOpKeyFilter
)
