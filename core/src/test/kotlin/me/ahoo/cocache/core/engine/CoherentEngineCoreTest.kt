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
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 单 key 读与提前刷新运行在协程核心上（RFC 0005 V5）：运行时关闭取消作用域时，进行中的异步读取以关闭失败，
 * 申请的 lease 与刷新令牌照常交还；异步 SPI 的实现缺陷按 L1 读失败处理。
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
     * 提前刷新的 L1 读取尚未送达时关闭运行时：刷新的协程被取消，申请的刷新令牌被交还，另一个调用方随后能取得它。
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
        val cache = closing.create(
            spec {
                asyncLoader = AsyncCacheLoader { CompletableFuture.completedFuture("v") }
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
        closing.close()
        deliver.countDown()

        val other = RefreshRequest(window, Token(NodeId.random(), 1), Duration.ofSeconds(10))
        awaitCondition("refresh token released") {
            val read = memory.store("core").read("cocache:core:k", null, other)
            read is RemoteRead.Hit && read.refreshGranted
        }
    }

    /**
     * 异步写入的 L1 命令尚未送达时关闭运行时：写入照常做完（修改 L1、第二次递增与淘汰 L2），调用方以关闭失败。
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
        closing.close()
        deliver.countDown()
        val failure = failureOf(write)
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().isEqualTo("CacheRuntime closed.")
        requireNotNull(closing.metrics("core")).writeFailures.assert().isZero()
        runtime().create(spec { loader = CacheLoader { "loaded" } })["k"].assert().isEqualTo("v")
    }

    /**
     * 异步 SPI 的实现以 `null` 完成读取（Java 实现的缺陷）：按 L1 读失败处理，没有数据源时以 [CacheReadException] 失败，
     * cause 说明原因，而不是在之后的分支上抛出更难懂的异常。
     */
    @Test
    fun aNullAsyncReadIsAReadFailure() {
        val cache = runtime(NullReadBackend(memory)).create(spec {})
        val failure = failureOf(cache.async().get("k"))
        failure.assert().isInstanceOf(CacheReadException::class.java)
        failure?.cause.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.cause?.message.assert().isEqualTo("Cache[core] - remote read returned null.")
    }
}

/**
 * 读取以 `null` 完成的后端（模拟有缺陷的 Java 实现），其余操作委托给内存后端。
 */
private class NullReadBackend(private val delegate: InMemoryBackend) : CoherenceBackend by delegate {
    override fun store(namespace: String): RemoteStore = NullReadStore(delegate.store(namespace) as AsyncRemoteStore)
}

private class NullReadStore(private val delegate: AsyncRemoteStore) : AsyncRemoteStore by delegate {
    override fun readAsync(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead> =
        CompletableFuture.completedFuture(null)
}
