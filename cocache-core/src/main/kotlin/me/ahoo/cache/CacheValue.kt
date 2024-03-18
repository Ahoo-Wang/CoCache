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

object MissingGuard {
    const val STRING_VALUE = "_nil_"
    override fun toString(): String {
        return STRING_VALUE
    }
}

/**
 * Cache Value .
 *
 * @author ahoo wang
 */
data class CacheValue<V>(
    val value: V,
    /**
     * get time to live([java.time.temporal.ChronoUnit.SECONDS]).
     *
     * @return time to live (second)
     */
    override val ttlAt: Long
) : TtlAt {

    val isMissingGuard: Boolean
        get() = missingGuardValue<Any>() == value

    companion object {
        private val FOREVER_MISSING_GUARD_CACHE_VALUE: CacheValue<*> = forever(MissingGuard)

        @JvmStatic
        fun <V> forever(value: V): CacheValue<V> {
            return CacheValue(value, TtlAt.FOREVER)
        }

        @JvmStatic
        fun <V> ttlAt(value: V, ttl: Long, amplitude: Long = 0): CacheValue<V> {
            val ttlAt = TtlAt.at(ttl, amplitude)
            return CacheValue(value, ttlAt)
        }

        /**
         * forever missing guard value.
         */
        @JvmStatic
        fun <V : CacheValue<*>> missingGuard(): V {
            @Suppress("UNCHECKED_CAST")
            return FOREVER_MISSING_GUARD_CACHE_VALUE as V
        }

        @JvmStatic
        fun <V : CacheValue<*>> missingGuard(ttl: Long, amplitude: Long = 0): V {
            if (TtlAt.isForever(ttl)) {
                return missingGuard()
            }
            val ttlAt = TtlAt.at(ttl, amplitude)
            @Suppress("UNCHECKED_CAST")
            return CacheValue(MissingGuard, ttlAt) as V
        }

        @JvmStatic
        fun <V> missingGuardValue(): V {
            @Suppress("UNCHECKED_CAST")
            return MissingGuard as V
        }

        @JvmStatic
        fun isMissingGuard(value: String): Boolean {
            return MissingGuard.STRING_VALUE == value
        }

        @JvmStatic
        fun isMissingGuard(value: Set<String>): Boolean {
            return if (value.isEmpty()) {
                false
            } else {
                return value.contains(MissingGuard.STRING_VALUE)
            }
        }

        @JvmStatic
        fun isMissingGuard(value: Map<String, String>): Boolean {
            return if (value.isEmpty()) {
                false
            } else {
                value.containsKey(MissingGuard.STRING_VALUE)
            }
        }
    }
}
