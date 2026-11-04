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

import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ForwardingL1Store] 与三个 Forwarding 能力面：
 * - 转发全部操作与访问器，访问器原样返回被装饰者的能力面；
 * - 覆盖访问器、返回包了一层的 Forwarding 能力面即可拦截能力面上的操作；被装饰者没有该能力时仍是 `null`。
 */
class ForwardingL1StoreTest {
    private val node = NodeId.random()
    private val token = Token(node, 1)
    private val lease = LeaseRequest(token, Duration.ofSeconds(1))
    private val refresh = RefreshRequest(Duration.ofSeconds(1), Token(node, 2), Duration.ofSeconds(1))
    private val envelope = Envelope.Scalar(byteArrayOf(1))
    private val digest = Digest.of(envelope)
    private val fingerprint = ContentFingerprint.of(envelope)
    private val timeout = Duration.ofMillis(500)
    private val filled = L1Fill.Filled(false)
    private val rejected = L1Fill.Rejected(null)
    private val ttl = Duration.ofMinutes(1)
    private val frame = InvalidationFrame.encode(node, listOf("k"))
    private val fill = Fill("k", token, envelope, ttl)
    private val hit = L1Read.Hit(envelope, 1, ttl)
    private val members = listOf(byteArrayOf(2))

    @Test
    fun `forwards every operation and accessor`() {
        val keys = listOf("k")
        val leases = mapOf("k" to lease)
        val puts = listOf(Put("k", envelope, ttl))
        val conditionalSurface = mockk<ConditionalL1Store>()
        val setSurface = mockk<SetL1Store>()
        val delegate = mockk<L1Store> {
            every { conditional() } returns conditionalSurface
            every { versioned() } returns null
            every { sets() } returns setSurface
            coEvery { read("k", lease, refresh, timeout) } returns hit
            coEvery { readAll(keys, leases, timeout) } returns mapOf("k" to hit)
            coEvery { fill(fill) } returns filled
            coEvery { fillAll(listOf(fill)) } returns listOf(rejected)
            coJustRun { release("k", token) }
            coEvery { reclaim("k", fingerprint, lease) } returns true
            coJustRun { put("k", envelope, ttl, frame) }
            coJustRun { putAll(puts, frame) }
            coJustRun { invalidate(keys, frame) }
        }
        val store = object : ForwardingL1Store(delegate) {}

        Suspending.await { store.read("k", lease, refresh, timeout) }.assert().isSameAs(hit)
        Suspending.await { store.readAll(keys, leases, timeout) }.assert().isEqualTo(mapOf("k" to hit))
        Suspending.await { store.fill(fill) }.assert().isEqualTo(filled)
        Suspending.await { store.fillAll(listOf(fill)) }.assert().containsExactly(rejected)
        Suspending.await { store.release("k", token) }
        Suspending.await { store.reclaim("k", fingerprint, lease) }.assert().isTrue()
        Suspending.await { store.put("k", envelope, ttl, frame) }
        Suspending.await { store.putAll(puts, frame) }
        Suspending.await { store.invalidate(keys, frame) }
        store.conditional().assert().isSameAs(conditionalSurface)
        store.versioned().assert().isNull()
        store.sets().assert().isSameAs(setSurface)

        coVerify(exactly = 1) {
            delegate.release("k", token)
            delegate.put("k", envelope, ttl, frame)
            delegate.putAll(puts, frame)
            delegate.invalidate(keys, frame)
        }
    }

    @Test
    fun `forwarding capability surfaces forward every operation`() {
        val conditional = object : ForwardingConditionalL1Store(
            mockk {
                coEvery { readIfChanged("k", digest, timeout) } returns L1ConditionalRead.Unchanged(1)
                coEvery { putIfMatch("k", digest, envelope, ttl, frame) } returns L1ConditionalWrite.Conflict(null)
                coEvery { deleteIfMatch("k", digest, frame) } returns L1ConditionalWrite.Written
            }
        ) {}
        val versioned = object : ForwardingVersionedL1Store(
            mockk {
                coEvery { putIfNewer("k", Envelope.Absent, 3, ttl, frame) } returns L1VersionedWrite.Written
            }
        ) {}
        val sets = object : ForwardingSetL1Store(
            mockk {
                coEvery { addMembers("k", members, ttl, frame) } returns 0
                coEvery { removeMembers("k", members, ttl, frame) } returns null
            }
        ) {}

        val unchanged = Suspending.await { conditional.readIfChanged("k", digest, timeout) }
        unchanged.assert().isEqualTo(L1ConditionalRead.Unchanged(1))
        val conflict = Suspending.await { conditional.putIfMatch("k", digest, envelope, ttl, frame) }
        conflict.assert().isEqualTo(L1ConditionalWrite.Conflict(null))
        val deleted = Suspending.await { conditional.deleteIfMatch("k", digest, frame) }
        deleted.assert().isSameAs(L1ConditionalWrite.Written)
        val written = Suspending.await { versioned.putIfNewer("k", Envelope.Absent, 3, ttl, frame) }
        written.assert().isSameAs(L1VersionedWrite.Written)
        Suspending.await { sets.addMembers("k", members, ttl, frame) }.assert().isEqualTo(0)
        Suspending.await { sets.removeMembers("k", members, ttl, frame) }.assert().isNull()
    }

    @Test
    fun `overriding an accessor intercepts the capability surface`() {
        val surface = mockk<ConditionalL1Store> {
            coEvery { putIfMatch("k", null, envelope, ttl, frame) } returns L1ConditionalWrite.Written
        }
        val store = CountingStore(mockk { every { conditional() } returns surface })

        val conditional = requireNotNull(store.conditional())
        conditional.assert().isNotSameAs(surface)
        store.conditional().assert().isSameAs(conditional)
        val written = Suspending.await { conditional.putIfMatch("k", null, envelope, ttl, frame) }
        written.assert().isSameAs(L1ConditionalWrite.Written)

        store.conditionalWrites.get().assert().isEqualTo(1)
        coVerify(exactly = 1) { surface.putIfMatch("k", null, envelope, ttl, frame) }
    }

    @Test
    fun `overridden accessor stays null when the delegate lacks the capability`() {
        val store = CountingStore(mockk { every { conditional() } returns null })

        store.conditional().assert().isNull()
    }

    /**
     * [ForwardingL1Store] 类注释中的例子：统计条件写入。
     */
    private class CountingStore(delegate: L1Store) : ForwardingL1Store(delegate) {
        val conditionalWrites = AtomicInteger()

        private val conditional = delegate.conditional()?.let { surface ->
            object : ForwardingConditionalL1Store(surface) {
                override suspend fun putIfMatch(
                    key: String,
                    expected: Digest?,
                    envelope: Envelope.Scalar,
                    ttl: Duration?,
                    frame: ByteArray
                ): L1ConditionalWrite {
                    conditionalWrites.incrementAndGet()
                    return super.putIfMatch(key, expected, envelope, ttl, frame)
                }
            }
        }

        override fun conditional(): ConditionalL1Store? = conditional
    }
}
