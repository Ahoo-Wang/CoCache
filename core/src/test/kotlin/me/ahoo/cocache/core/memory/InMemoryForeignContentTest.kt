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

package me.ahoo.cocache.core.memory

import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.api.spi.L1Eviction
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存后端的旧格式内容（[InMemoryBackend.writeForeign]，RFC 0005 §4.7）：与 Redis 后端读到格式号更低的内容相同，
 * 读取原样返回并报告它的格式、不授予令牌；回收按报告的格式比较指纹（#834）；依赖旧内容的写入以它的格式拒绝，整体覆盖照常写入。
 */
class InMemoryForeignContentTest {
    private val clock = ManualClock()
    private val backend = InMemoryBackend(clock)
    private val keyspace = Keyspace("cocache:", "foreign")
    private val store = backend.store(keyspace)
    private val node = NodeId.random()
    private val sequence = AtomicLong()
    private val raw = "legacy".toByteArray()

    private fun token() = Token(node, sequence.incrementAndGet())

    private fun lease() = LeaseRequest(token(), Duration.ofSeconds(30))

    private fun frame() = InvalidationFrame.encode(node, listOf(KEY))

    private fun read(): L1Read = Suspending.await { store.read(KEY, null, null, null) }

    @Test
    fun foreignContentIsReadInItsFormatWithoutTokens() {
        val received = AtomicInteger()
        backend.channel.subscribe(
            keyspace,
            object : InvalidationListener {
                override fun onInvalidated(keys: List<String>, origin: NodeId) {
                    received.incrementAndGet()
                }

                override fun onReset() = Unit
            }
        )
        // 低于本格式的内容没有可读的段：信封中的版本、写入标识与摘要不保存
        val segmented = Envelope.Scalar(raw, 3, token(), Digest.ofPayload(raw))
        backend.writeForeign(keyspace, KEY, segmented, 1, Duration.ofMinutes(1))
        received.get().assert().isZero()
        val refresh = RefreshRequest(Duration.ofMinutes(5), token(), Duration.ofSeconds(30))
        val hit = Suspending.await { store.read(KEY, lease(), refresh, null) } as L1Read.Hit
        hit.envelope.assert().isEqualTo(Envelope.Scalar(raw))
        hit.format.assert().isEqualTo(1)
        hit.refreshGranted.assert().isFalse()
        hit.ttl.assert().isEqualTo(Duration.ofMinutes(1))
        Suspending.await { store.fill(Fill(KEY, refresh.token, Envelope.Scalar(raw), null)) }
            .assert().isEqualTo(L1Fill.Rejected(null))
        Suspending.await { store.release(KEY, refresh.token) }
        (read() as L1Read.Hit).format.assert().isEqualTo(1)
        clock.advance(Duration.ofMinutes(1))
        read().assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun reclaimComparesTheFingerprintOfTheReportedFormat() {
        backend.writeForeign(keyspace, KEY, Envelope.Scalar(raw), 0, null)
        val ofThisFormat = ContentFingerprint.of(Envelope.Scalar(raw), InMemoryBackend.FORMAT)
        Suspending.await { store.reclaim(KEY, ofThisFormat, lease()) }.assert().isFalse()
        val ofItsFormat = ContentFingerprint.of(Envelope.Scalar(raw), 0)
        Suspending.await { store.reclaim(KEY, ofItsFormat, lease()) }.assert().isTrue()
        read().assert().isInstanceOf(L1Read.Leased::class.java)
    }

    @Test
    fun writesThatDependOnTheContentRefuseItWithItsFormat() {
        val members = Envelope.Members(listOf("a".toByteArray()))
        backend.writeForeign(keyspace, KEY, members, 1, null)
        val digested = Envelope.Scalar(raw, Versioned.NO_VERSION, null, Digest.ofPayload(raw))
        Suspending.await {
            val sets = requireNotNull(store.sets())
            sets.addMembers(KEY, listOf("b".toByteArray()), null, frame()).assert().isEqualTo(1)
            sets.removeMembers(KEY, listOf("a".toByteArray()), null, frame()).assert().isEqualTo(1)
            val conditional = requireNotNull(store.conditional())
            conditional.readIfChanged(KEY, null, null).assert().isEqualTo(L1ConditionalRead.Unreadable(1))
            conditional.putIfMatch(KEY, null, digested, null, frame())
                .assert().isEqualTo(L1ConditionalWrite.Unreadable(1))
            conditional.deleteIfMatch(KEY, Digest.ofPayload(raw), frame())
                .assert().isEqualTo(L1ConditionalWrite.Unreadable(1))
            val versioned = requireNotNull(store.versioned())
            versioned.putIfNewer(KEY, Envelope.Scalar(raw), 1, null, frame())
                .assert().isEqualTo(L1VersionedWrite.Unreadable(1))
            versioned.evictKeepingVersion(KEY, null, frame()).assert().isEqualTo(L1Eviction.Unreadable(1))
        }
        val kept = read() as L1Read.Hit
        kept.envelope.assert().isEqualTo(members)
        kept.format.assert().isEqualTo(1)
        // 整体覆盖不依赖旧内容，照常写入本格式的内容
        Suspending.await { store.put(KEY, Envelope.Scalar(raw), null, frame()) }
        (read() as L1Read.Hit).format.assert().isEqualTo(InMemoryBackend.FORMAT)
    }

    @Test
    fun foreignContentIsALowerFormatScalarOrSet() {
        listOf(-1, InMemoryBackend.FORMAT, InMemoryBackend.FORMAT + 1).forEach { format ->
            runCatching { backend.writeForeign(keyspace, KEY, Envelope.Scalar(raw), format, null) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        runCatching { backend.writeForeign(keyspace, KEY, Envelope.Absent, 0, null) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        read().assert().isEqualTo(L1Read.Miss(false))
    }

    private companion object {
        const val KEY = "cocache:foreign:k"
    }
}
