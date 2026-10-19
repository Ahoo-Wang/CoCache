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
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyFilter
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LocalPolicy
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引擎的降级、批量与刷新分支（TCK 之外的白盒用例）。[AsyncCoherentEngineTest] 经异步视图重跑全部用例。
 */
open class CoherentEngineTest {
    /**
     * 访问缓存的方式：同步 API，或经异步视图（RFC 0004）。
     */
    protected open val access: CacheAccess = CacheAccess.SYNC

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
            loader = this@CoherentEngineTest.loader
            this.policy = policy
            block()
            access.source(this)
        }

    /**
     * 用例拿到的缓存：经 [access] 访问，另暴露引擎的白盒状态。
     */
    private class TestCache(val engine: CoherentEngine<String, String>, access: CacheAccess) :
        Cache<String, String> by access.cache(engine) {
        val metrics get() = engine.metrics
        val pendingInvalidationCount get() = engine.pendingInvalidationCount
        val localSize get() = engine.localSize

        fun close() = engine.close()
    }

    private fun CacheRuntime.engine(spec: CacheSpec<String, String> = spec()): TestCache =
        TestCache(create(spec) as CoherentEngine<String, String>, access)

    private fun key(key: String) = "cocache:$name:$key"

    private fun silentPut(key: String, envelope: Envelope) {
        memory.store(name).put(key(key), envelope, null, InvalidationFrame.encode(NodeId.random(), emptyList()))
    }

    private fun foreignLease(key: String): Token {
        val token = Token(NodeId.random(), 1)
        memory.store(name).read(key(key), LeaseRequest(token, Duration.ofMinutes(1)), null)
        return token
    }

    @Test
    fun getWithCallerLoaderServesLocalHitsAndUsesLease() {
        val cache = runtime().engine(spec { loader = null })
        val calls = AtomicInteger()
        cache.get("k") {
            calls.incrementAndGet()
            "loaded-$it"
        }.assert().isEqualTo("loaded-k")
        cache.get("k") {
            calls.incrementAndGet()
            "again"
        }.assert().isEqualTo("loaded-k")
        calls.get().assert().isEqualTo(1)
        (
            memory.store(
                name
            ).read(
                key("k"),
                null,
                null
            ) as RemoteRead.Hit
            ).envelope.assert().isEqualTo(Envelope.Scalar("loaded-k".toByteArray()))
    }

    @Test
    fun cacheWithoutLoaderNeverRefreshes() {
        val policy =
            CachePolicy(
                ttl = Duration.ofSeconds(10),
                ttlJitter = Duration.ZERO,
                localMaxAge = Duration.ofSeconds(8),
                refreshWindow = Duration.ofSeconds(5)
            )
        val cache = runtime().engine(spec(policy) { loader = null })
        cache.put("k", "v")
        cache["k"].assert().isEqualTo("v")
        clock.advance(Duration.ofSeconds(6))
        cache["k"].assert().isEqualTo("v")
        cache.metrics.refreshes.assert().isEqualTo(0)
    }

    @Test
    fun strictReadFailureThrowsInsteadOfDegrading() {
        val strict = CachePolicy(
            ttl = Duration.ofMinutes(10),
            ttlJitter = Duration.ZERO,
            readFailure = me.ahoo.cocache.core.policy.ReadFailureMode.THROW
        )
        val cache = runtime().engine(spec(strict))
        database["k"] = "v"
        backend.failAll = true
        val single = runCatching { cache["k"] }.exceptionOrNull()
        single.assert().isInstanceOf(me.ahoo.cocache.api.CacheReadException::class.java)
        (single as me.ahoo.cocache.api.CacheReadException).operation.assert().isEqualTo("read")
        single.cacheName.assert().isEqualTo(name)
        runCatching {
            cache.getAll(
                listOf("k")
            )
        }.exceptionOrNull().assert().isInstanceOf(me.ahoo.cocache.api.CacheReadException::class.java)
        backend.failAll = false
        loads.get().assert().isEqualTo(0)
        cache.metrics.remoteFailures.assert().isEqualTo(2)
        cache["k"].assert().isEqualTo("v")
    }

    @Test
    fun strictReclaimFailureThrows() {
        val strict = CachePolicy(
            ttl = Duration.ofMinutes(10),
            ttlJitter = Duration.ZERO,
            readFailure = me.ahoo.cocache.core.policy.ReadFailureMode.THROW
        )
        val cache = runtime().engine(spec(strict))
        database["k"] = "v"
        silentPut("k", Envelope.Scalar(TestCodec.CORRUPT))
        backend.afterRead = { _, read ->
            backend.failAll = true
            read
        }
        runCatching { cache["k"] }.exceptionOrNull().assert().isInstanceOf(
            me.ahoo.cocache.api.CacheReadException::class.java
        )
        backend.failAll = false
        backend.afterRead = null
    }

    @Test
    fun keyFilterThatAllowsLoadsNormally() {
        val cache = runtime().engine(spec { keyFilter = KeyFilter { false } })
        database["k"] = "v"
        cache["k"].assert().isEqualTo("v")
        cache.getAll(listOf("k")).assert().isEqualTo(mapOf("k" to "v"))
    }

    @Test
    fun missWithoutGrantedLeaseLoadsWithoutFilling() {
        val cache = runtime().engine()
        database["k"] = "v"
        backend.afterRead = { _, read -> if (read is RemoteRead.Miss) RemoteRead.Miss(false) else read }
        cache["k"].assert().isEqualTo("v")
        cache.metrics.degradedFills.assert().isEqualTo(1)
        cache.metrics.fillRejected.assert().isEqualTo(0)
    }

    @Test
    fun releaseFailureDoesNotHideLoaderFailure() {
        val cache = runtime().engine()
        val failure = IllegalStateException("source down")
        loadFailure = failure
        backend.failRelease = true
        runCatching { cache["k"] }.exceptionOrNull().assert().isSameAs(failure)
        cache.metrics.remoteFailures.assert().isEqualTo(1)
    }

    @Test
    fun fillFailureDegrades() {
        val cache = runtime().engine()
        database["k"] = "v"
        backend.failFill = true
        cache["k"].assert().isEqualTo("v")
        cache.metrics.fillRejected.assert().isEqualTo(1)
        cache.metrics.remoteFailures.assert().isEqualTo(1)
    }

    @Test
    fun reclaimFailureBypassesRemote() {
        val cache = runtime().engine()
        database["k"] = "v"
        silentPut("k", Envelope.Scalar(TestCodec.CORRUPT))
        backend.afterRead = { _, read ->
            // 回收之前内容已被并发写入改变
            if (read is RemoteRead.Hit) silentPut("k", Envelope.Scalar(TestCodec.CORRUPT + byteArrayOf(1)))
            read
        }
        cache["k"].assert().isEqualTo("v")
        cache.metrics.reclaims.assert().isEqualTo(1)
        cache.metrics.degradedFills.assert().isEqualTo(1)
    }

    @Test
    fun reclaimErrorBypassesRemote() {
        val cache = runtime().engine()
        database["k"] = "v"
        silentPut("k", Envelope.Scalar(TestCodec.CORRUPT))
        backend.afterRead = { _, read ->
            backend.failAll = true
            read
        }
        cache["k"].assert().isEqualTo("v")
        backend.failAll = false
        cache.metrics.remoteFailures.assert().isEqualTo(1)
    }

    @Test
    fun setShapeInRemoteIsTreatedAsCorrupt() {
        val cache = runtime().engine()
        database["k"] = "v"
        silentPut("k", Envelope.Members(listOf("a".toByteArray())))
        cache["k"].assert().isEqualTo("v")
        cache.metrics.reclaims.assert().isEqualTo(1)
    }

    @Test
    fun unrecognizedRemoteContentIsReclaimed() {
        val cache = runtime().engine()
        database["k"] = "v"
        silentPut("k", Envelope.Scalar("{\"legacy\":true}".toByteArray()))
        backend.afterRead = { _, read ->
            backend.afterRead = null
            if (read is RemoteRead.Hit) read.copy(format = 0) else read
        }
        cache["k"].assert().isEqualTo("v")
        cache.metrics.reclaims.assert().isEqualTo(1)
        (
            memory.store(
                name
            ).read(key("k"), null, null) as RemoteRead.Hit
            ).envelope.assert().isEqualTo(Envelope.Scalar("v".toByteArray()))
    }

    @Test
    fun unrecognizedRemoteContentInBatchIsReclaimed() {
        val cache = runtime().engine()
        database["k"] = "v"
        silentPut("k", Envelope.Scalar("legacy".toByteArray()))
        backend.afterReadAll = { reads -> reads.mapValues { (_, read) -> if (read is RemoteRead.Hit) read.copy(format = 0) else read } }
        cache.getAll(listOf("k")).assert().isEqualTo(mapOf("k" to "v"))
        cache.metrics.reclaims.assert().isEqualTo(1)
        cache.metrics.degradedFills.assert().isZero()
        (
            memory.store(
                name
            ).read(key("k"), null, null) as RemoteRead.Hit
            ).envelope.assert().isEqualTo(Envelope.Scalar("v".toByteArray()))
    }

    @Test
    fun remoteEventDuringRemoteReadDiscardsTheLocalFill() {
        val node = runtime()
        val cache = node.engine()
        val other = runtime().engine()
        cache.put("k", "v0")
        backend.afterRead = { _, read ->
            backend.afterRead = null
            other.put("k", "v1")
            read
        }
        cache["k"].assert().isEqualTo("v0")
        cache.metrics.staleFillsDiscarded.assert().isEqualTo(1)
        cache["k"].assert().isEqualTo("v1")
    }

    /**
     * 编码后碰撞的存储 key：以后出现的为准，每个存储 key 只编码、写入一次。
     */
    @Test
    fun putAllCollidingStorageKeysLastWins() {
        val encodes = AtomicInteger()
        val countingCodec = object : me.ahoo.cocache.api.Codec<String> {
            override fun encode(value: String): ByteArray {
                encodes.incrementAndGet()
                return TestCodec.encode(value)
            }

            override fun decode(bytes: ByteArray): String = TestCodec.decode(bytes)
        }
        val cache = runtime().engine(
            spec {
                codec = countingCodec
                keyEncoder = me.ahoo.cocache.core.KeyEncoder { it.lowercase() }
            }
        )
        cache.putAll(linkedMapOf("K" to "first", "k" to "second", "j" to "j"))
        encodes.get().assert().isEqualTo(2)
        backend.putAlls.get().assert().isEqualTo(1)
        cache["K"].assert().isEqualTo("second")
        cache["k"].assert().isEqualTo("second")
        cache["j"].assert().isEqualTo("j")
    }

    @Test
    fun nonAtomicBackendPublishesPutAllThroughTheChannel() {
        backend.atomicBroadcastOverride = false
        val cacheA = runtime().engine()
        val cacheB = runtime().engine()
        database["a"] = "a0"
        database["b"] = "b0"
        cacheB["a"]
        cacheB["b"]
        cacheA.putAll(mapOf("a" to "a1", "b" to "b1"))
        awaitCondition { cacheB["a"] == "a1" && cacheB["b"] == "b1" }
        cacheB.metrics.invalidationsReceived.assert().isGreaterThanOrEqualTo(1)
    }

    @Test
    fun nonAtomicBackendPublishesThroughTheChannel() {
        backend.atomicBroadcastOverride = false
        val cacheA = runtime().engine()
        val cacheB = runtime().engine()
        database["k"] = "v0"
        cacheB["k"]
        cacheA.put("k", "v1")
        awaitCondition { cacheB["k"] == "v1" }
        cacheA.invalidate("k")
        cacheB.metrics.invalidationsReceived.assert().isGreaterThanOrEqualTo(2)
    }

    @Test
    fun batchSkipsLeasedAndNewerFormatKeysAndReclaimsCorrupt() {
        val cache = runtime().engine()
        database["leased"] = "l"
        database["corrupt"] = "c"
        database["newer"] = "n"
        foreignLease("leased")
        silentPut("corrupt", Envelope.Scalar(TestCodec.CORRUPT))
        silentPut("newer", Envelope.Scalar("written-by-newer".toByteArray()))
        backend.afterReadAll = { reads ->
            reads.mapValues { (k, read) -> if (k == key("newer") && read is RemoteRead.Hit) read.copy(format = read.format + 1) else read }
        }
        val values = cache.getAll(listOf("leased", "corrupt", "newer"))
        values.assert().isEqualTo(mapOf("leased" to "l", "corrupt" to "c", "newer" to "n"))
        cache.metrics.degradedFills.assert().isEqualTo(2)
        cache.metrics.reclaims.assert().isEqualTo(1)
        cache.metrics.unknownFormats.assert().isEqualTo(1)
        (
            memory.store(
                name
            ).read(
                key("corrupt"),
                null,
                null
            ) as RemoteRead.Hit
            ).envelope.assert().isEqualTo(Envelope.Scalar("c".toByteArray()))
        memory.store(name).read(key("leased"), null, null).assert().isInstanceOf(RemoteRead.Leased::class.java)
        (
            memory.store(
                name
            ).read(
                key("newer"),
                null,
                null
            ) as RemoteRead.Hit
            ).envelope.assert().isEqualTo(Envelope.Scalar("written-by-newer".toByteArray()))
    }

    @Test
    fun batchLoaderFailureReleasesLeases() {
        val cache = runtime().engine()
        val failure = IllegalStateException("source down")
        loadFailure = failure
        runCatching { cache.getAll(listOf("a", "b")) }.exceptionOrNull().assert().isSameAs(failure)
        memory.store(name).read(key("a"), null, null).assert().isEqualTo(RemoteRead.Miss(false))
        memory.store(name).read(key("b"), null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun batchFillFailureDegrades() {
        val cache = runtime().engine()
        database["a"] = "a"
        backend.failFill = true
        cache.getAll(listOf("a")).assert().isEqualTo(mapOf("a" to "a"))
        cache.metrics.fillRejected.assert().isEqualTo(1)
        cache.metrics.degradedFills.assert().isEqualTo(1)
    }

    @Test
    fun batchBypassesRemoteForPendingInvalidations() {
        val cache = runtime(
            RetryPolicy(initialBackoff = Duration.ofHours(1), maxBackoff = Duration.ofHours(1))
        ).engine()
        cache.put("a", "a0")
        database["a"] = "a1"
        backend.failWrites = true
        cache.invalidate("a")
        cache.pendingInvalidationCount.assert().isEqualTo(1)
        cache.getAll(listOf("a")).assert().isEqualTo(mapOf("a" to "a1"))
    }

    @Test
    fun batchRefreshesLocalHitsInsideTheWindow() {
        val policy = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5)
        )
        val cache = runtime().engine(spec(policy))
        database["k"] = "v0"
        cache.getAll(listOf("k"))
        database["k"] = "v1"
        clock.advance(Duration.ofSeconds(6))
        cache.getAll(listOf("k")).assert().isEqualTo(mapOf("k" to "v0"))
        cache["k"].assert().isEqualTo("v1")
    }

    @Test
    fun refreshFallsBackToLoadWhenRemoteIsGoneOrFailing() {
        val policy = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5)
        )
        val cache = runtime().engine(spec(policy))
        database["k"] = "v0"
        cache["k"]
        memory.store(name).invalidate(listOf(key("k")), InvalidationFrame.encode(NodeId.random(), emptyList()))
        database["k"] = "v1"
        clock.advance(Duration.ofSeconds(6))
        cache["k"].assert().isEqualTo("v0")
        cache["k"].assert().isEqualTo("v1")

        clock.advance(Duration.ofSeconds(6))
        backend.afterRead = { _, read ->
            if (backend.afterRead != null) {
                backend.afterRead = null
                throw IllegalStateException("read failed")
            }
            read
        }
        database["k"] = "v2"
        cache["k"]
        // 刷新读取失败时退回普通加载：L1 中的 v1 仍然有效，只重新填充 L2
        cache["k"].assert().isEqualTo("v1")
        cache.metrics.remoteFailures.assert().isEqualTo(1)
    }

    @Test
    fun refreshWithCorruptRemoteValueReloads() {
        val policy = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5)
        )
        val cacheA = runtime().engine(spec(policy))
        val cacheB = runtime().engine(spec(policy))
        database["k"] = "v0"
        cacheA["k"]
        cacheB["k"]
        clock.advance(Duration.ofSeconds(6))
        // A 取得刷新令牌；B 读到的值损坏，退回普通加载
        refreshExecutor = Executor { }
        cacheA["k"]
        refreshExecutor = Executor { it.run() }
        memory.store(
            name
        ).read(key("k"), null, RefreshRequest(Duration.ofSeconds(5), Token(NodeId.random(), 9), Duration.ofMinutes(1)))
        backend.afterRead = { _, read -> if (read is RemoteRead.Hit) read.copy(envelope = Envelope.Scalar(TestCodec.CORRUPT)) else read }
        cacheB["k"].assert().isEqualTo("v0")
        backend.afterRead = null
    }

    @Test
    fun refreshFailureAndRejectionAreSwallowed() {
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
        loadFailure = IllegalStateException("source down")
        cache["k"].assert().isEqualTo("v0")
        loadFailure = null
        memory.store(name).read(key("k"), null, null).assert().isInstanceOf(RemoteRead.Hit::class.java)

        refreshExecutor = Executor { throw RejectedExecutionException("full") }
        cache["k"].assert().isEqualTo("v0")
        cache.metrics.refreshRejected.assert().isEqualTo(1)
    }

    @Test
    fun droppedInvalidationIsCounted() {
        val cache = runtime(
            RetryPolicy(maxAttempts = 1, initialBackoff = Duration.ofMillis(1), maxBackoff = Duration.ofMillis(1))
        ).engine()
        backend.failWrites = true
        cache.invalidate("k")
        awaitCondition { cache.metrics.invalidationsDropped == 1L }
        cache.pendingInvalidationCount.assert().isEqualTo(0)
    }

    @Test
    fun invalidationRejectedByFullQueueIsDropped() {
        val cache = runtime(
            RetryPolicy(capacity = 1, initialBackoff = Duration.ofHours(1), maxBackoff = Duration.ofHours(1))
        ).engine()
        backend.failWrites = true
        cache.invalidate("a")
        cache.invalidate("b")
        cache.metrics.invalidationsDropped.assert().isEqualTo(1)
        cache.pendingInvalidationCount.assert().isEqualTo(1)
    }

    @Test
    fun closeIsIdempotentAndLocalSizeIsVisible() {
        val cache = runtime().engine(
            spec(
                CachePolicy(
                    local = LocalPolicy(
                        maximumSize = 10,
                        initialCapacity = 4,
                        expireAfterAccess = Duration.ofMinutes(1)
                    )
                )
            )
        )
        database["k"] = "v"
        cache["k"]
        cache.localSize.assert().isEqualTo(1)
        cache.close()
        cache.close()
    }

    @Test
    fun metricsExposeEveryCounter() {
        val cache = runtime().engine()
        database["k"] = "v"
        cache["k"]
        val metrics = cache.metrics
        listOf(
            metrics.remoteHits, metrics.misses, metrics.filtered, metrics.loads, metrics.loadFailures, metrics.loadRejected,
            metrics.leaseWaits, metrics.leaseWaitTimeouts, metrics.fillRejected, metrics.degradedFills, metrics.staleFillsDiscarded,
            metrics.reclaims, metrics.unknownFormats, metrics.refreshes, metrics.refreshRejected, metrics.remoteFailures,
            metrics.invalidationsReceived, metrics.writeFailures, metrics.invalidationRetries, metrics.invalidationsDropped, metrics.resets,
        ).all { it >= 0 }.assert().isTrue()
        metrics.loadNanos.assert().isGreaterThanOrEqualTo(0)
        metrics.loads.assert().isEqualTo(1)
    }

    @Test
    fun leaseWaitEndsWhenHolderFills() {
        val cache = runtime().engine(
            spec(
                CachePolicy(
                    ttl = Duration.ofMinutes(10),
                    ttlJitter = Duration.ZERO,
                    lease = LeasePolicy(wait = Duration.ofSeconds(5))
                )
            )
        )
        database["k"] = "v"
        val token = foreignLease("k")
        backend.afterRead = { _, read ->
            if (read is RemoteRead.Leased) {
                // 持有者在等待者第一次读到 lease 之后写回
                backend.afterRead = null
                memory.store(name).fill(Fill(key("k"), token, Envelope.Scalar("filled".toByteArray()), null))
            }
            read
        }
        cache["k"].assert().isEqualTo("filled")
        cache.metrics.leaseWaits.assert().isEqualTo(1)
        loads.get().assert().isEqualTo(0)
    }
}

/**
 * [CoherentEngineTest] 的全部白盒用例经异步视图重跑（数据源换成在调用线程上执行的异步数据源）。
 */
class AsyncCoherentEngineTest : CoherentEngineTest() {
    override val access: CacheAccess = CacheAccess.ASYNC
}
