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
}
