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
 * L1 中一个 key 的内容（不含控制信息）。字节内容由 [me.ahoo.cocache.api.Codec] 产生。
 */
sealed interface Envelope {
    /**
     * 标量值。
     */
    class Scalar(bytes: ByteArray) : Envelope {
        private val value = bytes.copyOf()

        val bytes: ByteArray
            get() = value.copyOf()

        override fun equals(other: Any?): Boolean {
            return this === other || (other is Scalar && value.contentEquals(other.value))
        }

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Scalar(${value.size} bytes)"
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
