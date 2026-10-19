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
import java.security.MessageDigest

/**
 * L1 内容的 SHA-1 摘要，供条件回收（[RemoteStore.reclaim]）比较“内容是否仍是读到的那一份”。
 *
 * 只在解码失败时计算，命中路径不付出哈希成本。计算方式属于线格式：
 * - 标量：载荷字节。
 * - 集合：按 L1 返回顺序拼接 `int32 长度 + 成员字节`。
 * - 负缓存：空字节串。
 */
class Digest(sha1: ByteArray) {
    private val value = sha1.copyOf()

    val sha1: ByteArray
        get() = value.copyOf()

    override fun equals(other: Any?): Boolean {
        return this === other || (other is Digest && value.contentEquals(other.value))
    }

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = value.toHex()

    companion object {
        @JvmStatic
        fun of(envelope: Envelope): Digest {
            val digest = MessageDigest.getInstance("SHA-1")
            when (envelope) {
                is Envelope.Scalar -> digest.update(envelope.bytes)
                is Envelope.Members -> envelope.members.forEach {
                    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(it.size).array())
                    digest.update(it)
                }

                Envelope.Absent -> Unit
            }
            return Digest(digest.digest())
        }
    }
}
