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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class InvalidationFrameTest {
    private val origin = NodeId.random()

    @Test
    fun roundTripsArbitraryKeys() {
        val longKey = "x".repeat(70_000)
        val keys = listOf("plain", "with@@delimiter", "中文", "", longKey)
        val decoded = InvalidationFrame.decode(InvalidationFrame.encode(origin, keys))
        decoded.origin.assert().isEqualTo(origin)
        decoded.keys.assert().isEqualTo(keys)
    }

    /**
     * key 按 UTF-8 编码：补充平面字符（正确配对的 UTF-16 代理）无损往返；含未配对代理的 `String` 编不出来，被替换为 `?`，
     * 解码出的是另一个 key。所以引擎与 `ExternalInvalidator` 在 key 到达 L1 之前拒绝后者，帧本身不检查。
     */
    @Test
    fun roundTripsWellFormedUtf16AndReplacesUnpairedSurrogates() {
        val wellFormed = listOf("😀", "key-𝄞", "􏿿")
        InvalidationFrame.decode(InvalidationFrame.encode(origin, wellFormed)).keys.assert().isEqualTo(wellFormed)
        InvalidationFrame.encode(origin, listOf("😀")).takeLast(4).assert()
            .isEqualTo(listOf(0xF0, 0x9F, 0x98, 0x80).map { it.toByte() })
        InvalidationFrame.decode(InvalidationFrame.encode(origin, listOf("bad-\uD800", "\uDC00x"))).keys.assert()
            .containsExactly("bad-?", "?x")
    }

    /**
     * I-9：帧的字节布局在一个大版本内冻结：`[版本 0x01][nodeId:16B][count:varint]([len:varint][key UTF-8])*`，varint 是
     * 无符号 LEB128（低 7 位在前）。往返用例发现不了编码与解码一起改变，这里固定字节。
     */
    @Test
    fun encodesTheFrozenLayout() {
        val nodeBytes = ByteArray(NodeId.SIZE) { it.toByte() }
        val node = NodeId(nodeBytes)
        val longKey = "x".repeat(128)
        InvalidationFrame.encode(node, listOf("a", "中", longKey)).assert().isEqualTo(
            byteArrayOf(0x01) + nodeBytes + byteArrayOf(3) +
                byteArrayOf(1, 'a'.code.toByte()) +
                byteArrayOf(3, 0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte()) +
                byteArrayOf(0x80.toByte(), 0x01) + longKey.toByteArray()
        )
        InvalidationFrame.encode(node, emptyList()).assert().isEqualTo(byteArrayOf(0x01) + nodeBytes + byteArrayOf(0))
    }

    @Test
    fun roundTripsEmptyKeyList() {
        InvalidationFrame.decode(InvalidationFrame.encode(origin, emptyList())).keys.assert().isEmpty()
    }

    @Test
    fun rejectsUnknownVersion() {
        val frame = InvalidationFrame.encode(origin, listOf("k"))
        frame[0] = 9
        runCatching {
            InvalidationFrame.decode(frame)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 帧版本 2 预留给“namespace 重置”（RFC 0005 §4.7，D3）：8.0 不解码它，通道按读不懂的帧重置订阅者。
     */
    @Test
    fun rejectsReservedNamespaceResetVersion() {
        InvalidationFrame.NAMESPACE_RESET_VERSION.assert().isEqualTo(2.toByte())
        val frame = InvalidationFrame.encode(origin, listOf("k"))
        frame[0] = InvalidationFrame.NAMESPACE_RESET_VERSION
        runCatching {
            InvalidationFrame.decode(frame)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsTruncatedFrame() {
        val frame = InvalidationFrame.encode(origin, listOf("key"))
        val error = runCatching { InvalidationFrame.decode(frame.copyOf(frame.size - 1)) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsTrailingBytes() {
        val frame = InvalidationFrame.encode(origin, listOf("key")) + byteArrayOf(1)
        runCatching {
            InvalidationFrame.decode(frame)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsOverlongVarint() {
        val frame = byteArrayOf(InvalidationFrame.VERSION) + ByteArray(NodeId.SIZE) + ByteArray(6) { 0xFF.toByte() }
        runCatching {
            InvalidationFrame.decode(frame)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsNegativeLength() {
        val frame = byteArrayOf(InvalidationFrame.VERSION) + ByteArray(NodeId.SIZE) +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F)
        runCatching {
            InvalidationFrame.decode(frame)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsKeyCountBeyondRemainingBytesWithoutAllocating() {
        // count = Int.MAX_VALUE：若按计数预分配会 OOM
        val maxVarint = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07)
        val frame = byteArrayOf(InvalidationFrame.VERSION) + ByteArray(NodeId.SIZE) + maxVarint
        val error = runCatching { InvalidationFrame.decode(frame) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Key count")
    }

    @Test
    fun rejectsKeyLengthBeyondRemainingBytesWithoutAllocating() {
        val maxVarint = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07)
        val frame = byteArrayOf(InvalidationFrame.VERSION) + ByteArray(NodeId.SIZE) + byteArrayOf(1) + maxVarint
        val error = runCatching { InvalidationFrame.decode(frame) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Key length")
    }

    /**
     * 缺陷复现（#995）：非法的 UTF-8 曾按替换字符解码，失效落到另一个 key 上；现在按读不懂的帧拒绝。
     */
    @Test
    fun rejectsMalformedUtf8() {
        listOf(
            byteArrayOf(0xFF.toByte()),
            byteArrayOf('a'.code.toByte(), 0xC3.toByte()),
            // 编码的代理（surrogate）不是合法的 UTF-8
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        ).forEach { key ->
            val frame = byteArrayOf(InvalidationFrame.VERSION) + ByteArray(NodeId.SIZE) + byteArrayOf(1, key.size.toByte()) + key
            runCatching { InvalidationFrame.decode(frame) }.exceptionOrNull().assert()
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("UTF-8")
        }
    }

    /**
     * 合法的 U+FFFD 照常解码。
     */
    @Test
    fun acceptsALegalReplacementCharacter() {
        val origin = NodeId.random()
        InvalidationFrame.decode(
            InvalidationFrame.encode(origin, listOf("a\uFFFDb"))
        ).keys.assert().containsExactly("a\uFFFDb")
    }

    /**
     * 缺陷复现（#995）：溢出 `Int` 的 varint 曾在左移时截断（`80 80 80 80 10` 即 2^32 变成 0），帧被当作合法的探测帧。计数与长度都拒绝。
     */
    @Test
    fun rejectsAVarintThatOverflowsAnInt() {
        val twoTo32 = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10)
        val asCount = byteArrayOf(InvalidationFrame.VERSION) + ByteArray(NodeId.SIZE) + twoTo32
        val asLength = byteArrayOf(InvalidationFrame.VERSION) + ByteArray(NodeId.SIZE) + byteArrayOf(1) + twoTo32
        listOf(asCount, asLength).forEach { frame ->
            runCatching { InvalidationFrame.decode(frame) }.exceptionOrNull().assert()
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("overflows")
        }
        // 第五个字节带后续位同样溢出
        val continued = byteArrayOf(InvalidationFrame.VERSION) + ByteArray(NodeId.SIZE) +
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x81.toByte(), 0x00)
        runCatching { InvalidationFrame.decode(continued) }.exceptionOrNull().assert()
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 对照：五个字节的最大非负值（`FF FF FF FF 07` 即 `Int.MAX_VALUE`）照常解码为计数，再因超出剩余字节被拒绝；四个字节以内的值不受影响。
     */
    @Test
    fun acceptsTheLargestNonNegativeVarint() {
        val keys = List(200) { "k$it" }
        InvalidationFrame.decode(InvalidationFrame.encode(NodeId.random(), keys)).keys.assert().isEqualTo(keys)
        val long = "x".repeat(20_000)
        InvalidationFrame.decode(
            InvalidationFrame.encode(NodeId.random(), listOf(long))
        ).keys.assert().containsExactly(long)
    }
}
