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
 * 载荷的摘要（RFC 0005 §4.3、§4.7，D1）：SHA-256（32 字节），带算法标识 [algorithm]。比较“内容是否仍是读到的那一份”：
 * 条件读取与条件写入（[ConditionalL1Store.readIfChanged]、[ConditionalL1Store.putIfMatch]，用户 API 为
 * [me.ahoo.cocache.api.Cache.getIfChanged]、[me.ahoo.cocache.api.Cache.putIfMatch]）。
 *
 * 摘要由写入方在客户端计算（[of]、[ofPayload]），随 [Envelope.Scalar.digest] 写成 L1 头部的摘要段；条件读写的脚本只比较头部中的字节，
 * 读取方拿到载荷时重算并与头部比较（K3）。条件回收比较的是 SPI 自己的 [ContentFingerprint]（round 3 K12），与本类型无关。
 *
 * 计算方式属于线格式：
 * - 标量：载荷字节（`Codec.encode` 的结果），与头部（令牌、格式版本、段）和到期时间无关；`Codec` 对同一个值产生不同字节时摘要也不同。
 * - 集合：按 L1 返回顺序拼接 `int32 长度 + 成员字节`。
 * - 负缓存：空字节串。
 *
 * 条件读写只比较带摘要段的标量值：负缓存与 lease 占位是“没有值”，不按摘要比较。
 *
 * @param algorithm 算法标识，目前只有 [SHA_256]
 * @param bytes 摘要的字节（[SIZE] 字节）
 */
class Digest(val algorithm: String, bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(algorithm == SHA_256) { "Unsupported digest algorithm[$algorithm]: only $SHA_256." }
        require(value.size == SIZE) { "A $SHA_256 digest is $SIZE bytes, not ${value.size}." }
    }

    /**
     * SHA-256 摘要：[bytes] 为 32 字节。
     */
    constructor(bytes: ByteArray) : this(SHA_256, bytes)

    /**
     * 摘要的字节（副本）。
     */
    val bytes: ByteArray
        get() = value.copyOf()

    override fun equals(other: Any?): Boolean {
        return this === other || (other is Digest && algorithm == other.algorithm && value.contentEquals(other.value))
    }

    override fun hashCode(): Int = 31 * algorithm.hashCode() + value.contentHashCode()

    /**
     * `算法:小写十六进制`，如 `SHA-256:9f86…`。
     */
    override fun toString(): String = algorithm + ":" + value.toHex()

    companion object {
        /**
         * 算法标识：SHA-256。
         */
        const val SHA_256: String = "SHA-256"

        /**
         * SHA-256 摘要的字节数。
         */
        const val SIZE: Int = 32

        /**
         * [envelope] 的摘要：标量为载荷，集合为按顺序拼接的 `int32 长度 + 成员`，负缓存为空串。不复制标量的载荷。
         */
        @JvmStatic
        fun of(envelope: Envelope): Digest {
            val digest = MessageDigest.getInstance(SHA_256)
            when (envelope) {
                is Envelope.Scalar -> digest.update(envelope.payload)
                is Envelope.Members -> envelope.members.forEach {
                    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(it.size).array())
                    digest.update(it)
                }

                Envelope.Absent -> Unit
            }
            return Digest(SHA_256, digest.digest())
        }

        /**
         * 载荷字节 [payload] 的摘要（与 [of] 一个标量的结果相同）。
         */
        @JvmStatic
        fun ofPayload(payload: ByteArray): Digest = Digest(SHA_256, MessageDigest.getInstance(SHA_256).digest(payload))
    }
}
