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

package me.ahoo.cocache.api

import java.time.Instant

/**
 * [ReadableCache.lookup] 的结果：存在、确认不存在，或这一次读取没有答案（RFC 0005 §4.1，V2）。
 *
 * 在 JDK 21 上，Java 调用方用对密封类型的穷尽 `switch` 匹配它（`Present`、`Absent`、`Unknown` 三种情形，经 [Known] 也穷尽）；
 * 不便匹配时用 [valueOrThrow]。`as? Lookup.Present` 一类的写法会把 [Unknown] 静默当作不存在。
 */
sealed interface Lookup<out V> {
    /**
     * 存在时返回值，确认不存在时返回 `null`，[Unknown] 时抛出它的 [Unknown.error]。
     */
    fun valueOrThrow(): V?

    /**
     * 关于数据的答案：存在（[Present]）或确认不存在（[Absent]），即 [Unknown] 之外的两种结果。只返回答案的读取（如权威类型的
     * `lookupRemote`）以它为返回类型：没有答案时它们抛出，调用方只需匹配两种情形。
     */
    sealed interface Known<out V> : Lookup<V>

    /**
     * 命中值。
     *
     * @param expiresAt 返回的这份副本的到期时间（命中 L2 时是 L2 副本的到期时间），精度为缓存时钟的刻度；`null` 表示永不过期
     */
    data class Present<out V>(val value: V, val expiresAt: Instant?) : Known<V> {
        override fun valueOrThrow(): V = value
    }

    /**
     * 确认不存在：负缓存、权威副本的 L1 未命中，或外部来源的缓存的“未填充”。
     *
     * @param expiresAt 同 [Present.expiresAt]
     */
    data class Absent(val expiresAt: Instant?) : Known<Nothing> {
        override fun valueOrThrow(): Nothing? = null
    }

    /**
     * 这一次读取没有答案：只来自 L1 故障（含命令超时）。它不是关于数据的事实，不进入任何一层，也不交给本可以自己回源的调用方。
     *
     * @param error `get` 会抛出的异常（`cause` 为原始的 L1 故障）
     */
    class Unknown(val error: CacheReadException) : Lookup<Nothing> {
        override fun valueOrThrow(): Nothing = throw error

        override fun toString(): String = "Unknown(${error.cause})"
    }
}
