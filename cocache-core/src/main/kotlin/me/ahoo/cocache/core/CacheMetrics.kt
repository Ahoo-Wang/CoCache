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
 * 单个缓存的运行统计（只增不减的累计值）。全部记录在未命中、写入或失效路径上，L2 命中路径不计数。
 */
class CacheMetrics internal constructor() {
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
}
