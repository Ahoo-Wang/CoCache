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
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
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
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引擎 TCK：两个节点（运行时）共享同一个后端，覆盖 RFC 0003 §4.4–§4.6 与 §10 的全部反例。
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

    protected open fun policy(): CachePolicy = CachePolicy(
        ttl = Duration.ofMinutes(10),
        ttlJitter = Duration.ZERO,
        lease = LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ofSeconds(5)),
    )

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
     * 不广播地直接写 L1（模拟绕过协议的写入或丢失的事件）。[digest] 为载荷的摘要（像没有数据源的缓存那样写入摘要段，
     * RFC 0005 §4.7）；默认不带，像有数据源的缓存。
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
     * 像没有数据源的缓存那样不广播地写 L1：带载荷的摘要段。
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
     * 写入标识（RFC 0005 §4.7，D2）：M5 之前没有数据源的缓存充当权威标量，它的每次标量写入（put、putAll、调用方给出 loader 时的填充，
     * 声明了条件读写时的 putIfMatch）都带一个新的写入标识，与令牌同形、以本节点的 NodeId 开头；有数据源的缓存写入的值与回源填充都不带。
     */
    @Test
    fun onlyCachesWithoutASourceWriteAWriterId() {
        fun writerOf(key: String) = ((remote(key) as L1Read.Hit).envelope as Envelope.Scalar).writer
        val conditional = L1Capability.CONDITIONAL in expectedCapabilities

        val derived = nodeA.cache()
        database["filled"] = "v"
        derived["filled"].assert().isEqualTo("v")
        derived.put("put", "p")
        derived.putAll(mapOf("all" to "a"))
        if (conditional) {
            derived.putIfMatch("cas", null, "c").assert().isInstanceOf(ConditionalPut.Written::class.java)
        }
        listOfNotNull("filled", "put", "all", "cas".takeIf { conditional }).forEach {
            writerOf(it).assert().withFailMessage { "A derived write of [$it] carries no writer id." }.isNull()
        }

        // 同一个 keyspace 上没有数据源的缓存（另一个节点）
        val authoritative = nodeB.cache(spec { loader = null })
        authoritative.put("put", "p2")
        authoritative.putAll(mapOf("all" to "a2", "all-2" to "b2"))
        authoritative.get("filled-2") { "f2" }.assert().isEqualTo("f2")
        if (conditional) {
            authoritative.putIfMatch("cas-2", null, "c2").assert().isInstanceOf(ConditionalPut.Written::class.java)
        }
        val writers = listOfNotNull("put", "all", "all-2", "filled-2", "cas-2".takeIf { conditional }).map {
            requireNotNull(writerOf(it)) { "An authoritative write of [$it] carries a writer id." }
        }
        writers.forEach { it.node.assert().isEqualTo(nodeB.nodeId) }
        writers.toSet().assert().hasSize(writers.size)
        authoritative["put"].assert().isEqualTo("p2")
    }

    /**
     * 摘要段（RFC 0005 §4.7，D1）：M5 之前没有数据源的缓存的每次标量写入（put、putAll、调用方给出 loader 时的填充，声明了条件读写时的
     * putIfMatch）都带载荷的 SHA-256，由引擎在客户端计算，存储原样保存、读取原样返回；有数据源的缓存写入的值与回源填充都不带。
     */
    @Test
    fun onlyCachesWithoutASourceWriteADigest() {
        fun scalarOf(key: String) = (remote(key) as L1Read.Hit).envelope as Envelope.Scalar
        val conditional = L1Capability.CONDITIONAL in expectedCapabilities

        val derived = nodeA.cache()
        database["filled"] = "v"
        derived["filled"].assert().isEqualTo("v")
        derived.put("put", "p")
        derived.putAll(mapOf("all" to "a"))
        if (conditional) {
            derived.putIfMatch("cas", null, "c").assert().isInstanceOf(ConditionalPut.Written::class.java)
        }
        listOfNotNull("filled", "put", "all", "cas".takeIf { conditional }).forEach {
            scalarOf(it).digest.assert().withFailMessage { "A derived write of [$it] carries no digest." }.isNull()
        }

        val authoritative = nodeB.cache(spec { loader = null })
        authoritative.put("put", "p2")
        authoritative.putAll(mapOf("all" to "a2", "all-2" to "b2"))
        authoritative.get("filled-2") { "f2" }.assert().isEqualTo("f2")
        if (conditional) {
            authoritative.putIfMatch("cas-2", null, "c2").assert().isInstanceOf(ConditionalPut.Written::class.java)
        }
        listOfNotNull("put", "all", "all-2", "filled-2", "cas-2".takeIf { conditional }).forEach {
            val scalar = scalarOf(it)
            scalar.digest.assert().withFailMessage { "An authoritative write of [$it] carries the payload digest." }
                .isEqualTo(Digest.ofPayload(scalar.bytes))
        }
        authoritative.getAll(listOf("all", "all-2")).assert().isEqualTo(mapOf("all" to "a2", "all-2" to "b2"))
    }

    /**
     * 读取方校验（RFC 0005 §4.7，K3）：没有数据源的缓存从 L1 读到的值，摘要段与载荷不一致或缺摘要段时按损坏 `FAIL`，抛出
     * [CacheReadException]（cause 为 [ForeignFormatException]，本节点的格式），不回收、不写 L2，单 key 与批量都是如此。
     * 校验在引擎中，对所有后端相同。调用方给出 loader 时这次读取有数据源：带摘要段的内容从不回收（D4），绕过 L1 回源；
     * 缺摘要段的内容照常回收并回源。
     */
    @Test
    fun cacheWithoutASourceFailsOnATamperedOrMissingDigest() {
        val cache = nodeA.cache(spec { loader = null })
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
        remote("absent").assert().isEqualTo(L1Read.Miss(false))
        nodeA.metrics().unknownFormats.assert().isEqualTo(5)
        nodeA.metrics().reclaims.assert().isEqualTo(0)
        ((remote("tampered") as L1Read.Hit).envelope as Envelope.Scalar).bytes.assert().isEqualTo(TestCodec.encode("v2"))

        cache.get("tampered") { "loaded" }.assert().isEqualTo("loaded")
        ((remote("tampered") as L1Read.Hit).envelope as Envelope.Scalar).bytes.assert().isEqualTo(TestCodec.encode("v2"))
        cache.get("bare") { "loaded" }.assert().isEqualTo("loaded")
        remoteValue("bare").assert().isEqualTo("loaded")
        cache["bare"].assert().isEqualTo("loaded")
    }

    /**
     * 有数据源的缓存读到带摘要段的值（同一个 namespace 上还有没有数据源的缓存，RFC 0005 §4.7，D4）：照常读取，计入 `typeMismatches`；
     * 解码失败时也不回收（`RECLAIM` 拒绝带摘要段的内容），绕过 L1 回源，L1 中的唯一副本保留。
     */
    @Test
    fun derivedCacheCountsATypeMismatchAndNeverReclaimsDigestContent() {
        nodeB.cache(spec { loader = null }).put("k", "authoritative")
        val derived = nodeA.cache()
        derived["k"].assert().isEqualTo("authoritative")
        nodeA.metrics().typeMismatches.assert().isEqualTo(1)

        database["corrupt"] = "loaded"
        writeAuthoritativeSilently("corrupt", TestCodec.CORRUPT)
        derived["corrupt"].assert().isEqualTo("loaded")
        ((remote("corrupt") as L1Read.Hit).envelope as Envelope.Scalar).bytes.assert().isEqualTo(TestCodec.CORRUPT)
        nodeA.metrics().typeMismatches.assert().isEqualTo(2)
        nodeB.metrics().typeMismatches.assert().isEqualTo(0)
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
     * 没有数据源时 L1 是唯一副本：L1 读失败没有可降级的答案（绕过 L1 只能得到“不存在”），降级读失败模式下也抛出
     * [CacheReadException]（单 key 与批量），不写 L2。恢复后照常读到 L1 中的值；调用方给出的 loader 是数据源，照常降级回源。
     */
    @Test
    fun remoteReadFailureWithoutLoaderFailsClosed() {
        val cache = nodeA.cache(spec { loader = null })
        nodeB.cache(spec { loader = null }).put("k", "v")
        backend.failAll = true
        listOf<Pair<String, () -> Any?>>(
            "read" to { cache["k"] },
            "read" to { cache.lookup("k") },
            "readAll" to { cache.getAll(listOf("k", "other")) },
        ).forEach { (operation, read) ->
            val error = runCatching { read() }.exceptionOrNull()
            error.assert().isInstanceOf(CacheReadException::class.java)
            (error as CacheReadException).operation.assert().isEqualTo(operation)
            error.cause.assert().isInstanceOf(RemoteUnavailableException::class.java)
        }
        cache.get("other") { "fallback" }.assert().isEqualTo("fallback")
        backend.failAll = false
        nodeA.metrics().degradedFills.assert().isEqualTo(1)
        cache["k"].assert().isEqualTo("v")
        cache.get("k") { "unused" }.assert().isEqualTo("v")
        cache.getAll(listOf("k", "other")).assert().isEqualTo(mapOf("k" to "v", "other" to "fallback"))
    }

    /**
     * 显式 `readFailure = DEGRADE`：没有数据源的读取在 L1 失败时读到不存在（单 key、`lookup` 与批量），不抛出，计入 `remoteFailures`，
     * 但不写入任何一层：恢复后照常读到 L1 中的值，故障期间同一个 key 上带 loader 的读取也不被挡住。
     * 供把“读不到”当作安全答案的缓存使用（如撤销表：Redis 故障即视为未撤销）。
     */
    @Test
    fun remoteReadFailureWithoutLoaderDegradesWithoutCachingWhenConfigured() {
        val degrade = policy().toBuilder().readFailure(ReadFailureMode.DEGRADE).build()
        val cache = nodeA.cache(spec(degrade) { loader = null })
        nodeB.cache(spec { loader = null }).put("k", "v")
        backend.failAll = true
        cache["k"].assert().isNull()
        cache.lookup("k").assert().isInstanceOf(Lookup.Absent::class.java)
        cache.getAll(listOf("k", "other")).assert().isEmpty()
        cache["j"].assert().isNull()
        cache.get("j") { "fallback" }.assert().isEqualTo("fallback")
        backend.failAll = false
        nodeA.metrics().remoteFailures.assert().isEqualTo(5)
        nodeA.metrics().degradedFills.assert().isEqualTo(1)
        cache["k"].assert().isEqualTo("v")
        cache.getAll(listOf("k")).assert().isEqualTo(mapOf("k" to "v"))
        remote("j").assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 没有数据源时，绕过 L1 的读取（这里是等待 lease 超时）只能得到“不存在”：它只是没有答案，不是已决的负缓存，
     * 不挡住随后带 loader 的读取。
     */
    @Test
    fun bypassedReadWithoutLoaderDoesNotBlockACallSiteLoader() {
        val impatient = policy().toBuilder().lease(
            LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ZERO)
        ).build()
        val cacheA = nodeA.cache(spec(impatient) { loader = null })
        val cacheB = nodeB.cache(spec { loader = null })
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
     * 显式 `RETRY`：没有数据源的缓存也照常登记重试（延迟的删除可能删掉其间其它写入方的写入，由配置者承担）。
     * 重试期间读取绕过 L1，只能得到“不存在”：没有答案，不挡住带 loader 的读取。
     */
    @Test
    fun failedInvalidateWithoutLoaderIsRetriedWhenConfigured() {
        val retry = policy().toBuilder().invalidateFailure(InvalidateFailureMode.RETRY).build()
        val cache = nodeA.cache(spec(retry) { loader = null })
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

    @Test
    fun putFailureThrowsAndEvictsLocal() {
        val cache = nodeA.cache()
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
        val refreshing = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5),
        )
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
        val refreshing = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            refreshWindow = Duration.ofSeconds(5),
            lease = LeasePolicy(ttl = Duration.ofSeconds(30), wait = wait),
        )
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
        val refreshing = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            refreshWindow = Duration.ofSeconds(5),
        )
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
        val cache = nodeA.cache(spec { loader = null })
        writeAuthoritativeSilently("k", TestCodec.CORRUPT)
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
        val cache = nodeA.cache(spec { loader = null })
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
        val cache = nodeA.cache(spec { loader = null })
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
     * 读不懂的内容按这次读取有没有数据源决定：没有 spec 数据源的缓存经 `get(key, loader)` 读取时有数据源，照常回收并回源。
     */
    @Test
    fun foreignContentIsReclaimedWhenTheCallSuppliesALoader() {
        val cache = nodeA.cache(spec { loader = null })
        writeRemoteSilently("k", TestCodec.CORRUPT)
        cache.get("k") { "loaded" }.assert().isEqualTo("loaded")
        remoteValue("k").assert().isEqualTo("loaded")
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
     * 没有数据源时，更高版本写入的值（混合版本滚动升级期间）与读不懂的内容一样默认 `FAIL`：
     * L1 是唯一副本，当作不存在会让调用方把“不存在”当作真实结果。抛出、保留 key、不写 L2、计入 `unknownFormats`。
     */
    @Test
    fun newerFormatWithoutLoaderFailsAndIsKept() {
        val cache = nodeA.cache(spec { loader = null })
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
        val cache = nodeA.cache(spec { loader = null })
        cache["k"].assert().isNull()
        remote("k").assert().isEqualTo(L1Read.Miss(false))
        cache.put("k", "v")
        cache["k"].assert().isEqualTo("v")
        cache.getAll(listOf("k", "absent")).assert().isEqualTo(mapOf("k" to "v"))
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
        val cache = nodeA.cache()
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

    protected fun storeOfRecord(policy: CachePolicy = policy()): Cache<String, String> = nodeA.cache(
        spec(policy) { loader = null }
    )

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

        cache.invalidate("k")
        cache.getIfChanged("k", digestOf("v2")).assert().isEqualTo(ConditionalGet.Absent)
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
        val other = nodeB.cache(spec { loader = null })
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
        val other = nodeB.cache(spec { loader = null })
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
        val other = nodeB.cache(spec { loader = null })
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
        val caches = listOf(storeOfRecord(), nodeB.cache(spec { loader = null }))
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
    private fun increment(cache: Cache<String, String>, key: String, barrier: CyclicBarrier? = null): Int {
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

        val forever = nodeB.cache(spec(policy().toBuilder().ttl(null).build()) { loader = null })
        forever.putIfMatch("forever", null, "v").assert().isInstanceOf(ConditionalPut.Written::class.java)
        (remote("forever") as L1Read.Hit).ttl.assert().isNull()
        forever.putIfMatch("forever", digestOf("v"), "v2", Duration.ofMinutes(1))
        requireNotNull((remote("forever") as L1Read.Hit).ttl).assert().isLessThanOrEqualTo(Duration.ofMinutes(1))
    }

    /**
     * 读不懂的内容（这里是同一个 key 上的集合、解码失败的值）：条件读取以 [CacheReadException]、
     * 条件写入以 [CacheWriteException] 失败（cause 为 [ForeignFormatException]），不修改 L1、计入 `unknownFormats`。
     * 降级读失败模式与有数据源时同样如此（条件读写从不回源）。
     */
    @Test
    fun conditionalOperationsFailOnUnreadableContent() {
        assumeDeclared(L1Capability.CONDITIONAL, L1Capability.SETS)
        val cache = nodeA.cache(spec())
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
        (remote("set") as L1Read.Hit).envelope.assert().isInstanceOf(Envelope.Members::class.java)

        // 同版本内容解码失败：读取失败；写入的脚本看不到 Codec，只看摘要：调用方拿不到它的摘要，因此只会冲突、从不覆盖
        val corrupt = runCatching { cache.getIfChanged("corrupt", null) }.exceptionOrNull()
        corrupt.assert().isInstanceOf(CacheReadException::class.java)
        requireNotNull(corrupt?.cause as? ForeignFormatException).format.assert().isEqualTo(backend.format)
        cache.putIfMatch("corrupt", null, "v").assert().isEqualTo(
            ConditionalPut.Conflict(Digest.of(Envelope.Scalar(TestCodec.CORRUPT)))
        )
        ((remote("corrupt") as L1Read.Hit).envelope as Envelope.Scalar).bytes.assert().isEqualTo(TestCodec.CORRUPT)
        nodeA.metrics().unknownFormats.assert().isEqualTo(3)
        nodeA.metrics().writeFailures.assert().isEqualTo(1)
        loads.get().assert().isEqualTo(0)
    }

    /**
     * 回收只属于读取：有数据源的缓存上（读取会回收读不懂的内容），条件读写遇到读不懂的内容同样读取抛出、写入拒绝或冲突，
     * 从不当作没有值（否则 CAS 循环会从空值出发覆盖它，或者永远读到 `Absent`、写入永远冲突），也不回收、不回源。
     * 不是本协议写入的内容（格式 0）见各后端自己的测试。
     */
    @Test
    fun conditionalOperationsNeverReclaim() {
        assumeDeclared(L1Capability.CONDITIONAL, L1Capability.SETS)
        val cache = nodeA.cache(spec())
        val frame = InvalidationFrame.encode(NodeId.random(), emptyList())
        addMembersSilently("set", frame)
        writeAuthoritativeSilently("corrupt", TestCodec.CORRUPT)

        runCatching {
            cache.getIfChanged(
                "set",
                null
            )
        }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        runCatching {
            cache.putIfMatch("set", null, "v")
        }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        (remote("set") as L1Read.Hit).envelope.assert().isInstanceOf(Envelope.Members::class.java)

        runCatching {
            cache.getIfChanged("corrupt", null)
        }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        cache.putIfMatch(
            "corrupt",
            null,
            "v"
        ).assert().isEqualTo(ConditionalPut.Conflict(Digest.of(Envelope.Scalar(TestCodec.CORRUPT))))
        ((remote("corrupt") as L1Read.Hit).envelope as Envelope.Scalar).bytes.assert().isEqualTo(TestCodec.CORRUPT)
        nodeA.metrics().unknownFormats.assert().isEqualTo(3)
        nodeA.metrics().reclaims.assert().isEqualTo(0)
        loads.get().assert().isEqualTo(0)
    }

    /**
     * L1 失败：条件读取总是抛出 [CacheReadException]（没有可降级的答案，与 `readFailure` 无关），条件写入抛出 [CacheWriteException]。
     */
    @Test
    fun conditionalOperationsThrowWhenRemoteFails() {
        assumeDeclared(L1Capability.CONDITIONAL)
        val cache = nodeA.cache(spec())
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
