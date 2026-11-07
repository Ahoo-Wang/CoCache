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

package me.ahoo.cocache.api.spi

/**
 * [ConditionalL1Store.readIfChanged] 的结果。
 */
sealed interface L1ConditionalRead {
    /**
     * key 中是带摘要段的标量值，其摘要段等于 `known`：不返回正文。
     *
     * @param format 当前值的线格式版本：摘要只覆盖载荷，引擎据此确认这个值仍是本节点读得懂的格式
     */
    data class Unchanged(val format: Int) : L1ConditionalRead

    /**
     * key 中是带摘要段的标量值，摘要段不等于 `known`（或 `known` 为 `null`）。[envelope] 带存储读到的摘要段，引擎重算校验。
     *
     * @param format 线格式版本：引擎只解码不高于本节点版本、不低于 1 的值，其余按 [Unreadable] 处理
     */
    class Changed(val envelope: Envelope.Scalar, val format: Int) : L1ConditionalRead {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Changed && format == other.format && envelope == other.envelope)

        override fun hashCode(): Int = 31 * envelope.hashCode() + format

        override fun toString(): String = "Changed($envelope, format=$format)"
    }

    /**
     * key 中没有值：不存在、lease 占位或不带版本的负缓存。
     */
    data object Absent : L1ConditionalRead

    /**
     * key 中是不能当作带摘要段的标量值的内容：不是本协议写入的内容（`0`）、本协议的集合、没有摘要段的值与带版本的内容（值、墓碑、`E`，
     * 都是本协议的版本）、更高版本写入的值（其版本）。不返回正文。
     */
    data class Unreadable(val format: Int) : L1ConditionalRead
}

/**
 * [ConditionalL1Store.putIfMatch] 与 [ConditionalL1Store.deleteIfMatch] 的结果。
 */
sealed interface L1ConditionalWrite {
    /**
     * 已写入（`deleteIfMatch`：已删除）并广播。
     */
    data object Written : L1ConditionalWrite

    /**
     * 当前内容与期望不符：未修改、未广播。
     *
     * @param current 当前值的摘要段；`null` 表示没有值（不存在、lease 占位、不带版本的负缓存）
     */
    data class Conflict(val current: Digest?) : L1ConditionalWrite

    /**
     * key 中是不能当作本协议标量值的内容（同 [L1ConditionalRead.Unreadable]），从不视为没有值：未修改、未广播。
     */
    data class Unreadable(val format: Int) : L1ConditionalWrite
}

/**
 * [VersionedL1Store.putIfNewer] 的结果。
 */
sealed interface L1VersionedWrite {
    /**
     * 已写入并广播。
     */
    data object Written : L1VersionedWrite

    /**
     * key 中的版本不小于给出的版本：未修改、未广播。
     *
     * @param current key 中的版本
     */
    data class Stale(val current: Long) : L1VersionedWrite

    /**
     * key 中是不能当作本协议标量值的内容（同 [L1ConditionalWrite.Unreadable]）：未修改、未广播。
     */
    data class Unreadable(val format: Int) : L1VersionedWrite
}

/**
 * [VersionedL1Store.evictKeepingVersion] 的结果。
 */
sealed interface L1Eviction {
    /**
     * 已淘汰并广播：带版本的内容转为 `E(版本)`，没有版本的内容已删除。
     */
    data object Evicted : L1Eviction

    /**
     * key 中是带摘要段的内容（权威标量，D4）：保留版本的失效不适用于它，未修改、未广播。
     */
    data object Authoritative : L1Eviction

    /**
     * key 中是读不懂的内容（同 [L1VersionedWrite.Unreadable]）：未修改、未广播。
     */
    data class Unreadable(val format: Int) : L1Eviction
}

/**
 * [SetL1Store.addMembers] 与 [SetL1Store.removeMembers] 的结果。
 */
sealed interface L1SetWrite {
    /**
     * 已执行并广播（`removeMembers` 在 key 不存在、为负缓存或处于 lease 状态时不修改 key，同样广播）。
     */
    data object Written : L1SetWrite

    /**
     * key 中是不能当作本协议集合的内容：不是本协议写入的内容（`0`）、本协议的标量值（其格式版本）、更高格式版本写入的集合
     * （其格式版本）。未修改、未广播。
     *
     * @param format 该内容的格式版本，`0` 表示不是本协议写入
     */
    data class Unreadable(val format: Int) : L1SetWrite
}
