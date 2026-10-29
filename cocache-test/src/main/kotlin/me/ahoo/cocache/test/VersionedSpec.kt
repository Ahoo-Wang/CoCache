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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteConditionalWrite
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.RemoteVersionedWrite
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 按版本有序的写入的 TCK（6.8，`docs/architecture.md` §6.5）：两个节点（运行时）共享同一个后端。
 * 只适用于声明 [CoherenceBackend.versionedWrites] 的后端；不支持版本的后端不必继承它（声明带版本的缓存会在创建时失败）。
 *
 * - 协议（直接调用 [RemoteStore]）：版本随 fill / put / putAll 保存、读取原样返回（putIfMatch 写入没有版本的值）；按版本写入销毁 lease、填充因此被拒绝；
 *   带版本的内容不授予刷新令牌、不被回收；墓碑读作负缓存；版本按有符号 64 位整数比较。
 * - 引擎（经 [access]，同步或异步视图）：有序、相等版本幂等、旧版本拒绝且不广播不改寿命、墓碑挡住迟到的旧版本、
 *   lease 持有者回源期间落地的按版本写入胜出、回源保存版本且与随后的写入比较、并发写入收敛到最高版本、结果未知时重放安全。
 *
 * 竞态用 latch 编排；跨节点效果用带超时的轮询断言。
 */
@Suppress("LargeClass", "TooManyFunctions")
abstract class VersionedSpec {
    protected abstract fun createBackend(): CoherenceBackend

    /**
     * 引擎与后端共用的时钟。
     */
    protected abstract val clock: CacheClock

    /**
     * 让时间前进 [duration]（同时作用于后端与引擎）。
     */
    protected abstract fun elapse(duration: Duration)

    /**
     * 访问缓存的方式：同步 API（默认），或经异步视图（[AsyncVersionedSpec]）。
     */
    protected open val access: CacheAccess = CacheAccess.SYNC

    /**
     * 用例结束时是否关闭 [createBackend] 返回的后端（见 [HookedBackend.closesDelegate]）。
     */
    protected open val closesBackend: Boolean = false

    protected lateinit var backend: HookedBackend
    protected lateinit var nodeA: CacheRuntime
    protected lateinit var nodeB: CacheRuntime
    protected lateinit var cacheName: String

    /**
     * 模拟数据源：每个 key 的当前值与版本；`value` 为 `null` 表示在这个版本时已删除（带版本的不存在）。
     */
    protected val database = ConcurrentHashMap<String, Versioned<String?>>()
    protected val loads = AtomicInteger()

    /**
     * 每次单 key 回源读到数据之后调用（用于在回源中途编排竞态）。
     */
    @Volatile
    protected var afterLoad: ((String) -> Unit)? = null

    private val directExecutor = Executor { it.run() }

    @BeforeEach
    open fun setup() {
        backend = HookedBackend(createBackend()).apply { closesDelegate = closesBackend }
        nodeA = newNode()
        nodeB = newNode()
        cacheName = "tck-v-" + UUID.randomUUID().toString().substring(0, 8)
    }

    @AfterEach
    open fun destroy() {
        nodeA.close()
        nodeB.close()
        backend.close()
    }

    private fun newNode(): CacheRuntime = CacheRuntime.builder()
        .backend(backend)
        .clock(clock)
        .refreshExecutor(directExecutor)
        .retryPolicy(RetryPolicy(initialBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(50)))
        .also { configureNode(it) }
        .build()

    /**
     * 调整 [nodeA] / [nodeB] 的运行时（如异步路径的执行器）。
     */
    protected open fun configureNode(builder: CacheRuntime.Builder) = Unit

    protected open fun policy(): CachePolicy = CachePolicy(
        ttl = Duration.ofMinutes(10),
        ttlJitter = Duration.ZERO,
        lease = LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ofSeconds(5)),
    )

    protected val loader = object : CacheLoader<String, Versioned<String?>> {
        override fun load(key: String): Versioned<String?>? {
            loads.incrementAndGet()
            val value = database[key]
            afterLoad?.invoke(key)
            return value
        }

        override fun loadAll(keys: Collection<String>): Map<String, Versioned<String?>> =
            keys.mapNotNull { key -> database[key]?.let { key to it } }.toMap()
    }

    protected fun spec(
        policy: CachePolicy = policy(),
        block: CacheSpec.Builder<String, String>.() -> Unit = {}
    ): CacheSpec<String, String> = cacheSpec(cacheName) {
        codec = TestCodec
        versionedLoader = this@VersionedSpec.loader
        this.policy = policy
        block()
        access.source(this)
    }

    protected fun CacheRuntime.cache(spec: CacheSpec<String, String> = spec()): Cache<String, String> = access.cache(
        create(spec)
    )

    protected fun CacheRuntime.metrics(): CacheMetrics = requireNotNull(metrics(cacheName))

    protected fun storageKey(key: String) = "cocache:$cacheName:$key"

    protected fun store(): RemoteStore = backend.delegate.store(cacheName)

    /**
     * 不经过引擎直接读 L1。
     */
    protected fun remote(key: String): RemoteRead = store().read(storageKey(key), null, null)

    /**
     * L1 中的值与版本；没有值（不存在、lease、负缓存或墓碑）时为 `null`。
     */
    protected fun remoteVersioned(key: String): Versioned<String>? {
        val scalar = (remote(key) as? RemoteRead.Hit)?.envelope as? Envelope.Scalar ?: return null
        return Versioned(TestCodec.decode(scalar.bytes), scalar.version)
    }

    protected fun remoteTtl(key: String): Duration? = (remote(key) as RemoteRead.Hit).ttl

    private fun frame(key: String): ByteArray = InvalidationFrame.encode(NodeId.random(), listOf(storageKey(key)))

    private fun token(): Token = Token(NodeId.random(), System.nanoTime())

    private fun scalar(value: String, version: Long = Versioned.NO_VERSION) = Envelope.Scalar(
        TestCodec.encode(value),
        version
    )

    // ---------------------------------------------------------------------------------------------
    // 协议（RemoteStore）
    // ---------------------------------------------------------------------------------------------

    /**
     * 版本随整体写入（fill、put、putAll）保存，读取原样返回；没有版本的写入读出 [Versioned.NO_VERSION]。
     */
    @Test
    fun wholeWritesStoreTheEnvelopeVersion() {
        val store = store()
        val lease = LeaseRequest(token(), Duration.ofSeconds(30))
        store.read(storageKey("fill"), lease, null).assert().isEqualTo(RemoteRead.Miss(true))
        store.fill(Fill(storageKey("fill"), lease.token, scalar("v7", 7), Duration.ofMinutes(1))).assert().isTrue()
        ((remote("fill") as RemoteRead.Hit).envelope as Envelope.Scalar).version.assert().isEqualTo(7L)

        store.put(storageKey("put"), scalar("v9", 9), null, frame("put"))
        remoteVersioned("put").assert().isEqualTo(Versioned("v9", 9))
        store.putAll(listOf(Put(storageKey("all"), scalar("v3", 3), null)), frame("all"))
        remoteVersioned("all").assert().isEqualTo(Versioned("v3", 3))
        store.put(storageKey("plain"), scalar("p"), null, frame("plain"))
        remoteVersioned("plain").assert().isEqualTo(Versioned("p", Versioned.NO_VERSION))
        // 条件写入写入没有版本的值（信封自带的版本被忽略）
        store.putIfMatch(storageKey("put"), Digest.of(scalar("v9")), scalar("m", 5), null, frame("put"), false)
            .assert().isEqualTo(RemoteConditionalWrite.Written)
        remoteVersioned("put").assert().isEqualTo(Versioned("m", Versioned.NO_VERSION))
    }

    /**
     * 按版本写入只在版本更高时写入：没有版本的内容与不存在总是被覆盖；保存的是参数中的版本，不是信封自带的版本。
     */
    @Test
    fun putIfNewerComparesAgainstTheStoredVersion() {
        val store = store()
        val key = storageKey("k")
        store.putIfNewer(key, scalar("v2"), 2, null, frame("k"), false).assert().isEqualTo(RemoteVersionedWrite.Written)
        remoteVersioned("k").assert().isEqualTo(Versioned("v2", 2))
        store.putIfNewer(
            key,
            scalar("v1"),
            1,
            null,
            frame("k"),
            false
        ).assert().isEqualTo(RemoteVersionedWrite.Stale(2))
        store.putIfNewer(
            key,
            scalar("v2'"),
            2,
            null,
            frame("k"),
            false
        ).assert().isEqualTo(RemoteVersionedWrite.Stale(2))
        store.putIfNewer(
            key,
            scalar("v3", 99),
            3,
            null,
            frame("k"),
            false
        ).assert().isEqualTo(RemoteVersionedWrite.Written)
        remoteVersioned("k").assert().isEqualTo(Versioned("v3", 3))

        store.put(key, scalar("plain"), null, frame("k"))
        store.putIfNewer(key, scalar("v0"), 0, null, frame("k"), false).assert().isEqualTo(RemoteVersionedWrite.Written)
        remoteVersioned("k").assert().isEqualTo(Versioned("v0", 0))
    }

    /**
     * 版本按有符号 64 位整数比较（跨越 32 位与 2^53 的边界、负数、极值）。
     */
    @Test
    fun versionsCompareAsSigned64BitIntegers() {
        val store = store()
        val key = storageKey("k")
        val ascending = listOf(
            Long.MIN_VALUE + 1,
            -(1L shl 40),
            -5L,
            0L,
            1L,
            (1L shl 32) - 1,
            1L shl 32,
            1L shl 53,
            (1L shl 53) + 1,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE,
        )
        var previous: Long? = null
        for (version in ascending) {
            store.putIfNewer(key, scalar("v$version"), version, null, frame("k"), false)
                .assert().withFailMessage { "$version must be newer than $previous" }.isEqualTo(
                    RemoteVersionedWrite.Written
                )
            previous?.let {
                store.putIfNewer(
                    key,
                    scalar("old"),
                    it,
                    null,
                    frame("k"),
                    false
                ).assert().isEqualTo(RemoteVersionedWrite.Stale(version))
            }
            store.putIfNewer(
                key,
                scalar("dup"),
                version,
                null,
                frame("k"),
                false
            ).assert().isEqualTo(RemoteVersionedWrite.Stale(version))
            previous = version
        }
        remoteVersioned("k").assert().isEqualTo(Versioned("v${Long.MAX_VALUE}", Long.MAX_VALUE))
    }

    /**
     * 按版本写入销毁 lease（与 put 相同）：持有者的 fill 被拒绝，哪怕它带的版本更高。
     */
    @Test
    fun putIfNewerDestroysTheLeaseSoTheFillIsRefused() {
        val store = store()
        val key = storageKey("k")
        val lease = LeaseRequest(token(), Duration.ofSeconds(30))
        store.read(key, lease, null).assert().isEqualTo(RemoteRead.Miss(true))
        store.putIfNewer(key, scalar("v2"), 2, null, frame("k"), false).assert().isEqualTo(RemoteVersionedWrite.Written)
        store.fill(Fill(key, lease.token, scalar("v3", 3), null)).assert().isFalse()
        store.release(key, lease.token)
        remoteVersioned("k").assert().isEqualTo(Versioned("v2", 2))
    }

    /**
     * 带版本的内容不持有令牌：从不授予刷新令牌（版本与刷新令牌放不下），从不被回收（回收为 lease 会丢掉版本）。
     */
    @Test
    fun versionedContentIsNeverRefreshedOrReclaimed() {
        val store = store()
        val key = storageKey("k")
        store.putIfNewer(key, scalar("v1"), 1, Duration.ofMinutes(1), frame("k"), false)
        val refresh = RefreshRequest(Duration.ofDays(1), token(), Duration.ZERO)
        val read = store.read(key, null, refresh) as RemoteRead.Hit
        read.refreshGranted.assert().isFalse()
        store.reclaim(key, Digest.of(scalar("v1")), LeaseRequest(token(), Duration.ofSeconds(30))).assert().isFalse()
        remoteVersioned("k").assert().isEqualTo(Versioned("v1", 1))
    }

    /**
     * 墓碑（带版本的负缓存）读作负缓存，挡住不更新的版本，不接受任何令牌的填充。
     */
    @Test
    fun tombstoneReadsAsAbsentAndKeepsItsVersion() {
        val store = store()
        val key = storageKey("k")
        store.putIfNewer(key, Envelope.Absent, 3, Duration.ofMinutes(1), frame("k"), false)
            .assert().isEqualTo(RemoteVersionedWrite.Written)
        (remote("k") as RemoteRead.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        store.putIfNewer(
            key,
            scalar("v2"),
            2,
            null,
            frame("k"),
            false
        ).assert().isEqualTo(RemoteVersionedWrite.Stale(3))
        store.putIfNewer(
            key,
            Envelope.Absent,
            3,
            null,
            frame("k"),
            false
        ).assert().isEqualTo(RemoteVersionedWrite.Stale(3))
        store.fill(Fill(key, token(), scalar("v9", 9), null)).assert().isFalse()
        store.putIfNewer(key, scalar("v4"), 4, null, frame("k"), false).assert().isEqualTo(RemoteVersionedWrite.Written)
        remoteVersioned("k").assert().isEqualTo(Versioned("v4", 4))
    }

    /**
     * 带版本的负缓存经填充写入（[Fill.version]）：凭 lease 写入墓碑，与按版本写入的墓碑相同。
     */
    @Test
    fun fillOfAVersionedAbsenceWritesATombstone() {
        val store = store()
        val key = storageKey("k")
        val lease = LeaseRequest(token(), Duration.ofSeconds(30))
        store.read(key, lease, null).assert().isEqualTo(RemoteRead.Miss(true))
        store.fill(Fill(key, lease.token, Envelope.Absent, Duration.ofMinutes(1), 7)).assert().isTrue()
        (remote("k") as RemoteRead.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        store.putIfNewer(
            key,
            scalar("v6"),
            6,
            null,
            frame("k"),
            false
        ).assert().isEqualTo(RemoteVersionedWrite.Stale(7))
        store.putIfNewer(key, scalar("v8"), 8, null, frame("k"), false).assert().isEqualTo(RemoteVersionedWrite.Written)
        remoteVersioned("k").assert().isEqualTo(Versioned("v8", 8))
    }

    // ---------------------------------------------------------------------------------------------
    // 引擎
    // ---------------------------------------------------------------------------------------------

    /**
     * 有序：更高的版本写入，迟到的旧版本被拒绝；另一个节点的 L2 随写入的广播失效。
     */
    @Test
    fun versionedWritesAreOrdered() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        cacheA.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        cacheB["k"].assert().isEqualTo("v1")
        cacheA.putIfNewer("k", "v3", 3).assert().isEqualTo(VersionedPut.Written)
        cacheA.putIfNewer("k", "v2", 2).assert().isEqualTo(VersionedPut.Stale(3))
        cacheA.getVersioned("k").assert().isEqualTo(Versioned("v3", 3))
        awaitCondition("B sees the newest version") { cacheB.getVersioned("k") == Versioned("v3", 3) }
        cacheB["k"].assert().isEqualTo("v3")
        remoteVersioned("k").assert().isEqualTo(Versioned("v3", 3))
        loads.get().assert().isEqualTo(0)
    }

    /**
     * 相等的版本是幂等的空操作，旧版本被拒绝：都不修改 L1、不广播、不改变到期时间，计入 `staleVersions`。
     */
    @Test
    fun refusedVersionsChangeNothingAndDoNotBroadcast() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        cacheA.putIfNewer("k", "v1", 1, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        cacheB["k"].assert().isEqualTo("v1")
        awaitCondition("the write reached B") { nodeB.metrics().invalidationsReceived >= 1 }
        val received = nodeB.metrics().invalidationsReceived
        val ttl = requireNotNull(remoteTtl("k"))

        cacheA.putIfNewer("k", "again", 1, Duration.ofDays(1)).assert().isEqualTo(VersionedPut.Stale(1))
        cacheA.putIfNewer("k", "older", 0).assert().isEqualTo(VersionedPut.Stale(1))
        cacheA.invalidateIfNewer("k", 1).assert().isEqualTo(VersionedPut.Stale(1))
        cacheA.putIfNewer("marker", "m", 1).assert().isEqualTo(VersionedPut.Written)
        awaitCondition("marker broadcast") { nodeB.metrics().invalidationsReceived > received }
        nodeB.metrics().invalidationsReceived.assert().isEqualTo(received + 1)

        remoteVersioned("k").assert().isEqualTo(Versioned("v1", 1))
        requireNotNull(remoteTtl("k")).assert().isLessThanOrEqualTo(ttl)
        cacheB["k"].assert().isEqualTo("v1")
        nodeA.metrics().staleVersions.assert().isEqualTo(3)
        nodeA.metrics().writeFailures.assert().isEqualTo(0)
    }

    /**
     * 寿命同 put：默认按策略，显式时长优先；墓碑默认是值的寿命（不是 `absentTtl`）。
     */
    @Test
    fun versionedWritesSetTheTtlLikePut() {
        val cache = nodeA.cache()
        cache.putIfNewer("k", "v1", 1, Duration.ofMinutes(1))
        requireNotNull(remoteTtl("k")).assert().isBetween(Duration.ofSeconds(50), Duration.ofMinutes(1))
        cache.putIfNewer("k", "v2", 2)
        requireNotNull(remoteTtl("k")).assert().isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10))
        cache.invalidateIfNewer("k", 3)
        requireNotNull(remoteTtl("k")).assert().isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10))
        cache.invalidateIfNewer("k", 4, Duration.ofMinutes(2))
        requireNotNull(remoteTtl("k")).assert().isBetween(Duration.ofSeconds(110), Duration.ofMinutes(2))
    }

    /**
     * 墓碑：读取得到不存在（不回源），迟到的旧版本与重复的删除被拒绝，更新的版本可以重新写入。到期之后不再挡住旧版本。
     */
    @Test
    fun tombstoneBlocksOlderRedelivery() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = Versioned("v1", 1)
        cacheB["k"].assert().isEqualTo("v1")
        loads.set(0)

        cacheA.invalidateIfNewer("k", 2).assert().isEqualTo(VersionedPut.Written)
        awaitCondition("B sees the deletion") { cacheB["k"] == null }
        cacheA["k"].assert().isNull()
        cacheA.getVersioned("k").assert().isNull()
        loads.get().assert().isEqualTo(0)

        cacheA.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Stale(2))
        cacheB.putIfNewer("k", "v2", 2).assert().isEqualTo(VersionedPut.Stale(2))
        cacheB.invalidateIfNewer("k", 2).assert().isEqualTo(VersionedPut.Stale(2))
        cacheA["k"].assert().isNull()

        cacheA.putIfNewer("k", "v3", 3).assert().isEqualTo(VersionedPut.Written)
        awaitCondition("B sees the newer version") { cacheB["k"] == "v3" }

        cacheA.invalidateIfNewer("k", 4, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        elapse(Duration.ofMinutes(2))
        cacheA.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
    }

    /**
     * 版本顺序只在条目存活期间成立：带版本的值或墓碑到期之后，key 中没有版本，任何版本（包括更旧的）都能写入。
     * 墓碑存活期间读取（含 `getVersioned`）得到不存在，更旧的版本被拒绝。
     */
    @Test
    fun expiredEntriesAcceptAnyVersion() {
        val cache = nodeA.cache()
        cache.putIfNewer("value", "v5", 5, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        cache.putIfNewer("value", "v4", 4).assert().isEqualTo(VersionedPut.Stale(5))
        cache.invalidateIfNewer("tombstone", 5, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        cache.getVersioned("tombstone").assert().isNull()
        cache.putIfNewer("tombstone", "t4", 4).assert().isEqualTo(VersionedPut.Stale(5))

        elapse(Duration.ofMinutes(2))
        remote("value").assert().isEqualTo(RemoteRead.Miss(false))
        remote("tombstone").assert().isEqualTo(RemoteRead.Miss(false))
        cache.putIfNewer("value", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        cache.putIfNewer("tombstone", "t1", 1).assert().isEqualTo(VersionedPut.Written)
        cache.getVersioned("value").assert().isEqualTo(Versioned("v1", 1))
        cache.getVersioned("tombstone").assert().isEqualTo(Versioned("t1", 1))
    }

    /**
     * 带版本的数据源返回“在版本 N 时不存在”：回源写入墓碑（值的寿命，不是 `absentTtl`），读取得到不存在且不再回源，
     * N 及更旧的版本被拒绝，N+1 写入。批量回源同样如此。数据源中从来没有的 key 是没有版本的负缓存（见 [plainWritesAndAbsentLoadsCarryNoVersion]）。
     */
    @Test
    fun versionedAbsenceFromTheLoaderIsATombstone() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = Versioned(null, 5)
        cacheA["k"].assert().isNull()
        cacheA.getVersioned("k").assert().isNull()
        cacheA.lookup("k").assert().isInstanceOf(Lookup.Absent::class.java)
        cacheB["k"].assert().isNull()
        loads.get().assert().isEqualTo(1)
        (remote("k") as RemoteRead.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        requireNotNull(remoteTtl("k")).assert().isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10))

        cacheB.putIfNewer("k", "v4", 4).assert().isEqualTo(VersionedPut.Stale(5))
        cacheB.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Stale(5))
        cacheB.putIfNewer("k", "v6", 6).assert().isEqualTo(VersionedPut.Written)
        awaitCondition("A sees the newer version") { cacheA.getVersioned("k") == Versioned("v6", 6) }

        database["deleted"] = Versioned(null, 3)
        database["alive"] = Versioned("a", 3)
        cacheA.getAll(listOf("deleted", "alive")).assert().isEqualTo(mapOf("alive" to "a"))
        cacheB.putIfNewer("deleted", "old", 2).assert().isEqualTo(VersionedPut.Stale(3))
        cacheB.putIfNewer("deleted", "new", 4).assert().isEqualTo(VersionedPut.Written)
    }

    /**
     * lease 持有者回源期间落地的按版本写入胜出：它销毁 lease，回源的写回被拒绝（即使回源读到的版本更高），L1 保留写入的值。
     */
    @Test
    fun versionedWriteWinsOverAFillHoldingTheLease() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = Versioned("v3", 3)
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        afterLoad = {
            loaded.countDown()
            release.awaitOrFail("release")
        }
        val onA = fork { cacheA["k"] }
        loaded.awaitOrFail("A holds the lease and read the source")
        afterLoad = null
        remote("k").assert().isInstanceOf(RemoteRead.Leased::class.java)
        cacheB.putIfNewer("k", "v2", 2).assert().isEqualTo(VersionedPut.Written)
        release.countDown()
        onA.join().assert().isEqualTo("v3")

        nodeA.metrics().fillRejected.assert().isEqualTo(1)
        remoteVersioned("k").assert().isEqualTo(Versioned("v2", 2))
        awaitCondition("A sees the write") { cacheA.getVersioned("k") == Versioned("v2", 2) }
        cacheB.putIfNewer("k", "v3", 3).assert().isEqualTo(VersionedPut.Written)
    }

    /**
     * 回源保存数据源给出的版本：随后迟到的旧版本与重复投递被拒绝，L1 命中的节点读到同一个版本。
     */
    @Test
    fun fillStoresTheLoadersVersionForLaterWrites() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = Versioned("v5", 5)
        cacheA.getVersioned("k").assert().isEqualTo(Versioned("v5", 5))
        remoteVersioned("k").assert().isEqualTo(Versioned("v5", 5))
        cacheB.getVersioned("k").assert().isEqualTo(Versioned("v5", 5))
        loads.get().assert().isEqualTo(1)

        cacheB.putIfNewer("k", "v4", 4).assert().isEqualTo(VersionedPut.Stale(5))
        cacheB.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Stale(5))
        cacheB.putIfNewer("k", "v6", 6).assert().isEqualTo(VersionedPut.Written)
        awaitCondition("A sees the newer write") { cacheA.getVersioned("k") == Versioned("v6", 6) }

        database["batch1"] = Versioned("b1", 11)
        database["batch2"] = Versioned("b2", 12)
        cacheA.getAll(listOf("batch1", "batch2")).assert().isEqualTo(mapOf("batch1" to "b1", "batch2" to "b2"))
        remoteVersioned("batch1").assert().isEqualTo(Versioned("b1", 11))
        cacheB.putIfNewer("batch2", "old", 12).assert().isEqualTo(VersionedPut.Stale(12))
    }

    /**
     * 回源写回 L1 之后、写入 L2 之前落地的更新版本：回源者的 L2 不会留下旧版本（失效戳），L1 是更新的版本。
     */
    @Test
    fun newerWriteRightAfterAFillReplacesIt() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = Versioned("v1", 1)
        val filled = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 只有这一次回源会写回 L1；异步视图上 fill 可能由异步执行器发起，因此不按线程名过滤
        backend.afterFill = { key, ok ->
            if (ok && key == storageKey("k") && filled.count > 0) {
                filled.countDown()
                release.awaitOrFail("release filler")
            }
        }
        val filler = fork("filler") { cacheA["k"] }
        filled.awaitOrFail("A filled L1")
        remoteVersioned("k").assert().isEqualTo(Versioned("v1", 1))
        cacheB.putIfNewer("k", "v2", 2).assert().isEqualTo(VersionedPut.Written)
        release.countDown()
        filler.join().assert().isEqualTo("v1")
        backend.afterFill = null

        remoteVersioned("k").assert().isEqualTo(Versioned("v2", 2))
        awaitCondition("A sees the newer write") { cacheA.getVersioned("k") == Versioned("v2", 2) }
        cacheA.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Stale(2))
    }

    /**
     * 多个线程（分布在两个节点上）乱序写入同一个 key 的 1..N 版本：每次写入要么写入、要么报告不更旧的当前版本，最终收敛到 N。
     * 第一轮强制竞争：全部线程就位后同时写入。
     */
    @Test
    fun concurrentVersionedWritersConvergeOnTheHighestVersion() {
        val caches = listOf(nodeA.cache(), nodeB.cache())
        val threads = 8
        val perThread = 25
        val total = threads * perThread
        val start = CyclicBarrier(threads)
        val written = AtomicInteger()
        val workers = (0 until threads).map { index ->
            val cache = caches[index % caches.size]
            val versions = (0 until perThread).map { it * threads + index + 1L }.shuffled()
            fork("writer-$index") {
                start.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                versions.forEach { version ->
                    when (val result = cache.putIfNewer("k", "v$version", version)) {
                        VersionedPut.Written -> written.incrementAndGet()
                        is VersionedPut.Stale -> result.current.assert().isGreaterThanOrEqualTo(version)
                    }
                }
            }
        }
        workers.forEach { it.join() }
        written.get().assert().isGreaterThanOrEqualTo(1)
        remoteVersioned("k").assert().isEqualTo(Versioned("v$total", total.toLong()))
        caches.forEach { cache ->
            awaitCondition(
                "${cache.name} converges"
            ) { cache.getVersioned("k") == Versioned("v$total", total.toLong()) }
        }
    }

    /**
     * 结果未知（已在 L1 生效却报错）的按版本写入可以安全重放：重放返回 [VersionedPut.Stale]，版本不变。
     */
    @Test
    fun versionedWriteWithUnknownOutcomeIsSafeToReplay() {
        val cache = nodeA.cache()
        backend.afterVersionedWrite = { _, _ ->
            backend.afterVersionedWrite = null
            throw RemoteUnavailableException("ack lost")
        }
        val error = runCatching { cache.putIfNewer("k", "v1", 1) }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        (error as CacheWriteException).operation.assert().isEqualTo("putIfNewer")
        cache.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Stale(1))
        cache.getVersioned("k").assert().isEqualTo(Versioned("v1", 1))
        nodeA.metrics().writeFailures.assert().isEqualTo(1)
    }

    /**
     * 普通写入（put、invalidate）写入没有版本的内容：之后任何版本都能写入。数据源返回 `null` 时是没有版本的负缓存。
     */
    @Test
    fun plainWritesAndAbsentLoadsCarryNoVersion() {
        val cache = nodeA.cache()
        cache.putIfNewer("k", "v5", 5)
        cache.put("k", "plain")
        cache.getVersioned("k").assert().isEqualTo(Versioned("plain", Versioned.NO_VERSION))
        cache.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        cache.invalidate("k")
        cache.putIfNewer("k", "v0", 0).assert().isEqualTo(VersionedPut.Written)

        cache["missing"].assert().isNull()
        (remote("missing") as RemoteRead.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        cache.putIfNewer("missing", "m1", 1).assert().isEqualTo(VersionedPut.Written)
        cache.getVersioned("missing").assert().isEqualTo(Versioned("m1", 1))
    }

    /**
     * 读不懂的内容（同一个 key 上的集合）拒绝按版本写入：不修改 L1，以 [CacheWriteException] 失败（cause 为 [ForeignFormatException]）。
     */
    @Test
    fun unreadableContentRefusesVersionedWrites() {
        val cache = nodeA.cache()
        store().addMembers(storageKey("set"), listOf("a".toByteArray()), null, frame("set"))
        val error = runCatching { cache.putIfNewer("set", "v", 1) }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        error?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        (remote("set") as RemoteRead.Hit).envelope.assert().isInstanceOf(Envelope.Members::class.java)
        nodeA.metrics().unknownFormats.assert().isEqualTo(1)
    }

    /**
     * [Versioned.NO_VERSION] 不能作为版本；没有声明为带版本的缓存不支持按版本的操作。
     */
    @Test
    fun invalidVersionsAndUnversionedCachesAreRejected() {
        val cache = nodeA.cache()
        runCatching { cache.putIfNewer("k", "v", Versioned.NO_VERSION) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { cache.invalidateIfNewer("k", Versioned.NO_VERSION) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))

        val plain = nodeB.create(
            cacheSpec<String, String>("plain-$cacheName") {
                codec = TestCodec
                policy = policy()
            }
        ).let { access.cache(it) }
        runCatching { plain.putIfNewer("k", "v", 1) }.exceptionOrNull()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
        runCatching { plain.getVersioned("k") }.exceptionOrNull()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
    }

    /**
     * 读不懂的带版本值（同版本但解码失败）不被回收：有数据源的读取绕过 L1 回源，L1 保留版本，迟到的旧版本仍被拒绝。
     */
    @Test
    fun corruptVersionedPayloadIsBypassedNotReclaimed() {
        val cache = nodeA.cache()
        database["k"] = Versioned("source", 7)
        store().putIfNewer(storageKey("k"), Envelope.Scalar(TestCodec.CORRUPT), 5, null, frame("k"), false)
        cache["k"].assert().isEqualTo("source")
        ((remote("k") as RemoteRead.Hit).envelope as Envelope.Scalar).version.assert().isEqualTo(5L)
        cache.putIfNewer("k", "v4", 4).assert().isEqualTo(VersionedPut.Stale(5))
        cache.putIfNewer("k", "v6", 6).assert().isEqualTo(VersionedPut.Written)
        awaitCondition("A reads the newer write") { cache.getVersioned("k") == Versioned("v6", 6) }
    }

    private companion object {
        const val BARRIER_TIMEOUT_SECONDS = 30L
    }
}

/**
 * [VersionedSpec] 的每个用例改由异步视图读写（同步数据源换成在调用线程上执行的异步数据源）。
 */
abstract class AsyncVersionedSpec : VersionedSpec() {
    override val access: CacheAccess = CacheAccess.ASYNC
}
