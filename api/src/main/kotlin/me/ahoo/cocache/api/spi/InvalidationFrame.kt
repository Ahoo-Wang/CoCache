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
 * 失效消息帧（线格式，随 M4 冻结）：`[版本:1][nodeId:16][count:varint]([len:varint][key UTF-8])*`。
 *
 * 带长度前缀，对 key 的内容没有任何限制；一条消息可以携带多个 key。`count` 为 0 的帧是存储创建时的频道探测，监听者什么也不做。
 *
 * 演进规则（RFC 0005 §4.7，K1、D3）：首字节是帧的版本，也是帧的类型。通道读不懂的帧（未知版本、未知类型、解码失败）
 * 一律按该频道的 [InvalidationListener.onReset] 处理：清空 L2、递增全部戳，只会多淘汰，不会丢失效。8.x 因此可以新增帧而
 * 不必两阶段发布：8.0 节点把新帧当作读不懂的帧重置，恰好做了正确的事。
 */
object InvalidationFrame {
    /**
     * 失效帧：携带若干存储 key。
     */
    const val VERSION: Byte = 1

    /**
     * 预留的帧版本 2：“namespace 重置”（Q12），帧本身在 8.x 加入。8.0 不发出、也不解码它，按读不懂的帧重置。
     */
    const val NAMESPACE_RESET_VERSION: Byte = 2

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
     * 解码失效帧。通道捕获 [IllegalArgumentException] 并按读不懂的帧重置订阅者，不丢弃（见类注释）。
     *
     * @throws IllegalArgumentException 版本未知（含预留的 [NAMESPACE_RESET_VERSION]）、帧不完整或有尾随字节
     */
    @JvmStatic
    fun decode(frame: ByteArray): Decoded {
        val buffer = ByteBuffer.wrap(frame)
        try {
            val version = buffer.get()
            require(version == VERSION) { "Unknown invalidation frame version[$version]." }
            val origin = NodeId(ByteArray(NodeId.SIZE).also { buffer.get(it) })
            val count = buffer.readVarint()
            // 不信任长度前缀：每个 key 至少占 1 字节（长度前缀），超过剩余字节数的计数或长度必然是畸形帧，避免按其预分配内存
            require(count <= buffer.remaining()) { "Key count[$count] exceeds remaining bytes in invalidation frame." }
            val keys = ArrayList<String>(count)
            repeat(count) {
                val length = buffer.readVarint()
                require(
                    length <= buffer.remaining()
                ) { "Key length[$length] exceeds remaining bytes in invalidation frame." }
                val bytes = ByteArray(length).also { buffer.get(it) }
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
