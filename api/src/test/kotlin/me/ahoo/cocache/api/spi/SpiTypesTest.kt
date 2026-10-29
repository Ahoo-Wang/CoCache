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
        scalar.sha1.size.assert().isEqualTo(20)
        scalar.toString().length.assert().isEqualTo(40)
        val members = Digest.of(Envelope.Members(listOf("a".toByteArray(), "b".toByteArray())))
        members.assert().isNotEqualTo(Digest.of(Envelope.Members(listOf("ab".toByteArray()))))
        members.assert().isNotEqualTo(scalar)
        Digest.of(Envelope.Absent).assert().isEqualTo(Digest.of(Envelope.Scalar(ByteArray(0))))
        scalar.equals(scalar).assert().isTrue()
        scalar.equals("x").assert().isFalse()
    }

    @Test
    fun requestsAndReadsAreValueObjects() {
        val token = Token(NodeId.random(), 1)
        LeaseRequest(token, Duration.ofSeconds(1)).assert().isEqualTo(LeaseRequest(token, Duration.ofSeconds(1)))
        RefreshRequest(Duration.ofSeconds(1), token, Duration.ofSeconds(2)).token.assert().isEqualTo(token)
        Fill("k", token, Envelope.Absent, null).key.assert().isEqualTo("k")
        RemoteRead.Hit(Envelope.Absent, 1, null).refreshGranted.assert().isFalse()
        RemoteRead.Leased(Duration.ofSeconds(1)).remaining.assert().isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun defaultCloseMethodsAreNoOps() {
        val backend = object : CoherenceBackend {
            override val atomicBroadcast = true
            override val format = 1
            override val channel: InvalidationChannel
                get() = throw UnsupportedOperationException()

            override fun store(namespace: String): RemoteStore = throw UnsupportedOperationException()
        }
        backend.close()
        Subscription { }.close()
    }
}
