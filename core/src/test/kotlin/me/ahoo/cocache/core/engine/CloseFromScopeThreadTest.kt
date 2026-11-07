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
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/**
 * #892：运行时作用域中的协程不只在调度器派发的任务中执行：异步与挂起调用第一次挂起之前在调用方线程上执行，
 * 回源观测在专用的线程上执行。在这些位置关闭运行时同样不等待作用域排空（否则只会等满 `closeTimeout`）；
 * 标记只属于这一个运行时：在其中关闭另一个运行时，照常等待那个运行时排空。
 *
 * 判据同 [CloseFromCallbackTest]：关闭的线程有没有进入 `AsyncContext.awaitDrained`，而不是比较耗时；`closeTimeout` 只保证失败的用例也能结束。
 */
class CloseFromScopeThreadTest {
    @Test
    fun closingInAnAsyncLoadObserverDoesNotWaitForItself() = closeInObserver(Variant.ASYNC)

    @Test
    fun closingInASuspendingLoadObserverDoesNotWaitForItself() = closeInObserver(Variant.SUSPENDING)

    @Test
    fun closingInASyncLoadObserverDoesNotWait() = closeInObserver(Variant.SYNC)

    @Test
    fun closingInAnAsyncSourceBeforeTheFirstSuspensionDoesNotWaitForItself() = closeInSource(Variant.ASYNC)

    @Test
    fun closingInASuspendingSourceBeforeTheFirstSuspensionDoesNotWaitForItself() = closeInSource(Variant.SUSPENDING)

    @Test
    fun closingAnotherRuntimeInALoadObserverWaitsForItToDrain() {
        val putApplied = CountDownLatch(1)
        val releasePut = CountDownLatch(1)
        lateinit var other: CacheRuntime
        val closing = Closing { other.close() }
        val observer = closing.afterObservedLoad()
        val hooked = HookedBackend(InMemoryBackend())
        hooked.use { otherBackend ->
            InMemoryBackend().use { backend ->
                other = CacheRuntime.builder().backend(otherBackend).closeTimeout(Duration.ofSeconds(10)).build()
                other.use {
                    val otherCache = other.create(sourced("other-put"))
                    // 另一个运行时中有一个停在 L1 命令之后的写入：关闭它必须等这个写入结束
                    otherBackend.afterPut = {
                        putApplied.countDown()
                        releasePut.awaitOrFail("put released")
                    }
                    val writer = fork("other-writer") { otherCache.async().put("k", "v").handle { _, _ -> }.join() }
                    putApplied.awaitOrFail("put applied")
                    newRuntime(backend, observer).use { runtime ->
                        val cache = runtime.create(sourced("observed"))
                        val caller = fork("observer-caller") { cache.async().get("k").join() }
                        val waited = closing.waitedForDrain()
                        releasePut.countDown()
                        closing.finished.awaitOrFail("bounded close finished")
                        writer.join()
                        caller.join().assert().isEqualTo("value")
                        waited.assert().withFailMessage {
                            "close of another runtime did not wait for it on ${closing.thread.get().name}"
                        }.isTrue()
                    }
                }
            }
        }
    }

    private fun closeInObserver(variant: Variant) {
        lateinit var runtime: CacheRuntime
        val closing = Closing { runtime.close() }
        val observer = closing.afterObservedLoad()
        InMemoryBackend().use { backend ->
            runtime = newRuntime(backend, observer)
            runtime.use {
                val cache = runtime.create(sourced("observer-close"))
                val caller = fork("observer-close-caller") { variant.read(cache) }
                val waited = closing.waitedForDrain()
                closing.finished.awaitOrFail("bounded close finished")
                caller.join()
                waited.assert().withFailMessage { "close waited for itself on ${closing.thread.get().name}" }.isFalse()
            }
        }
    }

    private fun closeInSource(variant: Variant) {
        lateinit var runtime: CacheRuntime
        val closing = Closing { runtime.close() }
        InMemoryBackend().use { backend ->
            runtime = newRuntime(backend, LoadObserver.NOOP)
            runtime.use {
                val cache = runtime.create(
                    cacheSpec<String, String>("source-close") {
                        codec = StringCodec
                        // 内存后端的 L1 命令不挂起：数据源在调用方线程上、协程第一次挂起之前被调用
                        asyncLoader = AsyncCacheLoader {
                            closing.run()
                            CompletableFuture.completedFuture("value")
                        }
                    }
                )
                val caller = fork("source-close-caller") { variant.read(cache) }
                val waited = closing.waitedForDrain()
                closing.finished.awaitOrFail("bounded close finished")
                caller.join()
                waited.assert().withFailMessage { "close waited for itself on ${closing.thread.get().name}" }.isFalse()
            }
        }
    }

    private fun newRuntime(backend: InMemoryBackend, observer: LoadObserver): CacheRuntime =
        CacheRuntime.builder().backend(backend).loadObserver(observer).closeTimeout(Duration.ofSeconds(2)).build()

    private fun sourced(name: String) = cacheSpec<String, String>(name) {
        codec = StringCodec
        asyncLoader = AsyncCacheLoader { CompletableFuture.completedFuture("value") }
    }

    private enum class Variant {
        SYNC, ASYNC, SUSPENDING;

        /**
         * 读一次，等它结束：关闭可能让读取以关闭异常结束，这里只关心关闭阶段有没有等待自己。
         */
        fun read(cache: Cache<String, String>): Any? = when (this) {
            SYNC -> runCatching { cache["k"] }.getOrNull()
            ASYNC -> cache.async().get("k").handle { value, _ -> value }.join()
            SUSPENDING -> runCatching { Suspending.await { cache.suspending().get("k") } }.getOrNull()
        }
    }

    /**
     * 在某个回调中关闭运行时，记下关闭的线程。
     */
    private class Closing(private val close: () -> Unit) {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val thread = AtomicReference<Thread>()

        /**
         * 观测器：回源结束之后、交出结果之前关闭运行时。
         */
        fun afterObservedLoad(): LoadObserver = object : LoadObserver {
            override fun <T> observe(
                cacheName: String,
                operation: LoadOperation,
                keyCount: Int,
                block: Supplier<T>
            ): T {
                val result = block.get()
                run()
                return result
            }
        }

        fun run() {
            thread.set(Thread.currentThread())
            started.countDown()
            try {
                close()
            } finally {
                finished.countDown()
            }
        }

        /**
         * 关闭返回之前，关闭的线程是否进入了作用域排空的等待。
         */
        fun waitedForDrain(): Boolean {
            started.awaitOrFail("close started")
            fun draining() = thread.get().stackTrace.any {
                it.className.endsWith("AsyncContext") && it.methodName == "awaitDrained"
            }
            awaitCondition("close returned or waiting") { finished.count == 0L || draining() }
            return finished.count != 0L && draining()
        }
    }
}
