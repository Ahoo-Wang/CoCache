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

package me.ahoo.cocache.redis

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.EngineSpec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 异步变体在真实 L1 客户端上的线程用例：[EngineSpec] 的每个用例经 `async()` 重跑，另加断言 Lettuce I/O 线程的用例。
 *
 * 异步变体的其它专有用例（取消、执行器拒绝、超时、重入、运行时关闭等）是核心的语义，在 `cocache-core` 的
 * `AsyncVariantEngineCases` 中以内存后端运行。这里的三个用例与它们一字不差，只在 Redis 上另跑一份：
 * L1 的结果在 Lettuce 的 I/O 线程上完成，等待期间的线程增量也包含 Lettuce 的定时与 I/O 线程，内存后端没有这些线程。
 */
abstract class RedisAsyncThreadCases : EngineSpec() {
    /**
     * 异步变体：[CacheAccess.ASYNC] 或 [CacheAccess.ASYNC_WITH_SYNC_SOURCE]，由子类选择。
     */
    abstract override val access: CacheAccess

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
     * 只在 [gate] 打开后才在 [pool] 上执行的执行器：既作 [HookedBackend.ioExecutor]（L1 结果的送达），也作运行时的执行器
     * （续接与异步路径上的同步数据源）。
     */
    private fun gated(pool: Executor, gate: () -> CountDownLatch, what: String): Executor = Executor { task ->
        val opened = gate()
        pool.execute {
            opened.awaitOrFail(what)
            task.run()
        }
    }

    private fun node(executor: Executor? = null): CacheRuntime {
        val builder = CacheRuntime.builder()
            .backend(backend)
            .clock(clock)
            .retryPolicy(RetryPolicy(initialBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(50)))
        executor?.let { builder.executor(it) }
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

    private fun CacheRuntime.rawCache(source: AsyncCacheLoader<String, String>, policy: CachePolicy = policy()): Cache<String, String> =
        create(
            spec(policy) {
                loader = null
                asyncLoader = source
            }
        )

    @Test
    fun futuresNeverCompleteOnTheBackendIoThread() {
        val (io, _) = named("tck-io", 1)
        val (pool, _) = named("tck-exec")
        // L1 的结果在回调注册之前不送达：回调一定是在完成时执行的，而不是注册时就地执行。
        // L1 的结果经 ioExecutor 送达，续接与同步数据源在运行时的执行器上执行，两者都要拦住
        val gate = AtomicReference(CountDownLatch(1))
        backend.ioExecutor = gated(io, gate::get, "callback registered")
        val runtime = node(gated(pool, gate::get, "callback registered"))
        try {
            database["k"] = "v"
            val async = runtime.create(spec()).async()
            val completedOn = AtomicReference<String>()
            val future = async.get("k")
            future.isDone.assert().isFalse()
            future.whenComplete { _, _ -> completedOn.compareAndSet(null, Concurrency.currentThreadName()) }
            gate.get().countDown()
            // 先等回调执行再 get：JDK 的 get / join 会在等待线程上执行尚未执行的依赖阶段
            awaitCondition("completion recorded") { completedOn.get() != null }
            future.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v")
            completedOn.get().assert().withFailMessage { "completed on ${completedOn.get()}" }.startsWith("tck-exec")
            gate.set(CountDownLatch(1))
            val write = async.put("k", "v2")
            write.isDone.assert().isFalse()
            val writtenOn = AtomicReference<String>()
            write.whenComplete { _, _ -> writtenOn.compareAndSet(null, Concurrency.currentThreadName()) }
            gate.get().countDown()
            awaitCondition("write completion recorded") { writtenOn.get() != null }
            write.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            writtenOn.get().assert().startsWith("tck-exec")
        } finally {
            gate.get().countDown()
            backend.ioExecutor = null
            runtime.close()
        }
    }

    /**
     * 不挂起线程：N 个 key 被另一节点的 lease 占住（数据源未完成，不占线程），本节点 N 个异步读取全部在等待 lease，
     * 等待期间线程数与 N 无关；lease 释放后全部完成，每个 key 只回源一次。
     */
    @Test
    fun leaseWaitsUseConstantThreads() {
        val keys = (1..WAITERS).map { "k$it" }
        val holder = PendingLoader()
        val asyncA = nodeA.rawCache(holder).async()
        val held = keys.map { asyncA.get(it) }
        awaitCondition("A holds every lease") { holder.calls.get() == WAITERS }
        val factory = CountingFactory("tck-exec")
        val pool = Executors.newSingleThreadExecutor(factory).also { executors.add(it) }
        val waiter = node(pool)
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
            factory.created.get().assert().isLessThanOrEqualTo(1)
            keys.forEach { holder.complete(it, "v-$it") }
            held.forEach { it.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
            waiting.forEachIndexed { index, future ->
                future.get(Concurrency.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).assert().isEqualTo("v-${keys[index]}")
            }
            waiterSource.calls.get().assert().isEqualTo(0)
            holder.calls.get().assert().isEqualTo(WAITERS)
            factory.created.get().assert().isLessThanOrEqualTo(1)
        } finally {
            waiter.close()
        }
    }

    /**
     * 不挂起线程：回源许可用尽时，等待许可的异步加载挂起在协程的信号量上，线程数与等待者数无关。
     */
    @Test
    fun permitWaitsUseConstantThreads() {
        val limited = policy().toBuilder().load(
            LoadPolicy(maxConcurrent = 1, acquireTimeout = Duration.ofSeconds(10))
        ).build()
        val source = PendingLoader()
        val factory = CountingFactory("tck-exec")
        val pool = Executors.newSingleThreadExecutor(factory).also { executors.add(it) }
        val runtime = node(pool)
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
            factory.created.get().assert().isLessThanOrEqualTo(1)
            requireNotNull(runtime.metrics(cacheName)).loadRejected.assert().isEqualTo(0)
        } finally {
            runtime.close()
        }
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
