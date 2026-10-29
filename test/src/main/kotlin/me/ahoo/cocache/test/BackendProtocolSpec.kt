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

package me.ahoo.cocache.test

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * L1 协议 TCK（RFC 0003 §4.4）：每个 [CoherenceBackend] 实现都必须通过。
 *
 * 时间相关用例通过 [elapse] 推进时间：内存后端推进手动时钟，真实后端可以 sleep（用例只使用很短的 TTL）。
 */
abstract class BackendProtocolSpec {
    protected abstract fun createBackend(): CoherenceBackend

    /**
     * 让 L1 的时间前进 [duration]。
     */
    protected abstract fun elapse(duration: Duration)

    protected lateinit var backend: CoherenceBackend
    protected lateinit var store: RemoteStore
    protected lateinit var namespace: String
    private val node = NodeId.random()
    private val sequence = AtomicLong()

    protected open val shortTtl: Duration = Duration.ofMillis(300)

    @BeforeEach
    open fun setup() {
        backend = createBackend()
        namespace = "tck-" + UUID.randomUUID().toString().substring(0, 8)
        store = backend.store(namespace)
    }

    @AfterEach
    open fun destroy() {
        store.close()
        backend.close()
    }

    protected fun key(): String = "cocache:$namespace:" + UUID.randomUUID()

    protected fun token(): Token = Token(node, sequence.incrementAndGet())

    protected fun lease(token: Token = token(), ttl: Duration = Duration.ofSeconds(30)) = LeaseRequest(token, ttl)

    protected fun frame(vararg keys: String): ByteArray = InvalidationFrame.encode(node, keys.toList())

    protected fun scalar(text: String) = Envelope.Scalar(text.toByteArray())

    private fun RemoteRead.hit(): RemoteRead.Hit = this as? RemoteRead.Hit ?: throw AssertionError("Expected a hit but was $this.")

    @Test
    fun missWithoutLeaseRequestGrantsNothing() {
        val key = key()
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun missGrantsLeaseAtomicallyAndOthersSeeLeased() {
        val key = key()
        store.read(key, lease(), null).assert().isEqualTo(RemoteRead.Miss(true))
        val other = store.read(key, lease(), null)
        other.assert().isInstanceOf(RemoteRead.Leased::class.java)
        (other as RemoteRead.Leased).remaining.isNegative.assert().isFalse()
    }

    @Test
    fun fillWithLeaseTokenWritesValue() {
        val key = key()
        val lease = lease()
        store.read(key, lease, null)
        store.fill(Fill(key, lease.token, scalar("v"), null)).assert().isTrue()
        val hit = store.read(key, lease(), null).hit()
        hit.envelope.assert().isEqualTo(scalar("v"))
        hit.ttl.assert().isNull()
        hit.format.assert().isEqualTo(backend.format)
    }

    @Test
    fun fillWithForeignTokenIsRejected() {
        val key = key()
        store.read(key, lease(), null)
        store.fill(Fill(key, token(), scalar("v"), null)).assert().isFalse()
        store.read(key, null, null).assert().isInstanceOf(RemoteRead.Leased::class.java)
    }

    @Test
    fun fillWithoutLeaseIsRejected() {
        val key = key()
        store.fill(Fill(key, token(), scalar("v"), null)).assert().isFalse()
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun invalidateDestroysLeaseSoStaleFillFails() {
        val key = key()
        val lease = lease()
        store.read(key, lease, null)
        store.invalidate(listOf(key), frame(key))
        store.fill(Fill(key, lease.token, scalar("stale"), null)).assert().isFalse()
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun invalidateAfterFillDeletesValue() {
        val key = key()
        val lease = lease()
        store.read(key, lease, null)
        store.fill(Fill(key, lease.token, scalar("v"), null))
        store.invalidate(listOf(key), frame(key))
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun putDestroysLeaseAndOverwrites() {
        val key = key()
        val lease = lease()
        store.read(key, lease, null)
        store.put(key, scalar("new"), null, frame(key))
        store.fill(Fill(key, lease.token, scalar("stale"), null)).assert().isFalse()
        store.read(key, null, null).hit().envelope.assert().isEqualTo(scalar("new"))
    }

    @Test
    fun releaseDeletesOwnLeaseOnly() {
        val key = key()
        val lease = lease()
        store.read(key, lease, null)
        store.release(key, token())
        store.read(key, null, null).assert().isInstanceOf(RemoteRead.Leased::class.java)
        store.release(key, lease.token)
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun releaseOnAbsentKeyIsNoOp() {
        val key = key()
        store.release(key, token())
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun expiredLeaseCanBeTakenAgainAndOldTokenFails() {
        val key = key()
        val first = lease(ttl = shortTtl)
        store.read(key, first, null)
        elapse(shortTtl.multipliedBy(2))
        val second = lease()
        store.read(key, second, null).assert().isEqualTo(RemoteRead.Miss(true))
        store.fill(Fill(key, first.token, scalar("stale"), null)).assert().isFalse()
        store.fill(Fill(key, second.token, scalar("fresh"), null)).assert().isTrue()
    }

    @Test
    fun fillResetsExpirySoLeaseTtlDoesNotLeak() {
        val key = key()
        val lease = lease(ttl = shortTtl)
        store.read(key, lease, null)
        store.fill(Fill(key, lease.token, scalar("v"), Duration.ofHours(1)))
        elapse(shortTtl.multipliedBy(2))
        val hit = store.read(key, null, null).hit()
        requireNotNull(hit.ttl).assert().isGreaterThan(Duration.ofMinutes(59))
    }

    @Test
    fun putWithTtlExpires() {
        val key = key()
        store.put(key, scalar("v"), shortTtl, frame(key))
        requireNotNull(store.read(key, null, null).hit().ttl).assert().isLessThanOrEqualTo(shortTtl)
        elapse(shortTtl.multipliedBy(2))
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun putWithoutTtlRemovesPreviousExpiry() {
        val key = key()
        store.put(key, scalar("v1"), shortTtl, frame(key))
        store.put(key, scalar("v2"), null, frame(key))
        elapse(shortTtl.multipliedBy(2))
        store.read(key, null, null).hit().ttl.assert().isNull()
    }

    @Test
    fun absentEnvelopeIsANegativeEntry() {
        val key = key()
        val lease = lease()
        store.read(key, lease, null)
        store.fill(Fill(key, lease.token, Envelope.Absent, Duration.ofMinutes(1))).assert().isTrue()
        store.read(key, lease(), null).hit().envelope.assert().isEqualTo(Envelope.Absent)
    }

    @Test
    fun refreshTokenGrantedOnlyInsideWindowAndOnce() {
        val key = key()
        store.put(key, scalar("v"), Duration.ofSeconds(60), frame(key))
        val outside = RefreshRequest(Duration.ofSeconds(1), token(), Duration.ofSeconds(30))
        store.read(key, null, outside).hit().refreshGranted.assert().isFalse()

        val first = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, first).hit().refreshGranted.assert().isTrue()
        val second = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, second).hit().refreshGranted.assert().isFalse()

        store.fill(Fill(key, second.token, scalar("other"), null)).assert().isFalse()
        store.fill(Fill(key, first.token, scalar("refreshed"), Duration.ofSeconds(60))).assert().isTrue()
        store.read(key, null, null).hit().envelope.assert().isEqualTo(scalar("refreshed"))
    }

    @Test
    fun refreshTokenIsNotGrantedOnForeverValues() {
        val key = key()
        store.put(key, scalar("v"), null, frame(key))
        val refresh = RefreshRequest(Duration.ofDays(1), token(), Duration.ofSeconds(30))
        store.read(key, null, refresh).hit().refreshGranted.assert().isFalse()
    }

    @Test
    fun expiredRefreshTokenCanBePreempted() {
        val key = key()
        store.put(key, scalar("v"), Duration.ofSeconds(60), frame(key))
        val first = RefreshRequest(Duration.ofSeconds(120), token(), shortTtl)
        store.read(key, null, first).hit().refreshGranted.assert().isTrue()
        elapse(shortTtl.multipliedBy(2))
        val second = RefreshRequest(Duration.ofSeconds(120), token(), shortTtl)
        store.read(key, null, second).hit().refreshGranted.assert().isTrue()
        store.fill(Fill(key, first.token, scalar("late"), null)).assert().isFalse()
    }

    @Test
    fun putDestroysRefreshToken() {
        val key = key()
        store.put(key, scalar("v"), Duration.ofSeconds(60), frame(key))
        val refresh = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, refresh)
        store.put(key, scalar("written"), Duration.ofSeconds(60), frame(key))
        store.fill(Fill(key, refresh.token, scalar("refreshed"), null)).assert().isFalse()
        store.read(key, null, null).hit().envelope.assert().isEqualTo(scalar("written"))
    }

    @Test
    fun releaseClearsRefreshTokenButKeepsValue() {
        val key = key()
        store.put(key, scalar("v"), Duration.ofSeconds(60), frame(key))
        val refresh = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, refresh)
        store.release(key, refresh.token)
        store.read(key, null, null).hit().envelope.assert().isEqualTo(scalar("v"))
        val again = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(key, null, again).hit().refreshGranted.assert().isTrue()
    }

    @Test
    fun reclaimConvertsUnchangedContentToLease() {
        val key = key()
        store.put(key, scalar("corrupt"), null, frame(key))
        val lease = lease()
        store.reclaim(key, Digest.of(scalar("other")), lease).assert().isFalse()
        store.reclaim(key, Digest.of(scalar("corrupt")), lease).assert().isTrue()
        store.read(key, null, null).assert().isInstanceOf(RemoteRead.Leased::class.java)
        store.fill(Fill(key, lease.token, scalar("v"), null)).assert().isTrue()
    }

    @Test
    fun reclaimOnAbsentKeyFails() {
        store.reclaim(key(), Digest.of(scalar("x")), lease()).assert().isFalse()
    }

    @Test
    fun readAllReturnsEveryKeyAndGrantsRequestedLeases() {
        val hitKey = key()
        val leasedKey = key()
        val missKey = key()
        val plainKey = key()
        store.put(hitKey, scalar("v"), null, frame(hitKey))
        store.read(leasedKey, lease(), null)
        val missLease = lease()
        val reads = store.readAll(
            listOf(hitKey, leasedKey, missKey, plainKey),
            mapOf(missKey to missLease, leasedKey to lease())
        )
        reads.keys.assert().containsExactly(hitKey, leasedKey, missKey, plainKey)
        reads.getValue(hitKey).hit().envelope.assert().isEqualTo(scalar("v"))
        reads.getValue(leasedKey).assert().isInstanceOf(RemoteRead.Leased::class.java)
        reads.getValue(missKey).assert().isEqualTo(RemoteRead.Miss(true))
        reads.getValue(plainKey).assert().isEqualTo(RemoteRead.Miss(false))

        val outcomes = store.fillAll(
            listOf(Fill(missKey, missLease.token, scalar("m"), null), Fill(plainKey, token(), scalar("p"), null))
        )
        outcomes.assert().containsExactly(true, false)
    }

    @Test
    fun membersAreAddedAtomicallyOverAnyNonSetState() {
        val key = key()
        val lease = lease()
        store.read(key, lease, null)
        store.addMembers(key, listOf("a".toByteArray()), null, frame(key))
        store.fill(Fill(key, lease.token, Envelope.Members(listOf("seed".toByteArray())), null)).assert().isFalse()
        store.addMembers(key, listOf("b".toByteArray(), "a".toByteArray()), null, frame(key))
        val members = (store.read(key, null, null).hit().envelope as Envelope.Members).members.map { String(it) }
        members.assert().containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun addMembersReplacesNegativeEntry() {
        val key = key()
        val lease = lease()
        store.read(key, lease, null)
        store.fill(Fill(key, lease.token, Envelope.Absent, Duration.ofMinutes(1)))
        store.addMembers(key, listOf("a".toByteArray()), null, frame(key))
        val hit = store.read(key, null, null).hit()
        (hit.envelope as Envelope.Members).members.map { String(it) }.assert().containsExactly("a")
        hit.ttl.assert().isNull()
    }

    @Test
    fun removeMembersOnlyAffectsSetsAndDeletesWhenEmpty() {
        val key = key()
        store.removeMembers(key, listOf("a".toByteArray()), null, frame(key))
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))

        store.addMembers(key, listOf("a".toByteArray(), "b".toByteArray()), null, frame(key))
        store.removeMembers(key, listOf("a".toByteArray()), Duration.ofMinutes(1), frame(key))
        val hit = store.read(key, null, null).hit()
        (hit.envelope as Envelope.Members).members.map { String(it) }.assert().containsExactly("b")
        hit.ttl.assert().isNotNull()

        store.removeMembers(key, listOf("b".toByteArray()), null, frame(key))
        store.read(key, null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun removeMembersOnScalarIsNoOp() {
        val key = key()
        store.put(key, scalar("v"), null, frame(key))
        store.removeMembers(key, listOf("v".toByteArray()), null, frame(key))
        store.read(key, null, null).hit().envelope.assert().isEqualTo(scalar("v"))
    }

    @Test
    fun subscribeResetsAndWritesBroadcastFrames() {
        val received = CopyOnWriteArrayList<Pair<List<String>, NodeId>>()
        val resets = AtomicInteger()
        val listener = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                received.add(keys to origin)
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        val subscription = backend.channel.subscribe(namespace, listener)
        awaitCondition("reset on subscribe") { resets.get() >= 1 }
        val key = key()
        val keys = mutableListOf<String>()
        store.put(key, scalar("v"), null, frame(key))
        keys.add(key)
        store.invalidate(listOf(key), frame(key))
        keys.add(key)
        store.addMembers(key, listOf("a".toByteArray()), null, frame(key))
        keys.add(key)
        store.removeMembers(key, listOf("a".toByteArray()), null, frame(key))
        keys.add(key)
        if (!backend.atomicBroadcast) {
            keys.forEach { backend.channel.publish(namespace, frame(it)) }
        }
        awaitCondition("four broadcasts") { received.size >= 4 }
        received.all { it.first == listOf(key) && it.second == node }.assert().isTrue()

        subscription.close()
        val countAfterClose = received.size
        store.invalidate(listOf(key), frame(key))
        if (!backend.atomicBroadcast) {
            backend.channel.publish(namespace, frame(key))
        }
        Thread.sleep(50)
        received.size.assert().isEqualTo(countAfterClose)
    }

    /**
     * 批量覆盖写入：每个 key 的转换同 put（任意状态 → 新内容，销毁 lease 与刷新令牌，重设到期时间）。
     */
    @Test
    fun putAllOverwritesEveryKeyAndDestroysTokens() {
        val leased = key()
        val refreshed = key()
        val absent = key()
        val set = key()
        val expiring = key()
        val lease = lease()
        store.read(leased, lease, null)
        store.put(refreshed, scalar("v"), Duration.ofSeconds(60), frame(refreshed))
        val refresh = RefreshRequest(Duration.ofSeconds(120), token(), Duration.ofSeconds(30))
        store.read(refreshed, null, refresh).hit().refreshGranted.assert().isTrue()
        store.put(expiring, scalar("old"), shortTtl, frame(expiring))

        store.putAll(
            listOf(
                Put(leased, scalar("a"), null),
                Put(refreshed, scalar("b"), Duration.ofMinutes(1)),
                Put(absent, Envelope.Absent, Duration.ofMinutes(1)),
                Put(set, Envelope.Members(listOf("x".toByteArray(), "y".toByteArray())), null),
                Put(expiring, scalar("e"), null),
            ),
            frame(leased, refreshed, absent, set, expiring)
        )

        store.fill(Fill(leased, lease.token, scalar("stale"), null)).assert().isFalse()
        store.fill(Fill(refreshed, refresh.token, scalar("stale"), null)).assert().isFalse()
        elapse(shortTtl.multipliedBy(2))
        store.read(leased, null, null).hit().let {
            it.envelope.assert().isEqualTo(scalar("a"))
            it.ttl.assert().isNull()
        }
        store.read(refreshed, null, null).hit().let {
            it.envelope.assert().isEqualTo(scalar("b"))
            requireNotNull(it.ttl).assert().isLessThanOrEqualTo(Duration.ofMinutes(1))
        }
        store.read(absent, null, null).hit().envelope.assert().isEqualTo(Envelope.Absent)
        store.read(set, null, null).hit().let {
            (it.envelope as Envelope.Members).members.map { member -> String(member) }
                .assert().containsExactlyInAnyOrder("x", "y")
            it.ttl.assert().isNull()
        }
        store.read(expiring, null, null).hit().let {
            it.envelope.assert().isEqualTo(scalar("e"))
            it.ttl.assert().isNull()
        }
    }

    /**
     * 批量覆盖写入的广播：每个 key 恰好随其写入广播一次（实现可以拆成若干帧，但不重复广播）。
     */
    @Test
    fun putAllBroadcastsEveryKeyExactlyOnce() {
        val received = CopyOnWriteArrayList<Pair<List<String>, NodeId>>()
        val resets = AtomicInteger()
        val listener = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                received.add(keys to origin)
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        backend.channel.subscribe(namespace, listener).use {
            awaitCondition("reset on subscribe") { resets.get() >= 1 }
            val keys = (1..5).map { key() }
            val frame = InvalidationFrame.encode(node, keys)
            store.putAll(keys.map { Put(it, scalar(it), null) }, frame)
            if (!backend.atomicBroadcast) {
                backend.channel.publish(namespace, frame)
            }
            awaitCondition("every key broadcast") { received.flatMap { it.first }.toSet() == keys.toSet() }
            Thread.sleep(50)
            received.flatMap { it.first }.sorted().assert().isEqualTo(keys.sorted())
            received.all { it.second == node }.assert().isTrue()
            keys.forEach { store.read(it, null, null).hit().envelope.assert().isEqualTo(scalar(it)) }
        }
    }
}
