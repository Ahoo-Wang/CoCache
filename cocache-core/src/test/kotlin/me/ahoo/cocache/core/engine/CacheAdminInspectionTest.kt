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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.KeyInspection.LocalState
import me.ahoo.cocache.core.KeyInspection.RemoteState
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 运维接口的按 key 查看与强制失效：只读、不加载、不申请令牌；强制失效走正常的失效路径。
 */
class CacheAdminInspectionTest {
    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val backend = HookedBackend(memory)
    private val loads = AtomicInteger()
    private val runtimes = mutableListOf<CacheRuntime>()

    @AfterEach
    fun destroy() {
        runtimes.forEach { it.close() }
    }

    private fun runtime(): CacheRuntime = CacheRuntime.builder()
        .backend(backend)
        .clock(clock)
        .retryPolicy(RetryPolicy(initialBackoff = Duration.ofHours(1), maxBackoff = Duration.ofHours(1)))
        .build()
        .also { runtimes.add(it) }

    private fun CacheRuntime.users(
        policy: CachePolicy = CachePolicy(
            refreshWindow = Duration.ofSeconds(30)
        )
    ): Cache<Int, String> = create(
        cacheSpec<Int, String>("user") {
            codec = TestCodec
            keyEncoder = KeyEncoder { "id-$it" }
            loader = CacheLoader { id ->
                loads.incrementAndGet()
                if (id < 0) null else "user-$id"
            }
            this.policy = policy
        }
    )

    private fun frame(): ByteArray = InvalidationFrame.encode(NodeId.random(), listOf("other"))

    private fun CacheRuntime.userAdmin(): CacheAdmin = requireNotNull(admin("user"))

    @Test
    fun inspectsMissWithoutLoadingOrLeasing() {
        val runtime = runtime()
        val users = runtime.users()
        val admin = runtime.userAdmin()

        val inspection = admin.inspect("id-1")
        inspection.cache.assert().isEqualTo("user")
        inspection.storageKey.assert().isEqualTo("cocache:user:id-1")
        inspection.local.assert().isNull()
        inspection.remote.assert().isEqualTo(KeyInspection.RemoteCopy(RemoteState.MISS))
        inspection.invalidationPending.assert().isFalse()
        loads.get().assert().isZero()
        admin.metrics.misses.assert().isZero()
        // 没有留下 lease：下一次读取直接回源并写回 L1
        memory.store("user").read("cocache:user:id-1", null, null).assert().isEqualTo(RemoteRead.Miss(false))
        users[1].assert().isEqualTo("user-1")
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun inspectsLocalAndRemoteValue() {
        val runtime = runtime()
        val users = runtime.users()
        val admin = runtime.userAdmin()
        val start = clock.nowMillis()
        users[1].assert().isEqualTo("user-1")

        val inspection = admin.inspect("id-1")
        val local = requireNotNull(inspection.local)
        local.state.assert().isEqualTo(LocalState.VALUE)
        local.value.assert().isEqualTo("user-1")
        local.expired.assert().isFalse()
        local.expiresAt.assert().isEqualTo(Instant.ofEpochMilli(start + CachePolicy.DEFAULT.localMaxAge.toMillis()))
        local.refreshAt.assert().isNotNull()
        inspection.remote.state.assert().isEqualTo(RemoteState.VALUE)
        inspection.remote.value.assert().isEqualTo("user-1")
        inspection.remote.format.assert().isEqualTo(InMemoryBackend.FORMAT)
        requireNotNull(inspection.remote.ttl).assert().isPositive()
        loads.get().assert().isEqualTo(1)

        // 查看不填充 L2、不影响命中路径；过期的 L2 副本照常展示
        clock.advance(CachePolicy.DEFAULT.localMaxAge)
        requireNotNull(admin.inspect("id-1").local).expired.assert().isTrue()
    }

    @Test
    fun inspectsNegativeAndUnresolvedEntries() {
        val runtime = runtime()
        val users = runtime.users(CachePolicy.DEFAULT)
        users[-1].assert().isNull()
        val negative = runtime.userAdmin().inspect("id--1")
        requireNotNull(negative.local).state.assert().isEqualTo(LocalState.ABSENT)
        negative.local?.value.assert().isNull()
        negative.local?.refreshAt.assert().isNull()
        negative.remote.state.assert().isEqualTo(RemoteState.ABSENT)
        negative.remote.value.assert().isNull()

        val orphans = runtime.create(cacheSpec<String, String>("orphan") { codec = StringCodec })
        orphans["x"].assert().isNull()
        val unresolved = requireNotNull(runtime.admin("orphan")).inspect("x")
        requireNotNull(unresolved.local).state.assert().isEqualTo(LocalState.UNRESOLVED)
        unresolved.remote.state.assert().isEqualTo(RemoteState.MISS)
    }

    @Test
    fun inspectsLeaseAndUnreadableContent() {
        val runtime = runtime()
        runtime.users()
        val admin = runtime.userAdmin()
        val store = memory.store("user")
        store.read("cocache:user:id-1", LeaseRequest(Token(NodeId.random(), 1), Duration.ofSeconds(10)), null)
        val leased = admin.inspect("id-1").remote
        leased.state.assert().isEqualTo(RemoteState.LEASED)
        requireNotNull(leased.ttl).assert().isPositive()

        store.put("cocache:user:id-2", Envelope.Scalar(TestCodec.CORRUPT), null, frame())
        val corrupt = admin.inspect("id-2").remote
        corrupt.state.assert().isEqualTo(RemoteState.UNREADABLE)
        corrupt.ttl.assert().isNull()
        corrupt.error.assert().contains("corrupted")
        // 查看从不回收：损坏的内容仍在
        store.read("cocache:user:id-2", null, null).assert().isInstanceOf(RemoteRead.Hit::class.java)

        store.put("cocache:user:id-3", Envelope.Members(listOf(byteArrayOf(1))), null, frame())
        admin.inspect("id-3").remote.state.assert().isEqualTo(RemoteState.UNREADABLE)

        backend.afterRead = { _, read -> (read as? RemoteRead.Hit)?.copy(format = 0) ?: read }
        admin.inspect("id-3").remote.error.assert().contains("Unrecognized")
        backend.afterRead = { _, read -> (read as? RemoteRead.Hit)?.copy(format = InMemoryBackend.FORMAT + 1) ?: read }
        admin.inspect("id-3").remote.error.assert().contains("newer format")
        backend.afterRead = null

        backend.failAll = true
        val error = admin.inspect("id-1").remote
        error.state.assert().isEqualTo(RemoteState.ERROR)
        error.error.assert().contains("unavailable")
    }

    @Test
    fun abbreviatesLongValues() {
        val runtime = runtime()
        val users = runtime.users()
        val long = "x".repeat(KeyInspection.MAX_VALUE_LENGTH + 10)
        users.put(1, long)
        val remote = runtime.userAdmin().inspect("id-1").remote
        remote.value.assert().isEqualTo("x".repeat(KeyInspection.MAX_VALUE_LENGTH) + "…(${long.length} chars)")
        KeyInspection.abbreviate("short").assert().isEqualTo("short")
        // 截断处不拆开代理对
        val emoji = "x".repeat(KeyInspection.MAX_VALUE_LENGTH - 1) + "😀" + "tail"
        KeyInspection.abbreviate(emoji).assert().startsWith("x".repeat(KeyInspection.MAX_VALUE_LENGTH - 1) + "…")
    }

    @Test
    fun displayToleratesFailingToString() {
        val broken = object {
            override fun toString(): String = throw IllegalStateException("lazy proxy")
        }
        KeyInspection.display(broken).assert().isEqualTo("<toString() failed: java.lang.IllegalStateException>")
        KeyInspection.display(42).assert().isEqualTo("42")
    }

    @Test
    fun rejectsEmptyKey() {
        val runtime = runtime()
        runtime.users()
        runCatching { runtime.userAdmin().inspect("") }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun invalidateEncodedIsClusterWideAndIdempotent() {
        val node1 = runtime()
        val node2 = runtime()
        val users1 = node1.users()
        val users2 = node2.users()
        users1[1].assert().isEqualTo("user-1")
        users2[1].assert().isEqualTo("user-1")
        loads.get().assert().isEqualTo(1)

        node1.userAdmin().invalidateEncoded("id-1")
        node1.userAdmin().inspect("id-1").local.assert().isNull()
        node1.userAdmin().inspect("id-1").remote.state.assert().isEqualTo(RemoteState.MISS)
        // 其它节点经广播淘汰 L2
        node2.userAdmin().inspect("id-1").local.assert().isNull()
        node2.userAdmin().metrics.invalidationsReceived.assert().isEqualTo(1)
        // 幂等
        node1.userAdmin().invalidateEncoded("id-1")
        users2[1].assert().isEqualTo("user-1")
        loads.get().assert().isEqualTo(2)
    }

    @Test
    fun invalidateEncodedRetriesOnFailure() {
        val runtime = runtime()
        val users = runtime.users()
        users[1].assert().isEqualTo("user-1")
        backend.failWrites = true
        runtime.userAdmin().invalidateEncoded("id-1")
        runtime.userAdmin().pendingInvalidationCount.assert().isEqualTo(1)
        runtime.userAdmin().inspect("id-1").invalidationPending.assert().isTrue()
        runtime.userAdmin().inspect("id-2").invalidationPending.assert().isFalse()
    }

    @Test
    fun setCacheInspectionAndClear() {
        val runtime = runtime()
        val index: SetCache<String, String> = runtime.create(setCacheSpec("index") { elementCodec = StringCodec })
        val admin = requireNotNull(runtime.admin("index"))
        admin.pendingInvalidationCount.assert().isZero()
        index.add("k", listOf("a", "b"))
        index["k"].assert().containsExactlyInAnyOrder("a", "b")

        val inspection = admin.inspect("k")
        inspection.remote.state.assert().isEqualTo(RemoteState.SET)
        inspection.remote.value.assert().isEqualTo("[a, b]")
        requireNotNull(inspection.local).value.assert().isEqualTo("[a, b]")
        inspection.invalidationPending.assert().isFalse()

        memory.store("index").put("cocache:index:scalar", Envelope.Scalar(byteArrayOf(1)), null, frame())
        admin.inspect("scalar").remote.state.assert().isEqualTo(RemoteState.UNREADABLE)

        // SetCache 的强制失效等价于 clear(key)
        admin.invalidateEncoded("k")
        index["k"].assert().isEmpty()
        admin.inspect("k").remote.state.assert().isEqualTo(RemoteState.MISS)

        backend.failWrites = true
        runCatching { admin.invalidateEncoded("k") }.exceptionOrNull()
            .assert().isInstanceOf(CacheWriteException::class.java)
    }
}
