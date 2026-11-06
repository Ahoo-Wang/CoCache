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
 * 它是 SPI 自己的类型，与条件读写的 [Digest]（SHA-256，写入方计算、写在头部的摘要段中）是两个类型：算法固定为 SHA-1
 * （存储在 L1 中按同样的方式现算），用途只是回收，与安全无关（RFC 0005 §4.7）。计算方式属于线格式，依次哈希：
 * 1 字节的内容类别、读取报告的格式版本（[L1Read.Hit.format]，大端 `int32`）、内容的字节：
 * - 标量：类别 `V`，内容为载荷字节（读不懂的内容为读到的全部字节）。
 * - 负缓存：类别 `M`，内容为空。
 * - 集合：类别 `S`，内容为按 L1 返回顺序拼接的 `int32 长度 + 成员字节`。
 *
 * 类别与格式让字节相同、形状不同的内容互不相等：格式升级前写入的读不懂的值与之后按本格式写入的同一段文本、空标量与负缓存、
 * 内容恰为成员编码的标量与集合。否则回收会把读取之后才写入的新值当作读到的那一份转为 lease，迟到的回源再把旧快照写回 L1（#834）。
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
         * 内容类别：标量、负缓存、集合（与 Redis 头部的状态字节 `V`、`M` 同值；集合是 Hash，没有状态字节）。
         */
        private const val SCALAR: Byte = 'V'.code.toByte()
        private const val NEGATIVE: Byte = 'M'.code.toByte()
        private const val SET: Byte = 'S'.code.toByte()

        /**
         * 一次读取得到的、读不懂或解码失败的内容的指纹：[envelope] 与 [format] 取自同一个 [L1Read.Hit]
         * （[L1Read.Hit.envelope] 与 [L1Read.Hit.format]）。
         */
        @JvmStatic
        fun of(envelope: Envelope, format: Int): ContentFingerprint {
            val digest = MessageDigest.getInstance("SHA-1")
            when (envelope) {
                is Envelope.Scalar -> {
                    digest.prefix(SCALAR, format)
                    digest.update(envelope.payload)
                }

                is Envelope.Members -> {
                    digest.prefix(SET, format)
                    envelope.members.forEach {
                        digest.update(int32(it.size))
                        digest.update(it)
                    }
                }

                Envelope.Absent -> digest.prefix(NEGATIVE, format)
            }
            return ContentFingerprint(digest.digest())
        }

        private fun MessageDigest.prefix(kind: Byte, format: Int) {
            update(kind)
            update(int32(format))
        }

        private fun int32(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
    }
}
