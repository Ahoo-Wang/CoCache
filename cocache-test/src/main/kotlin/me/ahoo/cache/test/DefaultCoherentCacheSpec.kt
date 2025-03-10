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

package me.ahoo.cache.test

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.consistency.CacheEvictedEvent
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.distributed.DistributedCache
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

abstract class DefaultCoherentCacheSpec<K, V> : CacheSpec<K, V>() {
    companion object {
        private val CACHE_SOURCE_VALUE = ThreadLocal<CacheValue<*>>()
    }

    private lateinit var keyConverter: KeyConverter<K>
    private lateinit var clientSideCache: ClientSideCache<V>
    private lateinit var distributedCache: DistributedCache<V>
    private lateinit var cacheEvictedEventBus: CacheEvictedEventBus
    private lateinit var coherentCache: DefaultCoherentCache<K, V>
    protected lateinit var cacheName: String
    protected val clientId: String = UUID.randomUUID().toString()

    private val cacheSource = object : CacheSource<K, V> {
        override fun loadCacheValue(key: K): CacheValue<V>? {
            return CACHE_SOURCE_VALUE.get() as CacheValue<V>?
        }
    }

    @BeforeEach
    override fun setup() {
        keyConverter = createKeyConverter()
        clientSideCache = createClientSideCache()
        distributedCache = createDistributedCache()
        cacheEvictedEventBus = createCacheEvictedEventBus()
        cacheName = createCacheName()
        coherentCache = DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                clientSideCache = clientSideCache,
                distributedCache = distributedCache,
                cacheSource = cacheSource
            )
        )
        super.setup()
    }

    protected abstract fun createKeyConverter(): KeyConverter<K>
    protected abstract fun createClientSideCache(): ClientSideCache<V>
    protected abstract fun createDistributedCache(): DistributedCache<V>
    protected abstract fun createCacheEvictedEventBus(): CacheEvictedEventBus
    protected abstract fun createCacheName(): String
    override fun createCache(): Cache<K, V> {
        return coherentCache
    }

    @Test
    fun getFromCacheSource() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        CACHE_SOURCE_VALUE.set(cacheValue)
        assertThat(coherentCache[key], equalTo(value))
        CACHE_SOURCE_VALUE.remove()
    }

    @Test
    fun onEvicted() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        coherentCache.setCache(key, cacheValue)
        val cacheKey = keyConverter.toStringKey(key)
        val event = CacheEvictedEvent(cacheName, cacheKey, "")
        coherentCache.onEvicted(event)
        assertThat(clientSideCache[cacheKey], nullValue())
        assertThat(distributedCache[cacheKey], equalTo(value))
        assertThat(coherentCache[key], equalTo(value))
    }

    @Test
    fun onEvictedWhenLoop() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        coherentCache.setCache(key, cacheValue)
        val cacheKey = keyConverter.toStringKey(key)
        val event = CacheEvictedEvent(cacheName, cacheKey, clientId)
        coherentCache.onEvicted(event)
        assertThat(clientSideCache[cacheKey], equalTo(value))
        assertThat(distributedCache[cacheKey], equalTo(value))
        assertThat(coherentCache[key], equalTo(value))
    }

    @Test
    fun onEvictedWhenCacheNameNotMatch() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        coherentCache.setCache(key, cacheValue)
        val cacheKey = keyConverter.toStringKey(key)
        val event = CacheEvictedEvent(UUID.randomUUID().toString(), cacheKey, "")
        coherentCache.onEvicted(event)
        assertThat(clientSideCache[cacheKey], equalTo(value))
        assertThat(distributedCache[cacheKey], equalTo(value))
        assertThat(coherentCache[key], equalTo(value))
    }
}
