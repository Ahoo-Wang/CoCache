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

import kotlinx.coroutines.future.await
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 核心在 `NonCancellable` 中调用 L1（RFC 0005 §4.3）：原生的挂起存储用可取消的 `await()` 等待底层命令也是安全的。
 * 这里的存储先在 L1 中取得 lease，再以 kotlinx 的可取消等待等一个门（模拟等待命令的应答）。调用方被取消
 * （同步变体中线程被中断，异步变体中运行时关闭）时，等待不被取消，读取照常交回 lease，调用方随后释放它；
 * 如果核心取消了这次调用，lease 就丢了，要到 `lease.ttl` 才回收。
 */
class NonCancellableL1Test {
    private val memory = InMemoryBackend()
    private val gate = CompletableFuture<Unit>()
    private val waiting = AtomicInteger()
    private val loads = AtomicInteger()
    private val runtimes = ArrayList<CacheRuntime>()

    @AfterEach
    fun close() {
        gate.complete(Unit)
        runtimes.forEach { it.close() }
    }

    /**
     * 读取在 L1 中执行之后，以可取消的等待等 [gate]。
     */
    private val backend = object : CoherenceBackend by memory {
        override fun store(namespace: String): L1Store = object : ForwardingL1Store(memory.store(namespace)) {
            override suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead {
                val read = delegate.read(key, lease, refresh)
                waiting.incrementAndGet()
                gate.await()
                return read
            }
        }
    }

    private fun cache(): me.ahoo.cocache.api.Cache<String, String> {
        val runtime = CacheRuntime.builder().backend(backend).build().also { runtimes += it }
        return runtime.create(
            cacheSpec(NAME) {
                codec = StringCodec
                loader = CacheLoader { key -> "$key-${loads.incrementAndGet()}" }
            }
        )
    }

    private fun leased(): Boolean = Suspending.await {
        memory.store(NAME).read("cocache:$NAME:k", null, null)
    } is RemoteRead.Leased

    @Test
    fun anInterruptedCallerWaitsForTheCancellableReadAndReleasesTheLease() {
        val cache = cache()
        val caller = fork("reader") {
            val result = runCatching { cache["k"] }
            result to Thread.currentThread().isInterrupted
        }
        awaitCondition("read waiting") { waiting.get() == 1 && caller.parked }
        leased().assert().isTrue()

        caller.thread.interrupt()
        awaitCondition(
            "runBlocking cancelled the coroutine and parked again"
        ) { !caller.thread.isInterrupted && caller.parked }
        gate.isCancelled.assert().withFailMessage { "the core must not cancel an L1 call" }.isFalse()
        gate.complete(Unit)

        val (result, interrupted) = caller.join()
        result.exceptionOrNull().assert().isInstanceOf(InterruptedException::class.java)
        interrupted.assert().isTrue()
        leased().assert().withFailMessage { "the lease taken by the read must be released" }.isFalse()
        loads.get().assert().isZero()
    }

    @Test
    fun closingTheRuntimeWaitsForTheCancellableReadAndReleasesTheLease() {
        val cache = cache()
        val reading = cache.async().get("k")
        awaitCondition("read waiting") { waiting.get() == 1 }
        leased().assert().isTrue()

        runtimes.single().close()
        gate.isCancelled.assert().withFailMessage { "the core must not cancel an L1 call" }.isFalse()
        gate.complete(Unit)

        // 运行时已关闭：调用以关闭失败，或在之后把阻塞 loader 交给已关闭的执行器时被拒绝
        reading.handle { _, error -> error }.get(5, TimeUnit.SECONDS).assert().isNotNull()
        awaitCondition("the lease taken by the read is released") { !leased() }
        loads.get().assert().isZero()
    }

    private companion object {
        const val NAME = "non-cancellable"
    }
}
