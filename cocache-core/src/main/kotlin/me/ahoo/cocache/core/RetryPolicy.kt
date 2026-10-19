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

import java.time.Duration

/**
 * 失效重试（RFC 0003 §4.13）：有界队列、指数退避。
 *
 * @param maxAttempts 每次失效的最多重试次数
 * @param capacity 同时排队的失效数上限；溢出时放弃并告警
 */
data class RetryPolicy @JvmOverloads constructor(
    val maxAttempts: Int = 8,
    val initialBackoff: Duration = Duration.ofMillis(100),
    val maxBackoff: Duration = Duration.ofSeconds(10),
    val capacity: Int = 10_000,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts[$maxAttempts] must be positive." }
        require(
            !initialBackoff.isNegative && !initialBackoff.isZero
        ) { "initialBackoff[$initialBackoff] must be positive." }
        require(maxBackoff >= initialBackoff) {
            "maxBackoff[$maxBackoff] must not be less than initialBackoff[$initialBackoff]."
        }
        require(capacity > 0) { "capacity[$capacity] must be positive." }
    }

    internal fun backoff(attempt: Int): Duration {
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        val millis = initialBackoff.toMillis() shl shift
        return Duration.ofMillis(millis.coerceAtMost(maxBackoff.toMillis()))
    }

    private companion object {
        const val MAX_SHIFT = 30
    }
}
