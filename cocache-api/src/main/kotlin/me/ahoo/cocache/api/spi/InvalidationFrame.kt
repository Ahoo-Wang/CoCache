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

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 失效消息帧（线格式）：`[版本:1][nodeId:16][count:varint]([len:varint][key UTF-8])*`。
 *
 * 带长度前缀，对 key 的内容没有任何限制；一条消息可以携带多个 key。
 */
object InvalidationFrame {
    const val VERSION: Byte = 1

    /**
     * 解码结果。
     */
    data class Decoded(val origin: NodeId, val keys: List<String>)

    @JvmStatic
    fun encode(origin: NodeId, keys: Collection<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION.toInt())
        out.write(origin.bytes)
        out.writeVarint(keys.size)
        for (key in keys) {
            val bytes = key.toByteArray(Charsets.UTF_8)
            out.writeVarint(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /**
     * @throws IllegalArgumentException 版本未知或帧不完整
     */
    @JvmStatic
    fun decode(frame: ByteArray): Decoded {
        val buffer = ByteBuffer.wrap(frame)
        try {
            val version = buffer.get()
            require(version == VERSION) { "Unknown invalidation frame version[$version]." }
            val origin = NodeId(ByteArray(NodeId.SIZE).also { buffer.get(it) })
            val count = buffer.readVarint()
            val keys = ArrayList<String>(count)
            repeat(count) {
                val bytes = ByteArray(buffer.readVarint()).also { buffer.get(it) }
                keys.add(String(bytes, Charsets.UTF_8))
            }
            require(!buffer.hasRemaining()) { "Trailing bytes in invalidation frame." }
            return Decoded(origin, keys)
        } catch (e: java.nio.BufferUnderflowException) {
            throw IllegalArgumentException("Truncated invalidation frame.", e)
        }
    }

    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var remaining = value
        while (remaining and 0x7F.inv() != 0) {
            write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        write(remaining)
    }

    private fun ByteBuffer.readVarint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = get().toInt()
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) {
                require(result >= 0) { "Negative length in invalidation frame." }
                return result
            }
            shift += 7
            require(shift < Int.SIZE_BITS) { "Varint too long in invalidation frame." }
        }
    }
}
