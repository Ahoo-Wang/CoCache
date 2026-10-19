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

package me.ahoo.cocache.spring.cache

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.cache.Cache as SpringCache

class CoCacheSpringCacheTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
    private val database = mutableMapOf("k" to "v")

    init {
        runtime.create(
            cacheSpec<String, String>("user") {
                codec = StringCodec
                loader = CacheLoader { database[it] }
            }
        )
    }

    private val manager = CoCacheSpringCacheManager(runtime)

    @Suppress("UNCHECKED_CAST")
    private val cache: SpringCache = requireNotNull(manager.getCache("user"))

    @AfterEach
    fun destroy() = runtime.close()

    @Test
    fun managerExposesRuntimeCaches() {
        manager.cacheNames.assert().containsExactly("user")
        manager.getCache("user").assert().isSameAs(cache)
        manager.getCache("absent").assert().isNull()
        cache.name.assert().isEqualTo("user")
        cache.nativeCache.assert().isSameAs(runtime.cache("user"))
    }

    @Test
    fun getTreatsNegativeEntriesAsMiss() {
        cache.get("k")?.get().assert().isEqualTo("v")
        cache.get("absent").assert().isNull()
        cache.get("k", String::class.java).assert().isEqualTo("v")
        cache.get("absent", String::class.java).assert().isNull()
        cache.get("k", null as Class<String>?).assert().isEqualTo("v")
        runCatching {
            cache.get("k", Integer::class.java)
        }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun putAndEvict() {
        cache.put("absent", "x")
        cache.get("absent")?.get().assert().isEqualTo("x")
        cache.put("absent", null)
        database.remove("absent")
        cache.get("absent").assert().isNull()
        cache.evict("k")
        database["k"] = "v2"
        cache.get("k")?.get().assert().isEqualTo("v2")
        cache.clear()
        runtime.admin("user")?.localSize.assert().isEqualTo(0)
    }

    @Test
    fun valueLoaderIsCoalescedAndWritten() {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = fork {
            cache.get("loaded") {
                calls.incrementAndGet()
                started.countDown()
                release.awaitOrFail()
                "L"
            }
        }
        started.awaitOrFail()
        val second = fork {
            cache.get("loaded") {
                calls.incrementAndGet()
                "other"
            }
        }
        awaitCondition { second.thread.state == Thread.State.WAITING }
        release.countDown()
        first.join().assert().isEqualTo("L")
        second.join().assert().isEqualTo("L")
        calls.get().assert().isEqualTo(1)
        cache.get("loaded") { "unused" }.assert().isEqualTo("L")
    }

    /**
     * `sync = true` 的加载受 lease 与失效戳保护：加载期间发生的失效不会被这次加载的旧结果覆盖。
     */
    @Test
    fun invalidationDuringValueLoaderIsNotOverwritten() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loading = fork {
            cache.get("stale") {
                started.countDown()
                release.awaitOrFail()
                "old"
            }
        }
        started.awaitOrFail()
        cache.evict("stale")
        release.countDown()
        loading.join().assert().isEqualTo("old")
        cache.get("stale") { "new" }.assert().isEqualTo("new")
    }

    @Test
    fun valueLoaderFailureIsWrappedAndShared() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = fork {
            cache.get<String>("failing") {
                started.countDown()
                release.awaitOrFail()
                throw IllegalStateException("boom")
            }
        }
        started.awaitOrFail()
        val second = fork { cache.get<String>("failing") { "other" } }
        awaitCondition { second.thread.state == Thread.State.WAITING }
        release.countDown()
        first.joinFailure().assert().isInstanceOf(SpringCache.ValueRetrievalException::class.java)
        second.joinFailure().assert().isInstanceOf(SpringCache.ValueRetrievalException::class.java)
    }

    @Test
    fun retrieveRunsAsync() {
        (cache.retrieve("k")?.get() as SpringCache.ValueWrapper).get().assert().isEqualTo("v")
        cache.retrieve("k") { CompletableFuture.completedFuture("unused") }.get().assert().isEqualTo("v")
        cache.retrieve("async") { CompletableFuture.completedFuture("A") }.get().assert().isEqualTo("A")
        cache.get("async")?.get().assert().isEqualTo("A")
        val failure = IllegalStateException("supplier failed")
        runCatching {
            cache.retrieve<String>("broken") { throw failure }.join()
        }.exceptionOrNull()?.cause.assert().isSameAs(failure)
    }

    @Test
    fun retrieveWithLoaderIsSingleFlightAndLeaseProtected() {
        val loads = java.util.concurrent.atomic.AtomicInteger()
        val gate = CompletableFuture<String>()
        val loader = java.util.function.Supplier {
            loads.incrementAndGet()
            gate
        }
        val first = cache.retrieve("shared", loader)
        awaitCondition { loads.get() == 1 }
        val second = cache.retrieve("shared", loader)
        gate.complete("S")
        first.get().assert().isEqualTo("S")
        second.get().assert().isEqualTo("S")
        // 并发的两次异步回源合并为一次，结果经引擎写入（不是读不到就 put）
        loads.get().assert().isEqualTo(1)
        cache.get("shared")?.get().assert().isEqualTo("S")

        val failure = IllegalStateException("async source failed")
        runCatching {
            cache.retrieve<String>("failing") { CompletableFuture.failedFuture(failure) }.join()
        }.exceptionOrNull()?.cause.assert().isSameAs(failure)
    }

    @Test
    fun retrieveUsesTheAsyncView() {
        // 命中：已完成的 future（不切换线程）
        cache.get("k")?.get().assert().isEqualTo("v")
        requireNotNull(cache.retrieve("k")).isDone.assert().isTrue()
        // 负缓存视为未命中：以 null 完成
        cache.retrieve("absent")?.get().assert().isNull()
        // valueLoader 的原始异常完成 future（不包装为 CompletionException）
        val failure = IllegalStateException("supplier failed")
        cache.retrieve<String>("thrown") { throw failure }.handle { _, e -> e }.join().assert().isSameAs(failure)
        cache.retrieve<String>("failed") { CompletableFuture.failedFuture(failure) }.handle { _, e -> e }.join()
            .assert().isSameAs(failure)
    }

    @Test
    fun retrieveKeepsTheOriginalLookupFailure() {
        val failure = IllegalStateException("lookup failed")
        val async = io.mockk.mockk<me.ahoo.cocache.api.AsyncCache<Any, Any>> {
            io.mockk.every { lookup(any()) } returns CompletableFuture.failedFuture(failure)
        }
        val native = io.mockk.mockk<me.ahoo.cocache.api.Cache<Any, Any>> {
            io.mockk.every { name } returns "mocked"
            io.mockk.every { async() } returns async
        }
        val spring = CoCacheSpringCache(native, null, java.util.concurrent.Executor { it.run() })
        requireNotNull(spring.retrieve("k")).handle { _, e -> e }.join().assert().isSameAs(failure)
        // 包装过的失败也解包
        io.mockk.every {
            async.lookup(any())
        } returns CompletableFuture.failedFuture(java.util.concurrent.CompletionException(failure))
        requireNotNull(spring.retrieve("k")).handle { _, e -> e }.join().assert().isSameAs(failure)
    }

    @Test
    fun retrieveRunsTheMethodBodyOnTheExecutor() {
        val tasks = AtomicInteger()
        val executor = java.util.concurrent.Executor {
            tasks.incrementAndGet()
            it.run()
        }

        @Suppress("UNCHECKED_CAST")
        val direct = CoCacheSpringCache(
            runtime.cache("user") as me.ahoo.cocache.api.Cache<Any, Any>,
            runtime.admin("user"),
            executor
        )
        (direct.retrieve("k")?.get() as SpringCache.ValueWrapper).get().assert().isEqualTo("v")
        tasks.get().assert().isEqualTo(0)
        direct.retrieve("viaExecutor") { CompletableFuture.completedFuture("E") }.get().assert().isEqualTo("E")
        tasks.get().assert().isEqualTo(1)
        // 方法体的失败（含包装过的）以原始异常完成
        val failure = IllegalStateException("body failed")
        direct.retrieve<String>(
            "wrapped"
        ) { CompletableFuture.failedFuture(java.util.concurrent.CompletionException(failure)) }
            .handle { _, e -> e }.join().assert().isSameAs(failure)
        // 执行器拒绝：以 RejectedExecutionException 失败
        val rejecting = CoCacheSpringCache(
            @Suppress("UNCHECKED_CAST") (runtime.cache("user") as me.ahoo.cocache.api.Cache<Any, Any>),
            runtime.admin("user"),
            java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException("full") }
        )
        rejecting.retrieve<String>("rejected") { CompletableFuture.completedFuture("never") }
            .handle { _, e -> e }.join().assert().isInstanceOf(
                java.util.concurrent.RejectedExecutionException::class.java
            )
    }

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedManagerConstructorIgnoresTheExecutor() {
        val tasks = AtomicInteger()
        val executor = java.util.concurrent.Executor {
            tasks.incrementAndGet()
            it.run()
        }
        val legacy = requireNotNull(CoCacheSpringCacheManager(runtime, executor).getCache("user"))
        (legacy.retrieve("k")?.get() as SpringCache.ValueWrapper).get().assert().isEqualTo("v")
        legacy.retrieve("legacy") { CompletableFuture.completedFuture("L") }.get().assert().isEqualTo("L")
        tasks.get().assert().isEqualTo(0)
    }
}
