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
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * 异步路径的回源观测（RFC 0005 §5：只有同步的 [LoadObserver.observe]）：观测在专用的虚拟线程上进行（不占用运行时的执行器与调用方线程），
 * 覆盖整个回源（直到数据源的 future 完成）；数据源的同步部分看得到观测器的线程局部状态；命中路径从不观测。
 */
class AsyncLoadObserverTest {
    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val database = ConcurrentHashMap<String, String>()
    private val runtimes = mutableListOf<CacheRuntime>()
    private val name = "observed-async"
    private val pending = ConcurrentHashMap<String, CompletableFuture<String?>>()
    private val seenInScope = CopyOnWriteArrayList<LoadOperation?>()

    @Volatile
    private var loadFailure: Throwable? = null

    private val source = object : AsyncCacheLoader<String, String> {
        override fun loadAsync(key: String): CompletableFuture<String?> {
            seenInScope.add(AsyncRecordingObserver.CURRENT.get())
            pending[key]?.let { return it }
            return loadFailure?.let { CompletableFuture.failedFuture(it) } ?: CompletableFuture.completedFuture(database[key])
        }

        override fun loadAllAsync(keys: Collection<String>): CompletableFuture<Map<String, String>> =
            CompletableFuture.completedFuture(keys.mapNotNull { k -> database[k]?.let { k to it } }.toMap())
    }

    class AsyncRecordingObserver : LoadObserver {
        data class Record(val operation: LoadOperation, val keyCount: Int, val error: Throwable?, val thread: String)

        val records = CopyOnWriteArrayList<Record>()

        @Suppress("TooGenericExceptionCaught")
        override fun <T> observe(cacheName: String, operation: LoadOperation, keyCount: Int, block: Supplier<T>): T {
            val thread = Concurrency.currentThreadName()
            CURRENT.set(operation)
            try {
                return block.get().also { records.add(Record(operation, keyCount, null, thread)) }
            } catch (e: Throwable) {
                records.add(Record(operation, keyCount, e, thread))
                throw e
            } finally {
                CURRENT.remove()
            }
        }

        companion object {
            /**
             * 观测期间当前线程上的操作（模拟 Micrometer 的当前观测）。
             */
            val CURRENT = ThreadLocal<LoadOperation?>()
        }
    }

    @AfterEach
    fun destroy() {
        runtimes.forEach { it.close() }
    }

    private fun runtime(observer: LoadObserver): CacheRuntime =
        CacheRuntime.builder().backend(memory).clock(clock).refreshExecutor { it.run() }.loadObserver(observer).build()
            .also { runtimes.add(it) }

    private fun CacheRuntime.cache(
        policy: CachePolicy = CachePolicy(
            ttl = Duration.ofMinutes(10),
            ttlJitter = Duration.ZERO
        )
    ) =
        create(
            cacheSpec<String, String>(name) {
                codec = TestCodec
                asyncLoader = source
                this.policy = policy
            }
        ).async()

    @Test
    fun observesAsyncLoadsBatchesAndFailuresButNotHits() {
        val observer = AsyncRecordingObserver()
        val cache = runtime(observer).cache()
        database["k"] = "v"
        cache.get("k").join().assert().isEqualTo("v")
        cache.get("k").join().assert().isEqualTo("v")
        cache.getAll(listOf("k", "a", "b")).join().assert().isEqualTo(mapOf("k" to "v"))
        val failure = IllegalStateException("down")
        loadFailure = failure
        cache.get("x").handle { _, e -> e }.join().assert().isSameAs(failure)
        observer.records.map { it.operation to it.keyCount }
            .assert().containsExactly(LoadOperation.LOAD to 1, LoadOperation.LOAD_ALL to 2, LoadOperation.LOAD to 1)
        observer.records.last().error.assert().isSameAs(failure)
        observer.records.all { it.thread.startsWith(OBSERVATION_THREAD_PREFIX) }
            .assert().withFailMessage { "observed on ${observer.records.map { it.thread }}" }.isTrue()
        seenInScope.assert().containsOnly(LoadOperation.LOAD)
    }

    @Test
    fun observesAsyncRefreshAndLeaseWaits() {
        val observer = AsyncRecordingObserver()
        val refreshing = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5),
            lease = LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ofSeconds(5)),
        )
        val cache = runtime(observer).cache(refreshing)
        database["k"] = "v0"
        cache.get("k").join()
        database["k"] = "v1"
        clock.advance(Duration.ofSeconds(6))
        cache.get("k").join().assert().isEqualTo("v0")
        awaitCondition("refreshed") { cache.get("k").join() == "v1" }
        observer.records.map { it.operation }.assert().contains(LoadOperation.REFRESH)
        // 其它节点持有 lease：等待被观测
        val token = Token(NodeId.random(), 1)
        Suspending.await {
            memory.store(
                Keyspace("cocache:", name)
            ).read("cocache:$name:held", LeaseRequest(token, Duration.ofMinutes(1)), null, null)
        }
        val waiting = cache.get("held")
        awaitCondition(
            "waiting"
        ) { observer.records.none { it.operation == LoadOperation.LEASE_WAIT } && !waiting.isDone }
        Suspending.await { memory.store(Keyspace("cocache:", name)).release("cocache:$name:held", token) }
        database["held"] = "h"
        waiting.get(10, TimeUnit.SECONDS).assert().isEqualTo("h")
        observer.records.map { it.operation }.assert().contains(LoadOperation.LEASE_WAIT)
    }

    /**
     * 观测覆盖整个回源：数据源的 future 完成之前观测不结束，调用方线程不被占用（异步调用立即返回）。
     */
    @Test
    fun observationCoversTheWholeLoadWithoutBlockingTheCaller() {
        val observer = AsyncRecordingObserver()
        val cache = runtime(observer).cache()
        val loading = CompletableFuture<String?>()
        pending["k"] = loading
        val call = cache.get("k")
        awaitCondition("source called") { seenInScope.isNotEmpty() }
        call.isDone.assert().isFalse()
        observer.records.assert().isEmpty()
        loading.complete("v")
        call.get(10, TimeUnit.SECONDS).assert().isEqualTo("v")
        awaitCondition("observation stopped") { observer.records.isNotEmpty() }
        observer.records.single().error.assert().isNull()
    }

    /**
     * 观测中的回源在运行时关闭时结束：调用以关闭失败，观测记录到取消，lease 照常释放（不留到 `lease.ttl`）。
     */
    @Test
    fun closingTheRuntimeEndsAnObservedLoadAndReleasesTheLease() {
        val observer = AsyncRecordingObserver()
        val runtime = runtime(observer)
        val cache = runtime.cache()
        pending["k"] = CompletableFuture()
        val call = cache.get("k")
        awaitCondition("source called") { seenInScope.isNotEmpty() }
        runtime.close()
        call.handle { _, e -> e }.get(10, TimeUnit.SECONDS).assert().isInstanceOf(IllegalStateException::class.java)
        observer.records.single().error.assert().isInstanceOf(CancellationException::class.java)
        val store = memory.store(Keyspace("cocache:", name))
        val read = Suspending.await { store.read("cocache:$name:k", null, null, null) }
        read.assert().isEqualTo(L1Read.Miss(false))
    }
}
