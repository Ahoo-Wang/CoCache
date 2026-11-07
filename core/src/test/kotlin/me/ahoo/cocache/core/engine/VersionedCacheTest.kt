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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.ForwardingVersionedL1Store
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.VersionedL1Store
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.KeyFilter
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 带版本的类型（`VersionedCache`、`VersionedStore`）在 TCK 之外的边界：门面、异步与挂起视图、异常、寿命（墓碑与 `E` 的 `tombstoneTtl`）、
 * 非原子广播的后端上版本不更新时不广播、不带版本的 `null` 落在带版本的内容上（K16）。
 */
class VersionedCacheTest {
    private val memory = InMemoryBackend()
    private val runtime = CacheRuntime.builder().backend(memory).build()
    private val database = ConcurrentHashMap<String, Versioned<String?>>()
    private val loader = CacheLoader<String, Versioned<String?>> { database[it] }

    @AfterEach
    fun destroy() = runtime.close()

    private fun versioned(
        name: String = "doc",
        block: VersionedCacheSpec.Builder<String, String>.() -> Unit = {}
    ): VersionedCache<String, String> = runtime.create(
        versionedCacheSpec(name) {
            codec = StringCodec
            versionedLoader = this@VersionedCacheTest.loader
            block()
        }
    )

    private fun store(
        name: String = "aggregate",
        block: VersionedStoreSpec.Builder<String, String>.() -> Unit = {}
    ): VersionedStore<String, String> = runtime.create(
        versionedStoreSpec(name) {
            codec = StringCodec
            block()
        }
    )

    private fun remote(key: String, name: String = "doc"): L1Read =
        Suspending.await { memory.store(Keyspace("cocache:", name)).read("cocache:$name:$key", null, null, null) }

    private fun remoteTtl(key: String, name: String): Duration? = (remote(key, name) as L1Read.Hit).ttl

    private fun remoteVersion(key: String, name: String): Long =
        ((remote(key, name) as L1Read.Hit).envelope as Envelope.Scalar).version

    /**
     * 带版本的 `lookup` / `lookupAll` 一步换算出用户的值（不先建 `Lookup<Versioned>` 再转换）：存在的值为 `Present`，墓碑与 key 过滤器
     * 判定不存在的 key 为 `Absent`，同步与异步视图相同。
     */
    @Test
    fun lookupsMapTombstonesAndFilteredKeysToAbsent() {
        database["live"] = Versioned("v", 1)
        database["gone"] = Versioned(null, 2)
        val cache = versioned("lookups") { keyFilter = KeyFilter { it == "blocked" } }
        val all = cache.lookupAll(listOf("live", "gone", "blocked"))
        (all.getValue("live") as Lookup.Present).value.assert().isEqualTo("v")
        all.getValue("gone").assert().isInstanceOf(Lookup.Absent::class.java)
        all.getValue("blocked").assert().isInstanceOf(Lookup.Absent::class.java)
        cache.lookup("blocked").assert().isInstanceOf(Lookup.Absent::class.java)
        (cache.lookup("live") as Lookup.Present).value.assert().isEqualTo("v")
        cache.async().lookupAll(listOf("blocked", "live")).join().getValue("blocked")
            .assert().isInstanceOf(Lookup.Absent::class.java)
    }

    @Test
    fun runtimeLooksUpTheVersionedTypes() {
        val cache = versioned()
        val store = store()
        runtime.cache("doc").assert().isSameAs(cache)
        runtime.cache("aggregate").assert().isSameAs(store)
        cache.name.assert().isEqualTo("doc")
        cache.toString().assert().contains("VersionedCache")
        store.toString().assert().contains("VersionedStore")
        cache.async().sync().assert().isSameAs(cache)
        store.async().sync().assert().isSameAs(store)
        cache.async().name.assert().isEqualTo("doc")
        store.async().name.assert().isEqualTo("aggregate")
        cache.async().toString().assert().contains("VersionedCache")
        store.async().toString().assert().contains("VersionedStore")
        cache.suspending().async().assert().isSameAs(cache.async())
        store.suspending().async().assert().isSameAs(store.async())
        cache.putIfNewer("k", "v", 3)
        requireNotNull(
            runtime.admin("doc")
        ).inspect("k").remote.value.assert().isEqualTo("Versioned(value=v, version=3)")
    }

    /**
     * 读取返回用户的值类型：墓碑对用户就是不存在（`get`、`lookup`、`getAll`、`lookupAll`）。
     */
    @Test
    fun readsUnwrapVersionsAndTombstones() {
        val cache = versioned()
        database["a"] = Versioned("1", 1)
        database["gone"] = Versioned(null, 2)
        cache.getAll(listOf("a", "gone", "never")).assert().isEqualTo(mapOf("a" to "1"))
        (cache.lookup("a") as Lookup.Present).value.assert().isEqualTo("1")
        cache.lookup("gone").assert().isInstanceOf(Lookup.Absent::class.java)
        val lookups = cache.lookupAll(listOf("a", "gone", "never"))
        (lookups.getValue("a") as Lookup.Present).value.assert().isEqualTo("1")
        lookups.getValue("gone").assert().isInstanceOf(Lookup.Absent::class.java)
        lookups.getValue("never").assert().isInstanceOf(Lookup.Absent::class.java)
        cache.getVersioned("gone").assert().isNull()
        cache.getVersioned("a").assert().isEqualTo(Versioned("1", 1))
        val async = cache.async()
        (async.lookupAll(listOf("a", "gone")).join().getValue("gone")).assert().isInstanceOf(Lookup.Absent::class.java)
        (async.lookup("a").join() as Lookup.Present).value.assert().isEqualTo("1")
        async.getAll(listOf("a", "gone")).join().assert().isEqualTo(mapOf("a" to "1"))
    }

    @Test
    fun asyncViewsMirrorTheSyncTypes() {
        val async = versioned().async()
        database["e"] = Versioned("loaded-e", 4)
        async.get("e").join().assert().isEqualTo("loaded-e")
        async.getVersioned("e").join().assert().isEqualTo(Versioned("loaded-e", 4))
        async.putIfNewer("v", "v1", 1).join().assert().isEqualTo(VersionedPut.Written)
        async.putIfNewer("v", "v0", 0, Duration.ofMinutes(1)).join().assert().isEqualTo(VersionedPut.Stale(1))
        async.invalidateIfNewer("v", 2).join().assert().isEqualTo(VersionedPut.Written)
        async.invalidateIfNewer("v", 3, Duration.ofMinutes(1)).join().assert().isEqualTo(VersionedPut.Written)
        async.getVersioned("v").join().assert().isNull()
        async.evict("v").join()
        async.putIfNewer("v", "v3", 3).join().assert().isEqualTo(VersionedPut.Stale(3))
        async.purge("v").join()
        async.putIfNewer("v", "v1", 1).join().assert().isEqualTo(VersionedPut.Written)

        val store = store().async()
        store.putIfNewer("k", "s1", 1).join().assert().isEqualTo(VersionedPut.Written)
        store.putIfNewer("k", "s0", 0, Duration.ofMinutes(1)).join().assert().isEqualTo(VersionedPut.Stale(1))
        store.getVersioned("k").join().assert().isEqualTo(Versioned("s1", 1))
        store.get("k").join().assert().isEqualTo("s1")
        store.getAll(listOf("k", "x")).join().assert().isEqualTo(mapOf("k" to "s1"))
        (store.lookup("k").join() as Lookup.Present).value.assert().isEqualTo("s1")
        store.lookupAll(listOf("x")).join().getValue("x").assert().isInstanceOf(Lookup.Absent::class.java)
        store.deleteIfNewer("k", 2).join().assert().isEqualTo(VersionedPut.Written)
        store.deleteIfNewer("k", 3, Duration.ofMinutes(1)).join().assert().isEqualTo(VersionedPut.Written)
        store.get("k").join().assert().isNull()
        store.purge("k").join()
        store.putIfNewer("k", "s0", 0).join().assert().isEqualTo(VersionedPut.Written)
    }

    @Test
    fun suspendingViewsMirrorTheSyncTypes() = runBlocking {
        val cache = versioned().suspending()
        database["e"] = Versioned("loaded-e", 4)
        cache.get("e").assert().isEqualTo("loaded-e")
        cache.getVersioned("e").assert().isEqualTo(Versioned("loaded-e", 4))
        cache.getAll(listOf("e", "x")).assert().isEqualTo(mapOf("e" to "loaded-e"))
        cache.lookup("x").assert().isInstanceOf(Lookup.Absent::class.java)
        cache.lookupAll(listOf("e")).getValue("e").assert().isInstanceOf(Lookup.Present::class.java)
        cache.putIfNewer("v", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        cache.putIfNewer("v", "v0", 0, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Stale(1))
        cache.invalidateIfNewer("v", 2).assert().isEqualTo(VersionedPut.Written)
        cache.invalidateIfNewer("v", 3, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        cache.evict("v")
        cache.purge("v")
        cache.putIfNewer("v", "v1", 1).assert().isEqualTo(VersionedPut.Written)

        val store = store().suspending()
        store.putIfNewer("k", "s1", 1).assert().isEqualTo(VersionedPut.Written)
        store.putIfNewer("k", "s2", 2, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        store.getVersioned("k").assert().isEqualTo(Versioned("s2", 2))
        store.deleteIfNewer("k", 3).assert().isEqualTo(VersionedPut.Written)
        store.deleteIfNewer("k", 4, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        store.get("k").assert().isNull()
        store.purge("k")
        store.putIfNewer("k", "s0", 0).assert().isEqualTo(VersionedPut.Written)
        Unit
    }

    /**
     * 异步视图以原始异常完成：数据源失败、非法版本都不包装为 CompletionException，也不同步抛出。
     */
    @Test
    fun asyncViewCompletesWithTheOriginalException() {
        val failure = IllegalStateException("down")
        val async = versioned { versionedLoader = CacheLoader { throw failure } }.async()
        val loadError = async.get("k").handle { _, error -> error }.join()
        loadError.assert().isSameAs(failure)
        val versionError = async.putIfNewer("k", "v", Versioned.NO_VERSION).handle { _, error -> error }.join()
        (versionError is CompletionException).assert().isFalse()
        versionError.assert().isInstanceOf(IllegalArgumentException::class.java)
        val storeError = store().async().deleteIfNewer("k", Versioned.NO_VERSION).handle { _, error -> error }.join()
        storeError.assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 带版本的异步数据源：同步与异步、单个与批量都保存版本。
     */
    @Test
    fun asyncVersionedLoadersFillWithTheirVersion() {
        val cache = versioned("async") {
            versionedLoader = null
            versionedAsyncLoader = AsyncCacheLoader { CompletableFuture.completedFuture(Versioned("a-$it", 7)) }
        }
        cache.async().getVersioned("k").join().assert().isEqualTo(Versioned("a-k", 7))
        cache.async().getAll(listOf("a", "b")).join().assert().isEqualTo(mapOf("a" to "a-a", "b" to "a-b"))
        cache.putIfNewer("k", "v7", 7).assert().isEqualTo(VersionedPut.Stale(7))
        cache.putIfNewer("k", "v8", 8).assert().isEqualTo(VersionedPut.Written)
    }

    /**
     * 非原子广播的后端：版本更旧时引擎不广播（存储没有修改 L1）。相等的版本可能是重放，仍然广播（`VersionedSpec`）。
     */
    @Test
    fun staleVersionsAreNotBroadcastWithoutAtomicBroadcast() {
        val writer = CacheRuntime.builder().backend(
            HookedBackend(memory).apply { atomicBroadcastOverride = false }
        ).build()
        writer.use {
            val cache = it.create(
                versionedCacheSpec<String, String>("shared") {
                    codec = StringCodec
                    versionedLoader = loader
                }
            )
            val observer = versioned("shared")
            cache.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
            val received = requireNotNull(runtime.metrics("shared")).invalidationsReceived
            received.assert().isGreaterThan(0L)
            cache.putIfNewer("k", "v0", 0).assert().isEqualTo(VersionedPut.Stale(1))
            cache.async().invalidateIfNewer("k", 0).join().assert().isEqualTo(VersionedPut.Stale(1))
            requireNotNull(runtime.metrics("shared")).invalidationsReceived.assert().isEqualTo(received)
            observer["k"].assert().isEqualTo("v1")
        }
    }

    /**
     * 非原子广播的后端上，版本前进的填充（`E` 上取得的 lease）由引擎广播：广播失败只计数告警，回源值照常返回，版本已写入 L1。
     */
    @Test
    fun failedBroadcastOfAnAdvancingFillIsCounted() {
        val failing = object : CoherenceBackend by memory {
            override val atomicBroadcast: Boolean = false
            override val channel: InvalidationChannel = object : InvalidationChannel by memory.channel {
                override suspend fun publish(keyspace: Keyspace, frame: ByteArray) = throw IllegalStateException(
                    "publish down"
                )
            }
        }
        versioned("evicted").putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        requireNotNull(runtime.admin("evicted")).invalidateEncoded("k")
        CacheRuntime.builder().backend(failing).build().use { other ->
            val cache = other.create(
                versionedCacheSpec<String, String>("evicted") {
                    codec = StringCodec
                    versionedLoader = CacheLoader { Versioned("v2", 2) }
                }
            )
            cache["k"].assert().isEqualTo("v2")
            requireNotNull(other.metrics("evicted")).remoteFailures.assert().isEqualTo(1)
        }
        remoteVersion("k", "evicted").assert().isEqualTo(2)
    }

    /**
     * 刷新路径的版本拒绝，而取得令牌时读到的更新的值解码失败：不能以它重新填充 L2，按未命中路径降级；L2 不按版本后退（TLA+ F3），
     * 回源得到的更旧的值不替换已有的版本 5 的副本（计入 `staleFillsDiscarded`），L1 中更新的版本不变。
     */
    @Test
    fun versionRejectedRefreshWithAnUndecodableCopyDegrades() {
        val clock = ManualClock()
        val backend = InMemoryBackend(clock)
        val failDecode = AtomicBoolean(false)
        val codec = object : Codec<String> {
            override fun encode(value: String): ByteArray = StringCodec.encode(value)

            override fun decode(bytes: ByteArray): String =
                if (failDecode.get()) throw IllegalStateException("codec down") else StringCodec.decode(bytes)
        }
        CacheRuntime.builder().backend(backend).clock(clock).refreshExecutor { it.run() }.build().use { other ->
            val cache = other.create(
                versionedCacheSpec<String, String>("refresh") {
                    this.codec = codec
                    versionedLoader = CacheLoader { Versioned("v4", 4) }
                    policy {
                        ttl = Duration.ofSeconds(10)
                        ttlJitter = Duration.ZERO
                        localMaxAge = Duration.ofSeconds(8)
                        refreshWindow = Duration.ofSeconds(5)
                    }
                }
            )
            cache.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
            cache["k"].assert().isEqualTo("v5")
            clock.advance(Duration.ofSeconds(6))
            failDecode.set(true)
            cache["k"].assert().isEqualTo("v5")
            val metrics = requireNotNull(other.metrics("refresh"))
            Concurrency.awaitCondition("the refresh was rejected") { metrics.fillRejected == 1L }
            metrics.degradedFills.assert().isEqualTo(1)
            metrics.staleFillsDiscarded.assert().isEqualTo(1)
            cache.getVersioned("k").assert().isEqualTo(Versioned("v5", 5))
            val hit = Suspending.await {
                backend.store(Keyspace("cocache:", "refresh")).read("cocache:refresh:k", null, null, null)
            } as L1Read.Hit
            (hit.envelope as Envelope.Scalar).version.assert().isEqualTo(5L)
        }
    }

    /**
     * 刷新路径的版本拒绝，而回源（连同 `FILL` 的应答）慢到取得令牌时读到的副本在 L1 中已经到期：读到的值不再填入 L2
     * （只交给合并到这次刷新的调用方），已有的 L2 副本照常到期，下一次读取回到 L1。
     */
    @Test
    fun versionRejectedRefreshNeverRefillsAnExpiredCopy() = refreshingVersioned { refresh ->
        // 第一次读取（0 s）建立的 L2 副本受限龄约束，在 8 s 到期
        val firstCopyExpiresAt = refresh.clock.nowMillis() + Duration.ofSeconds(2).toMillis()
        refresh.hooked.afterFill = { _, _ -> refresh.clock.advance(Duration.ofSeconds(5)) }
        refresh.cache["k"].assert().isEqualTo("v5")
        refresh.hooked.afterFill = null
        requireNotNull(refresh.runtime.metrics("refresh")).fillRejected.assert().isEqualTo(1)
        val local = requireNotNull(refresh.runtime.admin("refresh")).inspect("k").local
        (local == null || local.expiresAt.toEpochMilli() == firstCopyExpiresAt).assert()
            .withFailMessage { "the copy read at 6 s expired in L1 at 10 s, but L2 was refilled with it: $local" }
            .isTrue()
        refresh.cache["k"].assert().isEqualTo("v4")
    }

    /**
     * 同上，取得令牌时读到的副本没有寿命（后端为永不过期的值授予了刷新令牌）：保留的副本只受 L2 限龄约束，不设刷新点。
     */
    @Test
    fun versionRejectedRefreshOfAnEternalCopyIsBoundedByLocalMaxAge() = refreshingVersioned { refresh ->
        refresh.hooked.afterRead = { _, read ->
            if (read is L1Read.Hit && read.refreshGranted) read.copy(ttl = null) else read
        }
        refresh.cache["k"].assert().isEqualTo("v5")
        refresh.hooked.afterRead = null
        val local = requireNotNull(requireNotNull(refresh.runtime.admin("refresh")).inspect("k").local)
        local.expiresAt.toEpochMilli().assert().isEqualTo(refresh.clock.nowMillis() + Duration.ofSeconds(8).toMillis())
        local.refreshAt.assert().isNull()
    }

    private class RefreshingVersioned(
        val clock: ManualClock,
        val hooked: HookedBackend,
        val runtime: CacheRuntime,
        val cache: VersionedCache<String, String>
    )

    /**
     * 提前刷新的带版本的缓存 `refresh`（寿命 10 s，窗口 5 s，L2 限龄 8 s，数据源落后：总是返回版本 4）：写入版本 5、读一次建立 L2，
     * 时间前进 6 s（进入刷新窗口）之后执行 [block]。
     */
    private fun refreshingVersioned(block: (RefreshingVersioned) -> Unit) {
        val clock = ManualClock()
        val hooked = HookedBackend(InMemoryBackend(clock))
        CacheRuntime.builder().backend(hooked).clock(clock).refreshExecutor { it.run() }.build().use { other ->
            val cache = other.create(
                versionedCacheSpec<String, String>("refresh") {
                    codec = StringCodec
                    versionedLoader = CacheLoader { Versioned("v4", 4) }
                    policy {
                        ttl = Duration.ofSeconds(10)
                        ttlJitter = Duration.ZERO
                        localMaxAge = Duration.ofSeconds(8)
                        refreshWindow = Duration.ofSeconds(5)
                    }
                }
            )
            cache.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
            cache["k"].assert().isEqualTo("v5")
            clock.advance(Duration.ofSeconds(6))
            block(RefreshingVersioned(clock, hooked, other, cache))
        }
    }

    @Test
    fun memoryStoreRefusesSetsInVersionedWrites() {
        val store = requireNotNull(memory.store(Keyspace("cocache:", "edges")).versioned())
        val frame = InvalidationFrame.encode(NodeId.random(), emptyList())
        runCatching { Suspending.await { store.putIfNewer("k", Envelope.Members(emptyList()), 1, null, frame) } }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 按版本写入的默认寿命：值按 `ttlOf`（否则策略寿命），墓碑与数据源返回的带版本的不存在按墓碑寿命（`tombstoneTtl`，
     * 未设置时为策略寿命）；策略寿命为永不过期且没有墓碑寿命时都不过期。
     */
    @Test
    fun versionedWritesFollowTtlOfAndTombstonePolicies() {
        val cache = versioned("ttl") {
            ttlOf = TtlFunction { _, value -> if (value == "short") Duration.ofSeconds(30) else null }
            versionedLoader = CacheLoader { Versioned(null, 9) }
            policy {
                ttl = Duration.ofMinutes(10)
                ttlJitter = Duration.ZERO
            }
        }
        cache.putIfNewer("short", "short", 1)
        requireNotNull(remoteTtl("short", "ttl")).assert().isLessThanOrEqualTo(Duration.ofSeconds(30))
        cache.putIfNewer("long", "long", 1)
        requireNotNull(remoteTtl("long", "ttl")).assert().isGreaterThan(Duration.ofMinutes(9))
        cache["deleted"].assert().isNull()
        requireNotNull(remoteTtl("deleted", "ttl")).assert().isGreaterThan(Duration.ofMinutes(9))
        cache.putIfNewer("deleted", "old", 9).assert().isEqualTo(VersionedPut.Stale(9))

        val forever = versioned("forever") { policy { ttl = null } }
        forever.putIfNewer("k", "v", 1)
        remoteTtl("k", "forever").assert().isNull()
        forever.async().invalidateIfNewer("k", 2).join()
        remoteTtl("k", "forever").assert().isNull()

        // 墓碑寿命：墓碑、回源得到的带版本的不存在与 `E` 都用它
        val fenced = versioned("fenced") {
            versionedLoader = CacheLoader { Versioned(null, 9) }
            policy {
                ttl = null
                tombstoneTtl = Duration.ofMinutes(3)
            }
        }
        fenced.putIfNewer("value", "v", 1)
        remoteTtl("value", "fenced").assert().isNull()
        fenced.invalidateIfNewer("tombstone", 1)
        requireNotNull(
            remoteTtl("tombstone", "fenced")
        ).assert().isBetween(Duration.ofMinutes(2), Duration.ofMinutes(3))
        fenced["loaded"].assert().isNull()
        requireNotNull(remoteTtl("loaded", "fenced")).assert().isBetween(Duration.ofMinutes(2), Duration.ofMinutes(3))
        // `E` 读作未命中，它的寿命见 TCK（VersionedSpec.eKeepsItsOwnExpiry）
        fenced.evict("value")
        remote("value", "fenced").assert().isEqualTo(L1Read.Miss(false))

        // VersionedStore 的默认策略：值永不过期，墓碑 1 天
        val aggregate = store("aggregate-ttl")
        aggregate.putIfNewer("k", "v", 1)
        remoteTtl("k", "aggregate-ttl").assert().isNull()
        aggregate.deleteIfNewer("k", 2)
        requireNotNull(remoteTtl("k", "aggregate-ttl")).assert()
            .isBetween(Duration.ofHours(23), requireNotNull(DefaultPolicies.VERSIONED_STORE.tombstoneTtl))
    }

    /**
     * 带 `ttl` 的版本写入接受 `null`，含义与不带 `ttl` 的重载相同（策略寿命：`ttlOf` 优先；墓碑为墓碑寿命）：
     * 调用方（Wow）不必为“有没有给出寿命”各写一组分支。三种视图相同。
     */
    @Test
    fun nullTtlMeansThePolicyLifetime() {
        val cache = versioned("nullable") {
            ttlOf = TtlFunction { _, value -> if (value == "short") Duration.ofSeconds(30) else null }
            policy {
                ttl = Duration.ofMinutes(10)
                ttlJitter = Duration.ZERO
                tombstoneTtl = Duration.ofMinutes(3)
            }
        }
        val noTtl: Duration? = null
        cache.putIfNewer("short", "short", 1, noTtl).assert().isEqualTo(VersionedPut.Written)
        requireNotNull(remoteTtl("short", "nullable")).assert().isLessThanOrEqualTo(Duration.ofSeconds(30))
        cache.async().putIfNewer("long", "long", 1, noTtl).join().assert().isEqualTo(VersionedPut.Written)
        requireNotNull(remoteTtl("long", "nullable")).assert().isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10))
        val suspending = cache.suspending()
        Suspending.await { suspending.invalidateIfNewer("long", 2, noTtl) }.assert().isEqualTo(VersionedPut.Written)
        requireNotNull(remoteTtl("long", "nullable")).assert().isBetween(Duration.ofMinutes(2), Duration.ofMinutes(3))
        // 给出的寿命照常优先
        cache.putIfNewer("given", "short", 1, Duration.ofMinutes(5))
        requireNotNull(remoteTtl("given", "nullable")).assert().isGreaterThan(Duration.ofMinutes(4))

        val aggregate = store("nullable-store")
        aggregate.putIfNewer("k", "v", 1, noTtl).assert().isEqualTo(VersionedPut.Written)
        remoteTtl("k", "nullable-store").assert().isNull()
        aggregate.deleteIfNewer("k", 2, noTtl).assert().isEqualTo(VersionedPut.Written)
        requireNotNull(remoteTtl("k", "nullable-store")).assert()
            .isBetween(Duration.ofHours(23), requireNotNull(DefaultPolicies.VERSIONED_STORE.tombstoneTtl))
    }

    /**
     * 存储提供了按版本写入的能力面，但它以 [UnsupportedOperationException] 拒绝 `putIfNewer`：原样传给调用方，不包装。
     */
    @Test
    fun storesRefusingPutIfNewerFailUnwrapped() {
        val claiming = object : CoherenceBackend by memory {
            override fun store(keyspace: Keyspace): L1Store = object : ForwardingL1Store(memory.store(keyspace)) {
                private val refusing = delegate.versioned()?.let { surface ->
                    object : ForwardingVersionedL1Store(surface) {
                        override suspend fun putIfNewer(
                            key: String,
                            envelope: Envelope,
                            version: Long,
                            ttl: Duration?,
                            frame: ByteArray
                        ): L1VersionedWrite = throw UnsupportedOperationException("no putIfNewer")
                    }
                }

                override fun versioned(): VersionedL1Store? = refusing
            }
        }
        CacheRuntime.builder().backend(claiming).build().use { other ->
            val cache = other.create(
                versionedCacheSpec<String, String>("doc") {
                    codec = StringCodec
                    versionedLoader = loader
                }
            )
            runCatching { cache.putIfNewer("k", "v", 1) }.exceptionOrNull()
                .assert().isInstanceOf(UnsupportedOperationException::class.java)
            cache.async().invalidateIfNewer("k", 1).handle { _, error -> error }.join()
                .assert().isInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    /**
     * K16（RFC 0005 §4.1）：带版本的数据源返回不带版本的 `null`、落在 `E` 上被拒绝：计入 `unversionedAbsentRefusals` 与
     * `fillRejected`，`E` 保留；返回 `Versioned(null, w)` 时写入墓碑，不再计数。批量回源同样计数。
     */
    @Test
    fun unversionedAbsenceOverVersionedContentIsCounted() {
        val clock = ManualClock()
        val backend = InMemoryBackend(clock)
        CacheRuntime.builder().backend(backend).clock(clock).build().use { other ->
            val cache = other.create(
                versionedCacheSpec<String, String>("k16") {
                    codec = StringCodec
                    versionedLoader = loader
                }
            )
            val metrics = requireNotNull(other.metrics("k16"))
            fun remoteOf(key: String) = Suspending.await {
                backend.store(Keyspace("cocache:", "k16")).read("cocache:k16:$key", null, null, null)
            }
            cache.putIfNewer("a", "v5", 5)
            cache.putIfNewer("b", "v5", 5)
            cache.evict("a")
            cache.evict("b")
            cache["a"].assert().isNull()
            metrics.unversionedAbsentRefusals.assert().isEqualTo(1)
            metrics.fillRejected.assert().isEqualTo(1)
            remoteOf("a").assert().isEqualTo(L1Read.Miss(false))
            // 降级的副本到期之后，批量回源同样被拒绝、同样计数
            clock.advance(Duration.ofMinutes(1))
            cache.getAll(listOf("a", "b")).assert().isEmpty()
            metrics.unversionedAbsentRefusals.assert().isEqualTo(3)

            database["a"] = Versioned(null, 6)
            clock.advance(Duration.ofMinutes(1))
            cache["a"].assert().isNull()
            (remoteOf("a") as L1Read.Hit).envelope.assert().isEqualTo(Envelope.Absent)
            metrics.unversionedAbsentRefusals.assert().isEqualTo(3)
            // 在没有版本的内容上，不带版本的 null 是没有版本的负缓存：不计数
            cache["never"].assert().isNull()
            (remoteOf("never") as L1Read.Hit).envelope.assert().isEqualTo(Envelope.Absent)
            metrics.unversionedAbsentRefusals.assert().isEqualTo(3)
        }
    }

    /**
     * `VersionedStore` 的 L1 读失败是 `Unknown`（同 `Store`）：`get` 抛出，`lookup` 返回它；`getVersioned` 同 `get`。
     */
    @Test
    fun versionedStoreReadFailuresAreUnknown() {
        val hooked = HookedBackend(memory)
        CacheRuntime.builder().backend(hooked).build().use { other ->
            val store = other.create(versionedStoreSpec<String, String>("unknown") { codec = StringCodec })
            hooked.failAll = true
            runCatching { store["k"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
            runCatching {
                store.getVersioned(
                    "k"
                )
            }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
            store.lookup("k").assert().isInstanceOf(Lookup.Unknown::class.java)
            store.lookupAll(listOf("k")).getValue("k").assert().isInstanceOf(Lookup.Unknown::class.java)
            hooked.failAll = false
            requireNotNull(other.metrics("unknown")).readsUnknown.assert().isEqualTo(4)
        }
    }

    /**
     * `evict` 遇到 `Store` 写入的值（带摘要段，D4）每次都拒绝、计入 `typeMismatches`，但每个缓存只告警一次，之后记为 DEBUG：
     * 命名空间配置错误时每次 `evict` 都会遇到它，告警不能随调用量增长。读取遇到它共用同一个告警。
     */
    @Test
    fun refusedEvictionsWarnOncePerCache() {
        val bytes = StringCodec.encode("p")
        Suspending.await {
            memory.store(Keyspace("cocache:", "doc")).put(
                "cocache:doc:plain",
                Envelope.Scalar(bytes, Versioned.NO_VERSION, null, Digest.ofPayload(bytes)),
                null,
                InvalidationFrame.encode(NodeId.random(), emptyList())
            )
        }
        val cache = versioned()
        val logger = LoggerFactory.getLogger(AbstractEngine::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            repeat(3) {
                val refused = runCatching { cache.evict("plain") }.exceptionOrNull()
                refused.assert().isInstanceOf(CacheWriteException::class.java)
            }
            cache["plain"].assert().isEqualTo("p")
        } finally {
            logger.detachAppender(appender)
        }
        requireNotNull(runtime.metrics("doc")).typeMismatches.assert().isEqualTo(4)
        val warnings = appender.list.filter { it.level == Level.WARN && it.formattedMessage.contains("written by a Store") }
        warnings.assert().hasSize(1)
    }

    @Test
    fun formatsRefuseToEncodeAVersionedAbsence() {
        runCatching { VersionedFormat(StringCodec).encode(Versioned(null, 1), null, false) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        VersionedFormat(StringCodec).versionOf(Versioned("v", 3)).assert().isEqualTo(3L)
        VersionedFormat(StringCodec).isTombstone(Versioned(null, 3)).assert().isTrue()
        VersionedFormat(StringCodec).isTombstone(Versioned("v", 3)).assert().isFalse()
        CodecFormat(StringCodec).versionOf("v").assert().isEqualTo(Versioned.NO_VERSION)
        CodecFormat(StringCodec).isTombstone("v").assert().isFalse()
    }
}
