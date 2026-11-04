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
    private val refreshSkippedAdder = LongAdder()
    private val remoteFailureAdder = LongAdder()
    private val invalidationReceivedAdder = LongAdder()
    private val writeFailureAdder = LongAdder()
    private val invalidationRetryAdder = LongAdder()
    private val invalidationDroppedAdder = LongAdder()
    private val resetAdder = LongAdder()
    private val conditionalUnchangedAdder = LongAdder()
    private val conditionalConflictAdder = LongAdder()
    private val staleVersionAdder = LongAdder()
    private val typeMismatchAdder = LongAdder()
    private val unknownReadAdder = LongAdder()
    private val unversionedAbsentRefusedAdder = LongAdder()
    private val fillGatedAdder = LongAdder()
    private val ttlClampedAdder = LongAdder()

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
     * 超过 `LoadPolicy.timeout` 的加载数：异步数据源的一次加载，以及等待共享加载超时的跟随者。
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

    /**
     * 到期却没有运行的提前刷新（RFC 0005 §8）：
     * - 刷新读没有取得刷新令牌（令牌在别的节点手里）：只从 L1 重新填充 L2，刷新点退避，不回源；
     * - 提前刷新本该覆盖的副本没有刷新点：刷新只会再读到同样内容的副本（未知标志位、`E`、摘要段、版本被拒绝，P14），
     *   或 L1 剩余寿命不长于刷新窗口（副本一出生就在窗口内）。这样的副本到期之前不刷新。
     */
    val refreshSkipped: Long get() = refreshSkippedAdder.sum()
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
     * `putIfMatch` / `deleteIfMatch` 因 L1 中的当前内容与期望不符而未写入的次数（不计入 [writeFailures]）。
     */
    val conditionalConflicts: Long get() = conditionalConflictAdder.sum()

    /**
     * `putIfNewer` / `invalidateIfNewer` 因 L1 中已有不更旧的版本而未写入的次数（重复投递与迟到的旧版本；不计入 [writeFailures]）。
     */
    val staleVersions: Long get() = staleVersionAdder.sum()

    /**
     * 本缓存从 L1 读到不属于它这一类的内容的次数（RFC 0005 §4.7，D4）：派生副本（`Cache`、`VersionedCache`）读到带摘要段的值，
     * 即同一个 namespace 上还有 `Store`。这样的值照常读取，但不回收、不取得刷新令牌。
     */
    val typeMismatches: Long get() = typeMismatchAdder.sum()

    /**
     * 读取的结果为 `Lookup.Unknown`（L1 故障，RFC 0005 §4.1）的次数，按调用方计：含按 `unknownRead = ABSENT` 换算为不存在的、
     * 抛出 `CacheReadException` 的与 `lookup` 返回 `Unknown` 的。同一次 L1 故障另计入 [remoteFailures]。
     */
    val readsUnknown: Long get() = unknownReadAdder.sum()

    /**
     * 带版本的数据源返回不带版本的 `null`、落在带版本的内容上被 L1 拒绝的次数（K16）：数据源应以 `Versioned(null, version)` 表达删除。
     */
    val unversionedAbsentRefusals: Long get() = unversionedAbsentRefusedAdder.sum()

    /**
     * 写事务内的私有回源（RFC 0005 §4.9 机制 2，`fill-in-transaction`）按 key 计的次数：不申请 lease、不写 L1 与 L2、
     * 不经 SingleFlight 共享。每次另计入 [misses] 与 [loads]。
     */
    val fillsGated: Long get() = fillGatedAdder.sum()

    /**
     * 按调用给出的寿命（`ttlOf`、`put(k, v, ttl)`）超过 `cocache.overrides` 的 `ttl`、被钳制的次数（`CachePolicy.ttlCeiling`）。
     */
    val ttlClamped: Long get() = ttlClampedAdder.sum()

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
    internal fun refreshSkipped() = refreshSkippedAdder.increment()
    internal fun remoteFailure() = remoteFailureAdder.increment()
    internal fun invalidationReceived() = invalidationReceivedAdder.increment()
    internal fun writeFailure() = writeFailureAdder.increment()
    internal fun invalidationRetry() = invalidationRetryAdder.increment()
    internal fun invalidationDropped() = invalidationDroppedAdder.increment()
    internal fun reset() = resetAdder.increment()
    internal fun conditionalUnchanged() = conditionalUnchangedAdder.increment()
    internal fun conditionalConflict() = conditionalConflictAdder.increment()
    internal fun staleVersion() = staleVersionAdder.increment()
    internal fun typeMismatch() = typeMismatchAdder.increment()
    internal fun unknownRead() = unknownReadAdder.increment()
    internal fun unversionedAbsentRefused() = unversionedAbsentRefusedAdder.increment()
    internal fun fillGated(keys: Int) = fillGatedAdder.add(keys.toLong())
    internal fun ttlClamped() = ttlClampedAdder.increment()
}
