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

import me.ahoo.cocache.api.Versioned
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

class SpiTypesTest {
    @Test
    fun nodeIdRequiresSixteenBytesAndComparesByContent() {
        runCatching {
            NodeId(
                ByteArray(3)
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        val bytes = ByteArray(NodeId.SIZE) { it.toByte() }
        val nodeId = NodeId(bytes)
        bytes[0] = 99
        nodeId.bytes[0].assert().isEqualTo(0.toByte())
        nodeId.assert().isEqualTo(NodeId(ByteArray(NodeId.SIZE) { it.toByte() }))
        nodeId.hashCode().assert().isEqualTo(NodeId(ByteArray(NodeId.SIZE) { it.toByte() }).hashCode())
        nodeId.assert().isNotEqualTo(NodeId.random())
        nodeId.equals(nodeId).assert().isTrue()
        nodeId.equals("x").assert().isFalse()
        nodeId.toString().assert().isEqualTo("000102030405060708090a0b0c0d0e0f")
    }

    @Test
    fun tokenRoundTripsThroughBytes() {
        val token = Token(NodeId.random(), 42)
        val bytes = token.toBytes()
        bytes.size.assert().isEqualTo(Token.SIZE)
        Token.fromBytes(bytes).assert().isEqualTo(token)
        Token.fromBytes(bytes).hashCode().assert().isEqualTo(token.hashCode())
        token.equals(token).assert().isTrue()
        token.assert().isNotEqualTo(Token(token.node, 43))
        token.assert().isNotEqualTo(Token(NodeId.random(), 42))
        token.equals("x").assert().isFalse()
        token.toString().assert().contains(":42")
        runCatching {
            Token.fromBytes(ByteArray(3))
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun envelopesCompareByContentAndCopyDefensively() {
        val bytes = "v".toByteArray()
        val scalar = Envelope.Scalar(bytes)
        bytes[0] = 0
        String(scalar.bytes).assert().isEqualTo("v")
        scalar.assert().isEqualTo(Envelope.Scalar("v".toByteArray()))
        scalar.hashCode().assert().isEqualTo(Envelope.Scalar("v".toByteArray()).hashCode())
        scalar.equals(scalar).assert().isTrue()
        scalar.equals(Envelope.Absent).assert().isFalse()
        scalar.toString().assert().isEqualTo("Scalar(1 bytes)")

        // 版本与写入标识参与相等比较，各自出现在 toString 中
        val writer = Token(NodeId(ByteArray(NodeId.SIZE)), 7)
        val versioned = Envelope.Scalar("v".toByteArray(), 3)
        val written = Envelope.Scalar("v".toByteArray(), Versioned.NO_VERSION, writer)
        val both = Envelope.Scalar("v".toByteArray(), 3, writer)
        versioned.assert().isNotEqualTo(scalar)
        written.assert().isNotEqualTo(scalar)
        both.assert().isEqualTo(Envelope.Scalar("v".toByteArray(), 3, Token(NodeId(ByteArray(NodeId.SIZE)), 7)))
        both.assert().isNotEqualTo(Envelope.Scalar("v".toByteArray(), 3, Token(NodeId(ByteArray(NodeId.SIZE)), 8)))
        both.assert().isNotEqualTo(Envelope.Scalar("w".toByteArray(), 3, writer))
        both.hashCode().assert().isEqualTo(Envelope.Scalar("v".toByteArray(), 3, writer).hashCode())
        versioned.toString().assert().isEqualTo("Scalar(1 bytes, version=3)")
        written.toString().assert().isEqualTo("Scalar(1 bytes, writer=$writer)")
        both.toString().assert().isEqualTo("Scalar(1 bytes, version=3, writer=$writer)")

        val members = Envelope.Members(listOf("a".toByteArray(), "b".toByteArray()))
        members.size.assert().isEqualTo(2)
        members.members.map { String(it) }.assert().containsExactly("a", "b")
        members.assert().isEqualTo(Envelope.Members(listOf("a".toByteArray(), "b".toByteArray())))
        members.hashCode().assert().isEqualTo(Envelope.Members(listOf("a".toByteArray(), "b".toByteArray())).hashCode())
        members.assert().isNotEqualTo(Envelope.Members(listOf("a".toByteArray())))
        members.assert().isNotEqualTo(Envelope.Members(listOf("a".toByteArray(), "c".toByteArray())))
        members.equals(members).assert().isTrue()
        members.equals(scalar).assert().isFalse()
        members.toString().assert().isEqualTo("Members(2)")
    }

    @Test
    fun digestIsStableAndDistinguishesShapes() {
        val scalar = Digest.of(Envelope.Scalar("ab".toByteArray()))
        scalar.assert().isEqualTo(Digest.of(Envelope.Scalar("ab".toByteArray())))
        scalar.hashCode().assert().isEqualTo(Digest.of(Envelope.Scalar("ab".toByteArray())).hashCode())
        scalar.algorithm.assert().isEqualTo(Digest.SHA_256)
        scalar.bytes.size.assert().isEqualTo(Digest.SIZE)
        scalar.toString().assert().isEqualTo("SHA-256:" + scalar.bytes.joinToString("") { "%02x".format(it) })
        scalar.assert().isEqualTo(Digest.ofPayload("ab".toByteArray()))
        scalar.assert().isEqualTo(Digest(scalar.bytes))
        // RFC 0005 §4.7（D1）：载荷的 SHA-256，"ab" 的标准测试向量
        scalar.bytes.joinToString("") { "%02x".format(it) }
            .assert().isEqualTo("fb8e20fc2e4c3f248c60c39bd652f3c1347298bb977b8b4d5903b85055620603")
        runCatching {
            Digest(
                ByteArray(20)
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            Digest(
                "SHA-1",
                ByteArray(32)
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        val members = Digest.of(Envelope.Members(listOf("a".toByteArray(), "b".toByteArray())))
        members.assert().isNotEqualTo(Digest.of(Envelope.Members(listOf("ab".toByteArray()))))
        members.assert().isNotEqualTo(scalar)
        Digest.of(Envelope.Absent).assert().isEqualTo(Digest.of(Envelope.Scalar(ByteArray(0))))
        scalar.equals(scalar).assert().isTrue()
        scalar.equals("x").assert().isFalse()
    }

    /**
     * 条件回收的内容指纹（RFC 0005 round 3 K12）：SPI 自己的类型，算法是 SHA-1（存储现算），与 SHA-256 的 [Digest] 是两个类型。
     */
    @Test
    fun contentFingerprintIsStableAndDistinguishesShapes() {
        val scalar = ContentFingerprint.of(Envelope.Scalar("ab".toByteArray()))
        scalar.assert().isEqualTo(ContentFingerprint.of(Envelope.Scalar("ab".toByteArray())))
        scalar.hashCode().assert().isEqualTo(ContentFingerprint.of(Envelope.Scalar("ab".toByteArray())).hashCode())
        scalar.bytes.size.assert().isEqualTo(ContentFingerprint.SIZE)
        // "ab" 的 SHA-1 标准测试向量
        scalar.toString().assert().isEqualTo("da23614e02469a0d7c7bd1bdab5c9c474b1904dc")
        // 字节是副本：改动它不影响指纹
        scalar.bytes.fill(0)
        scalar.assert().isEqualTo(ContentFingerprint.of(Envelope.Scalar("ab".toByteArray())))
        val members = ContentFingerprint.of(Envelope.Members(listOf("a".toByteArray(), "b".toByteArray())))
        members.assert().isNotEqualTo(ContentFingerprint.of(Envelope.Members(listOf("ab".toByteArray()))))
        members.assert().isNotEqualTo(scalar)
        ContentFingerprint.of(Envelope.Absent).assert().isEqualTo(ContentFingerprint.of(Envelope.Scalar(ByteArray(0))))
        scalar.equals(scalar).assert().isTrue()
        scalar.equals(Digest.of(Envelope.Scalar("ab".toByteArray()))).assert().isFalse()
        runCatching { ContentFingerprint(ByteArray(32)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun fillOutcomesAreValueObjects() {
        L1Fill.Filled(advanced = false).assert().isEqualTo(L1Fill.Filled(false))
        L1Fill.Filled(advanced = true).assert().isNotEqualTo(L1Fill.Filled(false))
        L1Fill.Rejected(currentVersion = null).assert().isEqualTo(L1Fill.Rejected(null))
        L1Fill.Rejected(currentVersion = 3).currentVersion.assert().isEqualTo(3L)
        L1Fill.Rejected(null).assert().isNotEqualTo(L1Fill.Filled(false))
    }

    @Test
    fun requestsAndReadsAreValueObjects() {
        val token = Token(NodeId.random(), 1)
        LeaseRequest(token, Duration.ofSeconds(1)).assert().isEqualTo(LeaseRequest(token, Duration.ofSeconds(1)))
        RefreshRequest(Duration.ofSeconds(1), token, Duration.ofSeconds(2)).token.assert().isEqualTo(token)
        Fill("k", token, Envelope.Absent, null).key.assert().isEqualTo("k")
        L1Read.Hit(Envelope.Absent, 1, null).refreshGranted.assert().isFalse()
        L1Read.Leased(Duration.ofSeconds(1)).remaining.assert().isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun defaultCloseMethodsAreNoOps() {
        val backend = object : CoherenceBackend {
            override val atomicBroadcast = true
            override val format = 1
            override val channel: InvalidationChannel
                get() = throw UnsupportedOperationException()

            override fun store(keyspace: Keyspace): L1Store = throw UnsupportedOperationException()
        }
        backend.close()
        Subscription { }.close()
    }
}
