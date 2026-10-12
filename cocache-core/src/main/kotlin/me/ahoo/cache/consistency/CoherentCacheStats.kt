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

import java.util.concurrent.atomic.LongAdder

/**
 * 一致性缓存的运行统计：只增不减的累计值，供指标系统（如 Micrometer）按需读取。
 *
 * 计数基于 [LongAdder]，多线程累加互不争用；读取时求和，因此快照之间可能不完全一致。
 * 只有 [DefaultCoherentCache] 记录统计。
 */
class CoherentCacheStats(
    /**
     * 是否统计 L2 命中。L2 命中是最热的路径，计数会降低其吞吐，因此默认关闭；关闭时 [clientSideHitCount] 恒为 0。
     */
    val clientSideHitsRecorded: Boolean = false
) {
    companion object {
        /**
         * 不记录统计的实现所共享的空统计：所有计数恒为 0。
         */
        internal val EMPTY = CoherentCacheStats()
    }

    private val clientSideHits = LongAdder()
    private val distributedHits = LongAdder()
    private val misses = LongAdder()
    private val filtered = LongAdder()
    private val loads = LongAdder()
    private val loadFailures = LongAdder()
    private val loadNanos = LongAdder()
    private val staleLoadsDiscarded = LongAdder()
    private val evictionsReceived = LongAdder()
    private val resets = LongAdder()
    private val refreshes = LongAdder()

    /**
     * L2 命中次数；仅当 [clientSideHitsRecorded] 时统计。
     */
    val clientSideHitCount: Long get() = clientSideHits.sum()

    /**
     * L2 未命中、由 SingleFlight leader 在 L1 命中的次数。
     */
    val distributedHitCount: Long get() = distributedHits.sum()

    /**
     * L2 未命中次数（不含被 key 过滤器判定不存在的读取）。与 L1 命中、回源次数之差即被 SingleFlight 合并的读取。
     */
    val missCount: Long get() = misses.sum()

    /**
     * key 过滤器判定不存在、直接返回负缓存的次数。
     */
    val filteredCount: Long get() = filtered.sum()

    /**
     * 回源次数（含失败；一次批量回源计为一次）。
     */
    val loadCount: Long get() = loads.sum()

    /**
     * 回源失败（数据源抛出异常）次数。
     */
    val loadFailureCount: Long get() = loadFailures.sum()

    /**
     * 回源累计耗时（纳秒，含失败）。
     */
    val totalLoadNanos: Long get() = loadNanos.sum()

    /**
     * 写回因加载期间发生失效而被放弃或撤销的次数。
     */
    val staleLoadDiscardedCount: Long get() = staleLoadsDiscarded.sum()

    /**
     * 收到其它实例失效事件的次数（不含自身发布的事件）。
     */
    val evictionReceivedCount: Long get() = evictionsReceived.sum()

    /**
     * 失效通道重置（清空 L2）次数。
     */
    val resetCount: Long get() = resets.sum()

    /**
     * 提前刷新（`refreshAhead`）执行次数，含失败。刷新的回源同时计入 [loadCount]。
     */
    val refreshCount: Long get() = refreshes.sum()

    internal fun recordClientSideHit() = clientSideHits.increment()
    internal fun recordDistributedHit() = distributedHits.increment()
    internal fun recordMiss() = misses.increment()
    internal fun recordFiltered() = filtered.increment()
    internal fun recordLoad(nanos: Long, failed: Boolean) {
        loads.increment()
        loadNanos.add(nanos)
        if (failed) {
            loadFailures.increment()
        }
    }

    internal fun recordStaleLoadDiscarded() = staleLoadsDiscarded.increment()
    internal fun recordEvictionReceived() = evictionsReceived.increment()
    internal fun recordReset() = resets.increment()
    internal fun recordRefresh() = refreshes.increment()

    override fun toString(): String {
        return "CoherentCacheStats(clientSideHits=$clientSideHitCount, distributedHits=$distributedHitCount, " +
            "misses=$missCount, filtered=$filteredCount, loads=$loadCount, loadFailures=$loadFailureCount, " +
            "totalLoadNanos=$totalLoadNanos, staleLoadsDiscarded=$staleLoadDiscardedCount, " +
            "evictionsReceived=$evictionReceivedCount, resets=$resetCount, refreshes=$refreshCount)"
    }
}
