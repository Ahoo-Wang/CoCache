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

package me.ahoo.cocache.redis

import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * 线格式 2 的客户端一侧（[RedisScripts] 的 Kotlin 函数与 `RedisL1Store.decodeRead`），不需要 Redis：写入形状、载荷偏移、
 * 扩展区的 TLV 与集合的格式字段。与 Lua 一侧的一致性见 [WireFormat2Test]。
 */
class WireFormatTest {
    private val writer = Token(NodeId.random(), 42)

    private fun header(flags: Int, state: Char = 'V', format: Int = RedisScripts.FORMAT): ByteArray =
        ByteBuffer.allocate(RedisScripts.HEADER_SIZE)
            .put(RedisScripts.MAGIC)
            .put(format.toByte())
            .put(state.code.toByte())
            .put(flags.toByte())
            .array()

    private fun decode(raw: ByteArray): L1Read.Hit = RedisL1Store.decodeRead(listOf(2L, -1L, raw, 0L)) as L1Read.Hit

    /**
     * 形状：没有段时是共享的 2 字节常量；版本段在扩展区之前；写入标识的扩展区是 28 字节（总长 26、类型 1、长度 24）。
     */
    @Test
    fun scalarShapesFollowTheFlagOrder() {
        val plain = RedisScripts.scalarShape('V'.code.toByte(), Versioned.NO_VERSION, null)
        plain.assert().isEqualTo(byteArrayOf('V'.code.toByte(), 0))
        RedisScripts.scalarShape('V'.code.toByte(), Versioned.NO_VERSION, null).assert().isSameAs(plain)
        RedisScripts.scalarShape(
            'M'.code.toByte(),
            Versioned.NO_VERSION,
            null
        ).assert().isEqualTo(byteArrayOf('M'.code.toByte(), 0))

        val both = RedisScripts.scalarShape('V'.code.toByte(), 0x0102030405060708L, writer)
        both.size.assert().isEqualTo(2 + 8 + 28)
        both[1].assert().isEqualTo(0x81.toByte())
        ByteBuffer.wrap(both, 2, 8).long.assert().isEqualTo(0x0102030405060708L)
        both.copyOfRange(10, 14).assert().isEqualTo(byteArrayOf(0, 26, 1, 24))
        both.copyOfRange(14, 38).assert().isEqualTo(writer.toBytes())

        val versionOnly = RedisScripts.scalarShape('M'.code.toByte(), -1, null)
        versionOnly.assert().isEqualTo(byteArrayOf('M'.code.toByte(), 1) + ByteBuffer.allocate(8).putLong(-1).array())
    }

    /**
     * 载荷偏移由标志位计算：版本段 8、摘要段 32、扩展区 2 + 总长；段超出值的长度时为 -1。
     */
    @Test
    fun payloadOffsetSkipsTheDeclaredSegments() {
        val size = RedisScripts.HEADER_SIZE
        RedisScripts.payloadOffset(ByteArray(size), 0).assert().isEqualTo(size)
        RedisScripts.payloadOffset(ByteArray(size + 8), 0x01).assert().isEqualTo(size + 8)
        RedisScripts.payloadOffset(ByteArray(size + 40), 0x03).assert().isEqualTo(size + 40)
        val area = ByteArray(size + 8 + 2 + 3).also { it[size + 8 + 1] = 3 }
        RedisScripts.payloadOffset(area, 0x81).assert().isEqualTo(area.size)
        RedisScripts.extensionOffset(0x81).assert().isEqualTo(size + 8)
        RedisScripts.extensionOffset(0x83).assert().isEqualTo(size + 40)
        // 版本段、扩展区的总长字段、扩展区本身超出长度
        RedisScripts.payloadOffset(ByteArray(size + 7), 0x01).assert().isEqualTo(-1)
        RedisScripts.payloadOffset(ByteArray(size + 1), 0x80).assert().isEqualTo(-1)
        RedisScripts.payloadOffset(area.copyOf(area.size - 1), 0x81).assert().isEqualTo(-1)
    }

    /**
     * 扩展区的 TLV：跳过未知的类型（含 2 字节长度），取第一个类型 1；长度不是 24、或 TLV 超出扩展区时没有写入标识。
     */
    @Test
    fun writerIdIsTheFirstWellFormedType1Tlv() {
        fun area(vararg tlvs: ByteArray): ByteArray {
            val body = tlvs.fold(ByteArray(0)) { acc, tlv -> acc + tlv }
            return byteArrayOf((body.size shr 8).toByte(), body.size.toByte()) + body
        }
        val writerTlv = byteArrayOf(1, 24) + writer.toBytes()
        val long = byteArrayOf(9, 0x81.toByte(), 0x2C) + ByteArray(300)
        RedisScripts.writerOf(area(long, writerTlv), 0).assert().isEqualTo(writer)
        RedisScripts.writerOf(area(writerTlv, byteArrayOf(1, 24) + ByteArray(24)), 0).assert().isEqualTo(writer)
        RedisScripts.writerOf(area(byteArrayOf(1, 3, 1, 2, 3)), 0).assert().isNull()
        RedisScripts.writerOf(area(byteArrayOf(9, 50, 1)), 0).assert().isNull()
        RedisScripts.writerOf(area(byteArrayOf(9)), 0).assert().isNull()
        RedisScripts.writerOf(area(byteArrayOf(9, 0x80.toByte())), 0).assert().isNull()
        RedisScripts.writerOf(area(), 0).assert().isNull()
        // 扩展区之后的字节（载荷）不属于扩展区
        RedisScripts.writerOf(area() + writerTlv, 0).assert().isNull()
    }

    /**
     * 客户端的分类与脚本的 `scalar_format` 一致：先看格式号，再看标志位与状态，最后看段的长度。
     */
    @Test
    fun decodeClassifiesFormatFlagsAndState() {
        val payload = "p".toByteArray()
        val version = ByteBuffer.allocate(8).putLong(5).array()
        val extension = byteArrayOf(0, 26, 1, 24) + writer.toBytes()
        decode(header(0) + payload).envelope.assert().isEqualTo(Envelope.Scalar(payload))
        decode(header(0x01) + version + payload).envelope.assert().isEqualTo(Envelope.Scalar(payload, 5))
        decode(header(0x80) + extension + payload).envelope.assert()
            .isEqualTo(Envelope.Scalar(payload, Versioned.NO_VERSION, writer))
        decode(
            header(0x81) + version + extension + payload
        ).envelope.assert().isEqualTo(Envelope.Scalar(payload, 5, writer))
        decode(header(0x02) + ByteArray(32) + payload).envelope.assert().isEqualTo(Envelope.Scalar(payload))
        decode(header(0x01, state = 'M') + version).envelope.assert().isEqualTo(Envelope.Absent)

        for (bit in 2..6) {
            decode(header(1 shl bit) + payload).format.assert().isEqualTo(RedisScripts.NEWER_FORMAT)
        }
        decode(header(0, state = 'E')).format.assert().isEqualTo(RedisScripts.NEWER_FORMAT)
        decode(header(0, format = 1) + payload).format.assert().isEqualTo(1)
        decode(header(0, format = 3) + payload).format.assert().isEqualTo(3)
        val zero = header(0, format = 0) + payload
        decode(zero).format.assert().isEqualTo(RedisScripts.UNRECOGNIZED)
        decode(zero).envelope.assert().isEqualTo(Envelope.Scalar(zero))
        val truncated = header(0x01) + byteArrayOf(1, 2)
        decode(truncated).format.assert().isEqualTo(RedisScripts.UNRECOGNIZED)
        decode(truncated).envelope.assert().isEqualTo(Envelope.Scalar(truncated))
    }

    /**
     * 集合的格式（K10）：`!f` 是至多 9 位的十进制数字，否则为 0；`!f = 2` 而 `!m` 存在且不是 `'0'` 时为更高格式。
     */
    @Test
    fun setFormatReadsTheFormatAndTheMustUnderstandBitmap() {
        fun format(f: String, m: String = "") = RedisScripts.setFormat(f.toByteArray(), m.toByteArray())
        format("2").assert().isEqualTo(RedisScripts.FORMAT)
        format("2", "0").assert().isEqualTo(RedisScripts.FORMAT)
        format("2", "1").assert().isEqualTo(RedisScripts.NEWER_FORMAT)
        format("2", "x").assert().isEqualTo(RedisScripts.NEWER_FORMAT)
        format("1", "1").assert().isEqualTo(1)
        format("3").assert().isEqualTo(3)
        listOf("", "x", "-1", "+2", "1234567890").forEach { format(it).assert().isEqualTo(RedisScripts.UNRECOGNIZED) }
    }
}
