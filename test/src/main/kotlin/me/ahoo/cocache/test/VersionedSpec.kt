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

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.CoherenceBackend
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
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.api.spi.VersionedL1Store
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 按版本有序的写入的 TCK（`docs/architecture.md` §6.6）：两个节点（运行时）共享同一个后端。
 * 只适用于存储提供按版本写入能力面（`L1Store.versioned()`）的后端；不支持版本的后端不必继承它（声明带版本的缓存会在创建时失败）。
 * 继承了而没有声明 [L1Capability.VERSIONED] 时，除能力声明的检查之外的用例都跳过。
 *
 * - 协议（直接调用 [L1Store] 与它的能力面）：版本随 fill / put / putAll 保存、读取原样返回（声明了条件读写时，putIfMatch 写入没有版本的值，
 *   条件读写拒绝带版本的内容）；按版本写入销毁 lease、填充因此被拒绝；带版本的内容授予刷新令牌、不被回收；填充的版本规则；
 *   保留版本的失效（状态 `E`）的每个脚本；墓碑读作负缓存；版本按有符号 64 位整数比较（RFC 0005 §4.7）。
 * - 引擎（经 [access]，同步或异步视图）：`VersionedCache`（带版本的数据源）有序、相等版本幂等、旧版本拒绝且不广播不改寿命、
 *   墓碑挡住迟到的旧版本、lease 持有者回源期间落地的按版本写入胜出、回源保存版本且与随后的写入比较、并发写入收敛到最高版本、
 *   结果未知时重放安全；提前刷新、版本前进的填充按写入处理、版本拒绝的刷新保留副本、`evict` 与运维的按 key 删除保留版本、
 *   不带版本的 `null` 落在带版本的内容上被拒绝（K16）。`VersionedStore`（没有数据源）：写入标识、读取不申请 lease、墓碑寿命、`purge`。
 *
 * 竞态用 latch 编排；跨节点效果用带超时的轮询断言。
 */
@Suppress("LargeClass", "TooManyFunctions")
abstract class VersionedSpec {
    protected abstract fun createBackend(): CoherenceBackend

    /**
     * 后端的存储提供的可选能力（RFC 0005 §4.3），没有默认值：规范断言存储提供的能力与它完全相同（多出来的也算失败）、
     * 每个访问器稳定。用例都要求 [L1Capability.VERSIONED]，个别用例另要求 [L1Capability.SETS]。
     */
    protected abstract val expectedCapabilities: Set<L1Capability>

    /**
     * 引擎与后端共用的时钟。
     */
    protected abstract val clock: CacheClock

    /**
     * 让时间前进 [duration]（同时作用于后端与引擎）。
     */
    protected abstract fun elapse(duration: Duration)

    /**
     * 访问缓存的方式：同步 API（默认），或某个变体（如 [CacheAccess.ASYNC]）。后端实现者只需要默认的 [CacheAccess.SYNC]：
     * 变体（同步、异步、挂起）是核心的语义，由核心自己的测试覆盖（RFC 0005 §4.10）；仓库内覆盖它，在不同变体下运行本规范。
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

    /**
     * 用例需要的能力：[expectedCapabilities] 缺少其中任何一个时跳过这个用例。
     */
    protected fun assumeDeclared(vararg capabilities: L1Capability) =
        CapabilityDeclaration.assume(expectedCapabilities, capabilities)

    /**
     * 本用例的 `VersionedCache` 的声明。没有声明 [L1Capability.VERSIONED] 时跳过这个用例（引擎用例都先经它创建缓存）。
     */
    protected fun spec(
        policy: CachePolicy = policy(),
        block: VersionedCacheSpec.Builder<String, String>.() -> Unit = {}
    ): VersionedCacheSpec<String, String> {
        assumeDeclared(L1Capability.VERSIONED)
        return versionedCacheSpec(cacheName) {
            codec = TestCodec
            versionedLoader = this@VersionedSpec.loader
            this.policy = policy
            block()
            access.source(this)
        }
    }

    protected fun CacheRuntime.cache(
        spec: VersionedCacheSpec<String, String> = spec()
    ): VersionedCache<String, String> = access.versionedCache(create(spec))

    /**
     * 与 [spec] 同名、同 keyspace 的 `VersionedStore`（没有数据源）。没有声明 [L1Capability.VERSIONED] 时跳过这个用例。
     */
    protected fun storeSpec(
        policy: CachePolicy = policy(),
        block: VersionedStoreSpec.Builder<String, String>.() -> Unit = {}
    ): VersionedStoreSpec<String, String> {
        assumeDeclared(L1Capability.VERSIONED)
        return versionedStoreSpec(cacheName) {
            codec = TestCodec
            this.policy = policy
            block()
        }
    }

    protected fun CacheRuntime.versionedStore(
        spec: VersionedStoreSpec<String, String> = storeSpec()
    ): VersionedStore<String, String> = access.versionedStore(create(spec))

    protected fun CacheRuntime.metrics(): CacheMetrics = requireNotNull(metrics(cacheName))

    protected fun storageKey(key: String) = "cocache:$cacheName:$key"

    /**
     * 被测后端的存储（不经钩子）。没有声明 [L1Capability.VERSIONED] 时跳过这个用例（协议用例都先经它取得存储）。
     */
    protected fun store(): L1Store {
        assumeDeclared(L1Capability.VERSIONED)
        return backend.delegate.store(Keyspace("cocache:", cacheName))
    }

    /**
     * 存储提供的能力与 [expectedCapabilities] 完全相同（多出来的也算失败），每个访问器每次返回同一个对象或总是 `null`。
     */
    @Test
    fun storeProvidesExactlyTheDeclaredCapabilities() {
        CapabilityDeclaration.verify(backend.delegate, expectedCapabilities)
    }

    /**
     * 不经过引擎直接读 L1。
     */
    protected fun remote(key: String): L1Read = Suspending.await { store().read(storageKey(key), null, null, null) }

    /**
     * L1 中的值与版本；没有值（不存在、lease、负缓存或墓碑）时为 `null`。
     */
    protected fun remoteVersioned(key: String): Versioned<String>? {
        val scalar = (remote(key) as? L1Read.Hit)?.envelope as? Envelope.Scalar ?: return null
        return Versioned(TestCodec.decode(scalar.bytes), scalar.version)
    }

    protected fun remoteTtl(key: String): Duration? = (remote(key) as L1Read.Hit).ttl

    private fun frame(key: String): ByteArray = InvalidationFrame.encode(NodeId.random(), listOf(storageKey(key)))

    private fun token(): Token = Token(NodeId.random(), System.nanoTime())

    private fun lease(): LeaseRequest = LeaseRequest(token(), Duration.ofSeconds(30))

    /**
     * 窗口覆盖任何有限寿命的刷新请求。
     */
    private fun refresh(): RefreshRequest = RefreshRequest(Duration.ofDays(1), token(), Duration.ofSeconds(30))

    private fun scalar(value: String, version: Long = Versioned.NO_VERSION) = Envelope.Scalar(
        TestCodec.encode(value),
        version
    )

    /**
     * 像没有数据源的缓存那样写入的值：带载荷的摘要（RFC 0005 §4.7）。
     */
    private fun digested(value: String, version: Long = Versioned.NO_VERSION) =
        Envelope.Scalar(TestCodec.encode(value), version, null, Digest.ofPayload(TestCodec.encode(value)))

    /**
     * 在 [key] 上取得一个刷新令牌（key 中是有限寿命的带版本值）。
     */
    private suspend fun grantedRefresh(key: String): RefreshRequest {
        val refresh = refresh()
        val read = store().read(key, null, refresh, null) as L1Read.Hit
        read.refreshGranted.assert().withFailMessage { "a refresh token on [$key]" }.isTrue()
        return refresh
    }

    /**
     * 把收到的非空帧（key 与来源）记录到 [frames] 的订阅者。
     */
    private fun recording(frames: MutableList<Pair<List<String>, NodeId>>) = object : InvalidationListener {
        override fun onInvalidated(keys: List<String>, origin: NodeId) {
            if (keys.isNotEmpty()) frames.add(keys to origin)
        }

        override fun onReset() = Unit
    }

    // ---------------------------------------------------------------------------------------------
    // 协议（L1Store 与 VersionedL1Store）
    // ---------------------------------------------------------------------------------------------

    /**
     * 版本随整体写入（fill、put、putAll）保存，读取原样返回；没有版本的写入读出 [Versioned.NO_VERSION]。
     */
    @Test
    fun wholeWritesStoreTheEnvelopeVersion() = l1 {
        val store = store()
        val lease = LeaseRequest(token(), Duration.ofSeconds(30))
        store.read(storageKey("fill"), lease, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fill(
            Fill(storageKey("fill"), lease.token, scalar("v7", 7), Duration.ofMinutes(1))
        ).assert().isInstanceOf(L1Fill.Filled::class.java)
        ((remote("fill") as L1Read.Hit).envelope as Envelope.Scalar).version.assert().isEqualTo(7L)

        store.put(storageKey("put"), scalar("v9", 9), null, frame("put"))
        remoteVersioned("put").assert().isEqualTo(Versioned("v9", 9))
        store.putAll(listOf(Put(storageKey("all"), scalar("v3", 3), null)), frame("all"))
        remoteVersioned("all").assert().isEqualTo(Versioned("v3", 3))
        store.put(storageKey("plain"), scalar("p"), null, frame("plain"))
        remoteVersioned("plain").assert().isEqualTo(Versioned("p", Versioned.NO_VERSION))
        if (L1Capability.CONDITIONAL in expectedCapabilities) {
            val conditional = requireNotNull(store.conditional())
            // 带版本的内容不接受条件读写（RFC 0005 §4.7）：读不懂，拒绝，不修改
            conditional.readIfChanged(storageKey("put"), null, null)
                .assert().isEqualTo(L1ConditionalRead.Unreadable(backend.format))
            conditional.putIfMatch(storageKey("put"), Digest.of(scalar("v9")), digested("m", 5), null, frame("put"))
                .assert().isEqualTo(L1ConditionalWrite.Unreadable(backend.format))
            remoteVersioned("put").assert().isEqualTo(Versioned("v9", 9))
            // 条件写入写入没有版本的值（信封自带的版本被忽略）
            conditional.putIfMatch(storageKey("absent"), null, digested("m", 5), null, frame("absent"))
                .assert().isEqualTo(L1ConditionalWrite.Written)
            remoteVersioned("absent").assert().isEqualTo(Versioned("m", Versioned.NO_VERSION))
        }
    }

    /**
     * 按版本写入只在版本更高时写入：没有版本的内容与不存在总是被覆盖；保存的是参数中的版本，不是信封自带的版本。
     */
    @Test
    fun putIfNewerComparesAgainstTheStoredVersion() = l1 {
        val store = store()
        val key = storageKey("k")
        versioned().putIfNewer(key, scalar("v2"), 2, null, frame("k")).assert().isEqualTo(L1VersionedWrite.Written)
        remoteVersioned("k").assert().isEqualTo(Versioned("v2", 2))
        versioned().putIfNewer(
            key,
            scalar("v1"),
            1,
            null,
            frame("k")
        ).assert().isEqualTo(L1VersionedWrite.Stale(2))
        versioned().putIfNewer(
            key,
            scalar("v2'"),
            2,
            null,
            frame("k")
        ).assert().isEqualTo(L1VersionedWrite.Stale(2))
        versioned().putIfNewer(
            key,
            scalar("v3", 99),
            3,
            null,
            frame("k")
        ).assert().isEqualTo(L1VersionedWrite.Written)
        remoteVersioned("k").assert().isEqualTo(Versioned("v3", 3))

        store.put(key, scalar("plain"), null, frame("k"))
        versioned().putIfNewer(key, scalar("v0"), 0, null, frame("k")).assert().isEqualTo(L1VersionedWrite.Written)
        remoteVersioned("k").assert().isEqualTo(Versioned("v0", 0))
    }

    /**
     * 版本按有符号 64 位整数比较（跨越 32 位与 2^53 的边界、负数、极值）。
     */
    @Test
    fun versionsCompareAsSigned64BitIntegers() = l1 {
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
            versioned().putIfNewer(key, scalar("v$version"), version, null, frame("k"))
                .assert().withFailMessage { "$version must be newer than $previous" }.isEqualTo(
                    L1VersionedWrite.Written
                )
            previous?.let {
                versioned().putIfNewer(
                    key,
                    scalar("old"),
                    it,
                    null,
                    frame("k")
                ).assert().isEqualTo(L1VersionedWrite.Stale(version))
            }
            versioned().putIfNewer(
                key,
                scalar("dup"),
                version,
                null,
                frame("k")
            ).assert().isEqualTo(L1VersionedWrite.Stale(version))
            previous = version
        }
        remoteVersioned("k").assert().isEqualTo(Versioned("v${Long.MAX_VALUE}", Long.MAX_VALUE))
    }

    /**
     * 按版本写入销毁 lease（与 put 相同）：持有者的 fill 被拒绝，哪怕它带的版本更高。
     */
    @Test
    fun putIfNewerDestroysTheLeaseSoTheFillIsRefused() = l1 {
        val store = store()
        val key = storageKey("k")
        val lease = LeaseRequest(token(), Duration.ofSeconds(30))
        store.read(key, lease, null, null).assert().isEqualTo(L1Read.Miss(true))
        versioned().putIfNewer(key, scalar("v2"), 2, null, frame("k")).assert().isEqualTo(L1VersionedWrite.Written)
        store.fill(Fill(key, lease.token, scalar("v3", 3), null)).assert().isInstanceOf(L1Fill.Rejected::class.java)
        store.release(key, lease.token)
        remoteVersioned("k").assert().isEqualTo(Versioned("v2", 2))
    }

    /**
     * 带版本的内容照常取得刷新令牌（版本在版本段中，令牌位置是空的，RFC 0005 §4.7），从不被回收（回收为 lease 会丢掉版本）。
     * 墓碑不是值，不取得刷新令牌。
     */
    @Test
    fun versionedContentIsRefreshedButNeverReclaimed() = l1 {
        val store = store()
        val key = storageKey("k")
        versioned().putIfNewer(key, scalar("v1"), 1, Duration.ofMinutes(1), frame("k"))
        val read = store.read(key, null, refresh(), null) as L1Read.Hit
        read.refreshGranted.assert().isTrue()
        (read.envelope as Envelope.Scalar).version.assert().isEqualTo(1L)
        store.reclaim(
            key,
            ContentFingerprint.of(read.envelope, read.format),
            LeaseRequest(token(), Duration.ofSeconds(30))
        ).assert().isFalse()
        remoteVersioned("k").assert().isEqualTo(Versioned("v1", 1))

        versioned().putIfNewer(storageKey("t"), Envelope.Absent, 3, Duration.ofMinutes(1), frame("t"))
        (store.read(storageKey("t"), null, refresh(), null) as L1Read.Hit).refreshGranted.assert().isFalse()
    }

    /**
     * 填充的版本规则（RFC 0005 §4.7）：key 中是持有刷新令牌的带版本值时，
     * - 更小的版本或没有版本：拒绝，返回当前版本，并在同一次原子操作中清零令牌（同一个令牌再填充只是令牌不匹配，新的刷新令牌立即可以取得）；
     * - 相等：写入（续期），不广播，`advanced` 为 `false`；
     * - 更大：写入并在同一次原子操作中广播（原子广播的后端；帧的来源是令牌的节点，只含这个 key），`advanced` 为 `true`。
     */
    @Test
    fun fillFollowsTheVersionRules() = l1 {
        val store = store()
        val key = storageKey("k")
        val frames = CopyOnWriteArrayList<Pair<List<String>, NodeId>>()
        val subscription = backend.delegate.channel.subscribe(Keyspace("cocache:", cacheName), recording(frames))
        versioned().putIfNewer(key, scalar("v5"), 5, Duration.ofMinutes(1), frame("k"))

        val smaller = grantedRefresh(key)
        store.fill(
            Fill(key, smaller.token, scalar("v4", 4), Duration.ofMinutes(1))
        ).assert().isEqualTo(L1Fill.Rejected(5))
        store.fill(
            Fill(key, smaller.token, scalar("v6", 6), Duration.ofMinutes(1))
        ).assert().isEqualTo(L1Fill.Rejected(null))
        val unversioned = grantedRefresh(key)
        store.fill(
            Fill(key, unversioned.token, scalar("plain"), Duration.ofMinutes(1))
        ).assert().isEqualTo(L1Fill.Rejected(5))
        remoteVersioned("k").assert().isEqualTo(Versioned("v5", 5))

        val equal = grantedRefresh(key)
        store.fill(
            Fill(key, equal.token, scalar("v5'", 5), Duration.ofMinutes(2))
        ).assert().isEqualTo(L1Fill.Filled(false))
        remoteVersioned("k").assert().isEqualTo(Versioned("v5'", 5))
        requireNotNull(remoteTtl("k")).assert().isGreaterThan(Duration.ofMinutes(1))

        val greater = grantedRefresh(key)
        store.fill(
            Fill(key, greater.token, scalar("v6", 6), Duration.ofMinutes(1))
        ).assert().isEqualTo(L1Fill.Filled(true))
        remoteVersioned("k").assert().isEqualTo(Versioned("v6", 6))
        subscription.use {
            if (backend.delegate.atomicBroadcast) {
                awaitCondition("the advancing fill broadcast") { frames.any { it.second == greater.token.node } }
                // 订阅者收到编码 key（S4）
                frames.filter { it.second == greater.token.node }.assert().containsExactly(
                    listOf("k") to greater.token.node
                )
                frames.none { it.second == equal.token.node }.assert().isTrue()
            }
        }
    }

    /**
     * 保留版本的失效（RFC 0005 §4.7 的 `E` 表）：带版本的值转为 `E(v)`，
     * - `READ`：不申请 lease 时是未命中（不是负缓存，也不是 lease）；申请时原地取得 lease，其它读取者看到 `Leased`；
     * - `RELEASE` 只清零令牌，保留 `E`；
     * - `FILL` 的版本更小或没有版本：拒绝并清零令牌，返回 v；版本不小于 v：写入并广播（`advanced`）；
     * - `PUT_IF_NEWER` 与墓碑按 v 比较；`RECLAIM` 拒绝；声明了时，集合脚本与条件脚本把它当作读不懂的内容。
     */
    @Test
    fun evictKeepingVersionLeavesEAndEveryScriptHonoursIt() = l1 {
        val store = store()
        val key = storageKey("k")
        versioned().putIfNewer(key, scalar("v5"), 5, null, frame("k"))
        versioned().evictKeepingVersion(key, Duration.ofMinutes(1), frame("k")).assert().isEqualTo(L1Eviction.Evicted)

        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
        store.read(key, null, refresh(), null).assert().isEqualTo(L1Read.Miss(false))
        store.readAll(listOf(key), emptyMap(), null).getValue(key).assert().isEqualTo(L1Read.Miss(false))
        versioned().putIfNewer(key, scalar("v4"), 4, null, frame("k")).assert().isEqualTo(L1VersionedWrite.Stale(5))
        versioned().putIfNewer(key, Envelope.Absent, 5, null, frame("k")).assert().isEqualTo(L1VersionedWrite.Stale(5))
        store.reclaim(key, ContentFingerprint.of(Envelope.Absent, backend.format), lease()).assert().isFalse()
        if (L1Capability.SETS in expectedCapabilities) {
            requireNotNull(store.sets()).addMembers(key, listOf("a".toByteArray()), null, frame("k"))
                .assert().isEqualTo(backend.format)
        }
        if (L1Capability.CONDITIONAL in expectedCapabilities) {
            val conditional = requireNotNull(store.conditional())
            conditional.readIfChanged(key, null, null).assert().isEqualTo(L1ConditionalRead.Unreadable(backend.format))
            conditional.putIfMatch(key, null, digested("x"), null, frame("k"))
                .assert().isEqualTo(L1ConditionalWrite.Unreadable(backend.format))
        }

        val first = lease()
        store.read(key, first, null, null).assert().isEqualTo(L1Read.Miss(true))
        val leased = store.read(key, lease(), null, null)
        leased.assert().isInstanceOf(L1Read.Leased::class.java)
        (leased as L1Read.Leased).remaining.assert().isLessThanOrEqualTo(first.ttl)
        store.release(key, first.token)
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
        versioned().putIfNewer(key, scalar("v5"), 5, null, frame("k")).assert().isEqualTo(L1VersionedWrite.Stale(5))

        val older = lease()
        store.read(key, older, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fill(Fill(key, older.token, scalar("v4", 4), null)).assert().isEqualTo(L1Fill.Rejected(5))
        store.fill(Fill(key, older.token, scalar("v6", 6), null)).assert().isEqualTo(L1Fill.Rejected(null))
        val unversioned = lease()
        store.read(key, unversioned, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fill(Fill(key, unversioned.token, scalar("plain"), null)).assert().isEqualTo(L1Fill.Rejected(5))

        val equal = lease()
        store.read(key, equal, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fill(
            Fill(key, equal.token, scalar("v5'", 5), Duration.ofMinutes(1))
        ).assert().isEqualTo(L1Fill.Filled(true))
        remoteVersioned("k").assert().isEqualTo(Versioned("v5'", 5))
    }

    /**
     * `E` 保留它自己的到期时间：在其上取得 lease、释放与被拒绝的填充只触及令牌（RFC 0005 §4.7，K15），不把 lease 的寿命带到 `E` 上；
     * `E` 到期之后 key 中没有版本，任何版本都能写入。lease 的抢占见 `BackendProtocolSpec`。
     */
    @Test
    fun eKeepsItsOwnExpiry() = l1 {
        val store = store()
        val key = storageKey("k")
        versioned().putIfNewer(key, scalar("v5"), 5, null, frame("k"))
        versioned().evictKeepingVersion(key, Duration.ofMinutes(1), frame("k")).assert().isEqualTo(L1Eviction.Evicted)
        val rejected = LeaseRequest(token(), Duration.ofMinutes(10))
        store.read(key, rejected, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fill(Fill(key, rejected.token, scalar("v4", 4), null)).assert().isEqualTo(L1Fill.Rejected(5))
        val released = LeaseRequest(token(), Duration.ofMinutes(10))
        store.read(key, released, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.release(key, released.token)

        elapse(Duration.ofMinutes(2))
        store.read(key, null, null, null).assert().isEqualTo(L1Read.Miss(false))
        versioned().putIfNewer(key, scalar("v1"), 1, null, frame("k")).assert().isEqualTo(L1VersionedWrite.Written)
    }

    /**
     * 保留版本的失效只对带版本的内容保留版本（RFC 0005 §4.7）：没有版本的值与负缓存、lease 占位、不存在都删除；墓碑与 `E` 转为
     * （新的）`E`，销毁其上的 lease；带摘要段的值（权威标量，D4）返回 [L1Eviction.Authoritative]，声明了时集合返回
     * [L1Eviction.Unreadable]，两者都不修改 key。
     */
    @Test
    fun evictKeepingVersionDeletesUnversionedContentAndRefusesTheRest() = l1 {
        val store = store()
        store.put(storageKey("plain"), scalar("p"), null, frame("plain"))
        val lease = lease()
        store.read(storageKey("leased"), lease, null, null).assert().isEqualTo(L1Read.Miss(true))
        versioned().putIfNewer(storageKey("tombstone"), Envelope.Absent, 3, null, frame("tombstone"))
        listOf("plain", "leased", "absent", "tombstone").forEach {
            versioned().evictKeepingVersion(storageKey(it), null, frame(it)).assert().isEqualTo(L1Eviction.Evicted)
            store.read(storageKey(it), null, null, null).assert().isEqualTo(L1Read.Miss(false))
        }
        store.fill(Fill(storageKey("leased"), lease.token, scalar("v"), null)).assert().isEqualTo(L1Fill.Rejected(null))
        versioned().putIfNewer(
            storageKey("plain"),
            scalar("p1"),
            1,
            null,
            frame("plain")
        ).assert().isEqualTo(L1VersionedWrite.Written)
        versioned().putIfNewer(storageKey("tombstone"), scalar("t3"), 3, null, frame("tombstone"))
            .assert().isEqualTo(L1VersionedWrite.Stale(3))

        val onE = lease()
        store.read(storageKey("tombstone"), onE, null, null).assert().isEqualTo(L1Read.Miss(true))
        versioned().evictKeepingVersion(
            storageKey("tombstone"),
            null,
            frame("tombstone")
        ).assert().isEqualTo(L1Eviction.Evicted)
        store.fill(
            Fill(storageKey("tombstone"), onE.token, scalar("t4", 4), null)
        ).assert().isEqualTo(L1Fill.Rejected(null))

        store.put(storageKey("record"), digested("r"), null, frame("record"))
        versioned().evictKeepingVersion(
            storageKey("record"),
            null,
            frame("record")
        ).assert().isEqualTo(L1Eviction.Authoritative)
        (remote("record") as L1Read.Hit).envelope.assert().isEqualTo(digested("r"))
        if (L1Capability.SETS in expectedCapabilities) {
            requireNotNull(store.sets()).addMembers(storageKey("set"), listOf("a".toByteArray()), null, frame("set"))
            versioned().evictKeepingVersion(storageKey("set"), null, frame("set"))
                .assert().isEqualTo(L1Eviction.Unreadable(backend.format))
            (remote("set") as L1Read.Hit).envelope.assert().isInstanceOf(Envelope.Members::class.java)
        }
    }

    /**
     * 墓碑（带版本的负缓存）读作负缓存，挡住不更新的版本，不接受任何令牌的填充。
     */
    @Test
    fun tombstoneReadsAsAbsentAndKeepsItsVersion() = l1 {
        val store = store()
        val key = storageKey("k")
        versioned().putIfNewer(key, Envelope.Absent, 3, Duration.ofMinutes(1), frame("k"))
            .assert().isEqualTo(L1VersionedWrite.Written)
        (remote("k") as L1Read.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        versioned().putIfNewer(
            key,
            scalar("v2"),
            2,
            null,
            frame("k")
        ).assert().isEqualTo(L1VersionedWrite.Stale(3))
        versioned().putIfNewer(
            key,
            Envelope.Absent,
            3,
            null,
            frame("k")
        ).assert().isEqualTo(L1VersionedWrite.Stale(3))
        store.fill(Fill(key, token(), scalar("v9", 9), null)).assert().isInstanceOf(L1Fill.Rejected::class.java)
        versioned().putIfNewer(key, scalar("v4"), 4, null, frame("k")).assert().isEqualTo(L1VersionedWrite.Written)
        remoteVersioned("k").assert().isEqualTo(Versioned("v4", 4))
    }

    /**
     * 带版本的负缓存经填充写入（[Fill.version]）：凭 lease 写入墓碑，与按版本写入的墓碑相同。
     */
    @Test
    fun fillOfAVersionedAbsenceWritesATombstone() = l1 {
        val store = store()
        val key = storageKey("k")
        val lease = LeaseRequest(token(), Duration.ofSeconds(30))
        store.read(key, lease, null, null).assert().isEqualTo(L1Read.Miss(true))
        store.fill(
            Fill(key, lease.token, Envelope.Absent, Duration.ofMinutes(1), 7)
        ).assert().isInstanceOf(L1Fill.Filled::class.java)
        (remote("k") as L1Read.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        versioned().putIfNewer(
            key,
            scalar("v6"),
            6,
            null,
            frame("k")
        ).assert().isEqualTo(L1VersionedWrite.Stale(7))
        versioned().putIfNewer(key, scalar("v8"), 8, null, frame("k")).assert().isEqualTo(L1VersionedWrite.Written)
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
     * 写入标识（RFC 0005 §4.7，D2）：`VersionedStore` 的 `putIfNewer` 带写入标识，版本照常保存，不带摘要段；`VersionedCache` 的写入与回源填充
     * 都不带。墓碑（`deleteIfNewer`）没有载荷，也不带。
     */
    @Test
    fun onlyVersionedStoresWriteAWriterId() {
        fun remoteScalar(key: String) = (remote(key) as L1Read.Hit).envelope as Envelope.Scalar
        val derived = nodeA.cache()
        database["filled"] = Versioned("v1", 1)
        derived.getVersioned("filled").assert().isEqualTo(Versioned("v1", 1))
        derived.putIfNewer("k", "v2", 2).assert().isEqualTo(VersionedPut.Written)
        remoteScalar("filled").writer.assert().isNull()
        remoteScalar("k").writer.assert().isNull()

        val authoritative = nodeB.versionedStore()
        authoritative.putIfNewer("k", "v3", 3).assert().isEqualTo(VersionedPut.Written)
        remoteScalar("k").version.assert().isEqualTo(3L)
        remoteScalar("k").digest.assert().isNull()
        requireNotNull(remoteScalar("k").writer).node.assert().isEqualTo(nodeB.nodeId)
        authoritative.deleteIfNewer("k", 4).assert().isEqualTo(VersionedPut.Written)
        authoritative.getVersioned("k").assert().isNull()
        authoritative.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
        authoritative.getVersioned("k").assert().isEqualTo(Versioned("v5", 5))
    }

    /**
     * `VersionedStore` 的读取同 `Store`（RFC 0005 §4.1）：不申请 lease，L1 中没有就是 `Absent`；L1 读失败为 `Unknown`。
     * 墓碑的寿命是 `tombstoneTtl`（默认策略 1 天，与值永不过期无关）；`purge` 删除 key、丢掉版本栅栏，之后任何版本都能写入。
     */
    @Test
    fun versionedStoreReadsWithoutALeaseAndTombstonesExpire() {
        val store = nodeA.versionedStore(storeSpec(DefaultPolicies.VERSIONED_STORE))
        val other = nodeB.versionedStore(storeSpec(DefaultPolicies.VERSIONED_STORE))
        store["k"].assert().isNull()
        store.lookup("k").assert().isInstanceOf(Lookup.Absent::class.java)
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        store.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        remoteTtl("k").assert().isNull()
        awaitCondition("B sees the value") { other.getVersioned("k") == Versioned("v1", 1) }

        store.deleteIfNewer("k", 2).assert().isEqualTo(VersionedPut.Written)
        val tombstoneTtl = requireNotNull(remoteTtl("k"))
        tombstoneTtl.assert().isBetween(
            Duration.ofHours(23),
            requireNotNull(DefaultPolicies.VERSIONED_STORE.tombstoneTtl)
        )
        awaitCondition("B sees the deletion") { other["k"] == null }
        other.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Stale(2))

        store.purge("k")
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        other.putIfNewer("k", "v0", 0).assert().isEqualTo(VersionedPut.Written)
        awaitCondition("A reads the version written after the purge") { store.getVersioned("k") == Versioned("v0", 0) }

        backend.failAll = true
        runCatching { store["k2"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        store.lookup("k2").assert().isInstanceOf(Lookup.Unknown::class.java)
        backend.failAll = false
        nodeA.metrics().leaseWaits.assert().isEqualTo(0)
    }

    /**
     * 相等的版本是幂等的空操作，旧版本被拒绝：都不修改 L1、不改变到期时间，计入 `staleVersions`。相等的版本仍然广播（这个版本可能是
     * 不广播的填充写入的，见 [anEqualVersionEvictsCopiesOlderThanAFilledVersion]），更旧的版本不广播；原子与非原子广播的后端一致。
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
        // 相等的版本（putIfNewer 与 invalidateIfNewer 各一次）广播，更旧的不广播。
        // 等到期望的条数全部到达：Redis Cluster 上经不同节点发布的消息之间没有顺序，标记可能先于它们到达
        val expected = received + 2 + 1
        awaitCondition("marker broadcast") { nodeB.metrics().invalidationsReceived >= expected }
        nodeB.metrics().invalidationsReceived.assert().isEqualTo(expected)

        remoteVersioned("k").assert().isEqualTo(Versioned("v1", 1))
        requireNotNull(remoteTtl("k")).assert().isLessThanOrEqualTo(ttl)
        cacheB["k"].assert().isEqualTo("v1")
        nodeA.metrics().staleVersions.assert().isEqualTo(3)
        nodeA.metrics().writeFailures.assert().isEqualTo(0)
    }

    /**
     * 相等的版本淘汰其它节点更旧的副本（RFC 0005 §4.7，TLA+ F1）：节点 B 的 L2 中是 v0（这里 L1 被绕过协议删除，如 Redis 的驱逐），
     * 节点 A 未命中、凭 lease 回源并填充 v1——在 lease 上的填充不广播；写入方随后的 `putIfNewer(v1)` 返回 `Stale(1)`，此时必须广播，
     * 否则 B 读到 v0 直到 `localMaxAge`。原子与非原子广播的后端都如此。
     */
    @Test
    fun anEqualVersionEvictsCopiesOlderThanAFilledVersion() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = Versioned("v0", 0)
        cacheB.getVersioned("k").assert().isEqualTo(Versioned("v0", 0))
        deleteSilently("k")
        database["k"] = Versioned("v1", 1)
        cacheA.getVersioned("k").assert().isEqualTo(Versioned("v1", 1))
        remoteVersioned("k").assert().isEqualTo(Versioned("v1", 1))
        cacheB.getVersioned("k").assert().isEqualTo(Versioned("v0", 0))

        cacheA.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Stale(1))
        awaitCondition("B drops its v0 copy") { cacheB.getVersioned("k") == Versioned("v1", 1) }
        loads.get().assert().isEqualTo(2)
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
        remote("value").assert().isEqualTo(L1Read.Miss(false))
        remote("tombstone").assert().isEqualTo(L1Read.Miss(false))
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
        (remote("k") as L1Read.Hit).envelope.assert().isEqualTo(Envelope.Absent)
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
        remote("k").assert().isInstanceOf(L1Read.Leased::class.java)
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
        backend.afterFill = { key, outcome ->
            if (outcome is L1Fill.Filled && key == storageKey("k") && filled.count > 0) {
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
     * 非原子广播的后端上，按版本写入已在 L1 生效而单独的广播失败（调用方得到 [CacheWriteException]）时，按 §6.5 重放同一版本返回
     * [VersionedPut.Stale] 且当前版本等于给出的版本：这时仍要广播，否则其它节点的 L2 陈旧至 `localMaxAge`。墓碑相同。
     */
    @Test
    fun replayOfAnAppliedVersionedWriteBroadcasts() {
        backend.atomicBroadcastOverride = false
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        writeSilently("k", scalar("v1", 1), 1)
        cacheB["k"].assert().isEqualTo("v1")
        // putIfNewer(k, v2, 2) 已在 L1 生效、广播没有发出
        writeSilently("k", scalar("v2", 2), 2)
        cacheB["k"].assert().isEqualTo("v1")

        cacheA.putIfNewer("k", "v2", 2).assert().isEqualTo(VersionedPut.Stale(2))
        awaitCondition("the replay reached B") { cacheB["k"] == "v2" }

        // 墓碑的重放用另一个键：被包装的存储本身原子广播时，相等的版本在脚本中也广播一次，
        // k 上迟到的帧随时可能淘汰 B 的副本，不能用来证明墓碑之前的副本还在
        writeSilently("t", scalar("t2", 2), 2)
        cacheB["t"].assert().isEqualTo("t2")
        writeSilently("t", Envelope.Absent, 3)
        cacheB["t"].assert().isEqualTo("t2")
        cacheA.invalidateIfNewer("t", 3).assert().isEqualTo(VersionedPut.Stale(3))
        awaitCondition("the tombstone replay reached B") { cacheB["t"] == null }
    }

    /**
     * 提前刷新的策略：寿命 10 s，窗口 5 s，L2 限龄 8 s。
     */
    private fun refreshingPolicy(): CachePolicy = CachePolicy(
        ttl = Duration.ofSeconds(10),
        ttlJitter = Duration.ZERO,
        localMaxAge = Duration.ofSeconds(8),
        refreshWindow = Duration.ofSeconds(5),
        lease = LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ofSeconds(5)),
    )

    /**
     * 带版本的缓存照常提前刷新（RFC 0005 §4.7）：带版本的值取得刷新令牌，持有者回源；版本更大的写回是一次写入（`advanced`）：
     * 广播（其它节点收到失效），本节点递增戳、淘汰本地副本、不填 L2，下一次读取从 L1 读到新版本。
     */
    @Test
    fun versionedCachesRefreshAheadAndAnAdvancingFillIsAWrite() {
        val cacheA = nodeA.cache(spec(refreshingPolicy()))
        val cacheB = nodeB.cache(spec(refreshingPolicy()))
        database["k"] = Versioned("v1", 1)
        cacheA.getVersioned("k").assert().isEqualTo(Versioned("v1", 1))
        cacheB.getVersioned("k").assert().isEqualTo(Versioned("v1", 1))
        loads.get().assert().isEqualTo(1)
        val received = nodeB.metrics().invalidationsReceived

        database["k"] = Versioned("v2", 2)
        elapse(Duration.ofSeconds(6))
        cacheA["k"].assert().isEqualTo("v1")
        awaitCondition("A refreshed L1") { remoteVersioned("k") == Versioned("v2", 2) }
        awaitCondition("the advancing fill reached B") { nodeB.metrics().invalidationsReceived > received }
        val remoteHits = nodeA.metrics().remoteHits
        awaitCondition("A reads the new version") { cacheA.getVersioned("k") == Versioned("v2", 2) }
        nodeA.metrics().remoteHits.assert().withFailMessage { "the advancing fill does not fill L2" }.isGreaterThan(
            remoteHits
        )
        cacheB.getVersioned("k").assert().isEqualTo(Versioned("v2", 2))
        loads.get().assert().isEqualTo(2)
        nodeA.metrics().fillRejected.assert().isEqualTo(0)
    }

    /**
     * 刷新路径的版本拒绝（RFC 0005 §4.7）：数据源落后于按版本写入，回源得到的版本更旧，`FILL` 拒绝并清零令牌。刷新不写回源得到的旧值，
     * 保留 L2 中的新版本，刷新点按退避（退避期间的命中不再回源），计入 `fillRejected`。
     */
    @Test
    fun versionRejectedRefreshKeepsTheNewerCopy() {
        val cache = nodeA.cache(spec(refreshingPolicy()))
        cache.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
        cache.getVersioned("k").assert().isEqualTo(Versioned("v5", 5))
        database["k"] = Versioned("v4", 4)

        elapse(Duration.ofSeconds(6))
        cache["k"].assert().isEqualTo("v5")
        awaitCondition("the refresh was rejected") { nodeA.metrics().fillRejected == 1L }
        loads.get().assert().isEqualTo(1)
        cache.getVersioned("k").assert().isEqualTo(Versioned("v5", 5))
        remoteVersioned("k").assert().isEqualTo(Versioned("v5", 5))
        repeat(3) { cache["k"].assert().isEqualTo("v5") }
        loads.get().assert().isEqualTo(1)
        nodeA.metrics().degradedFills.assert().isEqualTo(0)
    }

    /**
     * 同上，回源慢（3 s）：版本拒绝不改变 L1 的到期时间，保留的副本不活过 L1（§5）。L1 在 10 s 到期，刷新在 6 s 读到剩余 4 s，
     * 回源在 9 s 结束，重新填充的 L2 副本在 10 s 到期（回源期间消耗的时间从剩余寿命中扣除），而不是 9 s 加上读到的 4 s。
     */
    @Test
    fun slowVersionRejectedRefreshDoesNotOutliveL1() = slowRejectedRefresh { database["k"] = Versioned("v4", 4) }

    /**
     * 同上，数据源返回不带版本的 `null`（K16：落在带版本的内容上被拒绝）：保留的副本同样不活过 L1。
     */
    @Test
    fun slowUnversionedAbsenceRefreshDoesNotOutliveL1() = slowRejectedRefresh { database.remove("k") }

    private fun slowRejectedRefresh(staleSource: () -> Unit) {
        val cache = nodeA.cache(spec(refreshingPolicy()))
        val l1ExpiresAt = clock.nowMillis() + Duration.ofSeconds(10).toMillis()
        cache.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
        cache["k"].assert().isEqualTo("v5")
        staleSource()
        afterLoad = { elapse(Duration.ofSeconds(3)) }

        elapse(Duration.ofSeconds(6))
        cache["k"].assert().isEqualTo("v5")
        awaitCondition("the refresh was rejected") { nodeA.metrics().fillRejected == 1L }
        afterLoad = null
        loads.get().assert().isEqualTo(1)
        val local = requireNotNull(requireNotNull(nodeA.admin(cacheName)).inspect("k").local)
        local.value.assert().contains("v5")
        local.expiresAt.toEpochMilli().assert()
            .withFailMessage { "the kept copy outlives L1 (expires at $l1ExpiresAt): $local" }
            .isLessThanOrEqualTo(l1ExpiresAt)
        local.expired.assert().isFalse()
        cache.getVersioned("k").assert().isEqualTo(Versioned("v5", 5))
        loads.get().assert().isEqualTo(1)
    }

    /**
     * 运维的按 key 删除（`CacheAdmin.invalidateEncoded`）在带版本的缓存上保留版本（RFC 0005 §4.7）：L1 转为 `E` 并广播，迟到的旧版本仍被拒绝。
     * 下一次未命中在 `E` 上取得 lease：数据源落后时填充被拒绝，回源值照常返回，以降级限龄写入 L2，计入 `fillRejected`，`E` 保留；
     * 数据源追上之后，填充写入并广播（按写入处理）。
     */
    @Test
    fun adminEvictionKeepsTheVersionFence() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        cacheA.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
        cacheB["k"].assert().isEqualTo("v5")
        val received = nodeB.metrics().invalidationsReceived
        requireNotNull(nodeA.admin(cacheName)).invalidateEncoded("k")
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        awaitCondition("the eviction reached B") { nodeB.metrics().invalidationsReceived > received }
        cacheA.putIfNewer("k", "v4", 4).assert().isEqualTo(VersionedPut.Stale(5))

        database["k"] = Versioned("v3", 3)
        cacheA["k"].assert().isEqualTo("v3")
        nodeA.metrics().fillRejected.assert().isEqualTo(1)
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        cacheA.putIfNewer("k", "v4", 4).assert().isEqualTo(VersionedPut.Stale(5))

        database["k"] = Versioned("v6", 6)
        elapse(policy().degradedLocalMaxAge.plusSeconds(1))
        cacheA["k"].assert().isEqualTo("v6")
        remoteVersioned("k").assert().isEqualTo(Versioned("v6", 6))
        awaitCondition("B reads the version that replaced E") { cacheB.getVersioned("k") == Versioned("v6", 6) }
        loads.get().assert().isEqualTo(2)
    }

    /**
     * `evict` 与运维的按 key 删除遇到 `Store` 写入的值（带摘要段，D4）：拒绝，以 [CacheWriteException] 失败（结果确定未执行），key 保留，
     * 计入 `typeMismatches`。`purge` 删除它。
     */
    @Test
    fun evictionOfAnAuthoritativeValueIsRefused() {
        val derived = nodeA.cache()
        val bytes = TestCodec.encode("p")
        Suspending.await {
            store().put(
                storageKey("plain"),
                Envelope.Scalar(bytes, Versioned.NO_VERSION, null, Digest.ofPayload(bytes)),
                null,
                InvalidationFrame.encode(NodeId.random(), emptyList())
            )
        }
        val error = runCatching { requireNotNull(nodeA.admin(cacheName)).invalidateEncoded("plain") }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        error?.cause.assert().isInstanceOf(IllegalStateException::class.java)
        (error as CacheWriteException).outcomeUnknown.assert().isFalse()
        runCatching { derived.evict("plain") }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        remoteVersioned("plain").assert().isEqualTo(Versioned("p", Versioned.NO_VERSION))
        nodeA.metrics().typeMismatches.assert().isEqualTo(2)

        derived.purge("plain")
        remote("plain").assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 不广播地直接删除 L1 中的 key（模拟 Redis 驱逐或到期：其它节点的 L2 不受影响）。
     */
    private fun deleteSilently(key: String) {
        Suspending.await {
            store().invalidate(listOf(storageKey(key)), InvalidationFrame.encode(NodeId.random(), emptyList()))
        }
    }

    /**
     * 不广播地直接按版本写 L1（模拟已生效但广播丢失的写入）。
     */
    private fun writeSilently(key: String, envelope: Envelope, version: Long) {
        val written = Suspending.await {
            versioned().putIfNewer(
                storageKey(key),
                envelope,
                version,
                null,
                InvalidationFrame.encode(NodeId.random(), emptyList())
            )
        }
        written.assert().isEqualTo(L1VersionedWrite.Written)
    }

    /**
     * 存储的按版本写入能力面（带版本的缓存要求它）。
     */
    private fun versioned(): VersionedL1Store = requireNotNull(store().versioned())

    /**
     * 协议用例的用例体：其中直接调用存储的挂起方法（[Suspending.await]）。
     */
    private fun l1(block: suspend () -> Unit) = Suspending.await(block)

    /**
     * `purge` 丢掉版本栅栏：之后任何版本都能写入。数据源返回 `null`（从来没有这个 key）时是没有版本的负缓存，之后任何版本都能写入。
     * `evict` 保留版本栅栏：迟到的旧版本仍被拒绝，更新的版本写入。
     */
    @Test
    fun purgeDropsTheVersionFenceAndEvictKeepsIt() {
        val cache = nodeA.cache()
        cache.putIfNewer("k", "v5", 5)
        cache.evict("k")
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        cache.putIfNewer("k", "v4", 4).assert().isEqualTo(VersionedPut.Stale(5))
        cache.purge("k")
        cache.putIfNewer("k", "v0", 0).assert().isEqualTo(VersionedPut.Written)
        cache.getVersioned("k").assert().isEqualTo(Versioned("v0", 0))

        cache["missing"].assert().isNull()
        (remote("missing") as L1Read.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        cache.putIfNewer("missing", "m1", 1).assert().isEqualTo(VersionedPut.Written)
        cache.getVersioned("missing").assert().isEqualTo(Versioned("m1", 1))
    }

    /**
     * `evict` 失败时抛出 [CacheWriteException]，从不进入失效重试（重试的 `DEL` 会丢掉版本，RFC 0005 §4.7）：在发出之前被拒绝时结果确定
     * 未执行（`outcomeUnknown = false`），L1 不变；结果未知的故障报告 `outcomeUnknown = true`。两种情况下本节点的 L2 副本都已淘汰
     * （写入方顺序），下一次读取重新读 L1。
     */
    @Test
    fun failedEvictionThrowsAndIsNeverRetried() {
        val cache = nodeA.cache()
        cache.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
        cache["k"].assert().isEqualTo("v5")

        backend.rejectWrites = true
        val rejected = runCatching { cache.evict("k") }.exceptionOrNull()
        backend.rejectWrites = false
        rejected.assert().isInstanceOf(CacheWriteException::class.java)
        (rejected as CacheWriteException).outcomeUnknown.assert().isFalse()
        remoteVersioned("k").assert().isEqualTo(Versioned("v5", 5))
        val reads = backend.reads.get()
        cache["k"].assert().isEqualTo("v5")
        backend.reads.get().assert().withFailMessage { "the failed evict dropped the local copy" }.isEqualTo(reads + 1)

        backend.failWrites = true
        val failed = runCatching { cache.evict("k") }.exceptionOrNull()
        backend.failWrites = false
        failed.assert().isInstanceOf(CacheWriteException::class.java)
        (failed as CacheWriteException).outcomeUnknown.assert().isTrue()
        requireNotNull(nodeA.admin(cacheName)).pendingInvalidationCount.assert().isZero()
        remoteVersioned("k").assert().isEqualTo(Versioned("v5", 5))
        cache["k"].assert().isEqualTo("v5")
        loads.get().assert().isZero()
    }

    /**
     * K16（RFC 0005 §4.1）：带版本的数据源必须以 `Versioned(null, w)` 表达删除。不带版本的 `null` 落在带版本的内容上（这里是 `evict`
     * 留下的 `E(5)`）被 L1 拒绝：回源值（不存在）照常返回，不写成负缓存，`E` 保留，计入 `unversionedAbsentRefusals` 与 `fillRejected`；
     * 已删除的实体因此在 `E` 到期之前每次未命中都回源。以 `Versioned(null, 6)` 表达删除时写入墓碑，不再回源。
     */
    @Test
    fun unversionedAbsenceOverVersionedContentIsRefused() {
        val cache = nodeA.cache()
        cache.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
        cache.evict("k")
        database.remove("k")
        cache["k"].assert().isNull()
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        nodeA.metrics().unversionedAbsentRefusals.assert().isEqualTo(1)
        nodeA.metrics().fillRejected.assert().isEqualTo(1)
        elapse(policy().degradedLocalMaxAge.plusSeconds(1))
        cache["k"].assert().isNull()
        loads.get().assert().isEqualTo(2)
        nodeA.metrics().unversionedAbsentRefusals.assert().isEqualTo(2)

        database["k"] = Versioned(null, 6)
        elapse(policy().degradedLocalMaxAge.plusSeconds(1))
        cache["k"].assert().isNull()
        (remote("k") as L1Read.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        cache["k"].assert().isNull()
        loads.get().assert().isEqualTo(3)
        nodeA.metrics().unversionedAbsentRefusals.assert().isEqualTo(2)
    }

    /**
     * 读不懂的内容（同一个 key 上的集合）拒绝按版本写入：不修改 L1，以 [CacheWriteException] 失败（cause 为 [ForeignFormatException]）。
     */
    @Test
    fun unreadableContentRefusesVersionedWrites() {
        assumeDeclared(L1Capability.VERSIONED, L1Capability.SETS)
        val cache = nodeA.cache()
        Suspending.await {
            requireNotNull(
                store().sets()
            ).addMembers(storageKey("set"), listOf("a".toByteArray()), null, frame("set"))
        }
        val error = runCatching { cache.putIfNewer("set", "v", 1) }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        error?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        (remote("set") as L1Read.Hit).envelope.assert().isInstanceOf(Envelope.Members::class.java)
        nodeA.metrics().unknownFormats.assert().isEqualTo(1)
    }

    /**
     * [Versioned.NO_VERSION] 不能作为版本（`VersionedCache` 与 `VersionedStore` 都是）。
     */
    @Test
    fun noVersionIsRejected() {
        val cache = nodeA.cache()
        runCatching { cache.putIfNewer("k", "v", Versioned.NO_VERSION) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { cache.invalidateIfNewer("k", Versioned.NO_VERSION) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        val store = nodeB.versionedStore()
        runCatching { store.putIfNewer("k", "v", Versioned.NO_VERSION) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { store.deleteIfNewer("k", Versioned.NO_VERSION) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        remote("k").assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 读不懂的带版本值（同版本但解码失败）不被回收：有数据源的读取绕过 L1 回源，L1 保留版本，迟到的旧版本仍被拒绝。
     */
    @Test
    fun corruptVersionedPayloadIsBypassedNotReclaimed() {
        val cache = nodeA.cache()
        database["k"] = Versioned("source", 7)
        Suspending.await {
            versioned().putIfNewer(
                storageKey("k"),
                Envelope.Scalar(TestCodec.CORRUPT),
                5,
                null,
                frame("k")
            )
        }
        cache["k"].assert().isEqualTo("source")
        ((remote("k") as L1Read.Hit).envelope as Envelope.Scalar).version.assert().isEqualTo(5L)
        cache.putIfNewer("k", "v4", 4).assert().isEqualTo(VersionedPut.Stale(5))
        cache.putIfNewer("k", "v6", 6).assert().isEqualTo(VersionedPut.Written)
        awaitCondition("A reads the newer write") { cache.getVersioned("k") == Versioned("v6", 6) }
    }

    private companion object {
        const val BARRIER_TIMEOUT_SECONDS = 30L
    }
}
