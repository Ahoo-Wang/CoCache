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

import me.ahoo.cocache.api.spi.Digest

/**
 * [Cache.getIfChanged] 的结果：L1（共享存储，本缓存的权威副本）中这个 key 的当前状态。
 *
 * “没有值”（[Absent]）包括：key 不存在、处于 lease 占位（其它节点正在回源）、负缓存。
 * 与 [Cache.putIfMatch] 的 `expected = null` 匹配的正是这些状态。
 */
sealed interface ConditionalGet<out V> {
    /**
     * L1 中的值的摘要仍等于调用方给出的 `known`：没有传输值的正文。
     */
    data object Unchanged : ConditionalGet<Nothing>

    /**
     * L1 中有值，且摘要不等于 `known`（或 `known` 为 `null`）。
     *
     * @param digest 这份值在 L1 中的字节（[Codec.encode] 的结果）的摘要，可作为下一次 `getIfChanged` 的 `known`
     *   或 `putIfMatch` 的 `expected`
     */
    data class Present<out V>(val value: V, val digest: Digest) : ConditionalGet<V>

    /**
     * L1 中没有值。
     */
    data object Absent : ConditionalGet<Nothing>
}

/**
 * [Cache.putIfMatch] 的结果。
 */
sealed interface ConditionalPut {
    /**
     * 已写入 L1 并广播失效。
     *
     * @param digest 写入的值的摘要（与随后 [Cache.getIfChanged] 读到的相同）
     */
    data class Written(val digest: Digest) : ConditionalPut

    /**
     * L1 中的当前内容与 `expected` 不符：没有写入，也没有广播。调用方重新读取（`getIfChanged`）后重试。
     *
     * @param current L1 中当前值的摘要；`null` 表示 L1 中没有值（见 [ConditionalGet.Absent]）
     */
    data class Conflict(val current: Digest?) : ConditionalPut
}
