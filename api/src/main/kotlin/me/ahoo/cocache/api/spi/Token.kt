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

import java.nio.ByteBuffer

/**
 * lease / 刷新令牌（24 字节）：[node] + 进程内单调递增的 [seq]。
 *
 * 由客户端构造，因此全局唯一、永不重复签发：被失效销毁的令牌不可能再次匹配（无 ABA）。
 */
class Token(val node: NodeId, val seq: Long) {
    fun toBytes(): ByteArray {
        return ByteBuffer.allocate(SIZE).put(node.bytes).putLong(seq).array()
    }

    override fun equals(other: Any?): Boolean {
        return this === other || (other is Token && seq == other.seq && node == other.node)
    }

    override fun hashCode(): Int = 31 * node.hashCode() + seq.hashCode()

    override fun toString(): String = "Token($node:$seq)"

    companion object {
        const val SIZE = NodeId.SIZE + Long.SIZE_BYTES

        @JvmStatic
        fun fromBytes(bytes: ByteArray): Token {
            require(bytes.size == SIZE) { "Token must be $SIZE bytes, but was ${bytes.size}." }
            val buffer = ByteBuffer.wrap(bytes)
            val node = ByteArray(NodeId.SIZE).also { buffer.get(it) }
            return Token(NodeId(node), buffer.getLong())
        }
    }
}
