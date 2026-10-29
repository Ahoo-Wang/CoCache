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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.concurrent.thread

/**
 * 回源观测：只在回源（未命中、批量回源、提前刷新）与确实发生的 lease 等待时调用，命中路径从不调用；异常原样透传。
 */
class LoadObserverTest {
    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val database = ConcurrentHashMap<String, String>()
    private val observer = RecordingObserver()
    private val runtimes = mutableListOf<CacheRuntime>()
    private val name = "observed"

    @Volatile
    private var loadFailure: Throwable? = null

    private val loader = object : CacheLoader<String, String> {
        override fun load(key: String): String? {
            loadFailure?.let { throw it }
            return database[key]
        }

        override fun loadAll(keys: Collection<String>): Map<String, String> {
            loadFailure?.let { throw it }
            return keys.mapNotNull { k -> database[k]?.let { k to it } }.toMap()
        }
    }

    /**
     * 记录每次观测：操作、key 数、缓存名与结果（成功或抛出的异常）。
     */
    class RecordingObserver : LoadObserver {
        data class Record(val cacheName: String, val operation: LoadOperation, val keyCount: Int, val error: Throwable?)

        val records = CopyOnWriteArrayList<Record>()

        @Suppress("TooGenericExceptionCaught")
        override fun <T> observe(cacheName: String, operation: LoadOperation, keyCount: Int, block: Supplier<T>): T {
            try {
                return block.get().also { records.add(Record(cacheName, operation, keyCount, null)) }
            } catch (e: Throwable) {
                records.add(Record(cacheName, operation, keyCount, e))
                throw e
            }
        }

        fun operations(): List<Pair<LoadOperation, Int>> = records.map { it.operation to it.keyCount }
    }

    @AfterEach
    fun destroy() {
        runtimes.forEach { it.close() }
    }

    private fun runtime(loadObserver: LoadObserver? = observer): CacheRuntime {
        val builder = CacheRuntime.builder().backend(memory).clock(clock).refreshExecutor { it.run() }
        loadObserver?.let { builder.loadObserver(it) }
        return builder.build().also { runtimes.add(it) }
    }

    private fun CacheRuntime.cache(
        policy: CachePolicy = CachePolicy(ttl = Duration.ofMinutes(10), ttlJitter = Duration.ZERO),
        withLoader: Boolean = true,
    ): CoherentEngine<String, String> = create(
        cacheSpec<String, String>(name) {
            codec = TestCodec
            loader = if (withLoader) this@LoadObserverTest.loader else null
            this.policy = policy
        }
    ) as CoherentEngine<String, String>

    @Test
    fun noopRunsTheBlock() {
        LoadObserver.NOOP.observe("c", LoadOperation.LOAD, 1) { "v" }.assert().isEqualTo("v")
        LoadObserver.NOOP.toString().assert().isEqualTo("LoadObserver.NOOP")
        LoadOperation.entries.map { it.value }.assert().containsExactly("load", "loadAll", "refresh", "lease.wait")
    }

    @Test
    fun defaultRuntimeLoadsWithoutObserver() {
        val cache = runtime(loadObserver = null).cache()
        database["k"] = "v"
        cache["k"].assert().isEqualTo("v")
        cache.getAll(listOf("a", "k")).assert().isEqualTo(mapOf("k" to "v"))
        observer.records.assert().isEmpty()
    }

    @Test
    fun observesOnlyMissLoadsNotHits() {
        val cache = runtime().cache()
        database["k"] = "v"
        cache["k"].assert().isEqualTo("v")
        observer.records.single().assert().isEqualTo(RecordingObserver.Record(name, LoadOperation.LOAD, 1, null))
        // L2 命中
        cache["k"].assert().isEqualTo("v")
        (cache.lookup("k") as Lookup.Present).value.assert().isEqualTo("v")
        cache.getAll(listOf("k")).assert().isEqualTo(mapOf("k" to "v"))
        // L1 命中（另一个节点）
        val other = runtime().create(
            cacheSpec<String, String>("$name-other") {
                codec = TestCodec
                namespace = this@LoadObserverTest.name
                loader = this@LoadObserverTest.loader
            }
        )
        other["k"].assert().isEqualTo("v")
        (other as CoherentEngine<*, *>).metrics.remoteHits.assert().isEqualTo(1)
        observer.operations().assert().containsExactly(LoadOperation.LOAD to 1)
    }

    @Test
    fun observesCallerLoaderAndNegativeLoads() {
        val cache = runtime().cache(withLoader = false)
        cache.get("k") { "loaded-$it" }.assert().isEqualTo("loaded-k")
        // 没有数据源的未命中不回源，也就没有观测
        cache["absent"].assert().isNull()
        observer.operations().assert().containsExactly(LoadOperation.LOAD to 1)
    }

    @Test
    fun observesBatchLoadsWithKeyCount() {
        val cache = runtime().cache()
        database["a"] = "a1"
        database["b"] = "b1"
        cache.getAll(listOf("a", "b", "c")).assert().isEqualTo(mapOf("a" to "a1", "b" to "b1"))
        observer.operations().assert().containsExactly(LoadOperation.LOAD_ALL to 3)
        cache.getAll(listOf("a", "b", "c")).assert().isEqualTo(mapOf("a" to "a1", "b" to "b1"))
        observer.operations().assert().containsExactly(LoadOperation.LOAD_ALL to 3)
    }

    @Test
    fun observesRefreshAhead() {
        val policy = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5)
        )
        val cache = runtime().cache(policy)
        database["k"] = "v0"
        cache["k"]
        database["k"] = "v1"
        clock.advance(Duration.ofSeconds(6))
        // 刷新窗口内的命中照常返回旧值，后台（此处同步执行）刷新回源
        cache["k"].assert().isEqualTo("v0")
        cache["k"].assert().isEqualTo("v1")
        observer.operations().assert().containsExactly(LoadOperation.LOAD to 1, LoadOperation.REFRESH to 1)
    }

    @Test
    fun refreshWithoutRemoteValueIsStillObservedAsRefresh() {
        val policy = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5)
        )
        val cache = runtime().cache(policy)
        database["k"] = "v0"
        cache["k"]
        // L1 中的值被静默删除（不广播）：刷新退回普通加载路径，仍以 refresh 观测
        memory.store(name).invalidate(listOf("cocache:$name:k"), InvalidationFrame.encode(NodeId.random(), emptyList()))
        clock.advance(Duration.ofSeconds(6))
        cache["k"]
        observer.operations().assert().containsExactly(LoadOperation.LOAD to 1, LoadOperation.REFRESH to 1)
    }

    @Test
    fun loaderExceptionsPropagateUnchanged() {
        val cache = runtime().cache()
        val failure = IllegalStateException("source down")
        loadFailure = failure
        assertThrows<IllegalStateException> { cache["k"] }.assert().isSameAs(failure)
        assertThrows<IllegalStateException> { cache.getAll(listOf("a", "b")) }.assert().isSameAs(failure)
        observer.records.map { it.error }.assert().containsExactly(failure, failure)
        observer.operations().assert().containsExactly(LoadOperation.LOAD to 1, LoadOperation.LOAD_ALL to 2)
        cache.metrics.loadFailures.assert().isEqualTo(2)
    }

    @Test
    fun observerExceptionsPropagateAndCountAsFailedLoads() {
        val failure = UnsupportedOperationException("observer broken")
        val broken = object : LoadObserver {
            override fun <T> observe(
                cacheName: String,
                operation: LoadOperation,
                keyCount: Int,
                block: Supplier<T>
            ): T = throw failure
        }
        val cache = runtime(broken).cache()
        assertThrows<UnsupportedOperationException> { cache["k"] }.assert().isSameAs(failure)
        cache.metrics.loadFailures.assert().isEqualTo(1)
    }

    @Test
    fun rejectedLoadsAreNotObserved() {
        val policy = CachePolicy(
            ttl = Duration.ofMinutes(10),
            ttlJitter = Duration.ZERO,
            load = LoadPolicy(maxConcurrent = 1, acquireTimeout = Duration.ZERO)
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val blocking = object : LoadObserver {
            override fun <T> observe(
                cacheName: String,
                operation: LoadOperation,
                keyCount: Int,
                block: Supplier<T>
            ): T {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return observer.observe(cacheName, operation, keyCount, block)
            }
        }
        val cache = runtime(blocking).cache(policy)
        val holder = thread {
            cache["a"]
            finished.countDown()
        }
        entered.await(10, TimeUnit.SECONDS).assert().isTrue()
        assertThrows<SourceLoadRejectedException> { cache["b"] }
        release.countDown()
        finished.await(10, TimeUnit.SECONDS).assert().isTrue()
        holder.join()
        observer.operations().assert().containsExactly(LoadOperation.LOAD to 1)
    }

    @Test
    fun observesLeaseWaitOnlyWhenWaiting() {
        val policy = CachePolicy(
            ttl = Duration.ofMinutes(10),
            ttlJitter = Duration.ZERO,
            lease = LeasePolicy(wait = Duration.ofMillis(20))
        )
        val cache = runtime().cache(policy)
        database["k"] = "v"
        // 其它节点持有 lease：等待超时后直接回源
        memory.store(name).read("cocache:$name:k", LeaseRequest(Token(NodeId.random(), 1), Duration.ofMinutes(1)), null)
        cache["k"].assert().isEqualTo("v")
        observer.operations().assert().containsExactly(LoadOperation.LEASE_WAIT to 1, LoadOperation.LOAD to 1)
        observer.records.first().error.assert().isNull()
        cache.metrics.leaseWaitTimeouts.assert().isEqualTo(1)
    }
}
