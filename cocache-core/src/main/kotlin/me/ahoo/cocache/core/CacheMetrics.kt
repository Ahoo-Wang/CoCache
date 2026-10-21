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

package me.ahoo.cocache.core

import java.util.concurrent.atomic.LongAdder

/**
 * 单个缓存的运行统计（只增不减的累计值）。
 *
 * 读取按层互斥计数：每次读取（批量读取按 key）计入 [localHits]（L2 命中）、[misses]（L2 未命中）或 [filtered]
 * （key 过滤器判定不存在）之一；未命中之后再由 L1 命中计入 [remoteHits]、由回源计入 [loads]。
 *
 * 除 [localHits] 外全部记录在未命中、写入或失效路径上。[localHits] 默认不记录（L2 命中路径不承担计数开销），
 * 由 `CacheRuntime.Builder.recordLocalHits(true)` 开启；开启后 L2 命中路径上唯一的记录是一次 [LongAdder] 自增：
 * 不加锁、不分配，多线程下按线程分散到不同单元。
 *
 * @property localHitsRecorded 是否记录 [localHits]；为 `false` 时 [localHits] 恒为 0，导出方不应把它当作“没有 L2 命中”
 */
class CacheMetrics internal constructor(val localHitsRecorded: Boolean = false) {
    private val localHitAdder = LongAdder()
    private val remoteHitAdder = LongAdder()
    private val missAdder = LongAdder()
    private val filteredAdder = LongAdder()
    private val loadAdder = LongAdder()
    private val loadFailureAdder = LongAdder()
    private val loadNanosAdder = LongAdder()
    private val loadRejectedAdder = LongAdder()
    private val loadTimeoutAdder = LongAdder()
    private val leaseWaitAdder = LongAdder()
    private val leaseWaitTimeoutAdder = LongAdder()
    private val fillRejectedAdder = LongAdder()
    private val degradedFillAdder = LongAdder()
    private val staleFillDiscardedAdder = LongAdder()
    private val reclaimAdder = LongAdder()
    private val unknownFormatAdder = LongAdder()
    private val refreshAdder = LongAdder()
    private val refreshRejectedAdder = LongAdder()
    private val remoteFailureAdder = LongAdder()
    private val invalidationReceivedAdder = LongAdder()
    private val writeFailureAdder = LongAdder()
    private val invalidationRetryAdder = LongAdder()
    private val invalidationDroppedAdder = LongAdder()
    private val resetAdder = LongAdder()
    private val conditionalUnchangedAdder = LongAdder()
    private val conditionalConflictAdder = LongAdder()

    /**
     * 由 L2（本地副本）直接返回的读取数：未过期的本地副本，含负缓存与进入刷新窗口的副本。
     * 只在 [localHitsRecorded] 时记录。
     */
    val localHits: Long get() = localHitAdder.sum()

    /**
     * L2 未命中后由 L1 返回的读取数（每次 L1 读取计一次，合并的并发读取只计一次）。
     */
    val remoteHits: Long get() = remoteHitAdder.sum()
    val misses: Long get() = missAdder.sum()
    val filtered: Long get() = filteredAdder.sum()
    val loads: Long get() = loadAdder.sum()
    val loadFailures: Long get() = loadFailureAdder.sum()
    val loadNanos: Long get() = loadNanosAdder.sum()
    val loadRejected: Long get() = loadRejectedAdder.sum()

    /**
     * 异步数据源超过 `LoadPolicy.asyncTimeout` 的加载数。
     */
    val loadTimeouts: Long get() = loadTimeoutAdder.sum()
    val leaseWaits: Long get() = leaseWaitAdder.sum()
    val leaseWaitTimeouts: Long get() = leaseWaitTimeoutAdder.sum()
    val fillRejected: Long get() = fillRejectedAdder.sum()
    val degradedFills: Long get() = degradedFillAdder.sum()
    val staleFillsDiscarded: Long get() = staleFillDiscardedAdder.sum()
    val reclaims: Long get() = reclaimAdder.sum()
    val unknownFormats: Long get() = unknownFormatAdder.sum()
    val refreshes: Long get() = refreshAdder.sum()
    val refreshRejected: Long get() = refreshRejectedAdder.sum()
    val remoteFailures: Long get() = remoteFailureAdder.sum()
    val invalidationsReceived: Long get() = invalidationReceivedAdder.sum()
    val writeFailures: Long get() = writeFailureAdder.sum()
    val invalidationRetries: Long get() = invalidationRetryAdder.sum()
    val invalidationsDropped: Long get() = invalidationDroppedAdder.sum()
    val resets: Long get() = resetAdder.sum()

    /**
     * `getIfChanged` 确认 L1 中的值未变（没有传输正文）的次数。
     */
    val conditionalUnchanged: Long get() = conditionalUnchangedAdder.sum()

    /**
     * `putIfMatch` 因 L1 中的当前内容与期望不符而未写入的次数（不计入 [writeFailures]）。
     */
    val conditionalConflicts: Long get() = conditionalConflictAdder.sum()

    internal fun localHit() = localHitAdder.increment()
    internal fun remoteHit() = remoteHitAdder.increment()
    internal fun miss() = missAdder.increment()
    internal fun filtered() = filteredAdder.increment()
    internal fun load(nanos: Long, failed: Boolean) {
        loadAdder.increment()
        loadNanosAdder.add(nanos)
        if (failed) {
            loadFailureAdder.increment()
        }
    }
    internal fun loadRejected() = loadRejectedAdder.increment()
    internal fun loadTimeout() = loadTimeoutAdder.increment()
    internal fun leaseWait() = leaseWaitAdder.increment()
    internal fun leaseWaitTimeout() = leaseWaitTimeoutAdder.increment()
    internal fun fillRejected() = fillRejectedAdder.increment()
    internal fun degradedFill() = degradedFillAdder.increment()
    internal fun staleFillDiscarded() = staleFillDiscardedAdder.increment()
    internal fun reclaim() = reclaimAdder.increment()
    internal fun unknownFormat() = unknownFormatAdder.increment()
    internal fun refresh() = refreshAdder.increment()
    internal fun refreshRejected() = refreshRejectedAdder.increment()
    internal fun remoteFailure() = remoteFailureAdder.increment()
    internal fun invalidationReceived() = invalidationReceivedAdder.increment()
    internal fun writeFailure() = writeFailureAdder.increment()
    internal fun invalidationRetry() = invalidationRetryAdder.increment()
    internal fun invalidationDropped() = invalidationDroppedAdder.increment()
    internal fun reset() = resetAdder.increment()
    internal fun conditionalUnchanged() = conditionalUnchangedAdder.increment()
    internal fun conditionalConflict() = conditionalConflictAdder.increment()
}
