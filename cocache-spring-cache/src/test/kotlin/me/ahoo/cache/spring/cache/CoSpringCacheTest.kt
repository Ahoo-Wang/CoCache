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

package me.ahoo.cache.spring.cache

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.CacheFactory
import me.ahoo.cache.annotation.joinCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinKeyExtractor
import me.ahoo.cache.api.join.JoinValue
import me.ahoo.cache.join.JoinKeyExtractorFactory
import me.ahoo.cache.join.SimpleJoinCache
import me.ahoo.cache.join.proxy.DefaultJoinCacheProxyFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.cache.Cache as SpringCache

class CoSpringCacheTest {
    private val delegate = inMemoryCache<Any, Any?>()
    private val coSpringCache = CoSpringCache("test", delegate)

    @Test
    fun nameAndNativeCache() {
        coSpringCache.name.assert().isEqualTo("test")
        coSpringCache.nativeCache.assert().isSameAs(delegate)
    }

    @Test
    fun getAbsent() {
        coSpringCache.get("absent").assert().isNull()
        coSpringCache.get("absent", String::class.java).assert().isNull()
    }

    @Test
    fun putAndGet() {
        coSpringCache.put("key", "value")
        coSpringCache.get("key")?.get().assert().isEqualTo("value")
        coSpringCache.get("key", String::class.java).assert().isEqualTo("value")
        coSpringCache.get<Any>("key", null).assert().isEqualTo("value")
    }

    @Test
    fun absentDelegateEntryIsMiss() {
        val empty = mockk<Cache<Any, Any?>> {
            every { getCache("key") } returns null
        }
        CoSpringCache("empty", empty).get("key").assert().isNull()
    }

    @Test
    fun expiredEntryIsMiss() {
        val expired = mockk<Cache<Any, Any?>> {
            every { getCache("key") } returns CacheValue.of("value", TtlAt.at(-5))
        }
        CoSpringCache("expired", expired).get("key").assert().isNull()
    }

    @Test
    fun getWithLoaderReturnsCachedValueWithoutLoading() {
        coSpringCache.put("key", "cached")
        coSpringCache.get("key") { "loaded" }.assert().isEqualTo("cached")
    }

    @Test
    fun clearBareSimpleJoinCacheClearsBothClientSides() {
        val firstCache = inMemoryCache<String, String>()
        val joinCache = inMemoryCache<String, String>()
        val joinDelegate = SimpleJoinCache(firstCache, joinCache) { it }
        joinDelegate["k"] = JoinValue("first", "first", "second")

        CoSpringCache("join", joinDelegate.asAnyCache()).clear()

        firstCache.configuration.clientSideCache.size.assert().isZero()
        joinCache.configuration.clientSideCache.size.assert().isZero()
    }

    @Test
    fun getWithTypeMismatchThrows() {
        coSpringCache.put("key", "value")
        assertThrows<IllegalStateException> {
            coSpringCache.get("key", Int::class.javaObjectType)
        }.message.assert().contains("java.lang.Integer")
    }

    @Test
    fun getWithoutTypeReturnsValue() {
        coSpringCache.put("key", "value")
        coSpringCache.get("key", null as Class<Any>?).assert().isEqualTo("value")
    }

    @Test
    fun getWithLoaderUsesValueCachedBeforeTheLoadStarted() {
        // 首次检查未命中后、进入合并加载前，其他线程已写入：加载内的二次检查直接复用，不调用 loader
        val reads = AtomicInteger()
        val racingDelegate = object : Cache<Any, Any?> by delegate {
            override fun getCache(key: Any): CacheValue<Any?>? {
                if (reads.incrementAndGet() == 1) {
                    delegate[key] = "written-concurrently"
                    return null
                }
                return delegate.getCache(key)
            }
        }
        val cache = CoSpringCache("racing", racingDelegate)
        val loader = Callable<String> { error("loader must not run") }
        cache.get("key", loader).assert().isEqualTo("written-concurrently")
    }

    @Test
    fun clearIgnoresCachesWithoutClientSide() {
        val plain = object : Cache<Any, Any?> by delegate {}
        delegate["key"] = "value"
        CoSpringCache("plain", plain).clear()
        delegate.configuration.clientSideCache.size.assert().isEqualTo(1L)
    }

    @Test
    fun missingIsMiss() {
        delegate.setCache("missing", CacheValue.missing())
        coSpringCache.get("missing").assert().isNull()
        coSpringCache.put("putNull", null)
        coSpringCache.get("putNull").assert().isNull()
    }

    @Test
    fun getWithLoader() {
        coSpringCache.get("key") { "loaded" }.assert().isEqualTo("loaded")
        coSpringCache.get("key")?.get().assert().isEqualTo("loaded")
    }

    @Test
    fun getWithLoaderWrapsException() {
        assertThrows<SpringCache.ValueRetrievalException> {
            coSpringCache.get("key") { throw IllegalStateException("boom") }
        }
    }

    @Test
    fun getWithLoaderLoadsOnceUnderConcurrency() {
        val loads = AtomicInteger()
        val release = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<Any?>()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                executor.submit {
                    results.add(
                        coSpringCache.get("sync") {
                            loads.incrementAndGet()
                            release.await(5, TimeUnit.SECONDS)
                            "loaded"
                        }
                    )
                }
            }
            while (loads.get() == 0) {
                Thread.onSpinWait()
            }
            release.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        loads.get().assert().isOne()
        results.all { it == "loaded" }.assert().isTrue()
    }

    @Test
    fun evict() {
        coSpringCache.put("key", "value")
        coSpringCache.evict("key")
        coSpringCache.get("key").assert().isNull()
    }

    @Test
    fun clearCoherentCacheClearsClientSide() {
        coSpringCache.put("key", "value")
        coSpringCache.clear()
        delegate.configuration.clientSideCache.size.assert().isZero()
    }

    interface TestJoinCache : JoinCache<String, String, String, String>

    @Test
    fun clearJoinCacheProxyClearsBothClientSides() {
        val firstCache = inMemoryCache<String, String>()
        val joinCache = inMemoryCache<String, String>()
        val cacheFactory = mockk<CacheFactory> {
            every { getCache<Cache<String, String>>("First") } returns firstCache
            every { getCache<Cache<String, String>>("Join") } returns joinCache
        }
        val metadata = joinCacheMetadata<TestJoinCache>().copy(firstCacheName = "First", joinCacheName = "Join")
        val joinKeyExtractorFactory = mockk<JoinKeyExtractorFactory> {
            every { create<String, String>(metadata) } returns JoinKeyExtractor { it }
        }
        val proxy = DefaultJoinCacheProxyFactory(cacheFactory, joinKeyExtractorFactory).create<TestJoinCache>(metadata)
        proxy["k"] = JoinValue("first", "first", "second")

        CoSpringCache("join", proxy.asAnyCache()).clear()

        firstCache.configuration.clientSideCache.size.assert().isZero()
        joinCache.configuration.clientSideCache.size.assert().isZero()
    }

    @Test
    fun retrieveRunsOnAsyncExecutor() {
        coSpringCache.put("key", "value")
        val callerThread = Thread.currentThread()
        var lookupThread: Thread? = null
        val cache = CoSpringCache("async", delegate) { command ->
            Thread {
                lookupThread = Thread.currentThread()
                command.run()
            }.start()
        }
        val valueWrapper = cache.retrieve("key")!!.get(5, TimeUnit.SECONDS) as SpringCache.ValueWrapper
        valueWrapper.get().assert().isEqualTo("value")
        (lookupThread !== callerThread).assert().isTrue()
    }

    @Test
    fun retrieveAbsentCompletesWithNull() {
        coSpringCache.retrieve("absent")!!.get().assert().isNull()
    }

    @Test
    fun retrieveWithLoader() {
        coSpringCache.retrieve("key") { CompletableFuture.completedFuture("loaded") }.get().assert().isEqualTo("loaded")
        coSpringCache.get("key")?.get().assert().isEqualTo("loaded")
    }

    @Test
    fun retrieveWithLoaderKeepsCachedValue() {
        coSpringCache.put("key", "cached")
        val loads = AtomicInteger()
        coSpringCache.retrieve("key") {
            loads.incrementAndGet()
            CompletableFuture.completedFuture("loaded")
        }.get().assert().isEqualTo("cached")
        loads.get().assert().isZero()
    }

    @Test
    fun retrieveWithLoaderFailure() {
        val actual = coSpringCache.retrieve<String>("key") { throw IllegalStateException("boom") }
        assertThrows<ExecutionException> { actual.get() }.cause.assert().isInstanceOf(IllegalStateException::class.java)
    }
}
