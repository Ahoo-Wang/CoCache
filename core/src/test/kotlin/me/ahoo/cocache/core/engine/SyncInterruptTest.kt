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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * 同步变体被中断（RFC 0005 §4.3，M3a）：L1 调用不可取消地挂起等待（`uncancellable`），中断不到达存储。`runBlocking` 取消协程并等它结束：
 * 在途的 L1 命令先完成，取消在之后的第一个挂起点生效，令牌按正常路径释放；取消之后不再调用 loader。
 * 调用方得到 [InterruptedException]，或在取消生效之前已经完成的结果，两种情形都保留中断标志。
 *
 * L1 命令由 [ScriptedBackend] 挂起，测试线程决定何时完成：调用方停在 `runBlocking` 的事件循环上，命令完成之前不会返回。
 */
class SyncInterruptTest {
    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val ledger = Ledger()
    private val backend = ScriptedBackend(memory, ledger)
    private val loads = AtomicInteger()
    private val runtimes = ArrayList<CacheRuntime>()

    @AfterEach
    fun close() {
        runtimes.forEach { it.close() }
    }

    private fun runtime(
        view: CoherenceBackend = backend.view(0),
        observer: LoadObserver? = null,
    ): CacheRuntime = CacheRuntime.builder()
        .backend(view)
        .clock(clock)
        .apply { observer?.let { loadObserver(it) } }
        .build()
        .also { runtimes += it }

    private fun cache(runtime: CacheRuntime = runtime(), leaseWait: Duration = Duration.ZERO) = runtime.create(
        cacheSpec<String, String>(NAME) {
            codec = StringCodec
            loader = CacheLoader { key -> "$key-${loads.incrementAndGet()}" }
            policy = CachePolicy(ttlJitter = Duration.ZERO, lease = LeasePolicy(wait = leaseWait))
        }
    )

    private fun storageKey(key: String) = "cocache:$NAME:$key"

    private fun leased(key: String): Boolean = Suspending.await {
        memory.store(NAME).read(storageKey(key), null, null, null)
    } is L1Read.Leased

    /**
     * 调用方的结局：结果（或异常），以及返回时线程的中断标志（线程结束后不可再读）。
     */
    private class Ended<T>(val result: Result<T>, val interrupted: Boolean)

    private fun <T> call(name: String, block: () -> T): Concurrency.Forked<Ended<T>> = fork(name) {
        val result = runCatching(block)
        Ended(result, Thread.currentThread().isInterrupted)
    }

    /**
     * 等调用方发出 [pending] 条命令、停在等待上。
     */
    private fun awaitParked(caller: Concurrency.Forked<*>, pending: Int, what: String) {
        awaitCondition(what) { backend.pendingCount == pending && caller.parked }
    }

    /**
     * 中断调用方，等 `runBlocking` 处理它（清除中断标志、取消协程）并重新停下：在途的命令还没有完成，调用方不能返回。
     */
    private fun interrupt(caller: Concurrency.Forked<*>) {
        caller.thread.interrupt()
        awaitCondition("runBlocking cancelled the coroutine and parked again") {
            !caller.thread.isInterrupted && caller.parked
        }
        caller.thread.isAlive.assert().withFailMessage { "the caller returned before its L1 command finished" }.isTrue()
    }

    private fun resolve(what: String) {
        backend.resolveOldest(Outcome.OK).assert().withFailMessage { "no pending $what" }.isTrue()
    }

    private fun <T> Ended<T>.assertInterrupted() {
        result.exceptionOrNull().assert().isInstanceOf(InterruptedException::class.java)
        interrupted.assert().withFailMessage { "the interrupt flag must be restored" }.isTrue()
    }

    /**
     * 中断落在取得 lease 的读取期间：读取照常完成、取得 lease；取消随后生效，loader 不再运行，lease 释放完成之后调用方才以
     * [InterruptedException] 返回（6.10 立即以 `RedisException("Interrupted while waiting for Redis.")` 失败，由存储在结果未知时释放）。
     */
    @Test
    fun anInterruptDuringALeaseGrantingReadReleasesTheLeaseAndSkipsTheLoader() {
        val cache = cache()
        val caller = call("reader") { cache["k"] }
        awaitParked(caller, 1, "read issued")
        interrupt(caller)

        resolve("read")
        awaitParked(caller, 1, "release issued")
        caller.thread.isAlive.assert().withFailMessage { "the caller returned before releasing its lease" }.isTrue()
        resolve("release")

        caller.join().assertInterrupted()
        loads.get().assert().isZero()
        ledger.unreturned().assert().isEmpty()
        leased("k").assert().isFalse()
    }

    /**
     * 批量读取同样：取得的 lease 并发释放、全部完成之后才返回，批量 loader 不运行。
     */
    @Test
    fun anInterruptDuringABatchReadReleasesEveryLease() {
        val cache = cache()
        val caller = call("batch") { cache.getAll(listOf("a", "b")) }
        awaitParked(caller, 1, "readAll issued")
        interrupt(caller)

        resolve("readAll")
        awaitParked(caller, 2, "releases issued")
        resolve("release")
        resolve("release")

        caller.join().assertInterrupted()
        loads.get().assert().isZero()
        ledger.unreturned().assert().isEmpty()
        leased("a").assert().isFalse()
        leased("b").assert().isFalse()
    }

    /**
     * 中断落在 L1 命中的读取期间：取消之后再没有挂起点，调用照常完成，返回值并保留中断标志（`runBlocking` 对此仍抛出
     * [InterruptedException]，结果由 `runSync` 带出）。
     */
    @Test
    fun anInterruptDuringAnL1HitReturnsTheValueWithTheFlagSet() {
        Suspending.await {
            memory.store(
                NAME
            ).put(storageKey("k"), Envelope.Scalar("v".toByteArray()), null, frame("k"))
        }
        val cache = cache()
        val caller = call("reader") { cache["k"] }
        awaitParked(caller, 1, "read issued")
        interrupt(caller)

        resolve("read")

        val outcome = caller.join()
        outcome.result.getOrThrow().assert().isEqualTo("v")
        outcome.interrupted.assert().withFailMessage { "the interrupt flag must be restored" }.isTrue()
        loads.get().assert().isZero()
    }

    /**
     * 中断落在 `put` 期间：调用方等命令完成，修改 L1 之后的步骤（非原子广播的后端上的广播、第二次递增与淘汰）照常执行，
     * 然后正常返回并保留中断标志。
     */
    @Test
    fun anInterruptDuringAPutWaitsForTheCommandAndStillBroadcasts() {
        val published = CopyOnWriteArrayList<List<String>>()
        val cache = cache(runtime(nonAtomic(backend.view(0), published)))
        val caller = call("writer") { cache.put("k", "v") }
        awaitParked(caller, 1, "put issued")
        interrupt(caller)
        published.assert().isEmpty()

        resolve("put")

        val outcome = caller.join()
        outcome.result.isSuccess.assert().withFailMessage { "the put failed: ${outcome.result}" }.isTrue()
        outcome.interrupted.assert().withFailMessage { "the interrupt flag must be restored" }.isTrue()
        published.assert().isEqualTo(listOf(listOf(storageKey("k"))))
        val read = Suspending.await { memory.store(NAME).read(storageKey("k"), null, null, null) } as L1Read.Hit
        read.envelope.assert().isEqualTo(Envelope.Scalar("v".toByteArray()))
    }

    /**
     * 等待其它节点的 lease 时被中断：在途的重读先完成，取消在随后的退避（`delay`）上生效，不再重读；调用方以
     * [InterruptedException] 返回，loader 不运行。
     */
    @Test
    fun anInterruptDuringALeaseWaitEndsItAfterTheRereadInFlight() {
        val runtime = runtime()
        interruptLeaseWait(runtime, cache(runtime, leaseWait = Duration.ofMinutes(1)))
    }

    /**
     * 有观测器时等待 lease 在嵌套的 `runSync` 中进行：中断在那里结束等待，外层不把这个中断当作 L1 故障降级回源，loader 不运行。
     */
    @Test
    fun anInterruptDuringAnObservedLeaseWaitIsNotAnL1Failure() {
        val runtime = runtime(observer = PassThroughObserver)
        interruptLeaseWait(runtime, cache(runtime, leaseWait = Duration.ofMinutes(1)))
        requireNotNull(runtime.metrics(NAME)).remoteFailures.assert().isZero()
    }

    /**
     * 别的节点持有 k 的 lease；读取进入等待之后，在一次重读在途时中断调用方。
     */
    private fun interruptLeaseWait(runtime: CacheRuntime, cache: me.ahoo.cocache.api.Cache<String, String>) {
        val holder = LeaseRequest(Token(NodeId.random(), 1), Duration.ofMinutes(1))
        Suspending.await {
            memory.store(
                NAME
            ).read(storageKey("k"), holder, null, null)
        }.assert().isEqualTo(L1Read.Miss(true))
        val caller = call("waiter") { cache["k"] }
        awaitParked(caller, 1, "read issued")
        resolve("read")
        awaitCondition("a reread in flight while waiting for the other node's lease") {
            requireNotNull(runtime.metrics(NAME)).leaseWaits == 1L && backend.pendingCount == 1 && caller.parked
        }
        interrupt(caller)

        resolve("reread")

        caller.join().assertInterrupted()
        backend.pendingCount.assert().withFailMessage { "the lease wait must not reread after the interrupt" }.isZero()
        loads.get().assert().isZero()
    }

    private fun frame(vararg keys: String) = InvalidationFrame.encode(NodeId.random(), keys.map { storageKey(it) })

    /**
     * 非原子广播的视图：引擎在写入之后自己发布，发布的 key 记入 [published]。
     */
    private fun nonAtomic(view: CoherenceBackend, published: MutableList<List<String>>): CoherenceBackend =
        object : CoherenceBackend by view {
            override val atomicBroadcast: Boolean = false
            override val channel: InvalidationChannel = RecordingChannel(view.channel, published)
        }

    private class RecordingChannel(
        private val delegate: InvalidationChannel,
        private val published: MutableList<List<String>>,
    ) : InvalidationChannel {
        override fun subscribe(namespace: String, listener: InvalidationListener): Subscription =
            delegate.subscribe(namespace, listener)

        override suspend fun publish(namespace: String, frame: ByteArray) {
            published += InvalidationFrame.decode(frame).keys
        }
    }

    /**
     * 不做任何事的观测器（不是 [LoadObserver.NOOP]）：同步变体经它在嵌套的 `runSync` 中回源与等待 lease。
     */
    private object PassThroughObserver : LoadObserver {
        override fun <T> observe(cacheName: String, operation: LoadOperation, keyCount: Int, block: Supplier<T>): T =
            block.get()

        override fun <T> observeAsync(
            cacheName: String,
            operation: LoadOperation,
            keyCount: Int,
            block: Supplier<CompletableFuture<T>>
        ): CompletableFuture<T> = block.get()
    }

    private companion object {
        const val NAME = "interrupt"
    }
}
