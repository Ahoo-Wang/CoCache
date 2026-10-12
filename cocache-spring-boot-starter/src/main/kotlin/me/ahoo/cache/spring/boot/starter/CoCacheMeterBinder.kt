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

package me.ahoo.cache.spring.boot.starter

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.FunctionTimer
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.binder.MeterBinder
import me.ahoo.cache.CacheFactory
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheStats
import me.ahoo.cache.spring.redis.EvictionPublishStats
import java.util.concurrent.TimeUnit

/**
 * 把每个一致性缓存的 [CoherentCacheStats] 与失效通道的发布失败数绑定为 Micrometer 指标。
 *
 * 指标读取累计值，不在缓存的读写路径上额外记录；每个缓存以 `cache` 标签区分。
 */
class CoCacheMeterBinder(
    private val cacheFactory: CacheFactory,
    private val cacheEvictedEventBus: CacheEvictedEventBus?
) : MeterBinder {
    companion object {
        const val TAG_CACHE = "cache"
        const val TAG_LEVEL = "level"
    }

    override fun bindTo(registry: MeterRegistry) {
        cacheFactory.caches.values
            .filterIsInstance<CoherentCache<*, *>>()
            .distinctBy { it.cacheName }
            .forEach { bindCache(registry, it) }
        (cacheEvictedEventBus as? EvictionPublishStats)?.let { bus ->
            FunctionCounter.builder("cocache.eviction.publish.failures", bus) { it.publishFailureCount.toDouble() }
                .description("Eviction events that failed to publish")
                .register(registry)
        }
    }

    private fun bindCache(registry: MeterRegistry, cache: CoherentCache<*, *>) {
        val tags = Tags.of(TAG_CACHE, cache.cacheName)
        val stats = cache.stats
        if (stats.clientSideHitsRecorded) {
            counter(registry, "cocache.hits", "Reads served by L2", tags.and(TAG_LEVEL, "l2"), stats) {
                it.clientSideHitCount
            }
        }
        counter(registry, "cocache.hits", "L2 misses served by L1", tags.and(TAG_LEVEL, "l1"), stats) {
            it.distributedHitCount
        }
        counter(registry, "cocache.misses", "L2 misses, before coalescing", tags, stats) { it.missCount }
        counter(registry, "cocache.filtered", "Reads rejected by the key filter", tags, stats) { it.filteredCount }
        counter(registry, "cocache.load.failures", "Cache source loads that threw", tags, stats) {
            it.loadFailureCount
        }
        counter(registry, "cocache.stale.discards", "Write-backs discarded or undone by an invalidation", tags, stats) {
            it.staleLoadDiscardedCount
        }
        counter(registry, "cocache.evictions.received", "Eviction events received from peers", tags, stats) {
            it.evictionReceivedCount
        }
        counter(registry, "cocache.resets", "Invalidation channel resets that cleared L2", tags, stats) {
            it.resetCount
        }
        counter(registry, "cocache.refreshes", "Refresh-ahead executions", tags, stats) { it.refreshCount }
        counter(registry, "cocache.load.rejections", "Source loads rejected by the concurrency limit", tags, stats) {
            it.loadRejectedCount
        }
        FunctionTimer.builder(
            "cocache.loads",
            stats,
            { it.loadCount },
            { it.totalLoadNanos.toDouble() },
            TimeUnit.NANOSECONDS
        )
            .description("Cache source loads")
            .tags(tags)
            .register(registry)
        Gauge.builder("cocache.l2.size", cache) { it.configuration.clientSideCache.size.toDouble() }
            .description("Entries in L2")
            .tags(tags)
            .register(registry)
    }

    @Suppress("LongParameterList")
    private fun counter(
        registry: MeterRegistry,
        name: String,
        description: String,
        tags: Tags,
        stats: CoherentCacheStats,
        count: (CoherentCacheStats) -> Long
    ) {
        FunctionCounter.builder(name, stats) { count(it).toDouble() }
            .description(description)
            .tags(tags)
            .register(registry)
    }
}
