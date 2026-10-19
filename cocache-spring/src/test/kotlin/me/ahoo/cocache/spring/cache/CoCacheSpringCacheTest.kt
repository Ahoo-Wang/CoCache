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
}
