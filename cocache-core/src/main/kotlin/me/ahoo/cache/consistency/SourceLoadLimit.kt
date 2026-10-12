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

import java.time.Duration

/**
 * 回源并发限制（bulkhead）：单个缓存同时进行的回源数上限。
 *
 * Redis 故障时每个 L2 未命中都会回源，此限制保护数据源。只计 [me.ahoo.cache.concurrent.SingleFlight] 的 leader
 * （合并的等待者不占用许可），一次批量回源占用一个许可。
 *
 * @param maxConcurrentLoads 上限；`0` 表示不限制
 * @param acquireTimeout 等待许可的最长时间，超时抛出 [SourceLoadRejectedException]
 */
data class SourceLoadLimit(
    val maxConcurrentLoads: Int = 0,
    val acquireTimeout: Duration = DEFAULT_ACQUIRE_TIMEOUT
) {
    init {
        require(maxConcurrentLoads >= 0) { "maxConcurrentLoads[$maxConcurrentLoads] must not be negative." }
        require(!acquireTimeout.isNegative) { "acquireTimeout[$acquireTimeout] must not be negative." }
    }

    val isLimited: Boolean
        get() = maxConcurrentLoads > 0

    companion object {
        @JvmField
        val DEFAULT_ACQUIRE_TIMEOUT: Duration = Duration.ofSeconds(1)

        @JvmField
        val UNLIMITED = SourceLoadLimit()
    }
}

/**
 * 回源并发达到 [SourceLoadLimit.maxConcurrentLoads] 且在 [SourceLoadLimit.acquireTimeout] 内未获得许可。
 */
class SourceLoadRejectedException(cacheName: String, limit: SourceLoadLimit) : RuntimeException(
    "Cache Name[$cacheName] - source load rejected: ${limit.maxConcurrentLoads} loads in flight " +
        "and no permit within ${limit.acquireTimeout}."
)
