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

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.filter.KeyFilter
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class DefaultCoherentCacheStatsTest {
    private val key = "key"

    private fun createCache(
        cacheSource: CacheSource<String, String> = CacheSource.noOp(),
        distributedCache: DistributedCache<String> = InMemoryDistributedCache(),
        keyFilter: KeyFilter = KeyFilter.NO_OP,
        recordClientSideHits: Boolean = false
    ): CoherentCache<String, String> {
        return DefaultCoherentCacheFactory(NoOpCacheEvictedEventBus, recordClientSideHits).create(
            CoherentCacheConfiguration(
                cacheName = "DefaultCoherentCacheStatsTest",
                clientId = "client-${System.identityHashCode(distributedCache)}",
                keyConverter = ToStringKeyConverter(""),
                clientSideCache = MapClientSideCache(),
                distributedCache = distributedCache,
                cacheSource = cacheSource,
                keyFilter = keyFilter
            )
        )
    }

    @Test
    fun recordLoadThenClientSideHit() {
        val cache = createCache(cacheSource = { CacheValue.forever("value") })

        cache[key].assert().isEqualTo("value")
        cache[key].assert().isEqualTo("value")

        val stats = cache.stats
        stats.missCount.assert().isOne()
        stats.loadCount.assert().isOne()
        stats.loadFailureCount.assert().isZero()
        stats.totalLoadNanos.assert().isPositive()
        stats.clientSideHitsRecorded.assert().isFalse()
        stats.clientSideHitCount.assert().isZero()
        stats.distributedHitCount.assert().isZero()
    }

    @Test
    fun recordClientSideHitWhenEnabled() {
        val cache = createCache(cacheSource = { CacheValue.forever("value") }, recordClientSideHits = true)

        cache[key].assert().isEqualTo("value")
        cache[key].assert().isEqualTo("value")

        cache.stats.clientSideHitsRecorded.assert().isTrue()
        cache.stats.clientSideHitCount.assert().isOne()
    }

    @Test
    fun recordDistributedHit() {
        val distributedCache = InMemoryDistributedCache<String>()
        distributedCache.setCache(key, CacheValue.forever("value"))
        val cache = createCache(distributedCache = distributedCache)

        cache[key].assert().isEqualTo("value")

        cache.stats.distributedHitCount.assert().isOne()
        cache.stats.loadCount.assert().isZero()
    }

    @Test
    fun recordFiltered() {
        val cache = createCache(keyFilter = { true })

        cache[key].assert().isNull()

        cache.stats.filteredCount.assert().isOne()
        cache.stats.missCount.assert().isZero()
    }

    @Test
    fun recordLoadFailure() {
        val cache = createCache(cacheSource = { error("source down") })

        runCatching { cache[key] }.isFailure.assert().isTrue()

        cache.stats.loadCount.assert().isOne()
        cache.stats.loadFailureCount.assert().isOne()
    }

    @Test
    fun recordStaleLoadDiscarded() {
        lateinit var cache: CoherentCache<String, String>
        cache = createCache(cacheSource = {
            // 加载期间发生失效：写回必须放弃
            (cache as CacheEvictedSubscriber).onEvicted(CacheEvictedEvent(cache.cacheName, key, "peer"))
            CacheValue.forever("value")
        })

        cache[key].assert().isEqualTo("value")

        cache.stats.staleLoadDiscardedCount.assert().isOne()
        cache.stats.evictionReceivedCount.assert().isOne()
    }

    @Test
    fun recordEvictionReceivedIgnoresSelf() {
        val cache = createCache()
        val subscriber = cache as CacheEvictedSubscriber

        subscriber.onEvicted(CacheEvictedEvent(cache.cacheName, key, cache.clientId))
        subscriber.onEvicted(CacheEvictedEvent(cache.cacheName, key, "peer"))

        cache.stats.evictionReceivedCount.assert().isOne()
    }

    @Test
    fun recordReset() {
        val cache = createCache()

        (cache as CacheEvictedSubscriber).onReset()

        cache.stats.resetCount.assert().isOne()
        cache.stats.toString().assert().contains("resets=1")
    }

    @Test
    fun nonRecordingCacheHasEmptyStats() {
        val delegate = createCache()
        val cache = object : CoherentCache<String, String> by delegate {
            override val stats: CoherentCacheStats
                get() = super.stats
        }

        cache.stats.clientSideHitCount.assert().isZero()
    }
}
