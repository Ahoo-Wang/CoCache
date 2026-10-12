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

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TtlPolicy.refreshAhead]：命中值进入刷新窗口时返回当前值并后台刷新一次。
 *
 * 默认使用同步执行器，刷新在命中调用返回前完成，断言无需等待。
 */
internal class DefaultCoherentCacheRefreshAheadTest {
    private val key = "key"
    private val sourceCalls = AtomicInteger()

    @Volatile
    private var sourceValue: () -> CacheValue<String>? = { CacheValue.of("fresh", TtlAt.at(60)) }

    private val countingSource = CacheSource<String, String> {
        sourceCalls.incrementAndGet()
        sourceValue()
    }

    /**
     * 不实现 SetElementsStore 的 L1（InMemoryDistributedCache 实现了它，回源写回会改为条件写入）。
     */
    private class MapDistributedCache : DistributedCache<String> {
        private val entries = ConcurrentHashMap<String, CacheValue<String>>()
        override fun getCache(key: String): CacheValue<String>? = entries[key]
        override fun setCache(key: String, value: CacheValue<String>) {
            entries[key] = value
        }

        override fun evict(key: String) {
            entries.remove(key)
        }
    }

    private fun createCache(
        refreshExecutor: Executor = Executor { it.run() },
        ttlPolicy: TtlPolicy = TtlPolicy(ttl = 60, ttlAmplitude = 0, refreshAhead = 10),
        distributedCache: DistributedCache<String> = MapDistributedCache(),
        cacheSource: CacheSource<String, String> = countingSource
    ): CoherentCache<String, String> {
        return DefaultCoherentCacheFactory(NoOpCacheEvictedEventBus, false, refreshExecutor).create(
            CoherentCacheConfiguration(
                cacheName = "DefaultCoherentCacheRefreshAheadTest",
                clientId = "client",
                keyConverter = ToStringKeyConverter(""),
                clientSideCache = MapClientSideCache(),
                distributedCache = distributedCache,
                cacheSource = cacheSource,
                ttlPolicy = ttlPolicy
            )
        )
    }

    private fun CoherentCache<String, String>.l1(): CacheValue<String>? = configuration.distributedCache.getCache(key)
    private fun CoherentCache<String, String>.l2(): CacheValue<String>? = configuration.clientSideCache.getCache(key)

    @Test
    fun dueValueIsServedAndReloadedFromSource() {
        val cache = createCache()
        cache.setCache(key, CacheValue.of("old", TtlAt.at(5)))

        cache[key].assert().isEqualTo("old")

        sourceCalls.get().assert().isOne()
        cache.l1()?.value.assert().isEqualTo("fresh")
        cache.l2()?.value.assert().isEqualTo("fresh")
        cache.stats.refreshCount.assert().isOne()
        cache[key].assert().isEqualTo("fresh")
        sourceCalls.get().assert().isOne()
    }

    @Test
    fun dueClientSideCopyRefillsFromFreshDistributed() {
        val cache = createCache(
            ttlPolicy = TtlPolicy(ttl = 60, ttlAmplitude = 0, clientSideTtl = 20, refreshAhead = 10)
        )
        cache.configuration.distributedCache.setCache(key, CacheValue.of("l1", TtlAt.at(60)))
        cache.configuration.clientSideCache.setCache(key, CacheValue.of("l1", TtlAt.at(5)))

        cache[key].assert().isEqualTo("l1")

        sourceCalls.get().assert().isZero()
        requireNotNull(cache.l2()).ttlAt.assert().isGreaterThan(TtlAt.at(15))
    }

    @Test
    fun setElementsDistributedIsAuthoritativeAndNotReloaded() {
        val cache = createCache(distributedCache = InMemoryDistributedCache())
        cache.setCache(key, CacheValue.of("old", TtlAt.at(5)))

        cache[key].assert().isEqualTo("old")

        cache.stats.refreshCount.assert().isOne()
        sourceCalls.get().assert().isZero()
        cache.l1()?.value.assert().isEqualTo("old")
    }

    @Test
    fun valueOutsideWindowIsNotRefreshed() {
        val cache = createCache()
        cache.setCache(key, CacheValue.of("value", TtlAt.at(30)))

        cache[key].assert().isEqualTo("value")

        cache.stats.refreshCount.assert().isZero()
    }

    @Test
    fun missingAndForeverValuesAreNotRefreshed() {
        val cache = createCache()
        cache.setCache(key, CacheValue.missing(TtlAt.at(5)))
        cache.setCache("forever", CacheValue.forever("value"))

        cache[key].assert().isNull()
        cache["forever"].assert().isEqualTo("value")

        cache.stats.refreshCount.assert().isZero()
    }

    @Test
    fun onlyOneRefreshPerKeyAtATime() {
        val pending = mutableListOf<Runnable>()
        val cache = createCache(refreshExecutor = Executor { pending.add(it) })
        cache.setCache(key, CacheValue.of("old", TtlAt.at(5)))

        cache[key]
        cache[key]
        pending.size.assert().isOne()

        pending.single().run()
        sourceCalls.get().assert().isOne()
        cache[key].assert().isEqualTo("fresh")
    }

    @Test
    fun rejectedRefreshCanBeRetried() {
        val rejecting = AtomicInteger(1)
        val cache = createCache(
            refreshExecutor = Executor {
                if (rejecting.getAndDecrement() > 0) {
                    throw RejectedExecutionException("full")
                }
                it.run()
            }
        )
        cache.setCache(key, CacheValue.of("old", TtlAt.at(5)))

        cache[key].assert().isEqualTo("old")
        sourceCalls.get().assert().isZero()

        cache[key].assert().isEqualTo("old")
        sourceCalls.get().assert().isOne()
    }

    @Test
    fun failedRefreshKeepsValueAndCanBeRetried() {
        val cache = createCache()
        cache.setCache(key, CacheValue.of("old", TtlAt.at(5)))
        sourceValue = { error("source down") }

        cache[key].assert().isEqualTo("old")
        cache.stats.loadFailureCount.assert().isOne()

        sourceValue = { CacheValue.of("fresh", TtlAt.at(60)) }
        cache[key].assert().isEqualTo("old")
        cache[key].assert().isEqualTo("fresh")
    }

    @Test
    fun invalidationDuringRefreshDiscardsWriteBack() {
        lateinit var cache: CoherentCache<String, String>
        sourceValue = {
            (cache as CacheEvictedSubscriber).onEvicted(CacheEvictedEvent(cache.cacheName, key, "peer"))
            CacheValue.of("stale", TtlAt.at(60))
        }
        cache = createCache()
        cache.setCache(key, CacheValue.of("old", TtlAt.at(5)))

        cache[key].assert().isEqualTo("old")

        cache.l2().assert().isNull()
        cache.l1()?.value.assert().isEqualTo("old")
        cache.stats.staleLoadDiscardedCount.assert().isOne()
    }

    @Test
    fun closedCacheDoesNotRefresh() {
        val cache = createCache()
        cache.setCache(key, CacheValue.of("old", TtlAt.at(5)))
        cache.close()

        cache[key].assert().isEqualTo("old")

        cache.stats.refreshCount.assert().isZero()
    }

    @Test
    fun batchReadRefreshesDueValues() {
        val cache = createCache()
        cache.setCache(key, CacheValue.of("old", TtlAt.at(5)))

        cache.getAll(listOf(key)).assert().isEqualTo(mapOf(key to "old"))

        cache.stats.refreshCount.assert().isOne()
        cache.l2()?.value.assert().isEqualTo("fresh")
    }
}
