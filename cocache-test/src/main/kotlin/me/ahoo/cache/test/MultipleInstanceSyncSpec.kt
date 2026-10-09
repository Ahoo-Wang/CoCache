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

import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.converter.KeyConverter
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * 多实例共享 L1 与失效通道时的一致性规格。
 */
abstract class MultipleInstanceSyncSpec<K, V> {
    private lateinit var keyConverter: KeyConverter<K>
    protected lateinit var cacheName: String
    private lateinit var currentClientSideCache: ClientSideCache<V>
    private lateinit var otherClientSideCache: ClientSideCache<V>
    private lateinit var currentCache: CoherentCache<K, V>
    private lateinit var otherCache: CoherentCache<K, V>
    private lateinit var cacheEvictedEventBus: CacheEvictedEventBus

    protected abstract fun createKeyConverter(): KeyConverter<K>
    protected abstract fun createClientSideCache(): ClientSideCache<V>
    protected abstract fun createDistributedCache(): DistributedCache<V>
    protected abstract fun createCacheEvictedEventBus(): CacheEvictedEventBus
    protected abstract fun createCacheName(): String
    protected abstract fun createCacheEntry(): Pair<K, V>

    @BeforeEach
    open fun setup() {
        keyConverter = createKeyConverter()
        cacheName = createCacheName()
        val distributedCache = createDistributedCache()
        cacheEvictedEventBus = createCacheEvictedEventBus()
        val coherentCacheFactory = DefaultCoherentCacheFactory(cacheEvictedEventBus)
        currentClientSideCache = createClientSideCache()
        otherClientSideCache = createClientSideCache()
        currentCache = coherentCacheFactory.create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = "currentClientId",
                keyConverter = keyConverter,
                distributedCache = distributedCache,
                clientSideCache = currentClientSideCache,
            )
        )
        otherCache = coherentCacheFactory.create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = "otherClientId",
                keyConverter = keyConverter,
                distributedCache = distributedCache,
                clientSideCache = otherClientSideCache,
            )
        )
    }

    @AfterEach
    open fun tearDown() {
        currentCache.close()
        otherCache.close()
    }

    @Test
    fun multipleInstanceSync() {
        val (key, value) = createCacheEntry()
        val cacheKey = keyConverter.toStringKey(key)

        currentCache[key] = value
        currentClientSideCache.getCache(cacheKey)?.value.assert().isEqualTo(value)
        otherCache[key].assert().isEqualTo(value)
        // 写入广播的失效事件异步到达，可能清掉刚填入的 L2；事件处理完后再次读取必定填入 L2
        awaitCondition {
            otherCache[key]
            otherClientSideCache.getCache(cacheKey)?.value == value
        }

        val (_, nextValue) = createCacheEntry()
        currentCache[key] = nextValue
        awaitCondition { otherClientSideCache.getCache(cacheKey) == null }
        otherCache[key].assert().isEqualTo(nextValue)

        currentCache.evict(key)
        awaitCondition { otherClientSideCache.getCache(cacheKey) == null }
        currentCache[key].assert().isNull()
        otherCache[key].assert().isNull()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met within 5 seconds." }
            Thread.sleep(10)
        }
    }
}
