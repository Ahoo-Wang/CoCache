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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Token

/**
 * 引擎的值与 L1 标量之间的转换：载荷由 [Codec] 产生，版本、写入标识与摘要（RFC 0005 §4.7，D1、D2）在载荷之外随 [Envelope.Scalar] 传递。
 */
internal interface ValueFormat<V : Any> {
    /**
     * @param writer 这次写入的写入标识；`null` 表示不带（有数据源的缓存）
     * @param digest 是否带载荷的摘要（[Digest.ofPayload]，权威标量的写入）。带版本的值从不带（它没有条件读写，RFC 0005 §4.7“写入哪些段”）
     */
    fun encode(value: V, writer: Token?, digest: Boolean): Envelope.Scalar

    fun decode(scalar: Envelope.Scalar): V

    /**
     * 回源结果写回 L1 时的内容：普通的值是标量；带版本的不存在是 [Envelope.Absent]（版本经 [versionOf] 随填充保存，即墓碑，不带写入标识）。
     */
    fun envelopeOf(value: V, writer: Token?, digest: Boolean): Envelope = encode(value, writer, digest)

    /**
     * 与值一起保存的版本。
     */
    fun versionOf(value: V): Long = Versioned.NO_VERSION
}

/**
 * 普通缓存：只有载荷，不保存版本。
 */
internal class CodecFormat<V : Any>(private val codec: Codec<V>) : ValueFormat<V> {
    override fun encode(value: V, writer: Token?, digest: Boolean): Envelope.Scalar {
        val bytes = codec.encode(value)
        return Envelope.Scalar(bytes, Versioned.NO_VERSION, writer, if (digest) Digest.ofPayload(bytes) else null)
    }

    override fun decode(scalar: Envelope.Scalar): V = codec.decode(scalar.bytes)
}

/**
 * 带版本的缓存：引擎的值是 [Versioned]，载荷是 [Versioned.value] 的编码，版本随标量保存在 L1 的版本段（不在载荷中），
 * L2 副本与值一起保存版本。带版本的值不带摘要段（RFC 0005 §4.7：带版本的内容没有条件读写）；不带版本的值（`put`、`putIfMatch`）
 * 与普通缓存相同。
 *
 * `value` 为 `null` 的 [Versioned] 是带版本的数据源给出的“在这个版本时不存在”：写回 L1 时是墓碑（[Envelope.Absent] + 版本）。
 * 从 L1 读到的墓碑与负缓存相同，解码为 `null`（没有版本）。
 */
internal class VersionedFormat<V : Any>(private val codec: Codec<V>) : ValueFormat<Versioned<V?>> {
    override fun encode(value: Versioned<V?>, writer: Token?, digest: Boolean): Envelope.Scalar {
        val bytes = codec.encode(requireNotNull(value.value) { "A versioned absence has no payload." })
        val digested = digest && value.version == Versioned.NO_VERSION
        return Envelope.Scalar(bytes, value.version, writer, if (digested) Digest.ofPayload(bytes) else null)
    }

    override fun decode(scalar: Envelope.Scalar): Versioned<V?> = Versioned(codec.decode(scalar.bytes), scalar.version)

    override fun envelopeOf(value: Versioned<V?>, writer: Token?, digest: Boolean): Envelope {
        if (value.value == null) {
            return Envelope.Absent
        }
        return encode(value, writer, digest)
    }

    override fun versionOf(value: Versioned<V?>): Long = value.version
}

/**
 * 带版本的缓存的内部声明所用的 [Codec]：只有载荷，版本由 [VersionedFormat] 处理。引擎不经它编解码，只为满足声明的必填项。
 */
internal class PayloadCodec<V : Any>(private val codec: Codec<V>) : Codec<Versioned<V?>> {
    override fun encode(value: Versioned<V?>): ByteArray = codec.encode(requireNotNull(value.value))

    override fun decode(bytes: ByteArray): Versioned<V?> = Versioned(codec.decode(bytes), Versioned.NO_VERSION)
}
