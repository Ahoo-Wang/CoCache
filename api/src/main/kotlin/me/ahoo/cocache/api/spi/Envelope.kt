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

import me.ahoo.cocache.api.Versioned

/**
 * L1 中一个 key 的内容（不含控制信息）。字节内容由 [me.ahoo.cocache.api.Codec] 产生。
 */
sealed interface Envelope {
    /**
     * 标量值。
     *
     * @param version 与值一起保存的版本（6.8，按版本有序的写入）；[Versioned.NO_VERSION] 表示没有版本。
     *   支持版本的存储（[L1Store.versioned] 非 `null`）在 [L1Store.fill]、[L1Store.put]、[L1Store.putAll] 时保存它，
     *   读取时原样返回（[ConditionalL1Store.putIfMatch] 写入没有版本的值）；版本不属于载荷，不影响 [Digest]
     * @param writer 写入标识（RFC 0005 §4.3“keyspace 与段”、§4.7，D2）：与 [Token] 同形（[NodeId] + 序号），每次写入新取一个，
     *   全局唯一、永不重复。由引擎给出：没有数据源的缓存（M5 之前充当权威标量）的每次标量写入都带它，有数据源的缓存不带（`null`）。
     *   存储在每个写入标量的操作（[L1Store.fill]、[L1Store.fillAll]、[L1Store.put]、[L1Store.putAll]、
     *   [ConditionalL1Store.putIfMatch]、[VersionedL1Store.putIfNewer]）中原样保存它，[L1Store.read] 与 [L1Store.readAll] 原样返回。
     *   它为之后按标识比较的条件写入（现值 ABA）而写；本版本的引擎不读它。不属于载荷，不影响 [Digest]
     */
    class Scalar @JvmOverloads constructor(
        bytes: ByteArray,
        val version: Long = Versioned.NO_VERSION,
        val writer: Token? = null,
    ) : Envelope {
        private val value = bytes.copyOf()

        val bytes: ByteArray
            get() = value.copyOf()

        override fun equals(other: Any?): Boolean {
            return this === other ||
                (other is Scalar && version == other.version && writer == other.writer && value.contentEquals(other.value))
        }

        override fun hashCode(): Int = 31 * (31 * value.contentHashCode() + version.hashCode()) + writer.hashCode()

        override fun toString(): String = buildString {
            append("Scalar(").append(value.size).append(" bytes")
            if (version != Versioned.NO_VERSION) {
                append(", version=").append(version)
            }
            writer?.let { append(", writer=").append(it) }
            append(')')
        }
    }

    /**
     * 集合值：成员按 L1 返回的顺序排列，不含重复内容。
     */
    class Members(members: List<ByteArray>) : Envelope {
        private val value: List<ByteArray> = members.map { it.copyOf() }

        val members: List<ByteArray>
            get() = value.map { it.copyOf() }

        val size: Int
            get() = value.size

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is Members || other.value.size != value.size) {
                return false
            }
            return value.indices.all { value[it].contentEquals(other.value[it]) }
        }

        override fun hashCode(): Int = value.fold(1) { acc, bytes -> 31 * acc + bytes.contentHashCode() }

        override fun toString(): String = "Members(${value.size})"
    }

    /**
     * 负缓存：数据源确认不存在。
     */
    data object Absent : Envelope
}
