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
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 异步视图每次操作派发到执行器的次数（docs/investigations/async-8t-gap.md）：只在上一步于执行器之外（I/O 线程、阻塞执行器、
 * 用户的线程）完成时派发；上一步已在执行器派发的续接中完成时就地执行。
 */
class AsyncDispatchTest {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "test-io").apply { isDaemon = true } }
    private val asyncPool = Executors.newFixedThreadPool(2) { Thread(it, "test-async").apply { isDaemon = true } }
    private val blockingPool = Executors.newCachedThreadPool { Thread(it, "test-blocking").apply { isDaemon = true } }
    private val sourcePool = Executors.newSingleThreadExecutor { Thread(it, "test-source").apply { isDaemon = true } }
    private val dispatches = AtomicInteger()
    private val blockingDispatches = AtomicInteger()
    private val gate = AtomicReference(CountDownLatch(0))
    private val backend = HookedBackend(InMemoryBackend()).apply {
        // L1 的结果在“I/O 线程”上、且只在闸门打开之后送达：调用返回时结果一定尚未完成
        ioExecutor = Executor { task ->
            val opened = gate.get()
            io.execute {
                opened.await(10, TimeUnit.SECONDS)
                task.run()
            }
        }
    }
    private val countingAsync = Executor { task ->
        dispatches.incrementAndGet()
        asyncPool.execute(task)
    }
    private val countingBlocking = Executor { task ->
        blockingDispatches.incrementAndGet()
        blockingPool.execute(task)
    }
    private val runtime = CacheRuntime.builder().backend(backend)
        .asyncExecutor(countingAsync)
        .blockingExecutor(countingBlocking)
        .build()

    @AfterEach
    fun close() {
        runtime.close()
        backend.close()
        listOf(io, asyncPool, blockingPool, sourcePool).forEach { it.shutdownNow() }
    }

    private fun cache(name: String, async: Boolean = false): Cache<String, String> = runtime.create(
        cacheSpec<String, String>(name) {
            codec = StringCodec
            if (async) {
                asyncLoader = AsyncCacheLoader { key -> CompletableFuture.supplyAsync({ "v-$key" }, sourcePool) }
            } else {
                loader = CacheLoader { key -> "v-$key" }
            }
        }
    )

    /**
     * 发起 [call]，在 L1 送达之前登记一个用户回调，再放行；返回结果、回调所在线程，以及这次操作的派发次数（异步执行器, 阻塞执行器）。
     * 只有第一个 L1 结果被闸门挡住，之后的结果（fill）可能在续接登记之前就已送达。
     */
    private fun <T> measure(call: () -> CompletableFuture<T>): Triple<T, String, Pair<Int, Int>> {
        val opened = CountDownLatch(1)
        gate.set(opened)
        dispatches.set(0)
        blockingDispatches.set(0)
        val future = call()
        val completedOn = CompletableFuture<String>()
        future.whenComplete { _, _ -> completedOn.complete(Thread.currentThread().name) }
        opened.countDown()
        // 先等回调执行完再等结果：在 future 上等待的线程按 JDK 规则可能代为执行它尚未执行的依赖阶段
        val thread = completedOn.get(10, TimeUnit.SECONDS)
        val value = future.get(10, TimeUnit.SECONDS)
        gate.set(CountDownLatch(0))
        return Triple(value, thread, dispatches.get() to blockingDispatches.get())
    }

    @Test
    fun l1HitDispatchesOnce() {
        val cache = cache("l1-hit")
        // put 淘汰 L2 并写入 L1：随后的读取命中 L1
        cache.put("k", "v")
        val (value, completedOn, counts) = measure { cache.async().get("k") }
        value.assert().isEqualTo("v")
        completedOn.assert().isEqualTo("test-async")
        counts.assert().isEqualTo(1 to 0)
        // 已填充 L2：命中不派发
        measure { cache.async().get("k") }.third.assert().isEqualTo(0 to 0)
    }

    @Test
    fun missWithBlockingLoaderDispatchesAfterEachForeignCompletion() {
        val cache = cache("miss-blocking")
        val (value, completedOn, counts) = measure { cache.async().get("k") }
        value.assert().isEqualTo("v-k")
        completedOn.assert().isEqualTo("test-async")
        // 至多在 L1 读、阻塞执行器上的 loader、fill 之后各一次（loader 或 fill 的结果在续接登记之前送达时就地执行，不派发）；
        // 读结果被闸门挡住，所以至少一次。改动之前每一级都派发，至少 4 次
        counts.first.assert().isBetween(1, 3)
        counts.second.assert().isEqualTo(1)
    }

    @Test
    fun missWithAsyncLoaderDispatchesAfterEachForeignCompletion() {
        val cache = cache("miss-async", async = true)
        val (value, completedOn, counts) = measure { cache.async().get("k") }
        value.assert().isEqualTo("v-k")
        completedOn.assert().isEqualTo("test-async")
        // 至多在 L1 读、数据源自己的线程、fill 之后各一次
        counts.first.assert().isBetween(1, 3)
        counts.second.assert().isEqualTo(0)
    }

    @Test
    fun writesDispatchOnce() {
        val cache = cache("write")
        val (_, putOn, putCounts) = measure { cache.async().put("k", "v") }
        putOn.assert().isEqualTo("test-async")
        putCounts.assert().isEqualTo(1 to 0)
        val (_, invalidateOn, invalidateCounts) = measure { cache.async().invalidate("k") }
        invalidateOn.assert().isEqualTo("test-async")
        invalidateCounts.assert().isEqualTo(1 to 0)
    }
}
