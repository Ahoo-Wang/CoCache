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
 * L1 内容的 SHA-1 摘要，比较“内容是否仍是读到的那一份”：条件回收（[RemoteStore.reclaim]）、
 * 条件读取与条件写入（[RemoteStore.readIfChanged]、[RemoteStore.putIfMatch]，用户 API 为
 * [me.ahoo.cocache.api.Cache.getIfChanged]、[me.ahoo.cocache.api.Cache.putIfMatch]）。
 *
 * 普通读取的命中路径不计算它（只在解码失败、条件读写时计算）。计算方式属于线格式：
 * - 标量：载荷字节。
 * - 集合：按 L1 返回顺序拼接 `int32 长度 + 成员字节`。
 * - 负缓存：空字节串。
 *
 * 标量的摘要只取决于 `Codec.encode` 的结果，与头部（令牌、格式版本）和到期时间无关；`Codec` 对同一个值产生不同字节时
 * 摘要也不同。条件读写只比较标量值：负缓存与载荷为空的标量摘要相同，因此它们把负缓存当作“没有值”，不按摘要比较。
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
