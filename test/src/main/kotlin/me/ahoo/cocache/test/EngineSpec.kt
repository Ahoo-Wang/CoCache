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

import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.SuspendingCache
import me.ahoo.cocache.api.SuspendingStore
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引擎 TCK：两个节点（运行时）共享同一个后端，覆盖 RFC 0003 §4.4–§4.6 与 §10 的全部反例，以及派生副本 `Cache`
 * （有数据源与外部来源）与权威副本 `Store` 的语义（RFC 0005 §4.1）。
 *
 * 竞态用 latch 编排；跨节点效果用带超时的轮询断言。
 */
@Suppress("LargeClass")
abstract class EngineSpec {
    protected abstract fun createBackend(): CoherenceBackend

    /**
     * 后端的存储提供的可选能力（RFC 0005 §4.3），没有默认值：规范断言存储提供的能力与它完全相同（多出来的也算失败）、
     * 每个访问器稳定。条件读写的用例只在没有声明 [L1Capability.CONDITIONAL] 时跳过（另需集合内容的用例同样要求
     * [L1Capability.SETS]）；声明了的条件写入必须原子，不原子的实现无法通过强制竞争的并发用例。
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
     * 模拟失效通道断开并重新订阅。
     */
    protected abstract fun simulateReconnect()

    /**
     * 访问缓存的方式：同步 API（默认），或某个变体（如 [CacheAccess.ASYNC]）。后端实现者只需要默认的 [CacheAccess.SYNC]：
     * 变体（同步、异步、挂起）是核心的语义，由核心自己的测试覆盖（RFC 0005 §4.10）；仓库内覆盖它，在不同变体下运行本规范。
     */
    protected open val access: CacheAccess = CacheAccess.SYNC

    /**
     * 用例结束时是否关闭 [createBackend] 返回的后端（见 [HookedBackend.closesDelegate]）。默认 `false`：后端可能是共享的。
     * 每个用例新建后端的实现应覆盖为 `true`。
     */
    protected open val closesBackend: Boolean = false

    /**
     * 暂停 [createBackend] 所建后端的 L1 命令，语义同 [BackendProtocolSpec.pause]：暂停期间开始的命令不完成，暂停至少持续
     * [READ_TIMEOUT] 的 10 倍，关闭返回值即恢复。缓存读取时限（`CachePolicy.readTimeout`，RFC 0005 §4.3，D6）的用例需要它，
     * 不支持时返回 `null`（默认），该用例跳过。
     */
    protected open fun pause(): AutoCloseable? = null

    /**
     * 不广播地在 [storageKey] 写入格式号低于本后端格式的内容（RFC 0005 §4.7：格式升级之前的节点或其它程序写入的值），
     * 读取以 [raw] 为信封的全部字节、报告低于本格式的格式版本（如 Redis 中原样的 String 为格式 0）。回收旧格式内容的用例需要它，
     * 不支持时返回 `false`（默认），该用例跳过。
     */
    protected open fun writeForeign(storageKey: String, raw: ByteArray): Boolean = false

    /**
     * 用例需要的能力：[expectedCapabilities] 缺少其中任何一个时跳过这个用例。
     */
    protected fun assumeDeclared(vararg capabilities: L1Capability) =
        CapabilityDeclaration.assume(expectedCapabilities, capabilities)

    protected lateinit var backend: HookedBackend
    protected lateinit var nodeA: CacheRuntime
    protected lateinit var nodeB: CacheRuntime
    protected lateinit var cacheName: String

    /**
     * 模拟数据源。
     */
    protected val database = ConcurrentHashMap<String, String>()
    protected val loads = AtomicInteger()
    protected val batchLoads = AtomicInteger()

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
        cacheName = "tck-" + UUID.randomUUID().toString().substring(0, 8)
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

    protected open fun policy(): CachePolicy = DefaultPolicies.CACHE.toBuilder()
        .ttl(Duration.ofMinutes(10))
        .ttlJitter(Duration.ZERO)
        .lease(LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ofSeconds(5)))
        .build()

    protected val loader = object : CacheLoader<String, String> {
        override fun load(key: String): String? {
            loads.incrementAndGet()
            val value = database[key]
            afterLoad?.invoke(key)
            return value
        }

        override fun loadAll(keys: Collection<String>): Map<String, String> {
            batchLoads.incrementAndGet()
            return keys.mapNotNull { key -> database[key]?.let { key to it } }.toMap()
        }
    }

    protected fun spec(
        policy: CachePolicy = policy(),
        block: CacheSpec.Builder<String, String>.() -> Unit = {
        }
    ): CacheSpec<String, String> {
        return cacheSpec(cacheName) {
            codec = TestCodec
            loader = this@EngineSpec.loader
            this.policy = policy
            block()
            access.source(this)
        }
    }

    protected fun CacheRuntime.cache(spec: CacheSpec<String, String> = spec()): Cache<String, String> = access.cache(
        create(spec)
    )

    /**
     * 外部来源的 `Cache`（`externallySourced()`）：没有声明的数据源。
     */
    protected fun externalSpec(
        policy: CachePolicy = policy(),
        block: CacheSpec.Builder<String, String>.() -> Unit = {}
    ): CacheSpec<String, String> = spec(policy) {
        loader = null
        externallySourced()
        block()
    }

    /**
     * 与 [spec] 同名、同 keyspace 的 `Store`：策略同 [policy]（用例的寿命与限龄与派生缓存相同，便于对照）。
     */
    protected fun storeSpec(
        policy: CachePolicy = policy(),
        block: StoreSpec.Builder<String, String>.() -> Unit = {}
    ): StoreSpec<String, String> = storeSpec(cacheName) {
        codec = TestCodec
        this.policy = policy
        block()
    }

    protected fun CacheRuntime.store(spec: StoreSpec<String, String> = storeSpec()): Store<String, String> =
        access.store(create(spec))

    protected fun CacheRuntime.metrics(): CacheMetrics = requireNotNull(metrics(cacheName))

    protected fun storageKey(key: String) = "cocache:$cacheName:$key"

    /**
     * 不经过引擎直接读 L1。
     */
    protected fun remote(key: String): L1Read = Suspending.await {
        backend.delegate.store(Keyspace("cocache:", cacheName)).read(storageKey(key), null, null, null)
    }

    protected fun remoteValue(key: String): String? {
        val read = remote(key) as? L1Read.Hit ?: return null
        return (read.envelope as? Envelope.Scalar)?.let { TestCodec.decode(it.bytes) }
    }

    /**
     * 不经过引擎直接在 L1 中创建集合（带 [frame] 广播）。
     */
    protected fun addMembersSilently(key: String, frame: ByteArray) = Suspending.await {
        requireNotNull(
            backend.delegate.store(Keyspace("cocache:", cacheName)).sets()
        ).addMembers(storageKey(key), listOf("a".toByteArray()), null, frame)
    }

    /**
     * 不广播地直接写 L1（模拟绕过协议的写入或丢失的事件）。[digest] 为载荷的摘要（像 `Store` 那样写入摘要段，
     * RFC 0005 §4.7）；默认不带，像派生缓存。
     */
    protected fun writeRemoteSilently(key: String, bytes: ByteArray, digest: Digest? = null) = Suspending.await {
        backend.delegate.store(Keyspace("cocache:", cacheName)).put(
            storageKey(key),
            Envelope.Scalar(bytes, Versioned.NO_VERSION, null, digest),
            null,
            InvalidationFrame.encode(NodeId.random(), emptyList())
        )
    }

    /**
     * 像 `Store` 那样不广播地写 L1：带载荷的摘要段。
     */
    protected fun writeAuthoritativeSilently(key: String, bytes: ByteArray) =
        writeRemoteSilently(key, bytes, Digest.ofPayload(bytes))

    /**
     * 存储提供的能力与 [expectedCapabilities] 完全相同（多出来的也算失败），每个访问器每次返回同一个对象或总是 `null`。
     */
    @Test
    fun storeProvidesExactlyTheDeclaredCapabilities() {
        CapabilityDeclaration.verify(backend.delegate, expectedCapabilities)
    }

    @Test
    fun getLoadsOnceThenServesFromLocal() {
        val cache = nodeA.cache()
        database["k"] = "v"
        cache["k"].assert().isEqualTo("v")
        cache["k"].assert().isEqualTo("v")
        loads.get().assert().isEqualTo(1)
        backend.reads.get().assert().isEqualTo(1)
        remoteValue("k").assert().isEqualTo("v")
    }

    @Test
    fun otherNodeHitsRemoteWithoutLoading() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = "v"
        cacheA["k"]
        cacheB["k"].assert().isEqualTo("v")
        loads.get().assert().isEqualTo(1)
        nodeB.metrics().remoteHits.assert().isEqualTo(1)
    }

    @Test
    fun absentValueIsCachedNegatively() {
        val cache = nodeA.cache()
        cache["missing"].assert().isNull()
        cache["missing"].assert().isNull()
        loads.get().assert().isEqualTo(1)
        cache.lookup("missing").assert().isInstanceOf(Lookup.Absent::class.java)
        nodeB.cache()["missing"].assert().isNull()
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun lookupReportsPresentValueWithExpiry() {
        val cache = nodeA.cache()
        database["k"] = "v"
        val lookup = cache.lookup("k")
        lookup.assert().isInstanceOf(Lookup.Present::class.java)
        (lookup as Lookup.Present).value.assert().isEqualTo("v")
        requireNotNull(lookup.expiresAt).toEpochMilli().assert().isGreaterThan(clock.nowMillis())
    }

    /**
     * 命名空间不含 `:`（RFC 0005 §4.7）：写进命名空间的前缀在创建时失败，提示把它移到 keyPrefix，不注册任何东西。
     */
    @Test
    fun namespaceWithColonFailsAtCreation() {
        val error = runCatching { nodeA.cache(spec { namespace = "app:$cacheName" }) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java)
        requireNotNull(error?.message).assert().contains("move the prefix into keyPrefix")
        nodeA.cache(cacheName).assert().isNull()
        database["k"] = "v"
        nodeA.cache()["k"].assert().isEqualTo("v")
    }

    /**
     * 零 key 的帧（存储创建时的频道探测，RFC 0005 §4.7，K8）什么也不做：不淘汰 L2，不计入收到的失效。
     */
    @Test
    fun zeroKeyFrameIsIgnored() {
        val cache = nodeB.cache()
        database["k"] = "v"
        cache["k"].assert().isEqualTo("v")
        val keyspace = Keyspace("cocache:", cacheName)
        Suspending.await {
            backend.channel.publish(keyspace, InvalidationFrame.encode(NodeId.random(), emptyList()))
            backend.channel.publish(keyspace, InvalidationFrame.encode(NodeId.random(), listOf(storageKey("other"))))
        }
        // 同一频道上的帧有序：收到第二帧时第一帧已经处理过
        awaitCondition("the frame after the zero-key one") { nodeB.metrics().invalidationsReceived >= 1 }
        nodeB.metrics().invalidationsReceived.assert().isEqualTo(1)
        val reads = backend.reads.get()
        cache["k"].assert().isEqualTo("v")
        backend.reads.get().assert().isEqualTo(reads)
    }

    /**
     * 命名空间相同、前缀不同的两个缓存相互隔离（RFC 0005 §4.7）：存储 key 不同，失效频道也不同，一个缓存的写入不会让另一个缓存
     * 收到失效。同一个运行时可以同时创建它们（按 keyspace 登记）。
     */
    @Test
    fun sameNamespaceUnderTwoPrefixesIsIsolated() {
        val otherName = "$cacheName-other"
        val other = cacheSpec<String, String>(otherName) {
            namespace = cacheName
            keyPrefix = "tck-other:"
            codec = TestCodec
            loader = this@EngineSpec.loader
            policy = policy()
            access.source(this)
        }
        val ours = nodeA.cache()
        val theirs = nodeA.cache(other)
        val oursB = nodeB.cache()
        val theirsB = nodeB.cache(other)
        fun theirMetrics() = requireNotNull(nodeB.metrics(otherName))
        ours.put("k", "ours")
        theirs.put("k", "theirs")
        awaitCondition("each cache receives its own invalidation") {
            nodeB.metrics().invalidationsReceived >= 1 && theirMetrics().invalidationsReceived >= 1
        }
        oursB["k"].assert().isEqualTo("ours")
        theirsB["k"].assert().isEqualTo("theirs")
        remoteValue("k").assert().isEqualTo("ours")
        loads.get().assert().isEqualTo(0)

        ours.invalidate("k")
        awaitCondition("node B receives the invalidation") { nodeB.metrics().invalidationsReceived >= 2 }
        theirMetrics().invalidationsReceived.assert().isEqualTo(1)
        val reads = backend.reads.get()
        theirsB["k"].assert().isEqualTo("theirs")
        backend.reads.get().assert().isEqualTo(reads)
    }

    /**
     * 写入哪些段（RFC 0005 §4.7，D1、D2）：派生副本（有数据源与外部来源的 `Cache`）写入的值、回源填充与调用方给出 loader 时的填充
     * 都不带写入标识与摘要段；`Store` 的每次标量写入（put、putAll、putIfMatch）都带一个新的写入标识（与令牌同形、以本节点的 NodeId 开头）
     * 与载荷的 SHA-256 摘要段，由引擎在客户端计算，存储原样保存、读取原样返回。
     */
    @Test
    fun onlyStoresWriteAWriterIdAndADigest() {
        fun scalarOf(key: String) = (remote(key) as L1Read.Hit).envelope as Envelope.Scalar

        val derived = nodeA.cache()
        database["filled"] = "v"
        derived["filled"].assert().isEqualTo("v")
        derived.put("put", "p")
        derived.putAll(mapOf("all" to "a"))
        val external = nodeB.cache(externalSpec())
        external.put("ext", "e")
        external.get("ext-filled") { "f" }.assert().isEqualTo("f")
        listOf("filled", "put", "all", "ext", "ext-filled").forEach {
            val scalar = scalarOf(it)
            scalar.writer.assert().withFailMessage { "A derived write of [$it] carries no writer id." }.isNull()
            scalar.digest.assert().withFailMessage { "A derived write of [$it] carries no digest." }.isNull()
        }
        nodeA.close()
        nodeB.close()
        nodeA = newNode()
        nodeB = newNode()

        // 同一个 keyspace 上的 Store（另一个节点）
        val conditional = L1Capability.CONDITIONAL in expectedCapabilities
        if (!conditional) {
            return
        }
        val authoritative = nodeB.store()
        authoritative.put("put", "p2")
        authoritative.putAll(mapOf("all" to "a2", "all-2" to "b2"))
        authoritative.putIfMatch("cas-2", null, "c2").assert().isInstanceOf(ConditionalPut.Written::class.java)
        val keys = listOf("put", "all", "all-2", "cas-2")
        val writers = keys.map {
            val scalar = scalarOf(it)
            scalar.digest.assert().withFailMessage { "An authoritative write of [$it] carries the payload digest." }
                .isEqualTo(Digest.ofPayload(scalar.bytes))
            requireNotNull(scalar.writer) { "An authoritative write of [$it] carries a writer id." }
        }
        writers.forEach { it.node.assert().isEqualTo(nodeB.nodeId) }
        writers.toSet().assert().hasSize(writers.size)
        authoritative["put"].assert().isEqualTo("p2")
        authoritative.getAll(listOf("all", "all-2")).assert().isEqualTo(mapOf("all" to "a2", "all-2" to "b2"))
    }

    /**
     * 读取方校验（RFC 0005 §4.7，K3）：`Store` 从 L1 读到的值，摘要段与载荷不一致或缺摘要段时按损坏 `FAIL`，抛出
     * [CacheReadException]（cause 为 [ForeignFormatException]，本节点的格式），不回收、不写 L2，单 key、`lookup` 与批量都是如此。
     * 校验在引擎中，对所有后端相同。
     */
    @Test
    fun storeFailsOnATamperedOrMissingDigest() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = nodeA.store()
        writeRemoteSilently("tampered", TestCodec.encode("v2"), digestOf("v1"))
        writeRemoteSilently("bare", TestCodec.encode("v"))
        listOf("tampered", "bare").forEach { key ->
            repeat(2) {
                val error = runCatching { cache[key] }.exceptionOrNull()
                error.assert().withFailMessage { "[$key] must fail" }.isInstanceOf(CacheReadException::class.java)
                val cause = requireNotNull(error?.cause as? ForeignFormatException)
                cause.storageKey.assert().isEqualTo(storageKey(key))
                cause.format.assert().isEqualTo(backend.format)
            }
        }
        runCatching { cache.getAll(listOf("tampered", "absent")) }.exceptionOrNull()
            .assert().isInstanceOf(CacheReadException::class.java)
        runCatching { cache.lookup("bare") }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        remote("absent").assert().isEqualTo(L1Read.Miss(false))
        nodeA.metrics().unknownFormats.assert().isEqualTo(6)
        nodeA.metrics().reclaims.assert().isEqualTo(0)
        val tampered = (remote("tampered") as L1Read.Hit).envelope as Envelope.Scalar
        tampered.bytes.assert().isEqualTo(TestCodec.encode("v2"))
        ((remote("bare") as L1Read.Hit).envelope as Envelope.Scalar).bytes.assert().isEqualTo(TestCodec.encode("v"))
    }

    /**
     * `Store` 读到带版本的值（同一 namespace 上另有 `VersionedCache` 或 `VersionedStore`，带版本的值不带摘要段）：与缺摘要段相同，
     * 按损坏失败（[CacheReadException]，cause 为本节点格式的 [ForeignFormatException]），不当作 `Store` 的值返回，不回收、不写 L2；
     * 单 key、`lookup` 与批量都是如此。条件读取同样拒绝它，读与条件读写对同一份内容给出同一个答案。
     */
    @Test
    fun storeFailsOnAVersionedValue() {
        assumeDeclared(L1Capability.CONDITIONAL, L1Capability.VERSIONED)
        val bytes = TestCodec.encode("v")
        Suspending.await {
            backend.delegate.store(Keyspace("cocache:", cacheName)).put(
                storageKey("versioned"),
                Envelope.Scalar(bytes, 7),
                null,
                InvalidationFrame.encode(NodeId.random(), emptyList())
            )
        }
        val cache = nodeA.store()
        repeat(2) {
            val error = runCatching { cache["versioned"] }.exceptionOrNull()
            error.assert().isInstanceOf(CacheReadException::class.java)
            requireNotNull(error?.cause as? ForeignFormatException).format.assert().isEqualTo(backend.format)
        }
        runCatching { cache.lookup("versioned") }.exceptionOrNull()
            .assert().isInstanceOf(CacheReadException::class.java)
        runCatching { cache.getAll(listOf("versioned", "absent")) }.exceptionOrNull()
            .assert().isInstanceOf(CacheReadException::class.java)
        runCatching { cache.getIfChanged("versioned", null) }.exceptionOrNull()
            .assert().isInstanceOf(CacheReadException::class.java)
        nodeA.metrics().reclaims.assert().isEqualTo(0)
        ((remote("versioned") as L1Read.Hit).envelope as Envelope.Scalar).version.assert().isEqualTo(7)
    }

    /**
     * 派生缓存读到带摘要段的值（同一个 namespace 上还有 `Store`，RFC 0005 §4.7，D4）：照常读取，计入 `typeMismatches`；
     * 解码失败时也不回收（`RECLAIM` 拒绝带摘要段的内容），不计入 `reclaims`，绕过 L1 回源，L1 中的唯一副本保留。单个与批量读取相同。
     */
    @Test
    fun derivedCacheCountsATypeMismatchAndNeverReclaimsDigestContent() {
        writeAuthoritativeSilently("k", TestCodec.encode("authoritative"))
        val derived = nodeA.cache()
        derived["k"].assert().isEqualTo("authoritative")
        nodeA.metrics().typeMismatches.assert().isEqualTo(1)

        database["corrupt"] = "loaded"
        database["corrupt-batch"] = "batch"
        writeAuthoritativeSilently("corrupt", TestCodec.CORRUPT)
        writeAuthoritativeSilently("corrupt-batch", TestCodec.CORRUPT)
        derived["corrupt"].assert().isEqualTo("loaded")
        derived.getAll(listOf("corrupt-batch")).assert().isEqualTo(mapOf("corrupt-batch" to "batch"))
        listOf("corrupt", "corrupt-batch").forEach { key ->
            ((remote(key) as L1Read.Hit).envelope as Envelope.Scalar).bytes.assert().isEqualTo(TestCodec.CORRUPT)
        }
        nodeA.metrics().typeMismatches.assert().isEqualTo(3)
        nodeA.metrics().reclaims.assert().withFailMessage { "digest content is never reclaimed, so it is not counted" }.isZero()
    }

    @Test
    fun concurrentGetsOnOneNodeLoadOnce() {
        val cache = nodeA.cache()
        database["k"] = "v"
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        afterLoad = {
            loading.countDown()
            release.awaitOrFail("release")
        }
        val leader = fork { cache["k"] }
        loading.awaitOrFail("leader loading")
        afterLoad = null
        val followers = (1..3).map { fork { cache["k"] } }
        awaitCondition(
            "followers waiting"
        ) { followers.all { it.parked } }
        release.countDown()
        leader.join().assert().isEqualTo("v")
        followers.forEach { it.join().assert().isEqualTo("v") }
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun concurrentMissesAcrossNodesLoadOnceThroughLease() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = "v"
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        afterLoad = {
            loading.countDown()
            release.awaitOrFail("release")
        }
        val onA = fork { cacheA["k"] }
        loading.awaitOrFail("A loading")
        afterLoad = null
        val onB = fork { cacheB["k"] }
        awaitCondition("B waiting on the lease") { nodeB.metrics().leaseWaits == 1L }
        release.countDown()
        onA.join().assert().isEqualTo("v")
        onB.join().assert().isEqualTo("v")
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun leaseWaitTimeoutLoadsWithoutFillingRemote() {
        val impatient = policy().toBuilder().lease(
            LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ZERO)
        ).build()
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache(spec(impatient))
        database["k"] = "v"
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 只挡住第一次回源（A 的）：不按线程名判断，异步视图上回源可能在别的线程上执行
        val first = AtomicBoolean(true)
        afterLoad = { _ ->
            if (first.compareAndSet(true, false)) {
                loading.countDown()
                release.awaitOrFail("release")
            }
        }
        val onA = fork("holder") { cacheA["k"] }
        loading.awaitOrFail("A loading")
        cacheB["k"].assert().isEqualTo("v")
        nodeB.metrics().leaseWaitTimeouts.assert().isEqualTo(1)
        remote("k").assert().isInstanceOf(L1Read.Leased::class.java)
        release.countDown()
        onA.join()
        remoteValue("k").assert().isEqualTo("v")
        loads.get().assert().isEqualTo(2)
    }

    @Test
    fun remoteInvalidationDuringLoadRejectsTheStaleFill() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = "v0"
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        afterLoad = {
            loaded.countDown()
            release.awaitOrFail("release")
        }
        val onA = fork { cacheA["k"] }
        loaded.awaitOrFail("A read the source")
        afterLoad = null
        database["k"] = "v1"
        cacheB.invalidate("k")
        release.countDown()
        onA.join().assert().isEqualTo("v0")
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        nodeA.metrics().fillRejected.assert().isEqualTo(1)
        awaitCondition("A sees the new value") { cacheA["k"] == "v1" }
        cacheB["k"].assert().isEqualTo("v1")
    }

    /**
     * §10 C1：读线程在失效方第一次递增戳之后取戳、在修改 L1 之前读到旧值。第二次递增必须使它的 L2 填充失效。
     */
    @Test
    fun sameNodeInvalidateRacingAReadLeavesNoStaleLocalCopy() {
        val cache = nodeA.cache()
        cache.put("k", "v0")
        database["k"] = "v1"
        val readerParked = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        var reader: Concurrency.Forked<String?>? = null
        backend.afterRead = { _, read ->
            if (backend.issuer() == "reader") {
                readerParked.countDown()
                releaseReader.awaitOrFail("release reader")
            }
            read
        }
        backend.beforeInvalidate = {
            reader = fork("reader") { cache["k"] }
            readerParked.awaitOrFail("reader read the old remote value")
        }
        cache.invalidate("k")
        backend.beforeInvalidate = null
        releaseReader.countDown()
        requireNotNull(reader).join().assert().isEqualTo("v0")
        backend.afterRead = null
        cache["k"].assert().isEqualTo("v1")
    }

    /**
     * §10 v3-1：同一节点两个并发的 put。L2 只能由读 L1 填充，最终必须与 L1 一致。
     */
    @Test
    fun concurrentPutsOnTheSameNodeKeepLocalConsistentWithRemote() {
        val cache = nodeA.cache()
        val firstWritten = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        backend.afterPut = {
            if (backend.issuer() == "first") {
                firstWritten.countDown()
                releaseFirst.awaitOrFail("release first put")
            }
        }
        val first = fork("first") { cache.put("k", "v1") }
        firstWritten.awaitOrFail("first put reached the remote store")
        cache.put("k", "v2")
        releaseFirst.countDown()
        first.join()
        remoteValue("k").assert().isEqualTo("v2")
        cache["k"].assert().isEqualTo("v2")
        nodeB.cache()["k"].assert().isEqualTo("v2")
    }

    @Test
    fun putIsVisibleToTheOtherNode() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = "v0"
        cacheB["k"].assert().isEqualTo("v0")
        cacheA.put("k", "v1")
        awaitCondition("B sees the put") { cacheB["k"] == "v1" }
        cacheA["k"].assert().isEqualTo("v1")
    }

    /**
     * 含未配对 UTF-16 代理的 key：存储 key 与失效帧按 UTF-8 编码，它被替换为 `?`，与另一个 key 撞到同一个存储 key，
     * 其它节点收到帧时淘汰的也是那个 key，而它自己的 L2 副本陈旧到 `localMaxAge`。每个到达 L1 的入口都在读 L1、回源、
     * 写入与广播之前以 [IllegalArgumentException] 拒绝它：信息给出缓存名与下标，未配对的代理转义为 `\uXXXX`，不原样输出 key。
     * 被替换之后会撞上的 key 在 L1 与两个节点的 L2 中都不受影响。
     */
    @Test
    fun unpairedSurrogateKeyIsRejectedBeforeReachingTheRemote() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        val bad = "bad-\uD800"
        val collided = "bad-?"
        database[bad] = "loaded"
        database[collided] = "v1"
        cacheA[collided].assert().isEqualTo("v1")
        cacheB[collided].assert().isEqualTo("v1")
        val counted = listOf(
            backend.reads,
            backend.readAlls,
            backend.fills,
            backend.puts,
            backend.putAlls,
            loads,
            batchLoads
        )
        val counters = { counted.map { it.get() } }
        val before = counters()
        val received = nodeB.metrics().invalidationsReceived
        val adminA = requireNotNull(nodeA.admin(cacheName))
        val adminB = requireNotNull(nodeB.admin(cacheName))
        mapOf<String, () -> Any?>(
            "get" to { cacheA[bad] },
            "get(loader)" to { cacheA.get(bad, loader) },
            "getAll" to { cacheB.getAll(listOf(collided, bad)) },
            "lookup" to { cacheA.lookup(bad) },
            "lookupAll" to { cacheB.lookupAll(listOf(collided, bad)) },
            "put" to { cacheA.put(bad, "v2") },
            "putAll" to { cacheA.putAll(mapOf(collided to "v2", bad to "v2")) },
            "invalidate" to { cacheA.invalidate(bad) },
            "invalidateAll" to { cacheB.invalidateAll(listOf(collided, bad)) },
            "inspect" to { adminA.inspect(bad) },
            "invalidateEncoded" to { adminA.invalidateEncoded(bad) },
            "broadcastEncoded" to { adminB.broadcastEncoded(bad) },
        ).forEach { (operation, call) ->
            val error = runCatching { call() }.exceptionOrNull()
            error.assert().withFailMessage { "$operation: $error" }.isInstanceOf(IllegalArgumentException::class.java)
            requireNotNull(error).message.assert().withFailMessage { "$operation: ${error.message}" }
                .contains("Cache[$cacheName]", "index[4]", "key[bad-\\uD800]")
                .doesNotContain(bad)
        }
        counters().assert().isEqualTo(before)
        listOf(adminA, adminB).forEach { it.pendingInvalidationCount.assert().isZero() }
        // 帧在一个频道上按序投递：标记的帧到达时，被拒绝的调用若发出过帧也已到达
        cacheA.put("marker", "m")
        awaitCondition("B receives the marker") { nodeB.metrics().invalidationsReceived > received }
        nodeB.metrics().invalidationsReceived.assert().isEqualTo(received + 1)
        val reads = backend.reads.get()
        cacheA[collided].assert().isEqualTo("v1")
        cacheB[collided].assert().isEqualTo("v1")
        backend.reads.get().assert().withFailMessage { "the collided key was evicted from L2" }.isEqualTo(reads)
        remoteValue(collided).assert().isEqualTo("v1")
    }

    /**
     * 合法的 Unicode key 照常经失效帧往返（上一个用例的对照）：补充平面字符（正确配对的代理）、中文与空串，
     * 写入与失效都淘汰另一个节点的 L2。
     */
    @Test
    fun wellFormedUnicodeKeysInvalidateAcrossNodes() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        val keys = listOf("😀", "key-𝄞-end", "中文", "")
        keys.forEach { key ->
            database[key] = "v0"
            cacheB[key].assert().isEqualTo("v0")
            cacheA.put(key, "v1")
            awaitCondition("B sees the put of [$key]") { cacheB[key] == "v1" }
            database[key] = "v2"
            cacheA.invalidate(key)
            awaitCondition("B reloads [$key] after its invalidation") { cacheB[key] == "v2" }
        }
        cacheA.invalidateAll(keys)
        keys.forEach { database[it] = "v3" }
        awaitCondition("B reloads every key after the batch invalidation") {
            cacheB.getAll(keys) == keys.associateWith { "v3" }
        }
        listOf(nodeA, nodeB).forEach { node ->
            requireNotNull(node.admin(cacheName)).inspect("😀").remote.value.assert().isEqualTo("v3")
        }
    }

    @Test
    fun putWithExplicitTtlExpires() {
        val cache = nodeA.cache()
        cache.put("k", "v", Duration.ofSeconds(1))
        cache["k"].assert().isEqualTo("v")
        elapse(Duration.ofSeconds(2))
        cache["k"].assert().isNull()
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun ttlFunctionOverridesPolicyTtl() {
        val cache = nodeA.cache(spec { ttlOf = me.ahoo.cocache.core.TtlFunction { _, _ -> Duration.ofSeconds(1) } })
        database["k"] = "v"
        cache["k"]
        elapse(Duration.ofSeconds(2))
        cache["k"]
        loads.get().assert().isEqualTo(2)
    }

    @Test
    fun getAfterOwnInvalidateNeverSharesALoadStartedBefore() {
        val cache = nodeA.cache()
        database["k"] = "v0"
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 只挡住第一次回源（early 的）：不按线程名判断，异步视图上回源可能在别的线程上执行
        val first = AtomicBoolean(true)
        afterLoad = {
            if (first.compareAndSet(true, false)) {
                loaded.countDown()
                release.awaitOrFail("release")
            }
        }
        val early = fork("early") { cache["k"] }
        loaded.awaitOrFail("early load read v0")
        database["k"] = "v1"
        cache.invalidate("k")
        val late = fork("late") { cache["k"] }
        awaitCondition("late joined the in-flight load") { late.parked }
        release.countDown()
        early.join().assert().isEqualTo("v0")
        late.join().assert().isEqualTo("v1")
        loads.get().assert().isEqualTo(2)
    }

    /**
     * 批量读取的读己之写：本节点失效之后的 `getAll` 合并到失效之前开始的在途加载时不接受它的结果，改走单 key 路径重新加载。
     */
    @Test
    fun getAllAfterOwnInvalidateNeverSharesALoadStartedBefore() {
        val cache = nodeA.cache()
        database["k"] = "v0"
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        afterLoad = {
            if (first.compareAndSet(true, false)) {
                loaded.countDown()
                release.awaitOrFail("release")
            }
        }
        val early = fork("early") { cache["k"] }
        loaded.awaitOrFail("early load read v0")
        database["k"] = "v1"
        cache.invalidate("k")
        val late = fork("late") { cache.getAll(listOf("k")) }
        awaitCondition("late joined the in-flight load") { late.parked }
        release.countDown()
        early.join().assert().isEqualTo("v0")
        late.join().assert().isEqualTo(mapOf("k" to "v1"))
        loads.get().assert().isEqualTo(2)
    }

    @Test
    fun loaderFailureReleasesTheLease() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        val failure = IllegalStateException("source down")
        afterLoad = { throw failure }
        runCatching { cacheA["k"] }.exceptionOrNull().assert().isSameAs(failure)
        afterLoad = null
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        database["k"] = "v"
        cacheB["k"].assert().isEqualTo("v")
        nodeB.metrics().leaseWaits.assert().isEqualTo(0)
    }

    @Test
    fun remoteReadFailureDegradesToTheSource() {
        val cache = nodeA.cache()
        database["k"] = "v"
        backend.failAll = true
        cache["k"].assert().isEqualTo("v")
        backend.failAll = false
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        nodeA.metrics().remoteFailures.assert().isGreaterThanOrEqualTo(1)
        nodeA.metrics().degradedFills.assert().isEqualTo(1)
        cache["k"]
        loads.get().assert().isEqualTo(1)
        elapse(policy().degradedLocalMaxAge.plusSeconds(1))
        cache["k"].assert().isEqualTo("v")
        loads.get().assert().isEqualTo(2)
        remoteValue("k").assert().isEqualTo("v")
    }

    /**
     * `Unknown`（RFC 0005 §4.1，V2）：没有数据源的读取（`Store`、外部来源的 `Cache` 上不带 loader 的读取）在 L1 读失败时没有答案：
     * `get` / `getAll` 抛出 [CacheReadException]（`cause` 为原始异常），`lookup` 返回 [Lookup.Unknown]，`lookupAll` 中受影响的 key 为
     * `Unknown`；不写 L2，计入 `readsUnknown` 与 `remoteFailures`。恢复后照常读到 L1 中的值；外部来源的缓存上调用方给出的 loader
     * 是数据源，照常降级回源（`readFailure` 默认 `DEGRADE`）。
     */
    @Test
    fun remoteReadFailureWithoutASourceIsUnknown() {
        val external = nodeA.cache(externalSpec())
        nodeB.cache(externalSpec()).put("k", "v")
        backend.failAll = true
        assertUnknown(external, "k")
        external.get("other") { "fallback" }.assert().isEqualTo("fallback")
        backend.failAll = false
        nodeA.metrics().degradedFills.assert().isEqualTo(1)
        nodeA.metrics().readsUnknown.assert().isEqualTo(5)
        external["k"].assert().isEqualTo("v")
        external.get("k") { "unused" }.assert().isEqualTo("v")
        external.getAll(listOf("k", "other")).assert().isEqualTo(mapOf("k" to "v", "other" to "fallback"))
    }

    /**
     * 同 [remoteReadFailureWithoutASourceIsUnknown]，在 `Store` 上：L1 是唯一副本，读失败没有可降级的答案。
     */
    @Test
    fun remoteReadFailureOfAStoreIsUnknown() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val store = nodeA.store()
        nodeB.store().put("k", "v")
        backend.failAll = true
        assertUnknown(store, "k")
        backend.failAll = false
        nodeA.metrics().readsUnknown.assert().isEqualTo(5)
        nodeA.metrics().remoteFailures.assert().isEqualTo(4)
        store["k"].assert().isEqualTo("v")
        store.lookup("k").assert().isInstanceOf(Lookup.Present::class.java)
    }

    private fun assertUnknown(cache: ReadableCache<String, String>, key: String) {
        listOf<Pair<String, () -> Any?>>(
            "read" to { cache[key] },
            "getAll" to { cache.getAll(listOf(key, "other")) },
        ).forEach { (operation, read) ->
            val error = runCatching { read() }.exceptionOrNull()
            error.assert().isInstanceOf(CacheReadException::class.java)
            (error as CacheReadException).operation.assert().isEqualTo(operation)
            error.cause.assert().isInstanceOf(RemoteUnavailableException::class.java)
        }
        val lookup = cache.lookup(key)
        lookup.assert().isInstanceOf(Lookup.Unknown::class.java)
        (lookup as Lookup.Unknown).error.cause.assert().isInstanceOf(RemoteUnavailableException::class.java)
        cache.lookupAll(listOf(key)).getValue(key).assert().isInstanceOf(Lookup.Unknown::class.java)
    }

    /**
     * 批量读取的部分 `Unknown`（RFC 0005 §4.1）：先完成其余 key，再抛出 [CacheReadException]，异常携带没有答案的 key
     * （[CacheReadException.unknownKeys]）与已得到的部分结果（[CacheReadException.partialResult]）；`lookupAll` 逐 key 给出结果。
     */
    @Test
    fun getAllWithSomeUnknownKeysCompletesTheRestThenThrows() {
        val cache = nodeA.cache(externalSpec())
        cache.put("hit", "h")
        cache["hit"].assert().isEqualTo("h")
        backend.failAll = true
        val error = runCatching { cache.getAll(listOf("hit", "miss")) }.exceptionOrNull()
        error.assert().isInstanceOf(CacheReadException::class.java)
        (error as CacheReadException).unknownKeys.assert().containsExactly("miss")
        error.partialResult.assert().isEqualTo(mapOf("hit" to "h"))
        error.cause.assert().isInstanceOf(RemoteUnavailableException::class.java)
        val lookups = cache.lookupAll(listOf("hit", "miss"))
        (lookups.getValue("hit") as Lookup.Present).value.assert().isEqualTo("h")
        lookups.getValue("miss").assert().isInstanceOf(Lookup.Unknown::class.java)
        backend.failAll = false
    }

    /**
     * `unknownRead = ABSENT`（RFC 0005 §4.1，S16）：没有数据源的读取在 L1 失败时读到不存在（单 key、`lookup` 与批量），不抛出，
     * 计入 `readsUnknown` 与 `remoteFailures`，但不写入任何一层：恢复后照常读到 L1 中的值，故障期间同一个 key 上带 loader 的读取
     * 也不被挡住（它不受 `unknownRead` 影响）。供把“读不到”当作安全答案的数据使用（如撤销表：Redis 故障即视为未撤销）。
     */
    @Test
    fun unknownReadAbsentReadsAbsentWithoutCaching() {
        val cache = nodeA.cache(externalSpec { unknownRead = UnknownReadMode.ABSENT })
        nodeB.cache(externalSpec()).put("k", "v")
        backend.failAll = true
        cache["k"].assert().isNull()
        cache.lookup("k").assert().isInstanceOf(Lookup.Absent::class.java)
        cache.getAll(listOf("k", "other")).assert().isEmpty()
        cache.lookupAll(listOf("k")).getValue("k").assert().isInstanceOf(Lookup.Absent::class.java)
        cache["j"].assert().isNull()
        cache.get("j") { "fallback" }.assert().isEqualTo("fallback")
        backend.failAll = false
        nodeA.metrics().remoteFailures.assert().isEqualTo(6)
        nodeA.metrics().readsUnknown.assert().isEqualTo(6)
        nodeA.metrics().degradedFills.assert().isEqualTo(1)
        cache["k"].assert().isEqualTo("v")
        cache.getAll(listOf("k")).assert().isEqualTo(mapOf("k" to "v"))
        remote("j").assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 显式 `readFailure = THROW`：带数据源的读取在 L1 失败时也是 `Unknown`（`get` 抛出，`lookup` 返回它），不降级回源；
     * `unknownRead` 不作用于带 loader 的读取。
     */
    @Test
    fun readFailureThrowMakesASourcedReadUnknown() {
        val strict = policy().toBuilder().readFailure(ReadFailureMode.THROW).build()
        val cache = nodeA.cache(spec(strict))
        database["k"] = "v"
        backend.failAll = true
        runCatching { cache["k"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        cache.lookup("k").assert().isInstanceOf(Lookup.Unknown::class.java)
        runCatching { cache.get("k") { "fallback" } }.exceptionOrNull()
            .assert().isInstanceOf(CacheReadException::class.java)
        backend.failAll = false
        loads.get().assert().isEqualTo(0)
        cache["k"].assert().isEqualTo("v")
    }

    /**
     * 外部来源的缓存上，绕过 L1 的读取（这里是等待 lease 超时）只能得到“未填充”：对不带 loader 的读取是不存在，不是已决的负缓存，
     * 不挡住随后带 loader 的读取。
     */
    @Test
    fun bypassedReadWithoutLoaderDoesNotBlockACallSiteLoader() {
        val impatient = policy().toBuilder().lease(
            LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ZERO)
        ).build()
        val cacheA = nodeA.cache(externalSpec(impatient))
        val cacheB = nodeB.cache(externalSpec())
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = fork("holder") {
            cacheB.get("k") { _ ->
                loading.countDown()
                release.awaitOrFail("release")
                "b"
            }
        }
        loading.awaitOrFail("B loading")
        cacheA["k"].assert().isNull()
        nodeA.metrics().leaseWaitTimeouts.assert().isEqualTo(1)
        val calls = AtomicInteger()
        cacheA.get("k") { _ ->
            calls.incrementAndGet()
            "a"
        }.assert().isEqualTo("a")
        calls.get().assert().isEqualTo(1)
        release.countDown()
        holder.join().assert().isEqualTo("b")
        remoteValue("k").assert().isEqualTo("b")
    }

    @Test
    fun failedInvalidateIsRetriedAndBypassesTheStaleRemoteMeanwhile() {
        val cache = nodeA.cache()
        cache.put("k", "v0")
        database["k"] = "v1"
        backend.failWrites = true
        cache.invalidate("k")
        cache["k"].assert().isEqualTo("v1")
        remoteValue("k").assert().isEqualTo("v0")
        backend.failWrites = false
        awaitCondition("retry deleted the stale remote value") { remote("k") == L1Read.Miss(false) }
        awaitCondition("retry succeeded") { nodeA.metrics().invalidationRetries >= 1 }
        awaitCondition("local copy refreshed from the source") { cache["k"] == "v1" && remoteValue("k") == "v1" }
    }

    @Test
    fun invalidateFailureThrowsWhenConfigured() {
        val strict = policy().toBuilder().invalidateFailure(InvalidateFailureMode.THROW).build()
        val cache = nodeA.cache(spec(strict))
        backend.failWrites = true
        val error = runCatching { cache.invalidate("k") }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        (error as CacheWriteException).operation.assert().isEqualTo("invalidate")
    }

    /**
     * 外部来源的缓存是派生副本，失效失败默认登记重试（RFC 0005 §4.1，U4）：删除副本在任何时刻都安全。
     * 重试期间读取绕过 L1，只能得到“未填充”：不挡住带 loader 的读取。
     */
    @Test
    fun failedInvalidateOfAnExternallySourcedCacheIsRetried() {
        val cache = nodeA.cache(externalSpec())
        val admin = requireNotNull(nodeA.admin(cacheName))
        cache.put("k", "v0")
        backend.failWrites = true
        cache.invalidate("k")
        admin.pendingInvalidationCount.assert().isEqualTo(1)
        cache["k"].assert().isNull()
        cache.get("k") { "loaded" }.assert().isEqualTo("loaded")
        backend.failWrites = false
        awaitCondition("retry deleted the remote value") { remote("k") == L1Read.Miss(false) }
        awaitCondition("retry succeeded") { admin.pendingInvalidationCount == 0 }
    }

    /**
     * 失效失败模式为 THROW 时，`put` 失败照常抛出 [CacheWriteException]（不论结果是否未知），本地副本已被淘汰。
     */
    @Test
    fun putFailureThrowsAndEvictsLocal() {
        val strict = policy().toBuilder().invalidateFailure(InvalidateFailureMode.THROW).build()
        val cache = nodeA.cache(spec(strict))
        database["k"] = "v0"
        cache["k"]
        backend.failWrites = true
        runCatching { cache.put("k", "v1") }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        backend.failWrites = false
        nodeA.metrics().writeFailures.assert().isEqualTo(1)
        cache["k"].assert().isEqualTo("v0")
        backend.reads.get().assert().isEqualTo(2)
    }

    @Test
    fun reconnectClearsLocalCopies() {
        val cache = nodeA.cache()
        database["k"] = "v0"
        cache["k"]
        writeRemoteSilently("k", TestCodec.encode("v1"))
        cache["k"].assert().isEqualTo("v0")
        simulateReconnect()
        awaitCondition("reset applied") { cache["k"] == "v1" }
        nodeA.metrics().resets.assert().isGreaterThanOrEqualTo(2)
    }

    @Test
    fun lostEventIsBoundedByLocalMaxAge() {
        val cache = nodeA.cache()
        database["k"] = "v0"
        cache["k"]
        writeRemoteSilently("k", TestCodec.encode("v1"))
        cache["k"].assert().isEqualTo("v0")
        elapse(policy().localMaxAge.plusSeconds(1))
        cache["k"].assert().isEqualTo("v1")
    }

    @Test
    fun getAllBatchesRemoteAndSource() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        cacheB.put("remote", "r")
        database["source"] = "s"
        database["local"] = "l"
        cacheA["local"]
        val reads = backend.readAlls.get()
        val result = cacheA.getAll(listOf("local", "remote", "source", "absent", "remote"))
        result.assert().isEqualTo(mapOf("local" to "l", "remote" to "r", "source" to "s"))
        result.keys.toList().assert().containsExactly("local", "remote", "source")
        (backend.readAlls.get() - reads).assert().isEqualTo(1)
        batchLoads.get().assert().isEqualTo(1)
        remoteValue("source").assert().isEqualTo("s")
        (remote("absent") as L1Read.Hit).envelope.assert().isEqualTo(Envelope.Absent)
        cacheA.getAll(listOf("remote", "source", "absent")).assert().isEqualTo(mapOf("remote" to "r", "source" to "s"))
        batchLoads.get().assert().isEqualTo(1)
    }

    @Test
    fun getAllJoinsInFlightSingleLoads() {
        val cache = nodeA.cache()
        database["k"] = "v"
        database["other"] = "o"
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        afterLoad = {
            loading.countDown()
            release.awaitOrFail("release")
        }
        val single = fork { cache["k"] }
        loading.awaitOrFail("single load")
        afterLoad = null
        val batch = fork { cache.getAll(listOf("k", "other")) }
        awaitCondition("batch waiting") { batch.parked }
        release.countDown()
        single.join().assert().isEqualTo("v")
        batch.join().assert().isEqualTo(mapOf("k" to "v", "other" to "o"))
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun getAllDegradesWhenRemoteFails() {
        val cache = nodeA.cache()
        database["k"] = "v"
        backend.failAll = true
        cache.getAll(listOf("k")).assert().isEqualTo(mapOf("k" to "v"))
        backend.failAll = false
        nodeA.metrics().degradedFills.assert().isEqualTo(1)
        remote("k").assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun refreshAheadReloadsOnceAcrossNodes() {
        val refreshing = DefaultPolicies.CACHE.toBuilder()
            .ttl(Duration.ofSeconds(10))
            .ttlJitter(Duration.ZERO)
            .localMaxAge(Duration.ofSeconds(8))
            .refreshWindow(Duration.ofSeconds(5))
            .build()
        val cacheA = nodeA.cache(spec(refreshing))
        val cacheB = nodeB.cache(spec(refreshing))
        database["k"] = "v0"
        cacheA["k"]
        cacheB["k"]
        database["k"] = "v1"
        elapse(Duration.ofSeconds(6))
        cacheA["k"].assert().isEqualTo("v0")
        // 刷新在异步视图上可能在另一个线程上完成：等它结束再触发 B 的刷新
        awaitCondition("A refreshed") { cacheA["k"] == "v1" }
        cacheB["k"]
        awaitCondition("B refreshed") { cacheB["k"] == "v1" }
        loads.get().assert().isEqualTo(2)
        nodeA.metrics().refreshes.assert().isEqualTo(1)
        nodeB.metrics().refreshes.assert().isEqualTo(1)
    }

    /**
     * 没取得刷新令牌的节点退避：A 持有令牌回源期间，B 从 L1 重新填充的副本仍在窗口内。刷新点若不退避，
     * B 的每次命中都会再读一次 L1（A 崩溃时持续到令牌签发超过 `lease.ttl`）。退避后 B 在刷新点之前一次 L1 也不读，
     * 到刷新点时取到 A 写回的新值，仍早于旧值在 L1 到期。
     */
    @Test
    fun refreshWithoutTokenBacksOffInsteadOfPollingRemote() {
        val wait = Duration.ofSeconds(1)
        val refreshing = DefaultPolicies.CACHE.toBuilder()
            .ttl(Duration.ofSeconds(10))
            .ttlJitter(Duration.ZERO)
            .refreshWindow(Duration.ofSeconds(5))
            .lease(LeasePolicy(ttl = Duration.ofSeconds(30), wait = wait))
            .build()
        val cacheA = nodeA.cache(spec(refreshing))
        val cacheB = nodeB.cache(spec(refreshing))
        database["k"] = "v0"
        cacheA["k"]
        cacheB["k"]
        database["k"] = "v1"
        // L1 剩余 4 s，在窗口内
        elapse(Duration.ofSeconds(6))
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        afterLoad = {
            loading.countDown()
            release.awaitOrFail("release the refresh load")
        }
        val holder = fork("refresh-holder") { cacheA["k"] }
        loading.awaitOrFail("A took the refresh token and is loading")
        afterLoad = null

        cacheB["k"].assert().isEqualTo("v0")
        // B 没取得令牌，只从 L1 重新填充 L2：新副本的刷新点退避到 lease.wait 之后，且早于 L1 到期
        awaitCondition("B refilled with a backed-off refresh point") {
            val refreshAt = localCopyOf(nodeB, "k")?.refreshAt
            refreshAt != null && refreshAt.toEpochMilli() > clock.nowMillis()
        }
        val refilled = requireNotNull(localCopyOf(nodeB, "k"))
        val refreshAt = requireNotNull(refilled.refreshAt).toEpochMilli()
        (refreshAt - clock.nowMillis()).assert().isGreaterThanOrEqualTo(wait.toMillis())
        refreshAt.assert().isLessThan(refilled.expiresAt.toEpochMilli())
        // 上界：刷新点之前 B 的命中不读 L1，也不再发起刷新
        val reads = backend.reads.get()
        repeat(HITS) { cacheB["k"].assert().isEqualTo("v0") }
        (backend.reads.get() - reads).assert().isEqualTo(0)
        nodeB.metrics().refreshes.assert().isEqualTo(1)

        release.countDown()
        holder.join().assert().isEqualTo("v0")
        awaitCondition("A's refresh filled L1") { remoteValue("k") == "v1" }
        // fill 不广播：B 到刷新点时再读一次 L1，取到新值（新值在窗口外，不取令牌、不回源）
        elapse(Duration.ofMillis(refreshAt - clock.nowMillis()))
        val beforePickup = backend.reads.get()
        awaitCondition("B picked up the refreshed value") { cacheB["k"] == "v1" }
        clock.nowMillis().assert().isLessThan(refilled.expiresAt.toEpochMilli())
        (backend.reads.get() - beforePickup).assert().isEqualTo(1)
        nodeB.metrics().refreshes.assert().isEqualTo(2)
        loads.get().assert().isEqualTo(2)
    }

    /**
     * 寿命不长于刷新窗口的值（`ttlOf` 或 `put(k, v, ttl)` 给出）不参与提前刷新：副本一出生就在窗口内，若设刷新点，
     * 持有令牌的节点每次写回之后（`fill` 清零令牌）又能取得新令牌，每次命中都回源一次。值照常到期、重新加载。
     */
    @Test
    fun valuesNotOutlivingTheRefreshWindowAreNotRefreshed() {
        val short = Duration.ofSeconds(4)
        val refreshing = DefaultPolicies.CACHE.toBuilder()
            .ttl(Duration.ofSeconds(10))
            .ttlJitter(Duration.ZERO)
            .refreshWindow(Duration.ofSeconds(5))
            .build()
        val shortLived = spec(refreshing) { ttlOf = TtlFunction { _, _ -> short } }
        val cacheA = nodeA.cache(shortLived)
        val cacheB = nodeB.cache(shortLived)
        database["k"] = "v"
        database["p"] = "x"
        cacheA["k"].assert().isEqualTo("v")
        cacheB["k"].assert().isEqualTo("v")
        cacheA.put("p", "x", short)
        cacheB["p"].assert().isEqualTo("x")
        // 上界：除了第一次未命中，命中不回源
        repeat(HITS) {
            cacheA["k"]
            cacheB["k"]
            cacheB["p"]
        }
        loads.get().assert().isEqualTo(1)
        nodeA.metrics().refreshes.assert().isEqualTo(0)
        nodeB.metrics().refreshes.assert().isEqualTo(0)
        localCopyOf(nodeA, "k")?.refreshAt.assert().isNull()
        localCopyOf(nodeB, "p")?.refreshAt.assert().isNull()

        elapse(short)
        cacheA["k"].assert().isEqualTo("v")
        loads.get().assert().isEqualTo(2)
    }

    /**
     * 带调用级 loader 的读取（`get(key, loader)`，如 Spring 的 `@Cacheable(sync = true)`）在 L2 命中进入刷新窗口时同普通读取触发提前刷新：
     * 这次命中立即返回旧值，刷新用声明的数据源，调用级 loader 只用于未命中。副本由声明的数据源或调用级 loader 填入都一样。
     * 轮询也用调用级 loader 读取（普通读取自己就会触发刷新）。
     */
    @Test
    fun callLoaderHitsRefreshAheadThroughTheDeclaredSource() {
        val cache = nodeA.cache(spec(callLoaderRefreshPolicy()))
        val callLoads = AtomicInteger()
        val callLoader = CacheLoader<String, String> { key ->
            callLoads.incrementAndGet()
            database[key]
        }
        database["k"] = "v0"
        database["c"] = "c0"
        cache["k"].assert().isEqualTo("v0")
        cache.get("c", callLoader).assert().isEqualTo("c0")
        loads.get().assert().isEqualTo(1)
        callLoads.get().assert().isEqualTo(1)

        database["k"] = "v1"
        database["c"] = "c1"
        elapse(Duration.ofSeconds(6))
        cache.get("k", callLoader).assert().isEqualTo("v0")
        cache.get("c", callLoader).assert().isEqualTo("c0")
        awaitCondition("the hits refreshed through the declared source") {
            cache.get("k", callLoader) == "v1" && cache.get("c", callLoader) == "c1"
        }
        loads.get().assert().isEqualTo(3)
        callLoads.get().assert().withFailMessage { "the call-level loader only serves misses" }.isEqualTo(1)
        nodeA.metrics().refreshes.assert().isEqualTo(2)
    }

    /**
     * 对照：外部来源的缓存没有声明的数据源，带调用级 loader 的命中进入刷新窗口也不刷新；值照常到期，之后由调用级 loader 重新加载。
     */
    @Test
    fun callLoaderHitsOfAnExternallySourcedCacheDoNotRefresh() {
        val cache = nodeA.cache(externalSpec(callLoaderRefreshPolicy()))
        val callLoads = AtomicInteger()
        val callLoader = CacheLoader<String, String> { key ->
            callLoads.incrementAndGet()
            database[key]
        }
        database["k"] = "v0"
        cache.get("k", callLoader).assert().isEqualTo("v0")
        database["k"] = "v1"
        elapse(Duration.ofSeconds(6))
        repeat(HITS) { cache.get("k", callLoader).assert().isEqualTo("v0") }
        nodeA.metrics().refreshes.assert().isEqualTo(0)
        callLoads.get().assert().isEqualTo(1)
        elapse(Duration.ofSeconds(5))
        cache.get("k", callLoader).assert().isEqualTo("v1")
        callLoads.get().assert().isEqualTo(2)
        loads.get().assert().isZero()
    }

    /**
     * 带调用级 loader 的读取的刷新用例的策略：寿命 10 s，窗口 5 s，L2 限龄 8 s。
     */
    private fun callLoaderRefreshPolicy(): CachePolicy = DefaultPolicies.CACHE.toBuilder()
        .ttl(Duration.ofSeconds(10))
        .ttlJitter(Duration.ZERO)
        .localMaxAge(Duration.ofSeconds(8))
        .refreshWindow(Duration.ofSeconds(5))
        .build()

    /**
     * 节点上 [key] 的 L2 副本（只读快照，不影响 L2）。注意它也读一次 L1（计入 [HookedBackend.reads]）。
     */
    private fun localCopyOf(node: CacheRuntime, key: String): KeyInspection.LocalCopy? =
        requireNotNull(node.admin(cacheName)).inspect(key).local

    @Test
    fun recursiveLoadOfTheSameKeyFailsFast() {
        lateinit var cache: Cache<String, String>
        cache = nodeA.cache(
            spec {
                loader = CacheLoader { key -> cache[key] }
            }
        )
        runCatching { cache["k"] }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
    }

    /**
     * I-6：同步 API 在调用方线程上执行阻塞的 loader（事务、MDC 等线程局部状态因此可用），单 key、批量与调用级 loader 都是如此，
     * 即使存储在别的线程（模拟 L1 客户端的 I/O 线程）上交出结果。只在 [CacheAccess.SYNC] 下运行：异步与挂起变体在运行时的执行器上
     * 执行阻塞的 loader，属于核心的语义，由核心自己的测试覆盖。
     */
    @Test
    fun syncLoaderRunsOnTheCallingThread() {
        assumeTrue(access == CacheAccess.SYNC, "only the sync API runs the loader on the caller's thread")
        val io = Executors.newSingleThreadExecutor { Thread(it, "tck-io").apply { isDaemon = true } }
        backend.ioExecutor = io
        try {
            val threads = ConcurrentLinkedQueue<String>()
            val cache = nodeA.cache(
                spec {
                    loader = object : CacheLoader<String, String> {
                        override fun load(key: String): String {
                            threads.add(Concurrency.currentThreadName())
                            return "v"
                        }

                        override fun loadAll(keys: Collection<String>): Map<String, String> {
                            threads.add(Concurrency.currentThreadName())
                            return keys.associateWith { "v" }
                        }
                    }
                }
            )
            fork("tck-caller") {
                cache["k"]
                cache.getAll(listOf("a", "b"))
                cache.get("c") {
                    threads.add(Concurrency.currentThreadName())
                    "v"
                }
            }.join()
            threads.toList().assert().containsExactly("tck-caller", "tck-caller", "tck-caller")
        } finally {
            backend.ioExecutor = null
            io.shutdownNow()
        }
    }

    @Test
    fun keyFilterShortCircuits() {
        val cache = nodeA.cache(spec { keyFilter = me.ahoo.cocache.core.KeyFilter { true } })
        database["k"] = "v"
        cache["k"].assert().isNull()
        cache.getAll(listOf("k")).assert().isEmpty()
        loads.get().assert().isEqualTo(0)
        backend.reads.get().assert().isEqualTo(0)
        nodeA.metrics().filtered.assert().isEqualTo(2)
    }

    @Test
    fun corruptPayloadIsReclaimedAndReloaded() {
        val cache = nodeA.cache()
        database["k"] = "v"
        writeRemoteSilently("k", TestCodec.CORRUPT)
        cache["k"].assert().isEqualTo("v")
        nodeA.metrics().reclaims.assert().isEqualTo(1)
        remoteValue("k").assert().isEqualTo("v")
    }

    /**
     * 没有数据源时 L1 是唯一副本：读不懂的内容（格式 0，如其它程序写入的值；同版本解码失败）默认 `FAIL`，
     * 抛出 [CacheReadException]（cause 为 [ForeignFormatException]），不回收、不写 L2，降级读失败模式下同样抛出。
     */
    @Test
    fun foreignContentWithoutLoaderFailsAndIsKept() {
        val cache = nodeA.cache(externalSpec())
        writeRemoteSilently("k", TestCodec.CORRUPT)
        repeat(2) {
            val error = runCatching { cache["k"] }.exceptionOrNull()
            error.assert().isInstanceOf(CacheReadException::class.java)
            val cause = requireNotNull(error?.cause as? ForeignFormatException)
            cause.storageKey.assert().isEqualTo(storageKey("k"))
            // 同版本内容解码失败：格式为本节点的版本
            cause.format.assert().isEqualTo(backend.format)
        }
        nodeA.metrics().unknownFormats.assert().isEqualTo(2)
        nodeA.metrics().reclaims.assert().isEqualTo(0)
        val kept = remote("k") as L1Read.Hit
        (kept.envelope as Envelope.Scalar).bytes.assert().isEqualTo(TestCodec.CORRUPT)
        runCatching { cache.lookup("k") }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
    }

    /**
     * 格式 0（不是本协议写入的内容）与同版本解码失败走同一条判定。
     */
    @Test
    fun unrecognizedFormatWithoutLoaderFails() {
        val cache = nodeA.cache(externalSpec())
        cache.put("k", "v")
        backend.afterRead = { _, read -> if (read is L1Read.Hit) read.copy(format = 0) else read }
        val error = runCatching { cache["k"] }.exceptionOrNull()
        backend.afterRead = null
        error.assert().isInstanceOf(CacheReadException::class.java)
        requireNotNull(error?.cause as? ForeignFormatException).format.assert().isEqualTo(0)
        remoteValue("k").assert().isEqualTo("v")
    }

    /**
     * 批量读取：读不懂的 key 使整批失败，其余 key 申请到的 lease 先释放（不留占位）。
     */
    @Test
    fun foreignContentFailsGetAllWithoutLeakingLeases() {
        val cache = nodeA.cache(externalSpec())
        cache.put("ok", "v")
        writeRemoteSilently("bad", TestCodec.CORRUPT)
        writeRemoteSilently("worse", TestCodec.CORRUPT)
        val error = runCatching {
            cache.getAll(listOf("ok", "bad", "absent", "worse"))
        }.exceptionOrNull()
        error.assert().isInstanceOf(CacheReadException::class.java)
        // 以其中一个读不懂的 key 失败，两个都计数且保留
        requireNotNull(
            error?.cause as? ForeignFormatException
        ).storageKey.assert().isIn(storageKey("bad"), storageKey("worse"))
        nodeA.metrics().unknownFormats.assert().isEqualTo(2)
        remote("absent").assert().isEqualTo(L1Read.Miss(false))
        remote("bad").assert().isInstanceOf(L1Read.Hit::class.java)
        remote("worse").assert().isInstanceOf(L1Read.Hit::class.java)
        cache["ok"].assert().isEqualTo("v")
    }

    /**
     * 批量读取中损坏的 key 回收之后重新加载失败：整批以这个失败结束，但其余 key 照常回源并凭 lease 写回 L1，不留下 lease：
     * 先完成整批，再回收。
     */
    @Test
    fun corruptKeyFailingItsReloadStillCompletesTheRestOfTheBatch() {
        val failure = IllegalStateException("reload failed")
        val cache = nodeA.cache(
            spec { loader = CacheLoader { key -> if (key == "bad") throw failure else database[key] } }
        )
        database["good"] = "v"
        writeRemoteSilently("bad", TestCodec.CORRUPT)
        runCatching { cache.getAll(listOf("bad", "good")) }.exceptionOrNull().assert().isSameAs(failure)
        remoteValue("good").assert().isEqualTo("v")
        nodeA.metrics().reclaims.assert().isEqualTo(1)
    }

    /**
     * 读不懂的内容按这次读取有没有数据源决定：外部来源的缓存经 `get(key, loader)` 读取时有数据源，照常回收并回源。
     */
    @Test
    fun foreignContentIsReclaimedWhenTheCallSuppliesALoader() {
        val cache = nodeA.cache(externalSpec())
        writeRemoteSilently("k", TestCodec.CORRUPT)
        cache.get("k") { "loaded" }.assert().isEqualTo("loaded")
        remoteValue("k").assert().isEqualTo("loaded")
    }

    /**
     * 格式号低于本格式的内容（不是本格式写入的）是读不懂的内容：有数据源的读取按读到的格式计算指纹、条件回收后回源并写回 L1。
     */
    @Test
    fun lowerFormatContentIsReclaimedAndReloaded() {
        assumeTrue(
            writeForeign(storageKey("k"), TestCodec.encode("legacy")),
            "the backend cannot write lower-format content"
        )
        val cache = nodeA.cache()
        database["k"] = "v"
        cache["k"].assert().isEqualTo("v")
        nodeA.metrics().reclaims.assert().isEqualTo(1)
        remoteValue("k").assert().isEqualTo("v")
        nodeB.cache(externalSpec())["k"].assert().isEqualTo("v")
    }

    /**
     * 回收比较的指纹带读到的格式（#834）：读到旧格式的 `hello` 之后、回收之前，`put("k", "hello")` 完成写入与广播，
     * L1 中的字节（载荷）与读到的相同，格式却不同。恢复后的回收必须失败：迟到的回源只返回给这次读取，不得把它的旧快照写回 L1，
     * 别的节点读到修复写入的值。
     */
    @Test
    fun lowerFormatReclaimNeverTakesARepairWithTheSameBytes() = assertLowerFormatReclaimKeepsTheRepair("hello")

    /**
     * 对照：修复写入的文本不同，内容本身就不同，回收同样失败。
     */
    @Test
    fun lowerFormatReclaimNeverTakesARepairWithOtherBytes() = assertLowerFormatReclaimKeepsTheRepair("repaired")

    private fun assertLowerFormatReclaimKeepsTheRepair(repair: String) {
        assumeTrue(
            writeForeign(storageKey("k"), TestCodec.encode("hello")),
            "the backend cannot write lower-format content"
        )
        val reader = nodeA.cache()
        val peer = nodeB.cache(externalSpec())
        database["k"] = "old-source-snapshot"
        val readParked = CountDownLatch(1)
        val resumeRead = CountDownLatch(1)
        backend.afterRead = { key, read ->
            val lowerFormat = read is L1Read.Hit && read.format < backend.format
            if (backend.issuer() == "legacy-reader" && key == storageKey("k") && lowerFormat) {
                readParked.countDown()
                resumeRead.awaitOrFail("resume the legacy read")
            }
            read
        }
        val legacy = fork("legacy-reader") { reader["k"] }
        readParked.awaitOrFail("legacy read saw the lower-format content")
        reader.put("k", repair)
        resumeRead.countDown()
        // 旧读取可以返回它自己的回源结果，但不能写入 L1
        legacy.join().assert().isEqualTo("old-source-snapshot")
        backend.afterRead = null
        nodeA.metrics().reclaims.assert().isEqualTo(1)
        remoteValue("k").assert().isEqualTo(repair)
        peer["k"].assert().isEqualTo(repair)
        reader["k"].assert().isEqualTo(repair)
    }

    @Test
    fun newerFormatBypassesRemoteWithoutOverwriting() {
        val cache = nodeA.cache()
        nodeB.cache().put("k", "new-format")
        database["k"] = "v"
        backend.afterRead = { _, read -> if (read is L1Read.Hit) read.copy(format = read.format + 1) else read }
        cache["k"].assert().isEqualTo("v")
        backend.afterRead = null
        nodeA.metrics().unknownFormats.assert().isEqualTo(1)
        remoteValue("k").assert().isEqualTo("new-format")
    }

    /**
     * 外部来源的缓存读到更高版本写入的值：绕过 L1，只能得到“未填充”（不带 loader 的读取看到不存在），不覆盖、不回收；
     * 调用方给出的 loader 照常回源（RFC 0005 §4.1）。
     */
    @Test
    fun newerFormatOfAnExternallySourcedCacheIsUnfilled() {
        val cache = nodeA.cache(externalSpec())
        cache.put("k", "v")
        backend.afterRead = { _, read -> if (read is L1Read.Hit) read.copy(format = read.format + 1) else read }
        cache["k"].assert().isNull()
        cache.get("k") { "loaded" }.assert().isEqualTo("loaded")
        backend.afterRead = null
        nodeA.metrics().unknownFormats.assert().isEqualTo(2)
        remoteValue("k").assert().isEqualTo("v")
    }

    /**
     * `Store` 读到更高版本写入的值（混合版本滚动升级期间）与读不懂的内容一样 `FAIL`：
     * L1 是唯一副本，当作不存在会让调用方把“不存在”当作真实结果。抛出、保留 key、不写 L2、计入 `unknownFormats`。
     */
    @Test
    fun newerFormatOfAStoreFailsAndIsKept() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = nodeA.store()
        cache.put("k", "v")
        cache.put("other", "o")
        val newer: (L1Read) -> L1Read = { read ->
            if (read is L1Read.Hit) {
                read.copy(
                    format = read.format + 1
                )
            } else {
                read
            }
        }
        backend.afterRead = { _, read -> newer(read) }
        backend.afterReadAll = { reads -> reads.mapValues { newer(it.value) } }
        val error = runCatching { cache["k"] }.exceptionOrNull()
        runCatching { cache.lookup("k") }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        val batchError = runCatching { cache.getAll(listOf("k", "other", "absent")) }.exceptionOrNull()
        backend.afterRead = null
        backend.afterReadAll = null
        error.assert().isInstanceOf(CacheReadException::class.java)
        val cause = requireNotNull(error?.cause as? ForeignFormatException)
        cause.storageKey.assert().isEqualTo(storageKey("k"))
        cause.format.assert().isEqualTo(backend.format + 1)
        batchError.assert().isInstanceOf(CacheReadException::class.java)
        batchError?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        // 单 key 两次 + 批量两个 key
        nodeA.metrics().unknownFormats.assert().isEqualTo(4)
        nodeA.metrics().reclaims.assert().isEqualTo(0)
        remote("absent").assert().isEqualTo(L1Read.Miss(false))
        remoteValue("k").assert().isEqualTo("v")
        // 没有写入 L2：恢复后照常读到
        cache["k"].assert().isEqualTo("v")
        cache.getAll(listOf("k", "other")).assert().isEqualTo(mapOf("k" to "v", "other" to "o"))
    }

    @Test
    fun loadConcurrencyLimitRejects() {
        val limited = policy().toBuilder().load(LoadPolicy(maxConcurrent = 1, acquireTimeout = Duration.ZERO)).build()
        val cache = nodeA.cache(spec(limited))
        database["a"] = "a"
        database["b"] = "b"
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        afterLoad = {
            if (it == "a") {
                loading.countDown()
                release.awaitOrFail("release")
            }
        }
        val first = fork { cache["a"] }
        loading.awaitOrFail("a loading")
        runCatching { cache["b"] }.exceptionOrNull().assert().isInstanceOf(SourceLoadRejectedException::class.java)
        nodeA.metrics().loadRejected.assert().isEqualTo(1)
        release.countDown()
        first.join().assert().isEqualTo("a")
        remote("b").assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun cacheWithoutLoaderReleasesLeaseAndReturnsNull() {
        val cache = nodeA.cache(externalSpec())
        cache["k"].assert().isNull()
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        cache.put("k", "v")
        cache["k"].assert().isEqualTo("v")
        cache.getAll(listOf("k", "absent")).assert().isEqualTo(mapOf("k" to "v"))
    }

    /**
     * 外部来源的缓存上不带 loader 的批量读取与单 key 读取规则相同（RFC 0005 §4.1）：L1 没有确认不存在的 key（其它节点持有 lease、
     * 更高格式）绕过 L1，结果是以 `degradedLocalMaxAge` 限龄的“未填充”，计入 `degradedFills`；L1 确认不存在的 key 仍以 `localMaxAge`
     * 与 `absentTtl` 限龄。
     */
    @Test
    fun batchReadsWithoutALoaderBypassLikeSingleKeyReads() {
        val noWait = policy().toBuilder().lease(LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ZERO)).build()
        val cache = nodeA.cache(externalSpec(noWait))
        listOf("leased-get", "leased-all").forEach { key ->
            val held = Suspending.await {
                backend.delegate.store(Keyspace("cocache:", cacheName))
                    .read(storageKey(key), LeaseRequest(Token(NodeId.random(), 1), Duration.ofSeconds(30)), null, null)
            }
            held.assert().isEqualTo(L1Read.Miss(true))
        }
        cache.put("newer-get", "v")
        cache.put("newer-all", "v")
        val newer: (L1Read) -> L1Read = { read ->
            if (read is L1Read.Hit) read.copy(format = read.format + 1) else read
        }
        backend.afterRead = { _, read -> newer(read) }
        backend.afterReadAll = { reads -> reads.mapValues { newer(it.value) } }
        cache["leased-get"].assert().isNull()
        cache["newer-get"].assert().isNull()
        cache.getAll(listOf("leased-all", "newer-all", "absent-all")).assert().isEmpty()
        backend.afterRead = null
        backend.afterReadAll = null

        val degradedBound = Instant.ofEpochMilli(clock.nowMillis()).plus(noWait.degradedLocalMaxAge)
        listOf("leased-get", "newer-get", "leased-all", "newer-all").forEach { key ->
            val local = requireNotNull(localCopyOf(nodeA, key))
            local.state.assert().isEqualTo(KeyInspection.LocalState.UNFILLED)
            local.expiresAt.assert()
                .withFailMessage { "$key bypassed L1 without a loader: its copy is capped at degradedLocalMaxAge" }
                .isBeforeOrEqualTo(degradedBound)
        }
        requireNotNull(localCopyOf(nodeA, "absent-all")).expiresAt.assert()
            .withFailMessage { "a confirmed absence keeps the localMaxAge and absentTtl cap" }
            .isAfter(degradedBound)
        nodeA.metrics().degradedFills.assert().isEqualTo(4)
        nodeA.metrics().unknownFormats.assert().isEqualTo(2)
    }

    /**
     * `Store` 的读取不申请 lease（RFC 0005 §4.1，C11）：L1 中没有就是 `Absent`，L1 中是 lease 占位（派生缓存的读取留下的）也是 `Absent`，
     * 不等待、不释放别人的 lease。`Absent` 受戳保护进入 L2，之后的写入照常可见。
     */
    @Test
    fun storeReadsNeverTakeALease() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val store = nodeA.store()
        store["k"].assert().isNull()
        store.lookup("k").assert().isInstanceOf(Lookup.Absent::class.java)
        store.getAll(listOf("k", "j")).assert().isEmpty()
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        remote("j").assert().isEqualTo(L1Read.Miss(false))
        val held = Suspending.await {
            backend.delegate.store(Keyspace("cocache:", cacheName))
                .read(storageKey("leased"), LeaseRequest(Token(NodeId.random(), 1), Duration.ofSeconds(30)), null, null)
        }
        held.assert().isEqualTo(L1Read.Miss(true))
        store["leased"].assert().isNull()
        remote("leased").assert().isInstanceOf(L1Read.Leased::class.java)
        nodeA.metrics().leaseWaits.assert().isEqualTo(0)
        nodeB.store().put("k", "v")
        awaitCondition("the put is visible") { store["k"] == "v" }
    }

    /**
     * 声明视图接口（RFC 0005 §4.2）：继承 `SuspendingCache` 与 `AsyncCache` 的接口经 `CoCacheFactory` 创建，代理转发给引擎的挂起与
     * 异步视图；跨节点的回源、写入与失效同直接经视图访问。
     */
    @Test
    fun declaredCacheViewsForwardToTheEngine() {
        val suspendingA = CoCacheFactory(nodeA).create(SuspendingStringCache::class.java, spec())
        val asyncB = CoCacheFactory(nodeB).create(AsyncStringCache::class.java, spec())
        database["k"] = "v"
        Suspending.await { suspendingA.get("k") }.assert().isEqualTo("v")
        asyncB.get("k").join().assert().isEqualTo("v")
        loads.get().assert().isEqualTo(1)
        Suspending.await { suspendingA.put("k", "v2") }
        awaitCondition("the put reaches nodeB") { asyncB.get("k").join() == "v2" }
        database["k"] = "v3"
        asyncB.invalidate("k").join()
        awaitCondition("the invalidation reaches nodeA") { Suspending.await { suspendingA.get("k") } == "v3" }
        asyncB.sync().assert().isSameAs(requireNotNull(nodeB.cache(cacheName)))
        suspendingA.async().assert().isSameAs(requireNotNull(nodeA.cache(cacheName)).async())
    }

    /**
     * 声明视图接口：继承 `SuspendingStore` 与 `AsyncStore` 的接口转发给权威副本的视图。
     */
    @Test
    fun declaredStoreViewsForwardToTheEngine() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val suspendingA = CoCacheFactory(nodeA).create(SuspendingStringStore::class.java, storeSpec())
        val asyncB = CoCacheFactory(nodeB).create(AsyncStringStore::class.java, storeSpec())
        Suspending.await { suspendingA.put("k", "v") }
        asyncB.get("k").join().assert().isEqualTo("v")
        asyncB.delete("k").join()
        awaitCondition("the delete reaches nodeA") { Suspending.await { suspendingA.get("k") } == null }
        Suspending.await { suspendingA.lookup("k") }.assert().isInstanceOf(Lookup.Absent::class.java)
    }

    /**
     * 声明视图接口的用例所用的接口。
     */
    interface SuspendingStringCache : SuspendingCache<String, String>

    interface AsyncStringCache : AsyncCache<String, String>

    interface SuspendingStringStore : SuspendingStore<String, String>

    interface AsyncStringStore : AsyncStore<String, String>

    /**
     * 写入失败按客户端能观察到的事实分类（RFC 0005 §4.3、§4.6）：存储在写出之前拒绝的命令（`L1RejectedException`）确定未执行，
     * [CacheWriteException.outcomeUnknown] 为 `false`；其余失败（这里是写入时的故障）结果未知，为 `true`。
     */
    @Test
    fun writeFailuresReportWhetherTheOutcomeIsKnown() {
        // 默认的 RETRY：确定未执行的写入不补偿，照常抛出
        val cache = nodeA.cache()
        backend.rejectWrites = true
        listOf<Pair<String, () -> Unit>>(
            "put" to { cache.put("k", "v") },
            "putAll" to { cache.putAll(mapOf("k" to "v")) },
        ).forEach { (operation, write) ->
            val error = runCatching { write() }.exceptionOrNull()
            error.assert().isInstanceOf(CacheWriteException::class.java)
            (error as CacheWriteException).operation.assert().isEqualTo(operation)
            error.outcomeUnknown.assert().isFalse()
        }
        backend.rejectWrites = false
        requireNotNull(nodeA.admin(cacheName)).pendingInvalidationCount.assert().isEqualTo(0)
        // 失效失败模式为 THROW：结果未知的写入照常抛出（另一个节点上的同名缓存，同一个 namespace）
        val strict = nodeB.cache(spec(policy().toBuilder().invalidateFailure(InvalidateFailureMode.THROW).build()))
        backend.failWrites = true
        val unknown = runCatching { strict.put("k", "v") }.exceptionOrNull()
        backend.failWrites = false
        (unknown as CacheWriteException).outcomeUnknown.assert().isTrue()
        remote("k").assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 派生副本的 `put` / `putAll` 结果未知、失效失败模式为默认的 RETRY 时（RFC 0005 §4.6，V7、U8）：登记补偿失效，不抛出，
     * 与重试的 `invalidate` 相同。重试期间本节点的未命中绕过 L1、从数据源加载；恢复之后补偿删除 L1 中的 key，读取从数据源重新加载。
     */
    @Test
    fun outcomeUnknownPutsAreCompensatedUnderRetry() {
        val cache = nodeA.cache()
        val admin = requireNotNull(nodeA.admin(cacheName))
        database["k"] = "v0"
        cache["k"].assert().isEqualTo("v0")
        database["k"] = "v1"
        database["a"] = "a1"
        backend.failWrites = true
        cache.put("k", "v1")
        cache.putAll(mapOf("a" to "a1"))
        nodeA.metrics().writeFailures.assert().isEqualTo(2)
        admin.pendingInvalidationCount.assert().isEqualTo(2)
        // 重试期间读取绕过 L1（其中仍是旧值）
        cache["k"].assert().isEqualTo("v1")
        backend.failWrites = false
        awaitCondition("the compensation retried") { admin.pendingInvalidationCount == 0 }
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        remote("a").assert().isEqualTo(L1Read.Miss(false))
        cache["a"].assert().isEqualTo("a1")
    }

    /**
     * `Store.delete` 同步完成：删除并广播，其它节点随即读到不存在。L1 失败时抛出 [CacheWriteException]，从不排队重试
     * （RFC 0005 §4.1，A.D2）：L1 中的值原样保留，没有登记的重试。
     */
    @Test
    fun storeDeleteIsSynchronousAndNeverRetried() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val store = nodeA.store()
        val other = nodeB.store()
        store.put("k", "v")
        other["k"].assert().isEqualTo("v")
        store.delete("k")
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        awaitCondition("the deletion is visible") { other["k"] == null }
        store.put("k", "v2")
        backend.failWrites = true
        val error = runCatching { store.delete("k") }.exceptionOrNull()
        backend.failWrites = false
        error.assert().isInstanceOf(CacheWriteException::class.java)
        (error as CacheWriteException).operation.assert().isEqualTo("delete")
        requireNotNull(nodeA.admin(cacheName)).pendingInvalidationCount.assert().isEqualTo(0)
        remoteValue("k").assert().isEqualTo("v2")
        nodeA.metrics().invalidationRetries.assert().isEqualTo(0)
    }

    /**
     * I-D：权威副本的写入从不补偿。结果未知的 `Store.put` / `putAll` 抛出 `outcomeUnknown = true` 的 [CacheWriteException]，
     * 不登记失效重试：L1 是唯一副本，延迟的删除会删掉之后写入的值（§7）。派生副本的对照见 [outcomeUnknownPutsAreCompensatedUnderRetry]。
     */
    @Test
    fun outcomeUnknownStorePutsThrowAndAreNeverCompensated() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val store = nodeA.store()
        backend.failWrites = true
        listOf<Pair<String, () -> Unit>>(
            "put" to { store.put("k", "v1") },
            "putAll" to { store.putAll(mapOf("k" to "v1")) },
        ).forEach { (operation, write) ->
            val error = runCatching { write() }.exceptionOrNull()
            error.assert().isInstanceOf(CacheWriteException::class.java)
            (error as CacheWriteException).operation.assert().isEqualTo(operation)
            error.outcomeUnknown.assert().isTrue()
        }
        backend.failWrites = false
        nodeB.store().put("k", "v2")
        requireNotNull(nodeA.admin(cacheName)).pendingInvalidationCount.assert().isEqualTo(0)
        nodeA.metrics().invalidationRetries.assert().isEqualTo(0)
        remoteValue("k").assert().isEqualTo("v2")
    }

    @Test
    fun invalidateAllEvictsEveryKeyOnBothNodes() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["a"] = "a0"
        database["b"] = "b0"
        cacheB.getAll(listOf("a", "b"))
        database["a"] = "a1"
        database["b"] = "b1"
        cacheA.invalidateAll(listOf("a", "b", "a"))
        cacheA.invalidateAll(emptyList())
        awaitCondition("B sees new values") { cacheB.getAll(listOf("a", "b")) == mapOf("a" to "a1", "b" to "b1") }
    }

    @Test
    fun putAllIsVisibleToTheOtherNodeInOneBatch() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["a"] = "a0"
        database["b"] = "b0"
        cacheB["a"].assert().isEqualTo("a0")
        cacheB["b"].assert().isEqualTo("b0")
        cacheA.putAll(mapOf("a" to "a1", "b" to "b1", "c" to "c1"))
        backend.putAlls.get().assert().isEqualTo(1)
        remoteValue("a").assert().isEqualTo("a1")
        remoteValue("c").assert().isEqualTo("c1")
        awaitCondition("B sees the batch") { cacheB["a"] == "a1" && cacheB["b"] == "b1" }
        cacheB["c"].assert().isEqualTo("c1")
        cacheA.getAll(listOf("a", "b", "c")).assert().isEqualTo(mapOf("a" to "a1", "b" to "b1", "c" to "c1"))
        loads.get().assert().isEqualTo(2)
    }

    @Test
    fun emptyPutAllIsANoOp() {
        val cache = nodeA.cache()
        cache.putAll(emptyMap())
        cache.putAll(emptyMap(), Duration.ofSeconds(1))
        backend.putAlls.get().assert().isEqualTo(0)
        nodeA.metrics().writeFailures.assert().isEqualTo(0)
    }

    @Test
    fun putAllTtlFollowsPutRules() {
        val cache = nodeA.cache(
            spec {
                ttlOf = me.ahoo.cocache.core.TtlFunction { key, _ -> if (key == "short") Duration.ofSeconds(1) else null }
            }
        )
        cache.putAll(mapOf("short" to "s", "policy" to "p"))
        cache.putAll(mapOf("explicit" to "e"), Duration.ofSeconds(1))
        requireNotNull((remote("policy") as L1Read.Hit).ttl).assert().isGreaterThan(Duration.ofMinutes(9))
        elapse(Duration.ofSeconds(2))
        cache["short"].assert().isNull()
        cache["explicit"].assert().isNull()
        cache["policy"].assert().isEqualTo("p")
        loads.get().assert().isEqualTo(2)
    }

    @Test
    fun putAllFailureThrowsAndEvictsLocal() {
        val strict = policy().toBuilder().invalidateFailure(InvalidateFailureMode.THROW).build()
        val cache = nodeA.cache(spec(strict))
        database["k"] = "v0"
        cache["k"]
        backend.failWrites = true
        val error = runCatching { cache.putAll(mapOf("k" to "v1", "j" to "w1")) }.exceptionOrNull()
        backend.failWrites = false
        error.assert().isInstanceOf(CacheWriteException::class.java)
        (error as CacheWriteException).operation.assert().isEqualTo("putAll")
        nodeA.metrics().writeFailures.assert().isEqualTo(1)
        cache["k"].assert().isEqualTo("v0")
        backend.reads.get().assert().isEqualTo(2)
    }

    /**
     * 另一节点的回源在 putAll 之前读到旧值：putAll 销毁了它的 lease，旧值写不回 L1。
     */
    @Test
    fun putAllDuringRemoteLoadRejectsTheStaleFill() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = "v0"
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        afterLoad = {
            loaded.countDown()
            release.awaitOrFail("release")
        }
        val onA = fork { cacheA["k"] }
        loaded.awaitOrFail("A read the source")
        afterLoad = null
        database["k"] = "v1"
        cacheB.putAll(mapOf("k" to "v1", "other" to "o1"))
        release.countDown()
        onA.join().assert().isEqualTo("v0")
        remoteValue("k").assert().isEqualTo("v1")
        nodeA.metrics().fillRejected.assert().isEqualTo(1)
        awaitCondition("A sees the new value") { cacheA["k"] == "v1" }
        cacheB["k"].assert().isEqualTo("v1")
    }

    /**
     * 同节点的在途加载开始于 putAll 之前：它的结果不能进入 L2，之后的读取也不能合并到它。
     */
    @Test
    fun getAfterOwnPutAllNeverSharesALoadStartedBefore() {
        val cache = nodeA.cache()
        database["k"] = "v0"
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 只挡住第一次回源（early 的）：不按线程名判断，异步视图上回源可能在别的线程上执行
        val first = AtomicBoolean(true)
        afterLoad = {
            if (first.compareAndSet(true, false)) {
                loaded.countDown()
                release.awaitOrFail("release")
            }
        }
        val early = fork("early") { cache["k"] }
        loaded.awaitOrFail("early load read v0")
        database["k"] = "v1"
        cache.putAll(mapOf("k" to "v1", "other" to "o1"))
        val late = fork("late") { cache["k"] }
        awaitCondition("late joined the in-flight load") { late.parked }
        release.countDown()
        early.join().assert().isEqualTo("v0")
        late.join().assert().isEqualTo("v1")
        cache["k"].assert().isEqualTo("v1")
        remoteValue("k").assert().isEqualTo("v1")
        loads.get().assert().isEqualTo(1)
    }

    // ---------------------------------------------------------------------------------------------
    // 条件读写（getIfChanged / putIfMatch）：没有数据源的缓存（L1 是唯一副本）上的“读—改—写”。需要 L1Capability.CONDITIONAL。
    // ---------------------------------------------------------------------------------------------

    protected fun storeOfRecord(policy: CachePolicy = policy()): Store<String, String> {
        assumeDeclared(L1Capability.CONDITIONAL)
        return nodeA.store(storeSpec(policy))
    }

    protected fun digestOf(value: String): Digest = Digest.of(Envelope.Scalar(TestCodec.encode(value)))

    /**
     * 摘要相同时 `Unchanged`；否则返回值与新摘要。总是读 L1：本节点 L2 中的旧副本不影响结果，结果也不写入 L2。
     */
    @Test
    fun getIfChangedConfirmsAgainstRemoteBypassingLocal() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = storeOfRecord()
        cache.getIfChanged("k", null).assert().isEqualTo(ConditionalGet.Absent)
        cache.put("k", "v1")
        val first = cache.getIfChanged("k", null)
        first.assert().isEqualTo(ConditionalGet.Present("v1", digestOf("v1")))
        cache.getIfChanged("k", digestOf("v1")).assert().isEqualTo(ConditionalGet.Unchanged)
        nodeA.metrics().conditionalUnchanged.assert().isEqualTo(1)

        // L2 中是 v1，L1 被绕过协议改成了 v2（没有广播）：普通读取仍读到 L2 的旧副本，条件读取读到 L1
        cache["k"].assert().isEqualTo("v1")
        writeAuthoritativeSilently("k", TestCodec.encode("v2"))
        cache.getIfChanged("k", digestOf("v1")).assert().isEqualTo(ConditionalGet.Present("v2", digestOf("v2")))
        cache["k"].assert().isEqualTo("v1")

        cache.delete("k")
        cache.getIfChanged("k", digestOf("v2")).assert().isEqualTo(ConditionalGet.Absent)
    }

    /**
     * 缓存的读取时限（`CachePolicy.readTimeout`，RFC 0005 §4.3，D6）：L1 卡顿时，每次 L1 读取（单 key、批量与条件读取）
     * 在大约这么长的时间内按 L1 读失败处理，不等后端的命令超时：有数据源的读取降级回源，`Store` 的读取与条件读取为 `Unknown`、
     * 抛出 [CacheReadException]。需要 [pause]，不支持时跳过。
     */
    @Test
    fun readTimeoutBoundsEveryL1Read() {
        database["k"] = "v"
        database["b"] = "w"
        val policy = policy().toBuilder().readTimeout(READ_TIMEOUT).build()
        val cache = nodeA.cache(spec(policy))
        // 条件读取只在 Store 上：建在共享同一个后端的另一个运行时上（namespace 相同，key 不同）
        val store = if (L1Capability.CONDITIONAL in expectedCapabilities) nodeB.store(storeSpec(policy)) else null
        val paused = pause()
        assumeTrue(paused != null, "${backend.delegate.javaClass.name} cannot be paused.")
        requireNotNull(paused).use {
            withinReadTimeout("get") { cache["k"].assert().isEqualTo("v") }
            withinReadTimeout("getAll") { cache.getAll(listOf("b")).assert().isEqualTo(mapOf("b" to "w")) }
            if (store != null) {
                withinReadTimeout("Store.get") {
                    runCatching { store["s"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
                }
                withinReadTimeout("getIfChanged") {
                    runCatching { store.getIfChanged("c", null) }.exceptionOrNull()
                        .assert().isInstanceOf(CacheReadException::class.java)
                }
            }
        }
        nodeA.metrics().remoteFailures.assert().isGreaterThanOrEqualTo(2)
    }

    private fun withinReadTimeout(operation: String, block: () -> Unit) {
        val started = System.nanoTime()
        block()
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        elapsed.assert().withFailMessage { "$operation took $elapsed while L1 was paused." }
            .isGreaterThanOrEqualTo(READ_TIMEOUT.dividedBy(2))
            .isLessThan(READ_TIMEOUT_LIMIT)
    }

    /**
     * 条件读取拿到载荷时同样校验（RFC 0005 §4.7，K3）：摘要段与载荷不一致时抛出 [CacheReadException]（cause 为 [ForeignFormatException]，
     * 本节点的格式），不返回 `Present`。摘要未变的回答只比较头部中的摘要段（成本与载荷大小无关），不重算载荷。
     */
    @Test
    fun conditionalReadVerifiesThePayloadItReturns() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = storeOfRecord()
        writeRemoteSilently("k", TestCodec.encode("v2"), digestOf("v1"))
        listOf(null, digestOf("v2")).forEach { known ->
            val error = runCatching { cache.getIfChanged("k", known) }.exceptionOrNull()
            error.assert().withFailMessage { "known[$known] must fail" }.isInstanceOf(CacheReadException::class.java)
            requireNotNull(error?.cause as? ForeignFormatException).format.assert().isEqualTo(backend.format)
        }
        cache.getIfChanged("k", digestOf("v1")).assert().isEqualTo(ConditionalGet.Unchanged)
        nodeA.metrics().unknownFormats.assert().isEqualTo(2)
    }

    /**
     * 条件读写只认带摘要段的值（RFC 0005 §4.7）：有数据源的缓存写入的值（没有摘要段）是读不懂的内容，条件读取抛出 [CacheReadException]、
     * 条件写入以 [CacheWriteException] 拒绝（cause 都是 [ForeignFormatException]，本节点的格式），不修改 L1、不广播。
     */
    @Test
    fun conditionalOperationsRefuseValuesWithoutADigest() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = storeOfRecord()
        nodeB.cache().put("k", "derived")
        val readError = runCatching { cache.getIfChanged("k", null) }.exceptionOrNull()
        readError.assert().isInstanceOf(CacheReadException::class.java)
        requireNotNull(readError?.cause as? ForeignFormatException).format.assert().isEqualTo(backend.format)
        listOf(null, digestOf("derived")).forEach { expected ->
            val writeError = runCatching { cache.putIfMatch("k", expected, "v") }.exceptionOrNull()
            writeError.assert().isInstanceOf(CacheWriteException::class.java)
            writeError?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        }
        remoteValue("k").assert().isEqualTo("derived")
        ((remote("k") as L1Read.Hit).envelope as Envelope.Scalar).digest.assert().isNull()
        nodeA.metrics().unknownFormats.assert().isEqualTo(3)
    }

    /**
     * 负缓存与 lease 占位都是“没有值”：条件读取返回 `Absent`，`expected = null` 的条件写入覆盖它们（与 put 相同，销毁 lease）。
     */
    @Test
    fun negativeEntriesAndLeasesHoldNoValue() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = storeOfRecord()
        val l1 = backend.delegate.store(Keyspace("cocache:", cacheName))
        Suspending.await {
            l1.put(
                storageKey("negative"),
                Envelope.Absent,
                null,
                InvalidationFrame.encode(NodeId.random(), emptyList())
            )
        }
        val lease = LeaseRequest(Token(NodeId.random(), 1), Duration.ofMinutes(1))
        Suspending.await { l1.read(storageKey("leased"), lease, null, null) }.assert().isEqualTo(L1Read.Miss(true))

        cache.getIfChanged("negative", null).assert().isEqualTo(ConditionalGet.Absent)
        cache.getIfChanged("leased", digestOf("x")).assert().isEqualTo(ConditionalGet.Absent)
        cache.putIfMatch("negative", digestOf(""), "v").assert().isEqualTo(ConditionalPut.Conflict(null))
        cache.putIfMatch("negative", null, "v").assert().isEqualTo(ConditionalPut.Written(digestOf("v")))
        cache.putIfMatch("leased", null, "v").assert().isEqualTo(ConditionalPut.Written(digestOf("v")))
        remoteValue("negative").assert().isEqualTo("v")
        remoteValue("leased").assert().isEqualTo("v")
        // lease 已被销毁：持有者的填充被拒绝
        val stale = me.ahoo.cocache.api.spi.Fill(
            storageKey("leased"),
            lease.token,
            Envelope.Scalar(TestCodec.encode("stale")),
            null
        )
        Suspending.await { l1.fill(stale) }.assert().isInstanceOf(L1Fill.Rejected::class.java)
    }

    /**
     * 只有摘要相符（或 `expected = null` 且没有值）时写入；冲突返回当前摘要，不修改 L1，不计入写入失败。
     */
    @Test
    fun putIfMatchWritesOnlyWhenTheDigestMatches() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = storeOfRecord()
        val other = nodeB.store()
        cache.putIfMatch("k", null, "v1").assert().isEqualTo(ConditionalPut.Written(digestOf("v1")))
        other["k"].assert().isEqualTo("v1")

        cache.putIfMatch("k", null, "x").assert().isEqualTo(ConditionalPut.Conflict(digestOf("v1")))
        cache.putIfMatch("k", digestOf("stale"), "x").assert().isEqualTo(ConditionalPut.Conflict(digestOf("v1")))
        remoteValue("k").assert().isEqualTo("v1")
        nodeA.metrics().conditionalConflicts.assert().isEqualTo(2)
        nodeA.metrics().writeFailures.assert().isEqualTo(0)

        val written = cache.putIfMatch("k", digestOf("v1"), "v2")
        written.assert().isEqualTo(ConditionalPut.Written(digestOf("v2")))
        // 写入广播失效：另一个节点的 L2 被淘汰；本节点读己之写
        awaitCondition("other node sees v2") { other["k"] == "v2" }
        cache["k"].assert().isEqualTo("v2")
        cache.getIfChanged("k", (written as ConditionalPut.Written).digest).assert().isEqualTo(ConditionalGet.Unchanged)

        cache.putIfMatch("absent", digestOf("v1"), "x").assert().isEqualTo(ConditionalPut.Conflict(null))
        remote("absent").assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 冲突不广播：随后一次成功写入的失效是另一个节点收到的唯一一次（同一频道按序送达）。
     */
    @Test
    fun putIfMatchConflictDoesNotBroadcast() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = storeOfRecord()
        val other = nodeB.store()
        cache.put("k", "v1")
        other["k"].assert().isEqualTo("v1")
        awaitCondition("the put reached the other node") { nodeB.metrics().invalidationsReceived >= 1 }
        val received = nodeB.metrics().invalidationsReceived

        cache.putIfMatch("k", digestOf("stale"), "x").assert().isInstanceOf(ConditionalPut.Conflict::class.java)
        cache.putIfMatch("k", null, "x").assert().isInstanceOf(ConditionalPut.Conflict::class.java)
        cache.putIfMatch("marker", null, "m").assert().isInstanceOf(ConditionalPut.Written::class.java)
        awaitCondition("marker broadcast") { nodeB.metrics().invalidationsReceived > received }
        nodeB.metrics().invalidationsReceived.assert().isEqualTo(received + 1)
        other["k"].assert().isEqualTo("v1")
    }

    /**
     * 非原子广播的后端上，条件写入已在 L1 生效而单独的广播失败（调用方得到 [CacheWriteException]）时，重放同一次写入会冲突，
     * 且冲突摘要等于这次写入的摘要：这时仍要广播，否则其它节点的 L2 陈旧至 `localMaxAge`。多余的广播只淘汰副本。
     */
    @Test
    fun replayOfAnAppliedConditionalWriteBroadcasts() {
        assumeDeclared(L1Capability.CONDITIONAL)
        backend.atomicBroadcastOverride = false
        val cache = storeOfRecord()
        val other = nodeB.store()
        writeAuthoritativeSilently("k", TestCodec.encode("v1"))
        other["k"].assert().isEqualTo("v1")
        // putIfMatch(k, v1 → v2) 已在 L1 生效、广播没有发出
        writeAuthoritativeSilently("k", TestCodec.encode("v2"))
        other["k"].assert().isEqualTo("v1")

        cache.putIfMatch("k", digestOf("v1"), "v2").assert().isEqualTo(ConditionalPut.Conflict(digestOf("v2")))
        awaitCondition("the replay reached the other node") { other["k"] == "v2" }
    }

    /**
     * N 个线程（分布在两个节点上）在同一个 key 上做“读—改—条件写”循环：没有一次更新丢失。
     * 第一轮强制竞争：全部线程读到同一个值后才同时写，因此恰好一个写入成功，其余都冲突（不原子的实现会让多个成功）。
     */
    @Test
    fun concurrentReadModifyWriteLosesNoUpdate() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val caches = listOf(storeOfRecord(), nodeB.store())
        val threads = 8
        val increments = 25
        val firstRead = CyclicBarrier(threads)
        val firstWins = AtomicInteger()
        val workers = (0 until threads).map { index ->
            val cache = caches[index % caches.size]
            fork("cas-$index") {
                if (increment(cache, "counter", firstRead) == 0) {
                    firstWins.incrementAndGet()
                }
                repeat(increments - 1) { increment(cache, "counter") }
            }
        }
        workers.forEach { it.join() }
        firstWins.get().assert().withFailMessage { "exactly one writer of the contended round must win" }.isEqualTo(1)
        val conflicts = nodeA.metrics().conditionalConflicts + nodeB.metrics().conditionalConflicts
        conflicts.assert().isGreaterThanOrEqualTo((threads - 1).toLong())
        val final = caches[0].getIfChanged("counter", null) as ConditionalGet.Present
        final.value.assert().isEqualTo((threads * increments).toString())
        remoteValue("counter").assert().isEqualTo((threads * increments).toString())
    }

    /**
     * 一次增量；返回期间遇到的冲突次数。[barrier] 非空时第一次读取之后在它上面等齐全部线程再写。
     */
    private fun increment(cache: Store<String, String>, key: String, barrier: CyclicBarrier? = null): Int {
        var conflicts = 0
        var gate = barrier
        while (true) {
            val current = cache.getIfChanged(key, null)
            val (value, digest) = when (current) {
                is ConditionalGet.Present -> current.value.toInt() to current.digest
                else -> 0 to null
            }
            gate?.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            gate = null
            if (cache.putIfMatch(key, digest, (value + 1).toString()) is ConditionalPut.Written) {
                return conflicts
            }
            conflicts++
        }
    }

    /**
     * 写入时与 put 相同地设置到期时间（策略或显式时长；`ttl = null` 永不过期）；冲突与条件读取不改变到期时间。
     */
    @Test
    fun putIfMatchSetsTtlLikePutAndConflictsKeepIt() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = storeOfRecord()
        cache.putIfMatch("k", null, "v1", Duration.ofMinutes(1))
        val ttl = requireNotNull((remote("k") as L1Read.Hit).ttl)
        ttl.assert().isBetween(Duration.ofSeconds(50), Duration.ofMinutes(1))

        cache.putIfMatch("k", null, "x", Duration.ofDays(1)).assert().isInstanceOf(ConditionalPut.Conflict::class.java)
        cache.getIfChanged("k", digestOf("v1")).assert().isEqualTo(ConditionalGet.Unchanged)
        requireNotNull((remote("k") as L1Read.Hit).ttl).assert().isLessThanOrEqualTo(ttl)

        cache.putIfMatch("k", digestOf("v1"), "v2").assert().isInstanceOf(ConditionalPut.Written::class.java)
        requireNotNull(
            (remote("k") as L1Read.Hit).ttl
        ).assert().isBetween(Duration.ofMinutes(9), Duration.ofMinutes(10))

        val forever = nodeB.store(storeSpec(policy().toBuilder().ttl(null).build()))
        forever.putIfMatch("forever", null, "v").assert().isInstanceOf(ConditionalPut.Written::class.java)
        (remote("forever") as L1Read.Hit).ttl.assert().isNull()
        forever.putIfMatch("forever", digestOf("v"), "v2", Duration.ofMinutes(1))
        requireNotNull((remote("forever") as L1Read.Hit).ttl).assert().isLessThanOrEqualTo(Duration.ofMinutes(1))
    }

    /**
     * 读不懂的内容（这里是同一个 key 上的集合、解码失败的值）：条件读取以 [CacheReadException]、条件写入与条件删除以
     * [CacheWriteException] 失败（cause 为 [ForeignFormatException]），不修改 L1、计入 `unknownFormats`；从不当作没有值
     * （否则 CAS 循环会从空值出发覆盖它，或者永远读到 `Absent`、写入永远冲突），也不回收。
     */
    @Test
    fun conditionalOperationsFailOnUnreadableContent() {
        assumeDeclared(L1Capability.SETS)
        val cache = storeOfRecord()
        val frame = InvalidationFrame.encode(NodeId.random(), emptyList())
        addMembersSilently("set", frame)
        writeAuthoritativeSilently("corrupt", TestCodec.CORRUPT)

        val readError = runCatching { cache.getIfChanged("set", null) }.exceptionOrNull()
        readError.assert().isInstanceOf(CacheReadException::class.java)
        requireNotNull(readError?.cause as? ForeignFormatException).storageKey.assert().isEqualTo(storageKey("set"))
        val writeError = runCatching { cache.putIfMatch("set", null, "v") }.exceptionOrNull()
        writeError.assert().isInstanceOf(CacheWriteException::class.java)
        (writeError as CacheWriteException).operation.assert().isEqualTo("putIfMatch")
        writeError.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        writeError.outcomeUnknown.assert().isFalse()
        val deleteError = runCatching { cache.deleteIfMatch("set", digestOf("v")) }.exceptionOrNull()
        deleteError.assert().isInstanceOf(CacheWriteException::class.java)
        (deleteError as CacheWriteException).operation.assert().isEqualTo("deleteIfMatch")
        deleteError.outcomeUnknown.assert().isFalse()
        (remote("set") as L1Read.Hit).envelope.assert().isInstanceOf(Envelope.Members::class.java)

        // 同版本内容解码失败：读取失败；写入的脚本看不到 Codec，只看摘要：调用方拿不到它的摘要，因此只会冲突、从不覆盖
        val corrupt = runCatching { cache.getIfChanged("corrupt", null) }.exceptionOrNull()
        corrupt.assert().isInstanceOf(CacheReadException::class.java)
        requireNotNull(corrupt?.cause as? ForeignFormatException).format.assert().isEqualTo(backend.format)
        cache.putIfMatch("corrupt", null, "v").assert().isEqualTo(
            ConditionalPut.Conflict(Digest.of(Envelope.Scalar(TestCodec.CORRUPT)))
        )
        cache.deleteIfMatch("corrupt", digestOf("v")).assert().isEqualTo(
            ConditionalDelete.Conflict(Digest.of(Envelope.Scalar(TestCodec.CORRUPT)))
        )
        ((remote("corrupt") as L1Read.Hit).envelope as Envelope.Scalar).bytes.assert().isEqualTo(TestCodec.CORRUPT)
        nodeA.metrics().unknownFormats.assert().isEqualTo(4)
        nodeA.metrics().writeFailures.assert().isEqualTo(2)
        nodeA.metrics().reclaims.assert().isEqualTo(0)
    }

    /**
     * 条件删除（`Store.deleteIfMatch`）：只有摘要相等时删除并广播；不相等或没有值时冲突，附当前摘要，不修改、不广播。
     */
    @Test
    fun deleteIfMatchDeletesOnlyWhenTheDigestMatches() {
        val cache = storeOfRecord()
        val other = nodeB.store()
        cache.put("k", "v1")
        other["k"].assert().isEqualTo("v1")
        cache.deleteIfMatch("k", digestOf("v0")).assert().isEqualTo(ConditionalDelete.Conflict(digestOf("v1")))
        remoteValue("k").assert().isEqualTo("v1")
        cache.deleteIfMatch("k", digestOf("v1")).assert().isEqualTo(ConditionalDelete.Deleted)
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        awaitCondition("the deletion is visible") { other["k"] == null }
        cache.deleteIfMatch("k", digestOf("v1")).assert().isEqualTo(ConditionalDelete.Conflict(null))
        nodeA.metrics().conditionalConflicts.assert().isEqualTo(2)
        cache.getIfChanged("k", null).assert().isEqualTo(ConditionalGet.Absent)
    }

    /**
     * L1 失败：条件读取总是抛出 [CacheReadException]（没有可降级的答案，与 `readFailure` 无关），条件写入抛出 [CacheWriteException]。
     */
    @Test
    fun conditionalOperationsThrowWhenRemoteFails() {
        val cache = storeOfRecord()
        backend.failAll = true
        runCatching {
            cache.getIfChanged(
                "k",
                null
            )
        }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        runCatching {
            cache.putIfMatch(
                "k",
                null,
                "v"
            )
        }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        backend.failAll = false
        nodeA.metrics().remoteFailures.assert().isGreaterThanOrEqualTo(1)
        nodeA.metrics().writeFailures.assert().isEqualTo(1)
        loads.get().assert().isEqualTo(0)
    }

    private companion object {
        const val BARRIER_TIMEOUT_SECONDS = 30L

        /**
         * 读取时限用例的 `readTimeout`。
         */
        val READ_TIMEOUT: Duration = Duration.ofMillis(200)

        /**
         * 读取所用时间的上限：远小于默认的命令超时（2 s），证明时限是缓存的 `readTimeout`，不是命令超时。
         */
        val READ_TIMEOUT_LIMIT: Duration = Duration.ofMillis(1500)

        /**
         * 计数类用例的命中次数。
         */
        const val HITS = 50
    }

    /**
     * 测试编解码：以 [CORRUPT] 开头的字节解码失败，模拟损坏的载荷。
     */
    object TestCodec : Codec<String> {
        val CORRUPT = byteArrayOf(-1, -1)

        override fun encode(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

        override fun decode(bytes: ByteArray): String {
            require(!(bytes.size >= 2 && bytes[0] == CORRUPT[0] && bytes[1] == CORRUPT[1])) { "corrupted" }
            return String(bytes, Charsets.UTF_8)
        }
    }
}
