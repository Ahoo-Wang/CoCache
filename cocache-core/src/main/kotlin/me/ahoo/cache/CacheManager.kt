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

import me.ahoo.cache.api.NamedCache
import me.ahoo.cache.api.annotation.MissingGuardCache
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedClientId
import me.ahoo.cache.filter.NoOpKeyFilter
import java.util.concurrent.ConcurrentHashMap

class CacheManager(private val cacheEvictedEventBus: CacheEvictedEventBus) {

    private val caches = ConcurrentHashMap<String, CoherentCache<*, *>>()

    fun getCacheNames(): Set<String> {
        return caches.keys
    }

    fun <K, V> getCache(cacheName: String): CoherentCache<K, V>? {
        @Suppress("UNCHECKED_CAST")
        return caches[cacheName] as CoherentCache<K, V>?
    }

    fun <K, V> createCache(cacheConfig: CoherentCacheConfiguration<K, V>): CoherentCache<K, V> {
        val cache = CoherentCache(
            config = cacheConfig,
            cacheEvictedEventBus = cacheEvictedEventBus
        )
        cacheEvictedEventBus.register(cache)
        return cache
    }

    fun <K, V> getOrCreateCache(cacheConfig: CoherentCacheConfiguration<K, V>): CoherentCache<K, V> {
        @Suppress("UNCHECKED_CAST")
        return caches.computeIfAbsent(cacheConfig.cacheName) {
            createCache(cacheConfig)
        } as CoherentCache<K, V>
    }
}

data class CoherentCacheConfiguration<K, V>(
    override val cacheName: String,
    override val clientId: String,
    val keyConverter: KeyConverter<K>,
    val distributedCache: DistributedCache<V>,
    val clientSideCache: ClientSideCache<V> = MapClientSideCache(),
    val cacheSource: CacheSource<K, V> = CacheSource.noOp(),
    val keyFilter: KeyFilter = NoOpKeyFilter,
    val missingGuardTtl: Long = MissingGuardCache.DEFAULT_TTL_SECONDS,
    val missingGuardTtlAmplitude: Long = MissingGuardCache.DEFAULT_TTL_AMPLITUDE_SECONDS
) : NamedCache, DistributedClientId
