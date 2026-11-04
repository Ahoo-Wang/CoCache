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

package me.ahoo.cocache.core.policy

import java.time.Duration

/**
 * 寿命（`ttl`、`tombstoneTtl`）：显式的“永不过期”与有限寿命（RFC 0005 §4.8，S8、S12）。
 *
 * 分层与 `constrain` 用它表达取值，“永不过期”参与比较时按 +∞：两个寿命取 [min] 时 [Forever] 让位于任何有限寿命。
 * [CachePolicy] 中仍以 `Duration?`（`null` 即永不过期）保存，命中路径不经过它。
 */
sealed interface Ttl {
    /**
     * 两者中更短的寿命：[Forever] 让位于任何有限寿命。
     */
    fun min(other: Ttl): Ttl

    /**
     * 有限寿命原样返回，[Forever] 换成 [duration]：用于“条目一定过期”的约束（`constrain(TTL) { it.finiteOr(1.minutes) }`）。
     */
    fun finiteOr(duration: Duration): Finite

    /**
     * 转回 [CachePolicy] 的表示：[Forever] 为 `null`。
     */
    fun toDuration(): Duration?

    data object Forever : Ttl {
        override fun min(other: Ttl): Ttl = other

        override fun finiteOr(duration: Duration): Finite = Finite(duration)

        override fun toDuration(): Duration? = null

        override fun toString(): String = "forever"
    }

    data class Finite(val duration: Duration) : Ttl {
        init {
            require(!duration.isNegative && !duration.isZero) { "A finite ttl[$duration] must be positive." }
        }

        override fun min(other: Ttl): Ttl = when (other) {
            Forever -> this
            is Finite -> if (other.duration < duration) other else this
        }

        override fun finiteOr(duration: Duration): Finite = this

        override fun toDuration(): Duration = duration

        override fun toString(): String = duration.toString()
    }

    companion object {
        /**
         * [CachePolicy] 的表示（`null` 即永不过期）→ [Ttl]。
         */
        @JvmStatic
        fun of(duration: Duration?): Ttl = duration?.let(::Finite) ?: Forever
    }
}

/**
 * 提前刷新窗口：显式的“关闭”与窗口时长（RFC 0005 §4.8，S8）。[CachePolicy] 中仍以 `Duration?`（`null` 即关闭）保存。
 */
sealed interface RefreshWindow {
    fun toDuration(): Duration?

    data object Off : RefreshWindow {
        override fun toDuration(): Duration? = null

        override fun toString(): String = "off"
    }

    data class Of(val duration: Duration) : RefreshWindow {
        init {
            require(
                !duration.isNegative && !duration.isZero
            ) { "A refresh window[$duration] must be positive; use off." }
        }

        override fun toDuration(): Duration = duration

        override fun toString(): String = duration.toString()
    }

    companion object {
        @JvmStatic
        fun of(duration: Duration?): RefreshWindow = duration?.let(::Of) ?: Off
    }
}

/**
 * 回源并发上限：显式的“不限”与许可数（RFC 0005 §4.8，S8）。[LoadPolicy.maxConcurrent] 中以 `Int?`（`null` 即不限）保存。
 */
sealed interface Concurrency {
    fun toPermits(): Int?

    data object Unlimited : Concurrency {
        override fun toPermits(): Int? = null

        override fun toString(): String = "unlimited"
    }

    data class Max(val permits: Int) : Concurrency {
        init {
            require(permits > 0) { "A load concurrency limit[$permits] must be positive; use unlimited." }
        }

        override fun toPermits(): Int = permits

        override fun toString(): String = permits.toString()
    }

    companion object {
        @JvmStatic
        fun of(permits: Int?): Concurrency = permits?.let(::Max) ?: Unlimited
    }
}
