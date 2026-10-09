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

/**
 * 缓存条目：带绝对到期时间的命中值 [PresentValue]，或负缓存 [MissingValue]。
 *
 * 负缓存是显式状态而非“哨兵值”：任何业务值（包括与哨兵同形的字符串/集合）都不会被误判为负缓存。
 *
 * @author ahoo wang
 */
sealed interface CacheValue<out V> : TtlAt {
    /**
     * 命中值；负缓存恒为 `null`。
     */
    val value: V?

    /**
     * 是否为负缓存（数据源确认该 key 不存在）。
     */
    val isMissing: Boolean

    companion object {
        /**
         * 构造缓存条目：`null` 值归一化为负缓存。
         */
        @JvmStatic
        fun <V> of(value: V?, ttlAt: Long): CacheValue<V> {
            if (value == null) {
                return missing(ttlAt)
            }
            return PresentValue(value, ttlAt)
        }

        @JvmStatic
        fun <V> forever(value: V?): CacheValue<V> {
            return of(value, TtlAt.FOREVER)
        }

        @JvmStatic
        @JvmOverloads
        fun <V> missing(ttlAt: Long = TtlAt.FOREVER): CacheValue<V> {
            return MissingValue(ttlAt)
        }
    }
}

/**
 * 命中值。
 */
data class PresentValue<out V>(
    override val value: V,
    override val ttlAt: Long
) : CacheValue<V> {
    override val isMissing: Boolean
        get() = false
}

/**
 * 负缓存：数据源确认 key 不存在，在 [ttlAt] 之前抑制回源（防缓存穿透）。
 */
data class MissingValue(
    override val ttlAt: Long
) : CacheValue<Nothing> {
    override val value: Nothing?
        get() = null
    override val isMissing: Boolean
        get() = true
}
