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

package me.ahoo.cocache.core

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 异步视图相关的运行时、声明与策略（RFC 0004 §3.2、§5.1、§8.3）。
 */
class AsyncRuntimeTest {
    private fun spec(name: String = "user", block: CacheSpec.Builder<String, String>.() -> Unit = {}) =
        cacheSpec<String, String>(name) {
            codec = StringCodec
            block()
        }

    @Test
    fun specAcceptsOneSourceOnly() {
        val asyncLoader = AsyncCacheLoader<String, String> { CompletableFuture.completedFuture(it) }
        val spec = CacheSpec.builder<String, String>("user").codec(StringCodec).asyncLoader(asyncLoader).build()
        spec.asyncLoader.assert().isSameAs(asyncLoader)
        spec.loader.assert().isNull()
        spec.toBuilder().build().asyncLoader.assert().isSameAs(asyncLoader)
        runCatching {
            spec {
                loader = CacheLoader { it }
                this.asyncLoader = asyncLoader
            }
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        LoadPolicy().asyncTimeout.assert().isEqualTo(LoadPolicy.DEFAULT_ASYNC_TIMEOUT)
    }

    @Test
    fun defaultExecutorsAreOwnedAndNamed() {
        val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
        val loadedOn = CompletableFuture<String>()
        val cache = runtime.create(
            spec {
                loader = CacheLoader { key ->
                    loadedOn.complete(Thread.currentThread().name)
                    key
                }
            }
        )
        val completedOn = CompletableFuture<String>()
        cache.async().get("k").whenComplete { _, _ -> completedOn.complete(Thread.currentThread().name) }
            .get(10, TimeUnit.SECONDS).assert().isEqualTo("k")
        // 同步数据源只在阻塞执行器上执行
        loadedOn.join().assert().doesNotStartWith("cocache-async")
        runtime.close()
        // 关闭后自建的执行器停止：异步加载以拒绝失败
        cache.async().get("other").handle { _, e -> e }.get(10, TimeUnit.SECONDS)
            .assert().isInstanceOf(RejectedExecutionException::class.java)
    }

    @Test
    fun providedExecutorsAreUsedAndNotClosed() {
        val async = Executors.newSingleThreadExecutor { Thread(it, "mine-async") }
        val blocking = Executors.newSingleThreadExecutor { Thread(it, "mine-blocking") }
        try {
            val runtime = CacheRuntime.builder().backend(
                InMemoryBackend()
            ).asyncExecutor(async).blockingExecutor(blocking).build()
            val loadedOn = CompletableFuture<String>()
            val cache = runtime.create(
                spec {
                    loader = CacheLoader { key ->
                        loadedOn.complete(Thread.currentThread().name)
                        key
                    }
                }
            )
            cache.async().get("k").get(10, TimeUnit.SECONDS).assert().isEqualTo("k")
            loadedOn.join().assert().isEqualTo("mine-blocking")
            runtime.close()
            async.isShutdown.assert().isFalse()
            blocking.isShutdown.assert().isFalse()
        } finally {
            async.shutdownNow()
            blocking.shutdownNow()
        }
    }

    @Test
    fun platformBlockingExecutorRejectsInsteadOfRunningOnTheCaller() {
        val executor = CacheRuntime.defaultBlockingExecutor { null } as ThreadPoolExecutor
        try {
            executor.maximumPoolSize.assert().isEqualTo(CacheRuntime.MAX_BLOCKING_THREADS)
            executor.rejectedExecutionHandler.assert().isInstanceOf(ThreadPoolExecutor.AbortPolicy::class.java)
            executor.submit<String> {
                Thread.currentThread().name
            }.get(10, TimeUnit.SECONDS).assert().startsWith("cocache-blocking")
        } finally {
            executor.shutdownNow()
        }
        val virtual: ExecutorService = Executors.newSingleThreadExecutor()
        CacheRuntime.defaultBlockingExecutor { virtual }.assert().isSameAs(virtual)
        virtual.shutdownNow()
    }

    @Test
    fun asyncSourceWaitedOnBySyncCallsHonoursTheTimeout() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            val failure = IllegalStateException("source down")
            val cache = runtime.create(
                spec {
                    asyncLoader = AsyncCacheLoader { key ->
                        when (key) {
                            "never", "never-2" -> CompletableFuture()
                            "fail" -> CompletableFuture.failedFuture(failure)
                            else -> CompletableFuture.completedFuture("v-$key")
                        }
                    }
                    policy { load = LoadPolicy(asyncTimeout = Duration.ofMillis(50)) }
                }
            )
            cache["k"].assert().isEqualTo("v-k")
            runCatching { cache["fail"] }.exceptionOrNull().assert().isSameAs(failure)
            runCatching { cache["never"] }.exceptionOrNull().assert().isInstanceOf(
                SourceLoadTimeoutException::class.java
            )
            requireNotNull(runtime.metrics("user")).loadTimeouts.assert().isEqualTo(1)
            Thread.currentThread().interrupt()
            runCatching { cache["never-2"] }.exceptionOrNull().assert().isInstanceOf(InterruptedException::class.java)
            Thread.interrupted().assert().isTrue()
        }
    }
}
