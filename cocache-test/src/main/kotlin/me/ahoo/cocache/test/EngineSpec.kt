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
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
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
import java.util.concurrent.Executor
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
     * 访问缓存的方式：同步 API（默认），或经异步视图（[AsyncEngineSpec] 等，RFC 0004 §10.1）。
     */
    protected open val access: CacheAccess = CacheAccess.SYNC

    /**
     * 用例结束时是否关闭 [createBackend] 返回的后端（见 [HookedBackend.closesDelegate]）。默认 `false`：后端可能是共享的。
     * 每个用例新建后端的实现应覆盖为 `true`。
     */
    protected open val closesBackend: Boolean = false

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
    protected fun remote(key: String): RemoteRead = backend.delegate.store(cacheName).read(storageKey(key), null, null)

    protected fun remoteValue(key: String): String? {
        val read = remote(key) as? RemoteRead.Hit ?: return null
        return (read.envelope as? Envelope.Scalar)?.let { TestCodec.decode(it.bytes) }
    }

    /**
     * 不广播地直接写 L1（模拟绕过协议的写入或丢失的事件）。
     */
    protected fun writeRemoteSilently(key: String, bytes: ByteArray) {
        backend.delegate.store(
            cacheName
        ).put(storageKey(key), Envelope.Scalar(bytes), null, InvalidationFrame.encode(NodeId.random(), emptyList()))
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
        ) { followers.all { it.thread.state == Thread.State.WAITING || it.thread.state == Thread.State.TIMED_WAITING } }
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
        remote("k").assert().isInstanceOf(RemoteRead.Leased::class.java)
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
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
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
        awaitCondition("late joined the in-flight load") { late.thread.state == Thread.State.WAITING }
        release.countDown()
        early.join().assert().isEqualTo("v0")
        late.join().assert().isEqualTo("v1")
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
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
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
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
        nodeA.metrics().remoteFailures.assert().isGreaterThanOrEqualTo(1)
        nodeA.metrics().degradedFills.assert().isEqualTo(1)
        cache["k"]
        loads.get().assert().isEqualTo(1)
        elapse(policy().degradedLocalMaxAge.plusSeconds(1))
        cache["k"].assert().isEqualTo("v")
        loads.get().assert().isEqualTo(2)
        remoteValue("k").assert().isEqualTo("v")
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
        awaitCondition("retry deleted the stale remote value") { remote("k") == RemoteRead.Miss(false) }
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
        (remote("absent") as RemoteRead.Hit).envelope.assert().isEqualTo(Envelope.Absent)
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
        awaitCondition("batch waiting") { batch.thread.state == Thread.State.WAITING }
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
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
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
     * 没有数据源时 L1 是唯一副本：读不懂的内容（格式 0，如 5.x 遗留；同版本解码失败）默认 `FAIL`，
     * 抛出 [CacheReadException]（cause 为 [ForeignFormatException]），不回收、不写 L2，降级读失败模式下同样抛出。
     */
    @Test
    fun foreignContentWithoutLoaderFailsAndIsKept() {
        val cache = nodeA.cache(spec { loader = null })
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
        val kept = remote("k") as RemoteRead.Hit
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
        backend.afterRead = { _, read -> if (read is RemoteRead.Hit) read.copy(format = 0) else read }
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
        remote("absent").assert().isEqualTo(RemoteRead.Miss(false))
        remote("bad").assert().isInstanceOf(RemoteRead.Hit::class.java)
        remote("worse").assert().isInstanceOf(RemoteRead.Hit::class.java)
        cache["ok"].assert().isEqualTo("v")
    }

    /**
     * 显式 `RECLAIM`：没有数据源时同样条件回收（6.4 及以前的行为），内容被删除，读取返回不存在。
     */
    @Test
    fun foreignContentWithoutLoaderIsReclaimedWhenConfigured() {
        val reclaim = policy().toBuilder().foreignFormat(ForeignFormatMode.RECLAIM).build()
        val cache = nodeA.cache(spec(reclaim) { loader = null })
        writeRemoteSilently("k", TestCodec.CORRUPT)
        cache["k"].assert().isNull()
        nodeA.metrics().reclaims.assert().isEqualTo(1)
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
    }

    /**
     * 显式 `FAIL`：有数据源也不回收、不回源。
     */
    @Test
    fun foreignContentFailsWithLoaderWhenConfigured() {
        val fail = policy().toBuilder().foreignFormat(ForeignFormatMode.FAIL).build()
        val cache = nodeA.cache(spec(fail))
        database["k"] = "v"
        writeRemoteSilently("k", TestCodec.CORRUPT)
        runCatching { cache["k"] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        loads.get().assert().isEqualTo(0)
        remote("k").assert().isInstanceOf(RemoteRead.Hit::class.java)
    }

    /**
     * 默认值按这次读取有没有数据源决定：没有 spec 数据源的缓存经 `get(key, loader)` 读取时有数据源，照常回收并回源。
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
        backend.afterRead = { _, read -> if (read is RemoteRead.Hit) read.copy(format = read.format + 1) else read }
        cache["k"].assert().isEqualTo("v")
        backend.afterRead = null
        nodeA.metrics().unknownFormats.assert().isEqualTo(1)
        remoteValue("k").assert().isEqualTo("new-format")
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
        remote("b").assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun cacheWithoutLoaderReleasesLeaseAndReturnsNull() {
        val cache = nodeA.cache(spec { loader = null })
        cache["k"].assert().isNull()
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
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
        requireNotNull((remote("policy") as RemoteRead.Hit).ttl).assert().isGreaterThan(Duration.ofMinutes(9))
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
        awaitCondition("late joined the in-flight load") { late.thread.state == Thread.State.WAITING }
        release.countDown()
        early.join().assert().isEqualTo("v0")
        late.join().assert().isEqualTo("v1")
        cache["k"].assert().isEqualTo("v1")
        remoteValue("k").assert().isEqualTo("v1")
        loads.get().assert().isEqualTo(1)
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
