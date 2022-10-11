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
package me.ahoo.cache.spring.redis

import me.ahoo.cache.CacheSource
import me.ahoo.cache.CoherentCache
import me.ahoo.cache.CoherentCache.Companion.builder
import me.ahoo.cache.client.ClientSideCache
import me.ahoo.cache.consistency.GuavaInvalidateEventBus
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.distributed.DistributedCache
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * Redis Coherent Cache Builder .
 *
 * @author ahoo wang
 */
class RedisCoherentCacheBuilder<K, V> {
    private lateinit var keyConverter: KeyConverter<K>
    private lateinit var cacheSource: CacheSource<K, V>
    private lateinit var clientSideCaching: ClientSideCache<V>
    private lateinit var distributedCaching: DistributedCache<V>
    private lateinit var listenerContainer: RedisMessageListenerContainer
    fun keyConverter(keyConverter: KeyConverter<K>): RedisCoherentCacheBuilder<K, V> {
        this.keyConverter = keyConverter
        return this
    }

    fun cacheSource(cacheSource: CacheSource<K, V>): RedisCoherentCacheBuilder<K, V> {
        this.cacheSource = cacheSource
        return this
    }

    fun clientSideCaching(clientSideCaching: ClientSideCache<V>): RedisCoherentCacheBuilder<K, V> {
        this.clientSideCaching = clientSideCaching
        return this
    }

    fun distributedCaching(distributedCaching: DistributedCache<V>): RedisCoherentCacheBuilder<K, V> {
        this.distributedCaching = distributedCaching
        return this
    }

    fun listenerContainer(listenerContainer: RedisMessageListenerContainer): RedisCoherentCacheBuilder<K, V> {
        this.listenerContainer = listenerContainer
        return this
    }

    fun build(): CoherentCache<K, V> {
        val invalidateEventBus = RedisInvalidateEventBus(
            keyConverter.keyPrefix,
            GuavaInvalidateEventBus(distributedCaching.clientId),
            listenerContainer
        )
        return builder<K, V>()
            .keyConverter(keyConverter)
            .cacheSource(cacheSource)
            .clientSideCaching(clientSideCaching)
            .distributedCaching(distributedCaching)
            .invalidateEventBus(invalidateEventBus)
            .build()
    }
}
