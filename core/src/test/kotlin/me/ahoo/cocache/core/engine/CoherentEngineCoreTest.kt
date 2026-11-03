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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.FutureL1Store
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SourceLoadCancelledException
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单 key 读与提前刷新运行在协程核心上（RFC 0005 V5）：运行时关闭取消作用域时，进行中的异步读取以关闭失败，
 * 申请的 lease 与刷新令牌照常交还；Java 实现的缺陷（future 以 `null` 完成）按 L1 读失败处理。
 */
class CoherentEngineCoreTest {
    private val memory = InMemoryBackend()
    private val runtimes = mutableListOf<CacheRuntime>()

    @AfterEach
    fun destroy() {
        runtimes.forEach { it.close() }
        io.shutdownNow()
    }

    private val io = Executors.newSingleThreadExecutor { Thread(it, "l1-io").apply { isDaemon = true } }

    private fun runtime(backend: CoherenceBackend = memory): CacheRuntime =
        CacheRuntime.builder().backend(backend).build().also { runtimes += it }

    private fun spec(block: CacheSpec.Builder<String, String>.() -> Unit) = cacheSpec<String, String>("core") {
        codec = StringCodec
        block()
    }

    private fun failureOf(future: CompletableFuture<*>): Throwable? = try {
        future.get(10, TimeUnit.SECONDS)
        null
    } catch (e: ExecutionException) {
        e.cause
    }

    /**
     * 数据源尚未完成时关闭运行时：读取以 [IllegalStateException] 失败，协程被取消之前取得的 lease 被释放，
     * 另一个节点随后的未命中直接回源，不等待 lease。
     */
    @Test
    fun closingTheRuntimeMidLoadReleasesTheLease() {
        val started = CountDownLatch(1)
        val closing = runtime()
        val cache = closing.create(
            spec {
                asyncLoader = AsyncCacheLoader {
                    started.countDown()
                    CompletableFuture<String>()
                }
            }
        )
        val read = cache.async().get("k")
        started.awaitOrFail("loader started")
        closing.close()
        val failure = failureOf(read)
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().isEqualTo("CacheRuntime closed.")

        val other = runtime()
        other.create(spec { loader = CacheLoader { "v-$it" } })["k"].assert().isEqualTo("v-k")
        requireNotNull(other.metrics("core")).leaseWaits.assert().isZero()
    }

    /**
     * 批量回源尚未完成时关闭运行时：读取以关闭失败，整批申请的 lease 都被释放，另一个节点随后的单 key 未命中不等待 lease。
     */
    @Test
    fun closingTheRuntimeMidBatchLoadReleasesTheLeases() {
        val started = CountDownLatch(1)
        val closing = runtime()
        val cache = closing.create(
            spec {
                asyncLoader = object : AsyncCacheLoader<String, String> {
                    override fun loadAsync(key: String): CompletionStage<out String?> =
                        CompletableFuture.completedFuture("v-$key")

                    override fun loadAllAsync(keys: Collection<String>): CompletionStage<out Map<String, String>> {
                        started.countDown()
                        return CompletableFuture()
                    }
                }
            }
        )
        val read = cache.async().getAll(listOf("a", "b"))
        started.awaitOrFail("batch loader started")
        closing.close()
        val failure = failureOf(read)
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().isEqualTo("CacheRuntime closed.")

        val other = runtime()
        val otherCache = other.create(spec { loader = CacheLoader { "v-$it" } })
        otherCache["a"].assert().isEqualTo("v-a")
        otherCache["b"].assert().isEqualTo("v-b")
        requireNotNull(other.metrics("core")).leaseWaits.assert().isZero()
    }

    /**
     * 异步变体：数据源自己以取消结束（数据源的 future 被取消，如 HTTP 客户端取消了请求）是普通的回源失败（RFC 0005 第三轮 K5）。
     * 合并到这次加载的调用方共享这一次回源，leader 与跟随者都收到同一个 [SourceLoadCancelledException]，而不是各自再回源
     * （实验 E4：8 个调用方回源 8 次）。lease 被释放，失败不被缓存：下一次读取重新回源。挂起变体的用例在 `cocache-coroutines`。
     */
    @Test
    fun anAsyncSourcesOwnCancellationIsSharedByTheWholeLoad() {
        val loads = AtomicInteger()
        val pending = CompletableFuture<String>()
        val runtime = runtime()
        val cache = runtime.create(
            spec {
                asyncLoader = AsyncCacheLoader { key ->
                    if (loads.getAndIncrement() == 0) pending else CompletableFuture.completedFuture("v-$key")
                }
            }
        )
        // 都在测试线程上执行到第一次挂起：第一个领头、等待数据源，其余合并到它
        val reads = List(CALLERS) { cache.async().get("k") }
        loads.get().assert().isEqualTo(1)
        pending.cancel(true)

        val failures = reads.map { failureOf(it) }
        val shared = failures.first()
        shared.assert().isInstanceOf(SourceLoadCancelledException::class.java)
        shared?.cause.assert().isInstanceOf(CancellationException::class.java)
        (shared as SourceLoadCancelledException).cacheName.assert().isEqualTo("core")
        failures.forEach { it.assert().isSameAs(shared) }
        loads.get().assert().isEqualTo(1)
        cache["k"].assert().isEqualTo("v-k")
        loads.get().assert().isEqualTo(2)
        requireNotNull(runtime.metrics("core")).leaseWaits.assert().isZero()
    }

    /**
     * 同步变体：同步数据源在调用方线程上抛出自己的 `CancellationException`（如对已取消的 future 调用 `join`）。调用方线程没有被中断，
     * leader 与合并到它的同步调用方都收到同一个 [SourceLoadCancelledException]，只回源一次，中断标志都不置位（K5）。
     */
    @Test
    fun aBlockingSourcesOwnCancellationIsTheSameFailureForEverySyncCaller() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val cache = runtime().create(
            spec {
                loader = CacheLoader { key ->
                    if (loads.getAndIncrement() == 0) {
                        started.countDown()
                        release.awaitOrFail("release")
                        CompletableFuture<String>().apply { cancel(true) }.join()
                    }
                    "v-$key"
                }
            }
        )
        val leader = fork("leader") { failureAndInterruptFlag { cache["k"] } }
        started.awaitOrFail("loader started")
        val followers = List(CALLERS - 1) { fork("follower-$it") { failureAndInterruptFlag { cache["k"] } } }
        // 同步跟随者停在 runBlocking 的事件循环里等待 leader：此时都已合并到它
        awaitCondition("followers joined") { followers.all { it.parked } }
        release.countDown()

        val (shared, leaderInterrupted) = leader.join()
        shared.assert().isInstanceOf(SourceLoadCancelledException::class.java)
        shared?.cause.assert().isInstanceOf(CancellationException::class.java)
        leaderInterrupted.assert().isFalse()
        followers.map { it.join() }.forEach { (failure, interrupted) ->
            failure.assert().isSameAs(shared)
            interrupted.assert().isFalse()
        }
        loads.get().assert().isEqualTo(1)
    }

    /**
     * 调用 [block]，返回它的失败与之后线程的中断标志（`Forked` 线程结束后标志不可再读）。
     */
    private fun failureAndInterruptFlag(block: () -> Any?): Pair<Throwable?, Boolean> =
        runCatching { block() }.exceptionOrNull() to Thread.currentThread().isInterrupted

    /**
     * 关闭运行时之后，被取消的加载释放 lease 的命令仍在途：`close` 等它完成才返回（RFC 0005 第三轮 K17），
     * 之后关闭的执行器与后端不会让它失败，lease 不会留到 `lease.ttl`。
     */
    @Test
    fun closeWaitsForAnInFlightRelease() {
        val held = HeldRelease()
        val closing = runtime(held.backend)
        val read = held.loadOn(closing)

        val closer = fork("closer") { closing.close().let { held.released.count } }
        held.releasing.awaitOrFail("release issued")
        awaitCondition("close waiting") { closer.parked || !closer.thread.isAlive }
        closer.thread.isAlive.assert().withFailMessage { "close returned while the release was in flight." }.isTrue()
        held.gate.complete(Unit)

        closer.join().assert().withFailMessage { "close returned before the release completed." }.isZero()
        failureOf(read)?.message.assert().isEqualTo("CacheRuntime closed.")
        val left = Suspending.await { memory.store("core").read("cocache:core:k", null, null) }
        left.assert().isEqualTo(RemoteRead.Miss(false))
    }

    /**
     * 卡住的步骤不让关闭无限期等待：等满上限（这里 1 ms）之后告警、照常关闭，步骤之后照常完成（K17）。
     */
    @Test
    fun closeStopsWaitingAtTheDrainTimeout() {
        val held = HeldRelease()
        val closing = CacheRuntime.builder().backend(held.backend).apply { drainTimeout = Duration.ofMillis(1) }.build()
        runtimes += closing
        held.loadOn(closing)

        // 作用域中的协程要等释放完成才结束，而释放停在 gate 上：close 只能等满上限
        closing.close()
        held.releasing.awaitOrFail("release issued")
        held.released.count.assert().withFailMessage { "the release was not held." }.isEqualTo(1)
        held.gate.complete(Unit)
        held.released.awaitOrFail("release completed")
    }

    /**
     * 关闭的线程在等待期间被中断：不再等待，返回时保留中断标志（K17）。
     */
    @Test
    fun anInterruptedCloseStopsWaitingAndKeepsTheFlag() {
        val held = HeldRelease()
        val closing = CacheRuntime.builder().backend(held.backend).apply { drainTimeout = Duration.ofHours(1) }.build()
        runtimes += closing
        held.loadOn(closing)

        val closer = fork("closer") { closing.close().let { Thread.currentThread().isInterrupted } }
        held.releasing.awaitOrFail("release issued")
        awaitCondition("close waiting") { closer.parked || !closer.thread.isAlive }
        closer.thread.interrupt()

        closer.join().assert().withFailMessage { "the interrupt flag must be restored." }.isTrue()
        held.released.count.assert().isEqualTo(1)
        held.gate.complete(Unit)
        held.released.awaitOrFail("release completed")
    }

    /**
     * 释放 lease 的命令停在 [gate] 上的后端（包装 [memory]）：[releasing] 在命令发出时、[released] 在它完成时倒数。
     */
    private inner class HeldRelease {
        val gate = CompletableDeferred<Unit>()
        val releasing = CountDownLatch(1)
        val released = CountDownLatch(1)
        val backend = object : CoherenceBackend by memory {
            override fun store(namespace: String): L1Store = object : ForwardingL1Store(memory.store(namespace)) {
                override suspend fun release(key: String, token: Token) {
                    releasing.countDown()
                    gate.await()
                    super.release(key, token)
                    released.countDown()
                }
            }
        }

        /**
         * 在 [runtime] 上发起一次异步读取：取得 lease、等待永不完成的数据源。关闭运行时会取消它、释放 lease。
         */
        fun loadOn(runtime: CacheRuntime): CompletableFuture<String?> {
            val started = CountDownLatch(1)
            val cache = runtime.create(
                spec {
                    asyncLoader = AsyncCacheLoader {
                        started.countDown()
                        CompletableFuture<String>()
                    }
                }
            )
            val read = cache.async().get("k")
            started.awaitOrFail("loader started")
            return read
        }
    }

    /**
     * 非原子广播的后端：写入修改 L1 之后补发的广播仍在途时关闭运行时，`close` 等广播发出才返回（K17），
     * 否则之后关闭的后端会让它失败，其它节点读旧的 L2 直到 `localMaxAge`。
     */
    @Test
    fun closeWaitsForAnInFlightBroadcast() {
        val gate = CompletableDeferred<Unit>()
        val publishing = CountDownLatch(1)
        val published = CountDownLatch(1)
        val channel = object : InvalidationChannel by memory.channel {
            override suspend fun publish(namespace: String, frame: ByteArray) {
                publishing.countDown()
                gate.await()
                memory.channel.publish(namespace, frame)
                published.countDown()
            }
        }
        val backend = object : CoherenceBackend by memory {
            override val atomicBroadcast: Boolean = false
            override val channel: InvalidationChannel = channel
        }
        val closing = runtime(backend)
        val cache = closing.create(spec { loader = CacheLoader { "v-$it" } })
        cache.async().invalidate("k")
        publishing.awaitOrFail("broadcast issued")

        val closer = fork("closer") { closing.close().let { published.count } }
        awaitCondition("close waiting") { closer.parked || !closer.thread.isAlive }
        closer.thread.isAlive.assert().withFailMessage { "close returned while the broadcast was in flight." }.isTrue()
        gate.complete(Unit)

        closer.join().assert().withFailMessage { "close returned before the broadcast was sent." }.isZero()
    }

    /**
     * 在异步执行器的线程上关闭（异步结果的回调里）不等待：调用方正是作用域中仍在收尾的协程，等待它自己只会等满上限。
     * 上限设为 1 小时，等待了就会让本用例超时。
     */
    @Test
    fun closeOnTheAsyncExecutorDoesNotWaitForItself() {
        val load = CompletableFuture<String>()
        val closing = CacheRuntime.builder().backend(memory).apply { drainTimeout = Duration.ofHours(1) }.build()
        runtimes += closing
        val cache = closing.create(spec { asyncLoader = AsyncCacheLoader { load } })
        val closed = cache.async().get("k").thenApply { value ->
            closing.close()
            value
        }
        // 数据源在测试线程上完成：协程派发回异步执行器，在那里完成结果、执行回调
        load.complete("v-k")

        closed.get(10, TimeUnit.SECONDS).assert().isEqualTo("v-k")
    }

    /**
     * 同步读取取得 lease 之后等待回源许可时被中断：`runBlocking` 取消协程并把它运行到结束，lease 在调用方线程上释放之后才抛出
     * [InterruptedException]；另一个节点随后的未命中不等待 lease。
     */
    @Test
    fun interruptingASyncReaderWaitingForAPermitReleasesItsLease() {
        val holderStarted = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val cache = runtime().create(
            spec {
                loader = CacheLoader { key ->
                    if (key == "held") {
                        holderStarted.countDown()
                        releaseHolder.awaitOrFail("release holder")
                    }
                    "v-$key"
                }
                policy = CachePolicy(load = LoadPolicy(maxConcurrent = 1, acquireTimeout = Duration.ofSeconds(30)))
            }
        )
        val holding = fork("holder") { cache["held"] }
        holderStarted.awaitOrFail("holder started")
        // 等待者在 L1 上取得 k 的 lease，然后停在许可的等待上
        val waiting = fork("waiter") { cache["k"] }
        awaitCondition("waiter parked on the permit") { waiting.parked }
        waiting.thread.interrupt()
        waiting.joinFailure().assert().isInstanceOf(InterruptedException::class.java)

        val other = runtime()
        other.create(spec { loader = CacheLoader { "w-$it" } })["k"].assert().isEqualTo("w-k")
        requireNotNull(other.metrics("core")).leaseWaits.assert().isZero()
        releaseHolder.countDown()
        holding.join().assert().isEqualTo("v-held")
    }

    /**
     * L2 的 key 不含存储前缀（RFC 0005 §4.5）：别的节点的失效帧按存储 key 淘汰本节点的副本。同一 namespace 上其它前缀的 key
     * （以及比前缀还短的 key）不属于本缓存：既不淘汰编码后同名的副本，也不出错。
     */
    @Test
    fun invalidationFramesMapStorageKeysToLocalKeys() {
        val runtime = runtime()
        val cache = runtime.create(spec { loader = CacheLoader { "v-$it" } })
        val metrics = requireNotNull(runtime.metrics("core"))
        val listener = cache as InvalidationListener
        val other = NodeId.random()
        cache["k"].assert().isEqualTo("v-k")

        // 与 `cocache:` 等长的前缀：只按长度剥掉前缀会得到同一个 L2 key
        listener.onInvalidated(listOf("abcdefg:core:k", "x"), other)
        cache["k"].assert().isEqualTo("v-k")
        metrics.remoteHits.assert().withFailMessage { "a foreign key evicted the local copy" }.isZero()

        listener.onInvalidated(listOf("cocache:core:k"), other)
        cache["k"].assert().isEqualTo("v-k")
        metrics.remoteHits.assert().withFailMessage { "the frame did not evict the local copy" }.isEqualTo(1)
    }

    /**
     * 没有数据源的批量读取释放 lease 失败：只计入 `remoteFailures` 并告警，读取照常返回（同步与异步变体）。
     */
    @Test
    fun releaseFailureInABatchIsCountedNotThrown() {
        val runtime = runtime(HookedBackend(memory).apply { failRelease = true })
        val cache = runtime.create(spec {})
        cache.getAll(listOf("a")).assert().isEmpty()
        cache.async().getAll(listOf("b")).get(10, TimeUnit.SECONDS).assert().isEmpty()
        requireNotNull(runtime.metrics("core")).remoteFailures.assert().isEqualTo(2)
    }

    /**
     * 等待其它节点的 lease 期间关闭运行时：定时重读不再触发，读取立即以关闭失败，而不是等满 `lease.wait`。
     */
    @Test
    fun closingTheRuntimeDuringALeaseWaitFailsTheAsyncReadAsClosed() {
        val holderStarted = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holder = runtime().create(
            spec {
                loader = CacheLoader {
                    holderStarted.countDown()
                    releaseHolder.awaitOrFail("release holder")
                    "v"
                }
            }
        )
        val holding = fork("holder") { holder["k"] }
        holderStarted.awaitOrFail("holder started")

        val closing = runtime()
        val cache = closing.create(
            spec {
                loader = CacheLoader { "w" }
                policy = CachePolicy(lease = LeasePolicy(wait = Duration.ofSeconds(30)))
            }
        )
        val read = cache.async().get("k")
        awaitCondition("waiting for the lease") { requireNotNull(closing.metrics("core")).leaseWaits == 1L }
        closing.close()
        val failure = failureOf(read)
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().isEqualTo("CacheRuntime closed.")

        releaseHolder.countDown()
        holding.join().assert().isEqualTo("v")
    }

    /**
     * 提前刷新的 L1 读取尚未送达时关闭运行时：读取照常完成（L1 命令不可取消），刷新的协程在之后的挂起点（等待数据源）被取消，
     * 取得的刷新令牌被交还，另一个调用方随后能取得它。`close` 等到交还之后才返回（K17）。
     */
    @Test
    fun closingTheRuntimeMidRefreshReleasesTheRefreshToken() {
        val clock = ManualClock()
        val memory = InMemoryBackend(clock)
        val backend = HookedBackend(memory)
        val closing = CacheRuntime.builder()
            .backend(backend)
            .clock(clock)
            .refreshExecutor(Executor { it.run() })
            .build()
        runtimes += closing
        val window = Duration.ofSeconds(5)
        val loads = AtomicInteger()
        val cache = closing.create(
            spec {
                // 第一次加载立即完成；刷新的加载不完成，取消在等待它时生效
                asyncLoader = AsyncCacheLoader {
                    if (loads.getAndIncrement() == 0) CompletableFuture.completedFuture("v") else CompletableFuture()
                }
                policy = CachePolicy(
                    ttl = Duration.ofSeconds(10),
                    ttlJitter = Duration.ZERO,
                    localMaxAge = Duration.ofSeconds(8),
                    refreshWindow = window
                )
            }
        )
        cache["k"].assert().isEqualTo("v")
        clock.advance(Duration.ofSeconds(6))
        val readIssued = CountDownLatch(1)
        val deliver = CountDownLatch(1)
        backend.ioExecutor = Executor { task ->
            readIssued.countDown()
            io.execute {
                deliver.awaitOrFail("deliver")
                task.run()
            }
        }
        // 窗口内的 L2 命中触发刷新：刷新的读取已在 L1 取得令牌，结果被挡在“I/O 线程”上
        cache["k"].assert().isEqualTo("v")
        readIssued.awaitOrFail("refresh read issued")
        // 关闭取消作用域之后、等待在途步骤期间，读取才送达
        val closer = fork("closer") { closing.close() }
        awaitCondition("close waiting") { closer.parked || !closer.thread.isAlive }
        deliver.countDown()
        closer.join()

        val other = RefreshRequest(window, Token(NodeId.random(), 1), Duration.ofSeconds(10))
        val read = Suspending.await { memory.store("core").read("cocache:core:k", null, other) }
        (read is RemoteRead.Hit && read.refreshGranted).assert().withFailMessage { "refresh token held: $read" }.isTrue()
    }

    /**
     * 异步写入的 L1 命令尚未送达时关闭运行时：写入照常做完（修改 L1、第二次递增与淘汰 L2），调用方以关闭失败。
     * `close` 等到写入做完才返回（K17）。
     */
    @Test
    fun closingTheRuntimeMidWriteStillFinishesTheWrite() {
        val backend = HookedBackend(memory)
        val closing = runtime(backend)
        val cache = closing.create(spec { loader = CacheLoader { "loaded" } })
        val issued = CountDownLatch(1)
        val deliver = CountDownLatch(1)
        backend.ioExecutor = Executor { task ->
            issued.countDown()
            io.execute {
                deliver.awaitOrFail("deliver")
                task.run()
            }
        }
        val write = cache.async().put("k", "v")
        issued.awaitOrFail("put issued")
        // 关闭取消作用域之后、等待在途步骤期间，写入的命令才送达
        val closer = fork("closer") { closing.close() }
        awaitCondition("close waiting") { closer.parked || !closer.thread.isAlive }
        deliver.countDown()
        closer.join()
        write.isDone.assert().withFailMessage { "close returned before the write finished." }.isTrue()
        val failure = failureOf(write)
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().isEqualTo("CacheRuntime closed.")
        requireNotNull(closing.metrics("core")).writeFailures.assert().isZero()
        runtime().create(spec { loader = CacheLoader { "loaded" } })["k"].assert().isEqualTo("v")
    }

    /**
     * 唯一的回源许可被占用时，持有者在 [holderStarted] 之后一直等 [releaseHolder]；其余 key 记录回源顺序。
     */
    private inner class PermitHolder(acquireTimeout: Duration) {
        val holderStarted = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val loaded = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runtime = runtime()
        val cache = runtime.create(
            spec {
                loader = CacheLoader { key ->
                    if (key == "holder") {
                        holderStarted.countDown()
                        releaseHolder.awaitOrFail("release holder")
                    }
                    loaded += key
                    "v-$key"
                }
                policy = CachePolicy(load = LoadPolicy(maxConcurrent = 1, acquireTimeout = acquireTimeout))
            }
        )
        val holding = fork("holder") { cache["holder"] }

        init {
            holderStarted.awaitOrFail("holder started")
        }

        fun release() {
            releaseHolder.countDown()
            holding.join().assert().isEqualTo("v-holder")
        }
    }

    /**
     * 等不到回源许可：同步与异步变体都在 `acquireTimeout` 后以 [SourceLoadRejectedException] 失败，许可不丢。
     */
    @Test
    fun permitWaitersTimeOutWithoutLosingThePermit() {
        val holder = PermitHolder(Duration.ofMillis(50))
        failureOf(holder.cache.async().get("async")).assert().isInstanceOf(SourceLoadRejectedException::class.java)
        runCatching { holder.cache["sync"] }.exceptionOrNull().assert().isInstanceOf(
            SourceLoadRejectedException::class.java
        )
        requireNotNull(holder.runtime.metrics("core")).loadRejected.assert().isEqualTo(2)
        holder.release()
        holder.cache["after"].assert().isEqualTo("v-after")
    }

    /**
     * 同步与异步变体共用一个按到达顺序交接的许可：先到的等待者先回源。
     */
    @Test
    fun permitWaitersAreServedInArrivalOrder() {
        val holder = PermitHolder(Duration.ofSeconds(10))
        val first = holder.cache.async().get("first")
        val second = fork("second") { holder.cache["second"] }
        awaitCondition("second waiting") { second.parked }
        val third = holder.cache.async().get("third")
        holder.release()
        first.get(10, TimeUnit.SECONDS).assert().isEqualTo("v-first")
        second.join().assert().isEqualTo("v-second")
        third.get(10, TimeUnit.SECONDS).assert().isEqualTo("v-third")
        holder.loaded.assert().containsExactly("holder", "first", "second", "third")
    }

    /**
     * 等待回源许可期间关闭运行时：异步等待者以关闭失败，不等满 `acquireTimeout`。
     */
    @Test
    fun closingTheRuntimeFailsPermitWaiters() {
        val holder = PermitHolder(Duration.ofSeconds(30))
        val waiting = holder.cache.async().get("waiting")
        holder.runtime.close()
        val failure = failureOf(waiting)
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().isEqualTo("CacheRuntime closed.")
        holder.release()
    }

    /**
     * 第一次回源一直阻塞到被中断的数据源：leader 被中断时，合并到它的读取按合并规则自己回源（RFC 0005 §4.4）。
     */
    private inner class InterruptibleSource {
        val started = CountDownLatch(1)
        val loads = java.util.concurrent.atomic.AtomicInteger()
        val cache = runtime().create(
            spec {
                loader = object : CacheLoader<String, String> {
                    override fun load(key: String): String? {
                        if (loads.incrementAndGet() == 1) {
                            started.countDown()
                            CountDownLatch(1).await()
                        }
                        return "v-$key"
                    }

                    override fun loadAll(keys: Collection<String>): Map<String, String> = keys.associateWith { load(it)!! }
                }
            }
        )
    }

    @Test
    fun readersJoiningAnInterruptedLeaderLoadThemselves() {
        val source = InterruptibleSource()
        val leader = fork("leader") { source.cache["k"] }
        source.started.awaitOrFail("leader loading")
        val follower = fork("follower") { source.cache["k"] }
        awaitCondition("follower joined") { follower.parked }
        leader.thread.interrupt()
        leader.joinFailure().assert().isInstanceOf(InterruptedException::class.java)
        follower.join().assert().isEqualTo("v-k")
        source.loads.get().assert().isEqualTo(2)
    }

    @Test
    fun aBatchJoiningAnInterruptedLeaderLoadsThatKeyItself() {
        val source = InterruptibleSource()
        val leader = fork("leader") { source.cache["a"] }
        source.started.awaitOrFail("leader loading")
        val batch = fork("batch") { source.cache.getAll(listOf("a", "b")) }
        awaitCondition("batch joined") { batch.parked }
        leader.thread.interrupt()
        leader.joinFailure().assert().isInstanceOf(InterruptedException::class.java)
        batch.join().assert().isEqualTo(mapOf("b" to "v-b", "a" to "v-a"))
    }

    /**
     * `load.timeout`（RFC 0005 §4.4）：卡住的同步 loader 不再让其它读取无限等待。等待它的读取在截止时刻以
     * [SourceLoadTimeoutException] 失败并计入 `loadTimeouts`；截止时刻之后的读取开始新的加载。
     */
    @Test
    fun theLoadTimeoutBoundsReadersBehindAStuckLoader() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = java.util.concurrent.atomic.AtomicInteger()
        val runtime = runtime()
        val cache = runtime.create(
            spec {
                loader = CacheLoader { key ->
                    if (loads.incrementAndGet() == 1) {
                        started.countDown()
                        release.awaitOrFail("release stuck loader")
                    }
                    "v-$key"
                }
                policy = CachePolicy(load = LoadPolicy(timeout = Duration.ofMillis(200)))
            }
        )
        val stuck = fork("stuck") { cache["k"] }
        started.awaitOrFail("stuck loader started")
        val waiting = fork("waiting") { cache["k"] }
        waiting.joinFailure().assert().isInstanceOf(SourceLoadTimeoutException::class.java)
        requireNotNull(runtime.metrics("core")).loadTimeouts.assert().isEqualTo(1)
        cache["k"].assert().isEqualTo("v-k")
        release.countDown()
        stuck.join().assert().isEqualTo("v-k")
        loads.get().assert().isEqualTo(2)
    }

    /**
     * Java 实现的 [FutureL1Store] 以 `null` 完成读取（实现缺陷）：`FutureL1Store.adapt` 以指明操作的 [IllegalStateException] 失败，
     * 引擎按 L1 读失败处理，没有数据源时以 [CacheReadException] 失败，而不是在之后的分支上抛出更难懂的异常。
     */
    @Test
    fun aNullFutureReadIsAReadFailure() {
        val nullReads = mockk<FutureL1Store> {
            every { conditional() } returns null
            every { versioned() } returns null
            every { sets() } returns null
            every { read(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
        }
        val backend = object : CoherenceBackend by memory {
            override fun store(namespace: String): L1Store = FutureL1Store.adapt(nullReads)
        }
        val cache = runtime(backend).create(spec {})
        val failure = failureOf(cache.async().get("k"))
        failure.assert().isInstanceOf(CacheReadException::class.java)
        failure?.cause.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.cause?.message.assert().endsWith(".read() completed with null.")
    }

    private companion object {
        /**
         * 合并到同一次加载的调用方个数（实验 E4）。
         */
        const val CALLERS = 8
    }
}
