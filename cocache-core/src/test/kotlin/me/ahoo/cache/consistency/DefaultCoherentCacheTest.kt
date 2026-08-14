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
package me.ahoo.cache.consistency

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.test.DefaultCoherentCacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coherent Cache Test .
 *
 * @author ahoo wang
 */
internal class DefaultCoherentCacheTest : DefaultCoherentCacheSpec<String, String>() {

    override fun createKeyConverter(): KeyConverter<String> = ToStringKeyConverter("")

    override fun createClientSideCache(): ClientSideCache<String> = MapClientSideCache()

    override fun createDistributedCache(): DistributedCache<String> = MockDistributedCache()

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = GuavaCacheEvictedEventBus()
    override fun createCacheName(): String {
        return "CoherentCacheTest"
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }

    @Test
    fun closeClosesDistributedCache() {
        val distributedCache = mockk<DistributedCache<String>>(relaxUnitFun = true)
        val cache = DefaultCoherentCache<String, String>(
            config = CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(""),
                clientSideCache = MapClientSideCache(),
                distributedCache = distributedCache,
                cacheSource = CacheSource.noOp()
            ),
            cacheEvictedEventBus = GuavaCacheEvictedEventBus()
        )
        cache.close()
        cache.close()
        verify(exactly = 1) { distributedCache.close() }
    }

    /**
     * close() must swallow exceptions from both the unregister and distributed-close steps,
     * execute the second cleanup step even when the first throws, and remain idempotent.
     */
    @Test
    fun closeSwallowsUnregisterAndCloseExceptions() {
        val eventBus = ThrowingUnregisterBus()
        val distributedCache = mockk<DistributedCache<String>>(relaxUnitFun = true)
        every { distributedCache.close() } throws IllegalStateException("close boom")

        // Pre-register so that the cache is the registered subscriber.
        val cache = DefaultCoherentCache<String, String>(
            config = CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(""),
                clientSideCache = MapClientSideCache(),
                distributedCache = distributedCache,
                cacheSource = CacheSource.noOp()
            ),
            cacheEvictedEventBus = eventBus
        )
        eventBus.register(cache)

        // Must not throw despite the throwing unregister and throwing close.
        cache.close()
        // Idempotent: a second close() must not throw either, and distributedCache.close()
        // must still only have been invoked once.
        cache.close()

        verify(exactly = 1) { distributedCache.close() }
    }

    /**
     * getL2Cache: distributedCache returns an expired value → evict L1 → fall through to source.
     */
    @Test
    fun getCacheEvictsExpiredL1EntryAndFallsThroughToSource() {
        val key = "expired-l1-key"
        val cacheKey = ToStringKeyConverter<String>("").toStringKey(key)
        val distributedCache = MockDistributedCache<String>()
        distributedCache.setCache(cacheKey, DefaultCacheValue("stale", ComputedTtlAt.at(-5)))

        val sourceValue: String = "fresh-from-source"
        val cache = DefaultCoherentCache<String, String>(
            config = CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(""),
                clientSideCache = MapClientSideCache(),
                distributedCache = distributedCache,
                cacheSource = object : CacheSource<String, String> {
                    override fun loadCacheValue(key: String): CacheValue<String>? {
                        return DefaultCacheValue.forever(sourceValue)
                    }
                }
            ),
            cacheEvictedEventBus = GuavaCacheEvictedEventBus()
        )

        val actual = cache.getCache(key)!!

        actual.value.assert().isEqualTo(sourceValue)
        // L1 expired entry must be evicted before falling through, then the fresh value
        // from the source is written back to both layers.
        distributedCache.getCache(cacheKey).assert().isNotNull()
        distributedCache.getCache(cacheKey)!!.value.assert().isEqualTo(sourceValue)
        (cache.clientSideCache.getCache(cacheKey)!!).value.assert().isEqualTo(sourceValue)
    }

    private class ThrowingUnregisterBus : CacheEvictedEventBus {
        val registered = AtomicInteger(0)
        override fun publish(event: CacheEvictedEvent) = Unit
        override fun register(subscriber: CacheEvictedSubscriber) {
            registered.incrementAndGet()
        }
        override fun unregister(subscriber: CacheEvictedSubscriber) {
            throw IllegalStateException("unregister boom")
        }
    }
}
