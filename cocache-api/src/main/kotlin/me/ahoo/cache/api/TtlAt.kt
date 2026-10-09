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

package me.ahoo.cache.api

import java.time.Duration

/**
 * 绝对到期时间（纪元秒）。
 *
 * 时间以秒为精度，统一取自 [currentTime]（缓存时钟）；[FOREVER] 表示永不过期。
 *
 * @author ahoo wang
 */
interface TtlAt {
    /**
     * 绝对到期时间戳（纪元秒）。
     */
    val ttlAt: Long

    val isForever: Boolean
        get() = isForever(ttlAt)

    val isExpired: Boolean
        get() = !isForever && currentTime() >= ttlAt

    /**
     * 距离到期的剩余时长；已过期为 [Duration.ZERO]，永不过期为 [ChronoUnit.FOREVER] 的时长。
     */
    val expiredDuration: Duration
        get() {
            if (isForever) {
                return FOREVER_DURATION
            }
            val remaining = ttlAt - currentTime()
            return if (remaining <= 0) Duration.ZERO else Duration.ofSeconds(remaining)
        }

    companion object {
        /**
         * 永不过期。
         */
        const val FOREVER = Long.MAX_VALUE

        private val FOREVER_DURATION: Duration = Duration.ofSeconds(Long.MAX_VALUE)

        /**
         * 当前纪元秒，取自 [CacheClock]（最多滞后 [CacheClock.TICK_MILLIS] 毫秒）。
         */
        @JvmStatic
        fun currentTime(): Long {
            return CacheClock.currentTime()
        }

        @JvmStatic
        fun isForever(ttlAt: Long): Boolean {
            return FOREVER == ttlAt
        }

        /**
         * 由相对时长计算绝对到期时间：[ttl] 为 [FOREVER] 时永不过期。
         *
         * [amplitude] 为随机抖动幅度（秒），用于打散批量到期以防缓存雪崩；
         * 抖动后的时长钳为正数，避免 `amplitude >= ttl` 时产生已过期的值。
         */
        @JvmStatic
        @JvmOverloads
        fun at(ttl: Long, amplitude: Long = 0): Long {
            if (isForever(ttl)) {
                return FOREVER
            }
            return currentTime() + jitter(ttl, amplitude)
        }

        private fun jitter(ttl: Long, amplitude: Long): Long {
            if (amplitude <= 0) {
                return ttl
            }
            val low = (ttl - amplitude).coerceAtLeast(1)
            val high = (ttl + amplitude).coerceAtLeast(1)
            return (low..high).random()
        }
    }
}
