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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 异步视图的续接经运行时的执行器派发（RFC 0005 §4.4、§5）：每个在执行器之外（I/O 线程、数据源的线程）完成的步骤都派发一次，
 * 调用方的回调从不在 L1 客户端的 I/O 线程或数据源的线程上执行；同步数据源在同一个执行器上执行。6.x 的就地续接已删除。
 *
 * 派发次数与完成的先后无关（[measure]）：外部的完成（L1 结果的送达、异步数据源的完成、同步 loader 的返回）都排进 [steps]，
 * 由测试线程在执行器空闲时逐个放行。那时协程一定已经挂起在这一步上，所以每个外部完成恰好派发一次；否则先于挂起完成的步骤
 * 就地返回、不派发，次数随时序变化。
 */
class AsyncDispatchTest {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "test-io").apply { isDaemon = true } }
    private val pool = Executors.newCachedThreadPool { Thread(it, "test-exec").apply { isDaemon = true } }
    private val dispatches = AtomicInteger()
    private val gate = AtomicReference(CountDownLatch(0))

    /**
     * 为 `true` 时外部的完成排进 [steps]（[measure] 期间）；否则 L1 的结果经闸门在“I/O 线程”上送达（重入用例）。
     */
    @Volatile
    private var stepping = false
    private val steps = LinkedBlockingQueue<Runnable>()

    /**
     * 已提交、尚未执行完的执行器任务数；其中 [blockedLoaders] 个是停在 [steps] 上的同步 loader。
     */
    private val running = AtomicInteger()
    private val blockedLoaders = AtomicInteger()
    private val backend = HookedBackend(InMemoryBackend()).apply {
        ioExecutor = Executor { task ->
            if (stepping) {
                steps.add(task)
            } else {
                // L1 的结果在“I/O 线程”上、且只在闸门打开之后送达：调用返回时结果一定尚未完成
                val opened = gate.get()
                io.execute {
                    opened.await(10, TimeUnit.SECONDS)
                    task.run()
                }
            }
        }
    }
    private val counting = Executor { task ->
        dispatches.incrementAndGet()
        running.incrementAndGet()
        pool.execute {
            try {
                task.run()
            } finally {
                running.decrementAndGet()
            }
        }
    }
    private val runtime = CacheRuntime.builder().backend(backend).executor(counting).build()
    private val loadedOn = AtomicReference<String>()

    @AfterEach
    fun close() {
        runtime.close()
        backend.close()
        listOf(io, pool).forEach { it.shutdownNow() }
    }

    private fun cache(name: String, async: Boolean = false): Cache<String, String> = runtime.create(
        cacheSpec<String, String>(name) {
            codec = StringCodec
            if (async) {
                asyncLoader = AsyncCacheLoader { key ->
                    CompletableFuture<String?>().also { loaded -> steps.add { loaded.complete("v-$key") } }
                }
            } else {
                loader = CacheLoader { key ->
                    loadedOn.set(Concurrency.currentThreadName())
                    awaitStep()
                    "v-$key"
                }
            }
        }
    )

    /**
     * 同步 loader 停在这里，直到测试线程放行（[measure] 之外直接返回）。
     */
    private fun awaitStep() {
        if (!stepping) {
            return
        }
        val released = CountDownLatch(1)
        blockedLoaders.incrementAndGet()
        steps.add {
            blockedLoaders.decrementAndGet()
            released.countDown()
        }
        released.awaitOrFail("loader released")
    }

    /**
     * 执行器空闲：除了停在 [steps] 上的同步 loader，没有在途的任务。
     */
    private fun quiescent(): Boolean = running.get() == blockedLoaders.get()

    /**
     * 发起 [call]，登记一个用户回调，然后逐个放行外部的完成，每次都等执行器空闲（协程已挂起）之后；
     * 返回结果、回调所在线程，以及这次操作派发到执行器的次数。外部的完成在测试线程上执行。
     */
    private fun <T> measure(call: () -> CompletableFuture<T>): Triple<T, String, Int> {
        dispatches.set(0)
        stepping = true
        try {
            val future = call()
            val completedOn = CompletableFuture<String>()
            future.whenComplete { _, _ -> completedOn.complete(Concurrency.currentThreadName()) }
            while (true) {
                awaitCondition("the call waits on an outside step") {
                    quiescent() && (future.isDone || steps.isNotEmpty())
                }
                if (future.isDone && steps.isEmpty()) {
                    break
                }
                requireNotNull(steps.poll()).run()
            }
            // 先等回调执行完再等结果：在 future 上等待的线程按 JDK 规则可能代为执行它尚未执行的依赖阶段
            val thread = completedOn.get(10, TimeUnit.SECONDS)
            val value = future.get(10, TimeUnit.SECONDS)
            return Triple(value, thread, dispatches.get())
        } finally {
            stepping = false
        }
    }

    @Test
    fun l1HitDispatchesOnce() {
        val cache = cache("l1-hit")
        // put 淘汰 L2 并写入 L1：随后的读取命中 L1
        cache.put("k", "v")
        val (value, completedOn, counts) = measure { cache.async().get("k") }
        value.assert().isEqualTo("v")
        completedOn.assert().isEqualTo("test-exec")
        counts.assert().isEqualTo(1)
        // 已填充 L2：命中不派发
        measure { cache.async().get("k") }.third.assert().isEqualTo(0)
    }

    @Test
    fun missWithBlockingLoaderRunsTheLoaderOnTheExecutor() {
        val cache = cache("miss-blocking")
        val (value, completedOn, counts) = measure { cache.async().get("k") }
        value.assert().isEqualTo("v-k")
        completedOn.assert().isEqualTo("test-exec")
        loadedOn.get().assert().isEqualTo("test-exec")
        // L1 读之后、loader（在执行器上执行）、loader 完成之后、fill 之后各一次
        counts.assert().isEqualTo(4)
    }

    @Test
    fun missWithAsyncLoaderNeverContinuesOnTheSourceThread() {
        val cache = cache("miss-async", async = true)
        val (value, completedOn, counts) = measure { cache.async().get("k") }
        value.assert().isEqualTo("v-k")
        completedOn.assert().isEqualTo("test-exec")
        // L1 读之后、数据源的 future 完成之后、fill 之后各一次
        counts.assert().isEqualTo(3)
    }

    @Test
    fun writesDispatchOnce() {
        val cache = cache("write")
        val (_, putOn, putCounts) = measure { cache.async().put("k", "v") }
        putOn.assert().isEqualTo("test-exec")
        putCounts.assert().isEqualTo(1)
        val (_, invalidateOn, invalidateCounts) = measure { cache.async().invalidate("k") }
        invalidateOn.assert().isEqualTo("test-exec")
        invalidateCounts.assert().isEqualTo(1)
    }

    /**
     * 数据源在执行器的续接里被调用，并在 `loading(A)` 作用域内同步完成另一个 key（B）的数据源 future：
     * B 的续接不能在 A 的作用域内就地执行，否则 B 的回调再读 A 会被误判为同一线程的重入（续接总是派发，正常合并到 A）。
     */
    @Test
    fun continuationsCompletedInsideALoaderAreNotInlinedIntoItsLoadingScope() {
        val pendingB = CompletableFuture<String>()
        val cache = runtime.create(
            cacheSpec<String, String>("reentry") {
                codec = StringCodec
                asyncLoader = AsyncCacheLoader { key ->
                    when (key) {
                        "b" -> pendingB
                        "a" -> {
                            // 在 loading(a) 作用域内同步完成 B 的数据源
                            pendingB.complete("v-b")
                            CompletableFuture.completedFuture("v-a")
                        }
                        else -> CompletableFuture.completedFuture("v-$key")
                    }
                }
            }
        )
        val async = cache.async()
        // B 先开始，等待它的数据源；B 完成后的回调读取 A
        val bThenA = async.get("b").thenCompose { async.get("a") }
        // 让 A 从执行器的续接里开始：x 的 L1 结果在“I/O 线程”上送达，经派发后在执行器线程上完成，用户回调随之在那里执行
        cache.put("x", "v-x")
        val opened = CountDownLatch(1)
        gate.set(opened)
        val x = async.get("x")
        backend.ioExecutor = null
        val a = x.thenCompose { async.get("a") }
        opened.countDown()
        a.get(10, TimeUnit.SECONDS).assert().isEqualTo("v-a")
        bThenA.get(10, TimeUnit.SECONDS).assert().isEqualTo("v-a")
    }

    /**
     * 用户代码在执行器的续接里调用同步 API：同步未命中 K 时，K 的 leader 在归还回源许可时唤醒 B 的异步等待者。
     * B 的续接不能在 K 的 owner 作用域内就地执行，否则 B 的回调再读 K 会被误判为同一线程的重入（续接总是派发，正常合并到 K）。
     */
    @Test
    fun continuationsWokenBySyncLeaderAreNotInlinedIntoItsOwnerScope() {
        val bThenK = AtomicReference<CompletableFuture<String?>>()
        lateinit var cache: Cache<String, String>
        cache = runtime.create(
            cacheSpec<String, String>("sync-owner") {
                codec = StringCodec
                asyncLoader = AsyncCacheLoader { key ->
                    if (key == "k") {
                        // K 持有唯一的回源许可：B 登记为等待者；K 归还许可时（在 K 的 leader 作用域内）唤醒它
                        bThenK.set(cache.async().get("b").thenApply { cache["k"] })
                    }
                    CompletableFuture.completedFuture("v-$key")
                }
                policy { load = LoadPolicy(maxConcurrent = 1) }
            }
        )
        cache.put("x", "v-x")
        val opened = CountDownLatch(1)
        gate.set(opened)
        val x = cache.async().get("x")
        backend.ioExecutor = null
        // x 的 L1 结果经派发后在执行器线程上完成，用户回调随之在那里同步读取 K
        val k = x.thenApply { cache["k"] }
        opened.countDown()
        k.get(10, TimeUnit.SECONDS).assert().isEqualTo("v-k")
        requireNotNull(bThenK.get()).get(10, TimeUnit.SECONDS).assert().isEqualTo("v-k")
    }
}
