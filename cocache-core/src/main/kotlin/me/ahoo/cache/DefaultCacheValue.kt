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

import me.ahoo.cache.MissingGuard.Companion.STRING_VALUE
import me.ahoo.cache.MissingGuard.Companion.isMissingGuard
import me.ahoo.cache.api.CacheValue

object DefaultMissingGuard : MissingGuard {
    override fun toString(): String {
        return STRING_VALUE
    }
}

/**
 * Cache Value .
 *
 * @author ahoo wang
 */
data class DefaultCacheValue<V>(
    override val value: V,
    /**
     * get time to live([java.time.temporal.ChronoUnit.SECONDS]).
     *
     * @return time to live (second)
     */
    override val ttlAt: Long
) : CacheValue<V>, ComputedTtlAt {

    override val isMissingGuard: Boolean
        get() = value.isMissingGuard

    companion object {
        private val FOREVER_MISSING_GUARD_CACHE_VALUE: CacheValue<*> = forever(DefaultMissingGuard)

        @JvmStatic
        fun <V> forever(value: V): CacheValue<V> {
            return DefaultCacheValue(value, ComputedTtlAt.FOREVER)
        }

        @JvmStatic
        fun <V> ttlAt(value: V, ttl: Long, amplitude: Long = 0): CacheValue<V> {
            if (ComputedTtlAt.isForever(ttl)) {
                return forever(value)
            }
            val ttlAt = ComputedTtlAt.at(ttl, amplitude)
            return DefaultCacheValue(value, ttlAt)
        }

        /**
         * forever missing guard value.
         */
        @JvmStatic
        fun <V : CacheValue<*>> missingGuard(): V {
            @Suppress("UNCHECKED_CAST")
            return FOREVER_MISSING_GUARD_CACHE_VALUE as V
        }

        fun <V : CacheValue<*>> missingGuard(ttl: Long, amplitude: Long = 0): V {
            return missingGuard(DefaultMissingGuard, ttl, amplitude)
        }

        @JvmStatic
        fun <V : CacheValue<*>> missingGuard(missingGuard: MissingGuard, ttl: Long, amplitude: Long = 0): V {
            if (ComputedTtlAt.isForever(ttl)) {
                return missingGuard()
            }
            val ttlAt = ComputedTtlAt.at(ttl, amplitude)
            @Suppress("UNCHECKED_CAST")
            return DefaultCacheValue(missingGuard, ttlAt) as V
        }

        @JvmStatic
        fun <V> missingGuardValue(): V {
            @Suppress("UNCHECKED_CAST")
            return DefaultMissingGuard as V
        }
    }
}
