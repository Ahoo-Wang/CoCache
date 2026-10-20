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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 异步视图的引擎 TCK（RFC 0004 §10.1）：[EngineSpec] 的每个用例都经 `async()` 重跑，另加异步路径专有的用例
 * （取消、线程、不挂起线程、超时、同步与异步互相合并、重入）。
 */
@Suppress("LargeClass")
abstract class AsyncEngineSpec : EngineSpec() {
    override val access: CacheAccess = CacheAccess.ASYNC

    /**
     * 命名线程的执行器（用于断言续接在哪里执行），测试结束时关闭。
     */
    private val executors = ArrayList<ExecutorService>()

    @AfterEach
    override fun destroy() {
        super.destroy()
        executors.forEach { it.shutdownNow() }
    }

    private class CountingFactory(private val prefix: String) : ThreadFactory {
        val created = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "$prefix-${created.incrementAndGet()}").apply { isDaemon = true }
    }

    private fun named(prefix: String, threads: Int = 4): Pair<ExecutorService, CountingFactory> {
        val factory = CountingFactory(prefix)
        return Executors.newFixedThreadPool(threads, factory).also { executors.add(it) } to factory
    }

    /**
     * 只在 [gate] 打开后才在 [pool] 上执行的执行器：既作 [HookedBackend.ioExecutor]（原生异步后端的结果送达），
     * 也作阻塞执行器（只实现同步 SPI 的后端经阻塞执行器调用，`ioExecutor` 对它不起作用）。
     */
    private fun gated(pool: Executor, gate: () -> CountDownLatch, what: String): Executor = Executor { task ->
        val opened = gate()
        pool.execute {
            opened.awaitOrFail(what)
            task.run()
        }
    }

    private fun node(asyncExecutor: Executor? = null, blockingExecutor: Executor? = null): CacheRuntime {
        val builder = CacheRuntime.builder()
            .backend(backend)
            .clock(clock)
            .retryPolicy(RetryPolicy(initialBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(50)))
        asyncExecutor?.let { builder.asyncExecutor(it) }
        blockingExecutor?.let { builder.blockingExecutor(it) }
        return builder.build()
    }

    /**
     * 返回调用方控制何时完成的 future 的异步数据源。
     */
    private class PendingLoader : AsyncCacheLoader<String, String> {
        val pending = ConcurrentHashMap<String, CompletableFuture<String?>>()
        val calls = AtomicInteger()

        override fun loadAsync(key: String): CompletableFuture<String?> {
            calls.incrementAndGet()
            return pending.computeIfAbsent(key) { CompletableFuture() }
        }

        fun complete(key: String, value: String?) {
            pending.computeIfAbsent(key) { CompletableFuture() }.complete(value)
        }
    }

    /**
     * 真正的同步数据源（不经 [CacheAccess.ASYNC] 换成异步数据源）。
     */
    private fun syncSpec(loader: CacheLoader<String, String>): CacheSpec<String, String> = cacheSpec(cacheName) {
        codec = TestCodec
        this.loader = loader
        policy = policy()
    }

    /**
     * 只有原生异步的后端才能做到等待期间不占线程（只实现同步 SPI 的后端经阻塞执行器调用）。
     */
    private fun assumeNativeAsync() {
        assumeTrue(backend.store(cacheName) is AsyncRemoteStore) { "The backend does not implement AsyncRemoteStore." }
    }

    private fun CacheRuntime.rawCache(source: AsyncCacheLoader<String, String>, policy: CachePolicy = policy()): Cache<String, String> =
        create(
            spec(policy) {
                loader = null
                asyncLoader = source
            }
        )

    @Test
    fun cancellingOneWaiterDoesNotCancelTheSharedLoad() {
        val source = PendingLoader()
        val async = nodeA.rawCache(source).async()
        val first = async.get("k")
        val second = async.get("k")
        val third = async.get("k")
        awaitCondition("loader called") { source.calls.get() == 1 }
        second.cancel(true).assert().isTrue()
        source.complete("k", "v")
        first.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v")
        third.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v")
        runCatching { second.join() }.exceptionOrNull().assert().isInstanceOf(CancellationException::class.java)
        remoteValue("k").assert().isEqualTo("v")
        source.calls.get().assert().isEqualTo(1)
    }

    @Test
    fun cancellingEveryWaiterStillFillsTheRemote() {
        val source = PendingLoader()
        val async = nodeA.rawCache(source).async()
        val waiters = (1..3).map { async.get("k") }
        awaitCondition("loader called") { source.calls.get() == 1 }
        waiters.forEach { it.cancel(true) }
        source.complete("k", "v")
        awaitCondition("the abandoned load fills L1") { remoteValue("k") == "v" }
        nodeB.cache()["k"].assert().isEqualTo("v")
        source.calls.get().assert().isEqualTo(1)
    }

    @Test
    fun futuresNeverCompleteOnTheBackendIoThread() {
        val (io, _) = named("tck-io", 1)
        val (blockingPool, _) = named("tck-blocking")
        val (asyncPool, _) = named("tck-async")
        // L1 的结果在回调注册之前不送达：回调一定是在完成时执行的，而不是注册时就地执行。
        // 原生异步后端经 ioExecutor 送达；只实现同步 SPI 的后端在阻塞执行器上调用，两者都要拦住
        val gate = AtomicReference(CountDownLatch(1))
        backend.ioExecutor = gated(io, gate::get, "callback registered")
        val runtime = node(
            asyncExecutor = asyncPool,
            blockingExecutor = gated(blockingPool, gate::get, "callback registered")
        )
        try {
            database["k"] = "v"
            val async = runtime.create(spec()).async()
            val completedOn = AtomicReference<String>()
            val future = async.get("k")
            future.isDone.assert().isFalse()
            future.whenComplete { _, _ -> completedOn.compareAndSet(null, Thread.currentThread().name) }
            gate.get().countDown()
            // 先等回调执行再 get：JDK 的 get / join 会在等待线程上执行尚未执行的依赖阶段
            awaitCondition("completion recorded") { completedOn.get() != null }
            future.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v")
            completedOn.get().assert().withFailMessage { "completed on ${completedOn.get()}" }.startsWith("tck-async")
            gate.set(CountDownLatch(1))
            val write = async.put("k", "v2")
            write.isDone.assert().isFalse()
            val writtenOn = AtomicReference<String>()
            write.whenComplete { _, _ -> writtenOn.compareAndSet(null, Thread.currentThread().name) }
            gate.get().countDown()
            awaitCondition("write completion recorded") { writtenOn.get() != null }
            write.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            writtenOn.get().assert().startsWith("tck-async")
        } finally {
            gate.get().countDown()
            backend.ioExecutor = null
            runtime.close()
        }
    }

    /**
     * 可以切换为拒绝一切任务的异步执行器（模拟饱和或关闭）。
     */
    private class SwitchExecutor(private val delegate: Executor) : Executor {
        @Volatile
        var rejecting = false

        override fun execute(command: Runnable) {
            if (rejecting) {
                throw RejectedExecutionException("rejecting")
            }
            delegate.execute(command)
        }
    }

    /**
     * 执行器拒绝写入的收尾时，第二次递增与淘汰照样执行：在两次递增之间读到旧值的填充不会留在 L2（G2）。
     */
    @Test
    fun rejectedWriteCompletionStillBumpsTheStampAgain() {
        val (io, _) = named("tck-io", 1)
        val (blockingPool, _) = named("tck-blocking")
        val (asyncPool, _) = named("tck-async")
        val switch = SwitchExecutor(asyncPool)
        // L1 的结果在 invalidate 返回之后才送达：收尾一定经执行器（否则结果已完成时就地收尾，执行器不参与）。
        // 原生异步后端经 ioExecutor 送达；只实现同步 SPI 的后端在阻塞执行器上调用，两者都要拦住
        val delivered = AtomicReference(CountDownLatch(0))
        val runtime = node(
            asyncExecutor = switch,
            blockingExecutor = gated(blockingPool, delivered::get, "write issued")
        )
        try {
            val engine = runtime.create(spec())
            engine.put("k", "v0")
            database["k"] = "v1"
            val readerRead = CountDownLatch(1)
            val releaseReader = CountDownLatch(1)
            var reader: Concurrency.Forked<String?>? = null
            backend.afterRead = { _, read ->
                if (Thread.currentThread().name == "reader") {
                    readerRead.countDown()
                    releaseReader.awaitOrFail("release reader")
                }
                read
            }
            backend.beforeInvalidate = {
                // 第一次递增之后、修改 L1 之前：读者取戳并读到旧值
                reader = fork("reader") { engine["k"] }
                readerRead.awaitOrFail("reader read the old value")
            }
            delivered.set(CountDownLatch(1))
            backend.ioExecutor = gated(io, delivered::get, "write issued")
            switch.rejecting = true
            val write = engine.async().invalidate("k")
            write.isDone.assert().isFalse()
            delivered.get().countDown()
            write.handle { _, e -> e }.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .assert().isInstanceOf(RejectedExecutionException::class.java)
            backend.beforeInvalidate = null
            releaseReader.countDown()
            requireNotNull(reader).join().assert().isEqualTo("v0")
            // 第二次递增确实发生：读者在两次递增之间取的戳已失效，它的填充被放弃
            requireNotNull(runtime.metrics(cacheName)).staleFillsDiscarded.assert().isGreaterThanOrEqualTo(1)
            switch.rejecting = false
            backend.ioExecutor = null
            backend.afterRead = null
            engine["k"].assert().isEqualTo("v1")
        } finally {
            delivered.get().countDown()
            switch.rejecting = false
            backend.ioExecutor = null
            runtime.close()
        }
    }

    /**
     * 执行器拒绝回源之后的步骤时，回源许可与 lease 照样归还：否则许可永久泄漏（同步路径也被拒绝），其它节点白等 lease。
     */
    @Test
    fun rejectedLoadCompletionReleasesThePermitAndTheLease() {
        val limited = policy().toBuilder().load(
            LoadPolicy(maxConcurrent = 1, acquireTimeout = Duration.ofMillis(200))
        ).build()
        val (asyncPool, _) = named("tck-async")
        val switch = SwitchExecutor(asyncPool)
        val runtime = node(asyncExecutor = switch)
        try {
            val source = PendingLoader()
            val engine = runtime.rawCache(source, limited)
            val load = engine.async().get("k")
            awaitCondition("loader called") { source.calls.get() == 1 }
            remote("k").assert().isInstanceOf(RemoteRead.Leased::class.java)
            switch.rejecting = true
            source.complete("k", "v")
            load.handle { _, e -> e }.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .assert().isInstanceOf(RejectedExecutionException::class.java)
            awaitCondition("lease released") { remote("k") == RemoteRead.Miss(false) }
            switch.rejecting = false
            engine["k"].assert().isEqualTo("v")
            requireNotNull(runtime.metrics(cacheName)).loadRejected.assert().isEqualTo(0)
        } finally {
            switch.rejecting = false
            runtime.close()
        }
    }

    /**
     * 数据源自己以 [TimeoutException] 失败（如 Reactor 的 `timeout`）不是引擎的超时：调用方拿到的是同一个异常（A8）。
     */
    @Test
    fun loaderTimeoutExceptionIsNotTheEngineTimeout() {
        val own = TimeoutException("loader's own timeout")
        val engine = nodeA.rawCache(AsyncCacheLoader { CompletableFuture.failedFuture<String?>(own) })
        engine.async().get("k").handle { _, e -> e }.join().assert().isSameAs(own)
        nodeA.metrics().loadTimeouts.assert().isEqualTo(0)
    }

    @Test
    fun reentryFromTheSynchronousPartOfLoadAsyncFailsFast() {
        lateinit var engine: Cache<String, String>
        engine = nodeA.rawCache(AsyncCacheLoader { key -> engine.async().get(key) })
        engine.async().get("k").handle { _, e -> e }.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .assert().isInstanceOf(IllegalStateException::class.java)
    }

    /**
     * 不挂起线程：N 个 key 被另一节点的 lease 占住（数据源未完成，不占线程），本节点 N 个异步读取全部在等待 lease，
     * 等待期间线程数与 N 无关；lease 释放后全部完成，每个 key 只回源一次。
     */
    @Test
    fun leaseWaitsUseConstantThreads() {
        assumeNativeAsync()
        val keys = (1..WAITERS).map { "k$it" }
        val holder = PendingLoader()
        val asyncA = nodeA.rawCache(holder).async()
        val held = keys.map { asyncA.get(it) }
        awaitCondition("A holds every lease") { holder.calls.get() == WAITERS }
        val asyncFactory = CountingFactory("tck-async")
        val asyncPool = Executors.newSingleThreadExecutor(asyncFactory).also { executors.add(it) }
        val blockingFactory = CountingFactory("tck-blocking")
        val blockingPool = Executors.newCachedThreadPool(blockingFactory).also { executors.add(it) }
        val waiter = node(asyncExecutor = asyncPool, blockingExecutor = blockingPool)
        try {
            val waiterSource = PendingLoader()
            val asyncB = waiter.rawCache(waiterSource).async()
            val threads = ManagementFactory.getThreadMXBean()
            val before = threads.threadCount
            val waiting = keys.map { asyncB.get(it) }
            awaitCondition("every read waits on a lease") {
                requireNotNull(waiter.metrics(cacheName)).leaseWaits == WAITERS.toLong()
            }
            (threads.threadCount - before).assert().isLessThanOrEqualTo(CONSTANT_THREADS)
            asyncFactory.created.get().assert().isLessThanOrEqualTo(1)
            blockingFactory.created.get().assert().isEqualTo(0)
            keys.forEach { holder.complete(it, "v-$it") }
            held.forEach { it.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
            waiting.forEachIndexed { index, future ->
                future.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v-${keys[index]}")
            }
            waiterSource.calls.get().assert().isEqualTo(0)
            holder.calls.get().assert().isEqualTo(WAITERS)
            blockingFactory.created.get().assert().isEqualTo(0)
        } finally {
            waiter.close()
        }
    }

    /**
     * 不挂起线程：回源许可用尽时，等待许可的异步加载经定时器重试，线程数与等待者数无关。
     */
    @Test
    fun permitWaitsUseConstantThreads() {
        assumeNativeAsync()
        val limited = policy().toBuilder().load(
            LoadPolicy(maxConcurrent = 1, acquireTimeout = Duration.ofSeconds(10))
        ).build()
        val source = PendingLoader()
        val asyncFactory = CountingFactory("tck-async")
        val asyncPool = Executors.newSingleThreadExecutor(asyncFactory).also { executors.add(it) }
        val runtime = node(asyncExecutor = asyncPool)
        try {
            val async = runtime.rawCache(source, limited).async()
            val first = async.get("k0")
            awaitCondition("first load holds the permit") { source.calls.get() == 1 }
            val threads = ManagementFactory.getThreadMXBean()
            val before = threads.threadCount
            val keys = (1..WAITERS).map { "k$it" }
            keys.forEach { source.complete(it, "v-$it") }
            val waiting = keys.map { async.get(it) }
            (threads.threadCount - before).assert().isLessThanOrEqualTo(CONSTANT_THREADS)
            waiting.none { it.isDone }.assert().isTrue()
            source.complete("k0", "v0")
            first.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v0")
            waiting.forEachIndexed { index, future ->
                future.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v-${keys[index]}")
            }
            asyncFactory.created.get().assert().isLessThanOrEqualTo(1)
            requireNotNull(runtime.metrics(cacheName)).loadRejected.assert().isEqualTo(0)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun failuresCompleteWithTheOriginalException() {
        val failure = IllegalArgumentException("boom")
        val async = nodeA.rawCache(AsyncCacheLoader { CompletableFuture.failedFuture<String?>(failure) }).async()
        val error = async.get("k").handle { _, e -> e }.join()
        error.assert().isSameAs(failure)
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
    }

    @Test
    fun asyncMethodsNeverThrowSynchronously() {
        val failure = IllegalStateException("encoder")
        val cache = nodeA.create(spec { keyEncoder = KeyEncoder { throw failure } })
        val async = cache.async()
        async.get("k").handle { _, e -> e }.join().assert().isSameAs(failure)
        async.lookup("k").handle { _, e -> e }.join().assert().isSameAs(failure)
        async.getAll(listOf("k")).handle { _, e -> e }.join().assert().isSameAs(failure)
        async.put("k", "v").handle { _, e -> e }.join().assert().isSameAs(failure)
        async.invalidate("k").handle { _, e -> e }.join().assert().isSameAs(failure)
        async.putAll(mapOf("k" to "v")).handle { _, e -> e }.join().assert().isSameAs(failure)
        async.putAll(mapOf("k" to "v"), Duration.ofMinutes(1)).handle { _, e -> e }.join().assert().isSameAs(failure)
        async.put("k", "v", Duration.ofMinutes(1)).handle { _, e -> e }.join().assert().isSameAs(failure)
        async.invalidateAll(listOf("k")).handle { _, e -> e }.join().assert().isSameAs(failure)
        async.get("k", AsyncCacheLoader { CompletableFuture.completedFuture("v") }).handle { _, e -> e }.join()
            .assert().isSameAs(failure)
    }

    @Test
    fun asyncTimeoutReleasesTheLease() {
        val impatient = policy().toBuilder().load(LoadPolicy(asyncTimeout = Duration.ofMillis(200))).build()
        val never = AsyncCacheLoader<String, String> { CompletableFuture() }
        val async = nodeA.rawCache(never, impatient).async()
        val error = async.get("k").handle { _, e -> e }.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        error.assert().isInstanceOf(SourceLoadTimeoutException::class.java)
        nodeA.metrics().loadTimeouts.assert().isEqualTo(1)
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
        database["k"] = "v"
        nodeB.cache()["k"].assert().isEqualTo("v")
        remoteValue("k").assert().isEqualTo("v")
    }

    @Test
    fun syncCallerJoiningAnAsyncLoadBlocksOnlyItself() {
        val source = PendingLoader()
        val engine = nodeA.rawCache(source)
        val asyncLeader = engine.async().get("k")
        awaitCondition("async leader loading") { source.calls.get() == 1 }
        val syncJoiner = fork("sync-joiner") { engine["k"] }
        awaitCondition("sync joiner waiting") { syncJoiner.thread.state == Thread.State.WAITING }
        source.complete("other", "o")
        engine.async().get("other").get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("o")
        source.complete("k", "v")
        asyncLeader.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v")
        syncJoiner.join().assert().isEqualTo("v")
        source.calls.get().assert().isEqualTo(2)
    }

    @Test
    fun asyncCallerJoiningASyncLoadCompletesOnTheAsyncExecutor() {
        val (asyncPool, _) = named("tck-async")
        val runtime = node(asyncExecutor = asyncPool)
        try {
            val loading = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val engine = runtime.create(
                syncSpec { key ->
                    calls.incrementAndGet()
                    loading.countDown()
                    release.awaitOrFail("release")
                    "v-$key"
                }
            )
            val syncLeader = fork("sync-leader") { engine["k"] }
            loading.awaitOrFail("sync leader loading")
            val joined = engine.async().get("k")
            val completedOn = AtomicReference<String>()
            joined.whenComplete { _, _ -> completedOn.compareAndSet(null, Thread.currentThread().name) }
            release.countDown()
            // 先等回调执行再 join / get：JDK 的 get / join 会在等待线程上执行尚未执行的依赖阶段
            awaitCondition("completion recorded") { completedOn.get() != null }
            syncLeader.join().assert().isEqualTo("v-k")
            joined.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v-k")
            completedOn.get().assert().startsWith("tck-async")
            calls.get().assert().isEqualTo(1)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun syncSourceUnderAsyncCallsRunsOnlyOnTheBlockingExecutor() {
        val (asyncPool, _) = named("tck-async")
        val (blockingPool, _) = named("tck-blocking")
        val runtime = node(asyncExecutor = asyncPool, blockingExecutor = blockingPool)
        try {
            val loadedOn = ConcurrentHashMap.newKeySet<String>()
            val engine = runtime.create(
                syncSpec(object : CacheLoader<String, String> {
                    override fun load(key: String): String {
                        loadedOn.add(Thread.currentThread().name)
                        return "v-$key"
                    }

                    override fun loadAll(keys: Collection<String>): Map<String, String> {
                        loadedOn.add(Thread.currentThread().name)
                        return keys.associateWith { "v-$it" }
                    }
                })
            )
            val async = engine.async()
            async.get("a").get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v-a")
            async.getAll(listOf("b", "c")).get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .assert().isEqualTo(mapOf("b" to "v-b", "c" to "v-c"))
            loadedOn.assert().isNotEmpty()
            loadedOn.all { it.startsWith("tck-blocking") }.assert().withFailMessage { "loaded on $loadedOn" }.isTrue()
        } finally {
            runtime.close()
        }
    }

    @Test
    fun syncSourceReentryUnderAsyncCallFailsFast() {
        lateinit var engine: Cache<String, String>
        engine = nodeA.create(syncSpec { key -> engine[key] })
        val error = engine.async().get(
            "k"
        ).handle { _, e -> e }.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        error.assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun asyncSourceServesSyncCalls() {
        val engine = nodeA.rawCache(AsyncCacheLoader { key -> CompletableFuture.supplyAsync { "v-$key" } })
        engine["k"].assert().isEqualTo("v-k")
        engine.getAll(listOf("a", "b")).assert().isEqualTo(mapOf("a" to "v-a", "b" to "v-b"))
        remoteValue("k").assert().isEqualTo("v-k")
    }

    @Test
    fun closingTheRuntimeFailsPendingLeaseWaits() {
        val holder = PendingLoader()
        nodeA.rawCache(holder).async().get("k")
        awaitCondition("A holds the lease") { holder.calls.get() == 1 }
        val waiter = node()
        val waiting = waiter.rawCache(PendingLoader()).async().get("k")
        awaitCondition("waiting on the lease") { requireNotNull(waiter.metrics(cacheName)).leaseWaits == 1L }
        waiter.close()
        val error = waiting.handle { _, e -> e }.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        error.assert().isInstanceOf(IllegalStateException::class.java)
        holder.complete("k", "v")
    }

    @Test
    fun putCompletesAfterTheLocalCopyIsEvicted() {
        database["k"] = "v0"
        val async = nodeA.create(spec()).async()
        async.get("k").join().assert().isEqualTo("v0")
        async.put("k", "v1").thenCompose { async.get("k") }.join().assert().isEqualTo("v1")
        async.invalidate("k").thenCompose { async.get("k") }.join().assert().isEqualTo("v0")
    }

    companion object {
        /**
         * 同时等待的异步读取数。
         */
        const val WAITERS = 1000

        /**
         * 等待期间允许新增的线程数，与 [WAITERS] 无关。原为 4，在 Redis 上实测为 5：定时器线程、命令超时的计时线程
         * （JDK 的延迟调度线程或 Lettuce 的时间轮线程，首次使用时创建），以及 L1 客户端按需创建的 I/O 线程都是常数级的固定开销。
         * 取 8 留出余量；每个等待者占一个线程时增量会是 [WAITERS] 量级（1000），断言仍然有区分度。
         */
        const val CONSTANT_THREADS = 8
    }
}
