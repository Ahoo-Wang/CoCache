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

package me.ahoo.cocache.core.suspending

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 挂起视图直接调用协程核心时的执行上下文（RFC 0005 §4.4，K2、K6）：调度器取自运行时，从不在 L1 客户端的完成线程上继续或恢复；
 * 共享加载的元素取自调用方。
 *
 * L1 的结果都在一个专门的线程（`l1-io`，模拟 Lettuce 的事件循环）上送达，而且只在调用方确实挂起之后才送达（[gate]）：
 * 否则送达可能抢在挂起之前完成，核心就在调用方线程上继续（与异步变体相同，第一次挂起之前在调用方线程上），用例就看不到换线程。
 */
class SuspendingContextTest {
    private val io = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD).apply { isDaemon = true } }

    /**
     * 当前这次调用的门闩：`l1-io` 送达 L1 的结果之前等它打开。默认打开（创建运行时与缓存时的 L1 调用不受影响）。
     */
    private val gate = AtomicReference(CountDownLatch(0))
    private val backend = HookedBackend(InMemoryBackend()).apply {
        ioExecutor = Executor { delivery ->
            val latch = gate.get()
            io.execute {
                latch.awaitOrFail("the caller suspended")
                delivery.run()
            }
        }
    }
    private val runtime = CacheRuntime.builder().backend(backend).build()

    @AfterEach
    fun destroy() {
        runtime.close()
        io.shutdownNow()
    }

    private fun currentThread(): String = Concurrency.currentThreadName()

    /**
     * 在 [context] 中发起 [call] 并等待它：先以 `UNDISPATCHED` 执行到第一次挂起（它在等 L1 的结果），再打开门闩让结果送达，
     * 返回调用方恢复时所在的线程。
     */
    private suspend fun CoroutineScope.resumedOn(
        context: CoroutineContext = EmptyCoroutineContext,
        call: suspend () -> Unit
    ): String {
        val latch = CountDownLatch(1)
        gate.set(latch)
        val waiting = async(context, CoroutineStart.UNDISPATCHED) {
            call()
            currentThread()
        }
        latch.countDown()
        return waiting.await().also { gate.set(CountDownLatch(0)) }
    }

    /**
     * K2：拦截器是 `Unconfined` 的调用方（Spring WebFlux 的挂起处理器默认如此）。L1 的结果在 `l1-io` 上送达，核心与调用级 loader
     * 经运行时的调度器继续，调用方在运行时的执行器线程上恢复：读取（未命中、调用级 loader、批量）与写入之后都不在 `l1-io` 上。
     */
    @Test
    fun anUnconfinedCallerNeverResumesOnTheL1CompletionThread(): Unit = runBlocking<Unit> {
        val cache = runtime.create(
            cacheSpec<String, String>("unconfined") {
                codec = StringCodec
                externallySourced()
            }
        ).suspending()
        val store = runtime.create(storeSpec<String, String>("store") { codec = StringCodec }).suspending()
        val loaderThread = AtomicReference<String>()
        val resumed = listOf<suspend () -> Unit>(
            {
                cache.get("k") {
                    loaderThread.set(currentThread())
                    "v"
                }.assert().isEqualTo("v")
            },
            { cache.put("p", "1") },
            {
                runtime.admin("unconfined")?.clearLocal()
                cache.getAll(listOf("k", "p")).assert().isEqualTo(mapOf("k" to "v", "p" to "1"))
            },
            { cache.invalidate("p") },
            { store.put("s", "1") },
            { store.getIfChanged("s", null) },
        ).map { resumedOn(Dispatchers.Unconfined, it) }
        loaderThread.get().assert().startsWith(RUNTIME_THREAD)
        resumed.forEach { it.assert().isNotEqualTo(IO_THREAD).startsWith(RUNTIME_THREAD) }
        backend.reads.get().assert().isGreaterThan(0)
    }

    /**
     * K6：共享加载继承调用方的 `ThreadContextElement`（MDC、追踪），去掉 `Job`。声明的异步数据源在 L1 的结果送达之后、
     * 运行时的执行器线程上被调用（调用方此时挂起着，不在那个线程上），仍看得到调用方的元素。
     */
    @Test
    fun aSpecLevelAsyncLoaderSeesTheCallersThreadContextElements(): Unit = runBlocking<Unit> {
        val seen = AtomicReference<String?>()
        val loaderThread = AtomicReference<String>()
        val cache = runtime.create(
            cacheSpec<String, String>("traced") {
                codec = StringCodec
                asyncLoader = AsyncCacheLoader { key ->
                    seen.set(TRACE.get())
                    loaderThread.set(currentThread())
                    CompletableFuture.completedFuture("v-$key")
                }
            }
        ).suspending()
        resumedOn(TRACE.asContextElement("trace-1")) {
            cache.get("k").assert().isEqualTo("v-k")
        }
        loaderThread.get().assert().startsWith(RUNTIME_THREAD)
        seen.get().assert().isEqualTo("trace-1")
        TRACE.get().assert().isNull()
    }

    private companion object {
        const val IO_THREAD = "l1-io"
        const val RUNTIME_THREAD = "cocache-async-"
        val TRACE = ThreadLocal<String?>()
    }
}
