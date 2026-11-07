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

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.internal.Ticker
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.RemoteUnavailableException
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 发版前评审发现的缺陷的回归用例：寿命溢出、带 loader 的读取、重试中的刷新、令牌泄漏、失效重试计数。
 */
class CoherentEngineRegressionTest {
    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val backend = HookedBackend(memory)
    private val database = ConcurrentHashMap<String, String>()
    private val loads = AtomicInteger()
    private var refreshExecutor: Executor = Executor { it.run() }
    private val runtimes = mutableListOf<CacheRuntime>()
    private val name = "engine"

    @Volatile
    private var loadFailure: Throwable? = null

    private val loader = object : CacheLoader<String, String> {
        override fun load(key: String): String? {
            loads.incrementAndGet()
            loadFailure?.let { throw it }
            return database[key]
        }

        override fun loadAll(keys: Collection<String>): Map<String, String> {
            loadFailure?.let { throw it }
            return keys.mapNotNull { k -> database[k]?.let { k to it } }.toMap()
        }
    }

    @AfterEach
    fun destroy() {
        runtimes.forEach { it.close() }
    }

    private fun runtime(
        retryPolicy: RetryPolicy = RetryPolicy(
            initialBackoff = Duration.ofMillis(5),
            maxBackoff = Duration.ofMillis(5)
        )
    ): CacheRuntime {
        return CacheRuntime.builder()
            .backend(backend)
            .clock(clock)
            .refreshExecutor { refreshExecutor.execute(it) }
            .retryPolicy(retryPolicy)
            .build()
            .also { runtimes.add(it) }
    }

    private fun spec(
        policy: CachePolicy = CachePolicy(
            ttl = Duration.ofMinutes(10),
            ttlJitter = Duration.ZERO
        ),
        block: CacheSpec.Builder<String, String>.() -> Unit = {}
    ) =
        cacheSpec<String, String>(name) {
            codec = TestCodec
            loader = this@CoherentEngineRegressionTest.loader
            this.policy = policy
            block()
        }

    private fun CacheRuntime.engine(spec: CacheSpec<String, String> = spec()): CacheEngine<String, String> = create(
        spec
    ) as CacheEngine<String, String>

    private fun key(key: String) = "cocache:$name:$key"

    private fun silentPut(key: String, envelope: Envelope) {
        Suspending.await {
            memory.store(
                Keyspace("cocache:", name)
            ).put(key(key), envelope, null, InvalidationFrame.encode(NodeId.random(), emptyList()))
        }
    }

    private fun foreignLease(key: String): Token {
        val token = Token(NodeId.random(), 1)
        Suspending.await {
            memory.store(
                Keyspace("cocache:", name)
            ).read(key(key), LeaseRequest(token, Duration.ofMinutes(1)), null, null)
        }
        return token
    }

    @Test
    fun getWithCallerLoaderIgnoresLoaderlessAbsence() {
        val cache = runtime().engine(spec { external() })
        // 无数据源缓存的未命中只在本地记为“不存在”
        cache["k"].assert().isNull()
        cache.get("k") { "loaded-$it" }.assert().isEqualTo("loaded-k")
        cache["k"].assert().isEqualTo("loaded-k")
    }

    /**
     * 没有数据源的读取在 L1 失败时没有答案（抛出），它的结果经 SingleFlight 共享：合并到它的带 loader 的读取不跟着失败，
     * 改用自己的 loader 降级回源。
     */
    @Test
    fun callSiteLoaderJoiningAFailedLoaderlessReadUsesItsOwnLoader() {
        val (plain, withLoader) = joinAFailingLoaderlessRead(spec { external() })
        plain.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
        withLoader.assert().isEqualTo("loaded")
    }

    /**
     * `unknownRead = ABSENT`：不带 loader 的一方读到不存在（不缓存），合并到它的带 loader 的一方同样不接受这个结果（共享的结果仍是
     * `Unknown`），用自己的 loader 降级。
     */
    @Test
    fun callSiteLoaderJoiningADegradedLoaderlessReadUsesItsOwnLoader() {
        val (plain, withLoader) = joinAFailingLoaderlessRead(
            spec {
                external()
                unknownRead = UnknownReadMode.ABSENT
            }
        )
        plain.getOrThrow().assert().isNull()
        withLoader.assert().isEqualTo("loaded")
    }

    /**
     * 不带 loader 的读取在 L1 读取中途被挡住，带 loader 的读取合并到它；随后两者的 L1 读取都失败。
     * 返回两者的结果（前者可能失败）；带 loader 的一方的降级填充是唯一的一次。
     */
    private fun joinAFailingLoaderlessRead(spec: CacheSpec<String, String>): Pair<Result<String?>, String?> {
        val cache = runtime().engine(spec)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.afterRead = { _, _ ->
            reading.countDown()
            release.awaitOrFail("release")
            throw RemoteUnavailableException("read")
        }
        val plain = fork("plain") { runCatching { cache["k"] } }
        reading.awaitOrFail("plain read")
        val withLoader = fork("loader") { cache.get("k") { "loaded" } }
        awaitCondition("joined the plain read") { withLoader.parked }
        backend.afterRead = null
        backend.failAll = true
        release.countDown()
        val results = plain.join() to withLoader.join()
        cache.metrics.degradedFills.assert().isEqualTo(1)
        return results
    }

    /**
     * [callSiteLoaderJoiningAFailedLoaderlessReadUsesItsOwnLoader] 的异步与批量版本。
     */
    @Test
    fun asyncAndBatchJoinersOfAFailedLoaderlessReadUseTheirOwnLoader() {
        val cache = runtime().engine(spec { external() })
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.afterReadAll = { _ ->
            reading.countDown()
            release.awaitOrFail("release")
            throw RemoteUnavailableException("readAll")
        }
        val batch = fork("batch") { cache.async().getAll(listOf("k", "other")).handle { _, error -> error }.join() }
        reading.awaitOrFail("batch read")
        val withLoader = cache.async().get("k", AsyncCacheLoader { CompletableFuture.completedFuture("loaded") })
        val plain = cache.async().get("other")
        withLoader.isDone.assert().isFalse()
        backend.afterReadAll = null
        backend.failAll = true
        release.countDown()
        batch.join().assert().isInstanceOf(CacheReadException::class.java)
        withLoader.join().assert().isEqualTo("loaded")
        plain.handle { _, error -> error }.join().assert().isInstanceOf(CacheReadException::class.java)
        runCatching { cache.getAll(listOf("k", "other")) }.exceptionOrNull()
            .assert().isInstanceOf(CacheReadException::class.java)
    }

    /**
     * 关闭之前已经发起、L1 读取在运行时关闭之后才失败的异步读取（没有数据源）：以原始异常失败，与有数据源时相同
     * （关闭时不降级、不包装为 CacheReadException）。关闭之后才发起的调用不启动（`AsyncContext.runAsync`）。
     */
    @Test
    fun loaderlessAsyncReadFailureAfterCloseFailsWithTheRemoteError() {
        val runtime = runtime()
        val cache = runtime.engine(spec { external() })
        val reading = CountDownLatch(2)
        val release = CountDownLatch(1)
        backend.afterRead = { key, read ->
            if (key.endsWith(":k")) {
                reading.countDown()
                release.awaitOrFail("release the single-key read")
                throw RemoteUnavailableException("read")
            }
            read
        }
        backend.afterReadAll = {
            reading.countDown()
            release.awaitOrFail("release the batch read")
            throw RemoteUnavailableException("readAll")
        }
        val single = fork("single") { cache.async().get("k").handle { _, error -> error }.join() }
        val batch = fork("batch") { cache.async().getAll(listOf("a", "b")).handle { _, error -> error }.join() }
        reading.awaitOrFail("both reads are in flight")
        // close 先置关闭状态、再等在途的 L1 步骤：关闭之后发起的读取立即失败，说明状态已置
        val closing = fork("closing") { runtime.close() }
        // 每次探测换一个 key：关闭之前的探测把“不存在”填进 L2，同一个 key 之后命中 L2，不再进入 runAsync
        val probes = AtomicInteger()
        awaitCondition("the runtime is closed to new calls") {
            val probe = "probe-${probes.incrementAndGet()}"
            cache.async().get(probe).handle { _, error -> error }.join() is IllegalStateException
        }
        release.countDown()
        single.join().assert().isInstanceOf(RemoteUnavailableException::class.java)
        batch.join().assert().isInstanceOf(RemoteUnavailableException::class.java)
        closing.join()
    }

    @Test
    fun hugeTtlFromTtlFunctionIsBounded() {
        val cache = runtime().engine(spec { ttlOf = TtlFunction { _, _ -> Duration.ofSeconds(Long.MAX_VALUE) } })
        database["k"] = "v"
        cache["k"].assert().isEqualTo("v")
        cache["k"].assert().isEqualTo("v")
        loads.get().assert().isEqualTo(1)
        cache.put("p", "v", Duration.ofSeconds(Long.MAX_VALUE))
        cache["p"].assert().isEqualTo("v")
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun failingTtlFunctionReleasesTheLease() {
        val failure = IllegalStateException("ttlOf failed")
        val cache = runtime().engine(spec { ttlOf = TtlFunction { _, _ -> throw failure } })
        database["k"] = "v"
        database["a"] = "a"
        runCatching { cache["k"] }.exceptionOrNull().assert().isSameAs(failure)
        Suspending.await {
            memory.store(
                Keyspace("cocache:", name)
            ).read(key("k"), null, null, null)
        }.assert().isEqualTo(L1Read.Miss(false))
        runCatching { cache.getAll(listOf("a")) }.exceptionOrNull().assert().isSameAs(failure)
        Suspending.await {
            memory.store(
                Keyspace("cocache:", name)
            ).read(key("a"), null, null, null)
        }.assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * #886：批量读取已为未命中的 key 取得 lease，命中的 key 解码抛出 [Error]（不是 `Exception`，不按损坏处理）：原样传出，
     * 未命中的 key 的 lease 也已释放。命中在前时，未命中的 key 在解码失败时还没有分类。
     */
    @Test
    fun aDecodeErrorOnABatchHitReleasesTheLeaseOfAMissNotYetClassified() =
        assertBatchErrorReleasesLeases(BatchFailure.DECODE, BatchVariant.SYNC, hitFirst = true)

    /**
     * #886：未命中在前（已经分类、等待回源），随后命中的 key 解码抛出 [Error]。
     */
    @Test
    fun anAsyncDecodeErrorOnABatchHitReleasesTheLeaseOfAClassifiedMiss() =
        assertBatchErrorReleasesLeases(BatchFailure.DECODE, BatchVariant.ASYNC, hitFirst = false)

    @Test
    fun aSuspendingDecodeErrorOnABatchHitReleasesTheLeaseOfAMiss() =
        assertBatchErrorReleasesLeases(BatchFailure.DECODE, BatchVariant.SUSPENDING, hitFirst = true)

    /**
     * #886 的对照：数据源、`Codec.encode` 与 `ttlOf` 抛出同样的 [Error] 时，整批 lease 与回源许可同样归还。
     */
    @Test
    fun aSourceErrorInABatchReleasesItsLeasesAndThePermit() =
        assertBatchErrorReleasesLeases(BatchFailure.SOURCE, BatchVariant.SYNC)

    @Test
    fun anEncodingErrorInABatchReleasesItsLeasesAndThePermit() =
        assertBatchErrorReleasesLeases(BatchFailure.ENCODE, BatchVariant.SYNC)

    @Test
    fun aTtlFunctionErrorInABatchReleasesItsLeasesAndThePermit() =
        assertBatchErrorReleasesLeases(BatchFailure.TTL, BatchVariant.ASYNC)

    /**
     * #886：带版本的缓存的批量读取走同一条路径，命中的 key 解码抛出 [Error] 时同样释放未命中的 key 的 lease。
     */
    @Test
    fun aDecodeErrorOnAVersionedBatchHitReleasesTheLeaseOfAMiss() {
        val failure = AssertionError("decode failed")
        val codec = ArmedCodec(BatchFailure.DECODE, failure)
        val runtime = runtime()
        val cache = runtime.create(
            versionedCacheSpec<String, String>(name) {
                this.codec = codec
                versionedLoader = CacheLoader<String, Versioned<String?>> { key -> Versioned("source-$key", 1) }
            }
        )
        cache.putIfNewer("cached", "seed", 1)
        codec.armed.set(true)
        runCatching { cache.getAll(listOf("cached", "missing")) }.exceptionOrNull().assert().isSameAs(failure)
        codec.armed.set(false)
        val admin = requireNotNull(runtime.admin(name))
        admin.inspect("missing").remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS)
        cache.getAll(listOf("missing")).assert().isEqualTo(mapOf("missing" to "source-missing"))
        admin.inspect("missing").remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE)
    }

    private enum class BatchFailure { DECODE, SOURCE, ENCODE, TTL }

    private enum class BatchVariant { SYNC, ASYNC, SUSPENDING }

    /**
     * 打开时在 [point]（解码或编码）抛出 [failure]，否则同 [TestCodec]。
     */
    private class ArmedCodec(private val point: BatchFailure, private val failure: Error) : Codec<String> {
        val armed = AtomicBoolean()

        override fun encode(value: String): ByteArray {
            if (armed.get() && point == BatchFailure.ENCODE) throw failure
            return TestCodec.encode(value)
        }

        override fun decode(bytes: ByteArray): String {
            if (armed.get() && point == BatchFailure.DECODE) throw failure
            return TestCodec.decode(bytes)
        }
    }

    /**
     * #886 的场景：`cached` 经 `put` 写入 L1（写入方不填 L2，随后的读取从 L1 命中并解码）。打开故障之后，批量读取以原样的 [Error] 失败，
     * 这次批量读取取得的 lease 全部释放（L1 为未命中，不是 lease 占位）。关闭故障之后，批量读取凭新的 lease 写回 L1，
     * 单 key 读取也取得回源许可（`maxConcurrent = 1`、不等待许可）。批量读取不等待遗留的 lease，所以不必推进时钟。
     */
    private fun assertBatchErrorReleasesLeases(point: BatchFailure, variant: BatchVariant, hitFirst: Boolean = true) {
        val failure = AssertionError("user component failed")
        val codec = ArmedCodec(point, failure)
        val runtime = runtime()
        val cache = runtime.create(
            cacheSpec<String, String>(name) {
                this.codec = codec
                loader = CacheLoader { key ->
                    if (codec.armed.get() && point == BatchFailure.SOURCE) throw failure
                    "source-$key"
                }
                ttlOf = TtlFunction { _, _ ->
                    if (codec.armed.get() && point == BatchFailure.TTL) throw failure
                    Duration.ofMinutes(1)
                }
                policy = CachePolicy(
                    ttl = Duration.ofMinutes(10),
                    ttlJitter = Duration.ZERO,
                    load = LoadPolicy(maxConcurrent = 1, acquireTimeout = Duration.ZERO)
                )
            }
        )
        cache.put("cached", "seed")
        val leased = if (point == BatchFailure.DECODE) listOf("missing") else listOf("a", "b")
        val requested = when {
            point != BatchFailure.DECODE -> leased
            hitFirst -> listOf("cached", "missing")
            else -> listOf("missing", "cached")
        }
        codec.armed.set(true)
        val error = when (variant) {
            BatchVariant.SYNC -> runCatching { cache.getAll(requested) }.exceptionOrNull()
            BatchVariant.ASYNC -> cache.async().getAll(requested).handle { _, failed -> failed }.join()
            BatchVariant.SUSPENDING -> runCatching { suspendingGetAll(cache, requested) }.exceptionOrNull()
        }
        codec.armed.set(false)
        error.assert().isSameAs(failure)
        val admin = requireNotNull(runtime.admin(name))
        leased.forEach {
            admin.inspect(it).remote.state.assert().withFailMessage { "$point $variant: key[$it] keeps its lease" }
                .isEqualTo(KeyInspection.RemoteState.MISS)
        }
        cache.getAll(leased).assert().isEqualTo(leased.associateWith { "source-$it" })
        cache["probe"].assert().isEqualTo("source-probe")
        leased.forEach { admin.inspect(it).remote.state.assert().isEqualTo(KeyInspection.RemoteState.VALUE) }
    }

    private fun suspendingGetAll(cache: Cache<String, String>, keys: List<String>): Map<String, String> =
        runBlocking { cache.suspending().getAll(keys) }

    @Test
    fun refreshDoesNotRefillFromRemoteWhileInvalidationIsPending() {
        val policy = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5)
        )
        val cache = runtime(
            RetryPolicy(initialBackoff = Duration.ofHours(1), maxBackoff = Duration.ofHours(1))
        ).engine(spec(policy))
        cache.put("k", "v0")
        database["k"] = "v1"
        backend.failWrites = true
        // 失效写入 L1 失败：L1 仍是 v0，本节点绕过 L1 加载到 v1
        cache.invalidate("k")
        cache["k"].assert().isEqualTo("v1")
        // 刷新令牌被其它节点持有：旧实现会从 L1 读回 v0 填充 L2
        clock.advance(Duration.ofSeconds(6))
        val foreignRefresh = RefreshRequest(Duration.ofSeconds(5), Token(NodeId.random(), 9), Duration.ofMinutes(1))
        Suspending.await { memory.store(Keyspace("cocache:", name)).read(key("k"), null, foreignRefresh, null) }
        cache["k"].assert().isEqualTo("v1")
        cache["k"].assert().isEqualTo("v1")
    }

    @Test
    fun refreshReleasesGrantedTokenWhenFormatIsUnknown() {
        val policy = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5)
        )
        val cache = runtime().engine(spec(policy))
        database["k"] = "v0"
        cache["k"]
        clock.advance(Duration.ofSeconds(6))
        backend.afterRead = { _, read ->
            backend.afterRead = null
            if (read is L1Read.Hit) read.copy(format = read.format + 1) else read
        }
        cache["k"]
        // 令牌已释放：其它节点可以立即取得刷新令牌
        val foreignRefresh = RefreshRequest(Duration.ofSeconds(5), Token(NodeId.random(), 9), Duration.ofMinutes(1))
        val other = Suspending.await {
            memory.store(
                Keyspace("cocache:", name)
            ).read(key("k"), null, foreignRefresh, null)
        }
        (other as L1Read.Hit).refreshGranted.assert().isTrue()
    }

    /**
     * D3（GA 前的架构审查）：没取得刷新令牌时，刷新以读到的值重新填充 L2，解码抛出的 [Error] 不是损坏，同命中的判定（只把 `Exception`
     * 按损坏处理）与 #886：原样传出，不吞掉之后改走普通加载（那会多读一次 L1、再解码一次）。这时不持有令牌，没有要释放的。
     */
    @Test
    fun anErrorDecodingARefreshWithoutTheTokenIsNotSwallowed() {
        val failure = AssertionError("decode failed")
        val failOnce = AtomicBoolean()
        val codec = object : Codec<String> {
            override fun encode(value: String): ByteArray = TestCodec.encode(value)

            override fun decode(bytes: ByteArray): String {
                if (failOnce.compareAndSet(true, false)) throw failure
                return TestCodec.decode(bytes)
            }
        }
        val refreshFailures = CopyOnWriteArrayList<Throwable>()
        refreshExecutor = Executor { task ->
            try {
                task.run()
            } catch (e: Throwable) {
                refreshFailures.add(e)
            }
        }
        val policy = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5)
        )
        val cache = runtime().engine(spec(policy) { this.codec = codec })
        database["k"] = "v0"
        cache["k"].assert().isEqualTo("v0")
        clock.advance(Duration.ofSeconds(6))
        // 刷新令牌在其它节点手里：本节点的刷新只能从 L1 重新填充 L2
        val foreignRefresh = RefreshRequest(Duration.ofSeconds(5), Token(NodeId.random(), 9), Duration.ofMinutes(1))
        Suspending.await { memory.store(Keyspace("cocache:", name)).read(key("k"), null, foreignRefresh, null) }
        val reads = AtomicInteger()
        backend.afterRead = { _, read ->
            reads.incrementAndGet()
            read
        }
        failOnce.set(true)

        cache["k"].assert().isEqualTo("v0")

        refreshFailures.assert().containsExactly(failure)
        reads.get().assert().withFailMessage { "the refresh re-read L1 after swallowing the error" }.isEqualTo(1)
        loads.get().assert().isEqualTo(1)
    }

    /**
     * D4（GA 前的架构审查）：没有数据源的缓存（只经写入或 Spring Cache 填充）声明了 `refreshWindow` 时，窗口内的 L2 命中不发起刷新，
     * 也不为此分配：判定刷新与否之前不拼接存储 key。按线程的分配计数衡量（HotSpot），平均每次命中不到 1 字节。
     */
    @Test
    fun aSourcelessHitInsideTheRefreshWindowAllocatesNothing() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        Assumptions.assumeTrue(threads?.isThreadAllocatedMemorySupported == true, "thread allocation counting")
        val counter = requireNotNull(threads)
        counter.isThreadAllocatedMemoryEnabled = true
        val policy = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5)
        )
        val cache = runtime().engine(spec(policy) { external() })
        cache.put("k", "v")
        cache["k"].assert().isEqualTo("v")
        clock.advance(Duration.ofSeconds(6))
        repeat(WARMUP_HITS) { cache["k"] }
        val before = counter.currentThreadAllocatedBytes
        repeat(MEASURED_HITS) { cache["k"] }
        val allocated = counter.currentThreadAllocatedBytes - before
        allocated.assert().withFailMessage { "$MEASURED_HITS hits allocated $allocated bytes" }
            .isLessThan(MEASURED_HITS.toLong())
        requireNotNull(runtimes.single().metrics(name)).refreshes.assert().isZero()
    }

    @Test
    fun overlappingFailedInvalidationsKeepTheKeyPending() {
        val cache = runtime(
            RetryPolicy(capacity = 1, initialBackoff = Duration.ofHours(1), maxBackoff = Duration.ofHours(1))
        ).engine()
        backend.failWrites = true
        cache.invalidate("k")
        // 第二次失效被满队列丢弃，但第一次仍在重试：key 必须保持“重试中”
        cache.invalidate("k")
        cache.metrics.invalidationsDropped.assert().isEqualTo(1)
        cache.pendingInvalidationCount.assert().isEqualTo(1)
    }

    @Test
    fun closingTheRuntimeCountsQueuedRetriesAsDropped() {
        val node = runtime(RetryPolicy(initialBackoff = Duration.ofHours(1), maxBackoff = Duration.ofHours(1)))
        val cache = node.engine()
        backend.failWrites = true
        cache.invalidate("k")
        node.close()
        cache.metrics.invalidationsDropped.assert().isEqualTo(1)
        cache.pendingInvalidationCount.assert().isZero()
    }

    @Test
    fun errorDuringRetryGivesUp() {
        val cache = runtime(
            RetryPolicy(maxAttempts = 1, initialBackoff = Duration.ofMillis(1), maxBackoff = Duration.ofMillis(1))
        ).engine()
        val calls = AtomicInteger()
        backend.beforeInvalidate = {
            if (calls.incrementAndGet() == 1) throw IllegalStateException("down") else throw AssertionError("fatal")
        }
        cache.invalidate("k")
        awaitCondition { cache.metrics.invalidationsDropped == 1L }
        cache.pendingInvalidationCount.assert().isZero()
    }

    /**
     * #816：同步 loader 执行超过 `load.timeout` 之后（运行时的计时源推进到截止时刻之后）再读本线程正在加载的同一个 key，
     * 经 `get` 或 `getAll` 都快速失败，loader 只执行一次；而不是注销自己的加载、递归地再回源一次。
     */
    @Test
    fun aSlowSyncLoaderReenteringItsOwnKeyFailsFast() {
        val nanos = AtomicLong()
        val timeout = Duration.ofSeconds(30)
        val builder = CacheRuntime.builder().backend(backend).clock(clock)
        builder.ticker = Ticker { nanos.get() }
        val runtime = builder.build().also { runtimes.add(it) }
        val reads: List<(Cache<String, String>, String) -> String?> = listOf(
            { cache, key -> cache[key] },
            { cache, key -> cache.getAll(listOf(key))[key] }
        )
        for ((index, read) in reads.withIndex()) {
            val calls = AtomicInteger()
            lateinit var cache: Cache<String, String>
            cache = runtime.create(
                cacheSpec("reentry$index") {
                    codec = TestCodec
                    policy = CachePolicy(
                        load = LoadPolicy(timeout = timeout),
                        lease = LeasePolicy(wait = Duration.ZERO)
                    )
                    loader = CacheLoader { key ->
                        if (calls.incrementAndGet() == 1) {
                            nanos.addAndGet(timeout.toNanos() + 1)
                            read(cache, key)
                        } else {
                            "nested"
                        }
                    }
                }
            )
            val error = runCatching { read(cache, "k") }.exceptionOrNull()
            error.assert().isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Recursive call")
            calls.get().assert().isEqualTo(1)
        }
    }

    /**
     * 外部来源（`externallySourced()`）：去掉用例默认的数据源。
     */
    private fun CacheSpec.Builder<String, String>.external() {
        loader = null
        externallySourced()
    }

    private companion object {
        const val WARMUP_HITS = 50_000
        const val MEASURED_HITS = 100_000
    }
}
