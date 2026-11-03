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
 * 条件回收（[L1Store.reclaim]）比较的内容指纹（RFC 0005 §4.3，round 3 K12）：判断读不懂的内容是否仍是读到的那一份。
 *
 * 它是 SPI 自己的类型，与条件读写的 [Digest] 是两个类型：算法固定为 SHA-1（存储在 L1 中按同样的方式现算），用途只是回收，与安全无关；
 * [Digest] 之后改为 SHA-256（RFC 0005 §4.7），本类型不变。计算方式属于线格式：
 * - 标量：载荷字节（读不懂的内容为读到的全部字节）。
 * - 集合：按 L1 返回顺序拼接 `int32 长度 + 成员字节`。
 * - 负缓存：空字节串。
 *
 * @param sha1 20 字节的 SHA-1
 */
class ContentFingerprint(sha1: ByteArray) {
    private val value = sha1.copyOf()

    init {
        require(value.size == SIZE) { "Content fingerprint must be $SIZE bytes, not ${value.size}." }
    }

    /**
     * 指纹的字节（副本）。
     */
    val bytes: ByteArray
        get() = value.copyOf()

    override fun equals(other: Any?): Boolean {
        return this === other || (other is ContentFingerprint && value.contentEquals(other.value))
    }

    override fun hashCode(): Int = value.contentHashCode()

    /**
     * 小写十六进制（存储以它与 L1 中现算的指纹比较）。
     */
    override fun toString(): String = value.toHex()

    companion object {
        /**
         * 指纹的字节数（SHA-1）。
         */
        const val SIZE: Int = 20

        /**
         * [envelope] 的内容指纹：读取得到的、读不懂或解码失败的内容。
         */
        @JvmStatic
        fun of(envelope: Envelope): ContentFingerprint {
            val digest = MessageDigest.getInstance("SHA-1")
            when (envelope) {
                is Envelope.Scalar -> digest.update(envelope.bytes)
                is Envelope.Members -> envelope.members.forEach {
                    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(it.size).array())
                    digest.update(it)
                }

                Envelope.Absent -> Unit
            }
            return ContentFingerprint(digest.digest())
        }
    }
}
