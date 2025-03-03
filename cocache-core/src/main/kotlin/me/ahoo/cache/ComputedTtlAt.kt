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

package me.ahoo.cache

import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.util.CacheSecondClock
import java.time.Duration

interface ComputedTtlAt : TtlAt {

    override val isForever: Boolean
        get() = isForever(ttlAt)
    override val isExpired: Boolean
        get() = if (isForever) {
            false
        } else {
            CacheSecondClock.INSTANCE.currentTime() > ttlAt
        }

    override val expiredDuration: Duration
        get() {
            val currentTime = CacheSecondClock.INSTANCE.currentTime()
            return if (currentTime > ttlAt) {
                Duration.ZERO
            } else {
                Duration.ofSeconds(ttlAt - currentTime)
            }
        }

    companion object {
        fun isForever(ttlAt: Long): Boolean {
            return FOREVER == ttlAt
        }

        /**
         * Jitter ttl (randomly) to prevent cache avalanche
         */
        fun jitter(ttl: Long, amplitude: Long): Long {
            if (amplitude == 0L) {
                return ttl
            }
            val low = ttl - amplitude
            val high = ttl + amplitude
            return (low..high).random()
        }

        fun at(ttl: Long, amplitude: Long = 0): Long {
            val jitteredTtl = jitter(ttl, amplitude)
            return CacheSecondClock.INSTANCE.currentTime() + jitteredTtl
        }

        /**
         * 9223372036854775807L.
         */
        const val FOREVER = Long.MAX_VALUE
    }
}
