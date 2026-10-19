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

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.core.CacheMetrics

/**
 * [CacheMetrics] 计数器目录：Micrometer 指标与 actuator 端点共用，保证两边口径一致。
 *
 * @property key 端点输出中的字段名（与 [CacheMetrics] 的属性同名）
 * @property meter Micrometer 指标名
 * @property level 非空时作为 `level` 标签（分层命中）
 */
internal class CacheCounter(
    val key: String,
    val meter: String,
    val description: String,
    val level: String? = null,
    val read: (CacheMetrics) -> Long,
)

internal object CacheCounters {
    /**
     * 回源次数与累计耗时绑定为 FunctionTimer，不在此目录中重复。
     */
    val ALL: List<CacheCounter> = listOf(
        CacheCounter("remoteHits", "cocache.hits", "L2 misses served by L1", level = "l1") { it.remoteHits },
        CacheCounter("misses", "cocache.misses", "L2 misses, before coalescing") { it.misses },
        CacheCounter("filtered", "cocache.filtered", "Reads rejected by the key filter") { it.filtered },
        CacheCounter("loadFailures", "cocache.load.failures", "Loader calls that threw") { it.loadFailures },
        CacheCounter("loadRejected", "cocache.load.rejections", "Loads rejected by the concurrency limit") {
            it.loadRejected
        },
        CacheCounter("leaseWaits", "cocache.lease.waits", "Reads that waited for another node's lease") {
            it.leaseWaits
        },
        CacheCounter("leaseWaitTimeouts", "cocache.lease.wait.timeouts", "Lease waits that timed out") {
            it.leaseWaitTimeouts
        },
        CacheCounter("fillRejected", "cocache.fill.rejections", "L1 fills rejected by the lease fence") {
            it.fillRejected
        },
        CacheCounter("degradedFills", "cocache.degraded.fills", "Loads written to L2 only, without an L1 fill") {
            it.degradedFills
        },
        CacheCounter(
            "staleFillsDiscarded",
            "cocache.stale.discards",
            "Fills discarded because an invalidation arrived"
        ) { it.staleFillsDiscarded },
        CacheCounter("reclaims", "cocache.reclaims", "Corrupt L1 payloads reclaimed and reloaded") { it.reclaims },
        CacheCounter("unknownFormats", "cocache.unknown.formats", "L1 payloads in an unknown format") {
            it.unknownFormats
        },
        CacheCounter("refreshes", "cocache.refreshes", "Refresh-ahead executions") { it.refreshes },
        CacheCounter("refreshRejected", "cocache.refresh.rejections", "Refreshes rejected by the executor") {
            it.refreshRejected
        },
        CacheCounter("remoteFailures", "cocache.remote.failures", "L1 operations that failed") {
            it.remoteFailures
        },
        CacheCounter(
            "invalidationsReceived",
            "cocache.invalidations.received",
            "Invalidations received from the channel"
        ) { it.invalidationsReceived },
        CacheCounter("writeFailures", "cocache.write.failures", "Writes or invalidations that failed on L1") {
            it.writeFailures
        },
        CacheCounter("invalidationRetries", "cocache.invalidation.retries", "Invalidation retries scheduled") {
            it.invalidationRetries
        },
        CacheCounter(
            "invalidationsDropped",
            "cocache.invalidations.dropped",
            "Invalidations dropped after retries were exhausted"
        ) { it.invalidationsDropped },
        CacheCounter("resets", "cocache.resets", "Channel resets that cleared L2") { it.resets },
    )
}
