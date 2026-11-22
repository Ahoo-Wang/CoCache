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
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 失效消息帧（线格式，随 M4 冻结）：`[版本:1][nodeId:16][count:varint]([len:varint][key UTF-8])*`。
 *
 * 带长度前缀，key 可以含任何字符（包括分隔符），也可以是空串；一条消息可以携带多个 key。`count` 为 0 的帧是存储创建时的频道探测，
 * 监听者什么也不做。唯一的要求是 key 必须是合法的 UTF-16：含未配对代理（surrogate）的 `String` 按 UTF-8 编不出来，被替换为 `?`，
 * 解码出的是另一个 key。
 * 帧本身不检查，CoCache 的缓存与 `ExternalInvalidator` 在 key 到达 L1 之前拒绝这样的 key；直接构造帧的写入方要自己保证。
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
     * key 按严格的 UTF-8 解码，varint 必须落在 `Int` 的非负范围内（#995）：非法的 UTF-8 若按替换字符解码，失效会落到另一个 key 上；
     * 溢出的 varint 若截断，计数可能变成 0，帧被当作探测帧。两者都按读不懂的帧抛出，通道据此重置订阅者。合法的 U+FFFD 照常解码。
     *
     * @throws IllegalArgumentException 版本未知（含预留的 [NAMESPACE_RESET_VERSION]）、帧不完整、有尾随字节、varint 溢出或 key 不是合法的 UTF-8
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
            val utf8 = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            repeat(count) {
                val length = buffer.readVarint()
                require(
                    length <= buffer.remaining()
                ) { "Key length[$length] exceeds remaining bytes in invalidation frame." }
                val bytes = ByteBuffer.wrap(ByteArray(length).also { buffer.get(it) })
                val key = try {
                    utf8.decode(bytes).toString()
                } catch (e: CharacterCodingException) {
                    throw IllegalArgumentException("Key is not valid UTF-8 in invalidation frame.", e)
                }
                keys.add(key)
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

    /**
     * 无符号 LEB128，结果必须是非负的 `Int`：第五个字节（位移 28）只能携带 3 位（`0..7`）且没有后续，否则溢出（#995）。
     */
    private fun ByteBuffer.readVarint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = get().toInt()
            if (shift == LAST_VARINT_SHIFT) {
                require(
                    byte and LAST_VARINT_BITS.inv() == 0
                ) { "Varint overflows a non-negative Int in invalidation frame." }
            }
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) {
                require(result >= 0) { "Negative length in invalidation frame." }
                return result
            }
            shift += 7
            require(shift < Int.SIZE_BITS) { "Varint too long in invalidation frame." }
        }
    }

    private const val LAST_VARINT_SHIFT = 28
    private const val LAST_VARINT_BITS = 0x07
}
