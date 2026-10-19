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
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * 异步路径的回源观测（RFC 0004 §8.1）：只有覆盖了 [LoadObserver.observeAsync] 的观测器才会看到异步加载，
 * 观测从调用 block 到其 future 完成；命中路径从不观测。
 */
class AsyncLoadObserverTest {
    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val database = ConcurrentHashMap<String, String>()
    private val runtimes = mutableListOf<CacheRuntime>()
    private val name = "observed-async"

    @Volatile
    private var loadFailure: Throwable? = null

    private val source = object : AsyncCacheLoader<String, String> {
        override fun loadAsync(key: String): CompletableFuture<String?> =
            loadFailure?.let { CompletableFuture.failedFuture(it) } ?: CompletableFuture.completedFuture(database[key])

        override fun loadAllAsync(keys: Collection<String>): CompletableFuture<Map<String, String>> =
            CompletableFuture.completedFuture(keys.mapNotNull { k -> database[k]?.let { k to it } }.toMap())
    }

    class AsyncRecordingObserver : LoadObserver {
        data class Record(val operation: LoadOperation, val keyCount: Int, val error: Throwable?)

        val records = CopyOnWriteArrayList<Record>()

        override fun <T> observe(cacheName: String, operation: LoadOperation, keyCount: Int, block: Supplier<T>): T = block.get()

        override fun <T> observeAsync(
            cacheName: String,
            operation: LoadOperation,
            keyCount: Int,
            block: Supplier<CompletableFuture<T>>
        ): CompletableFuture<T> = block.get().whenComplete { _, error ->
            records.add(
                Record(operation, keyCount, error)
            )
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
        memory.store(name).read("cocache:$name:held", LeaseRequest(token, Duration.ofMinutes(1)), null)
        val waiting = cache.get("held")
        awaitCondition(
            "waiting"
        ) { observer.records.none { it.operation == LoadOperation.LEASE_WAIT } && !waiting.isDone }
        memory.store(name).release("cocache:$name:held", token)
        database["held"] = "h"
        waiting.get(10, TimeUnit.SECONDS).assert().isEqualTo("h")
        observer.records.map { it.operation }.assert().contains(LoadOperation.LEASE_WAIT)
    }

    @Test
    fun observersWithoutAsyncSupportSeeNothingOnTheAsyncPath() {
        val syncOnly = LoadObserverTest.RecordingObserver()
        val cache = runtime(syncOnly).cache()
        database["k"] = "v"
        cache.get("k").join().assert().isEqualTo("v")
        syncOnly.records.assert().isEmpty()
        LoadObserver.NOOP.observeAsync("c", LoadOperation.LOAD, 1) { CompletableFuture.completedFuture("v") }.join()
            .assert().isEqualTo("v")
    }
}
