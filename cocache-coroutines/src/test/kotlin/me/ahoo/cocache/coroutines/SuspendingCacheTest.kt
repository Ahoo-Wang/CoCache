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

package me.ahoo.cocache.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * 挂起视图特有的语义（RFC 0004 §7）：取消、原始异常、与同步和异步调用合并、单线程 dispatcher 下不死锁、同 key 重入快速失败、
 * loader 的上下文与 dispatcher。
 */
class SuspendingCacheTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
    private val loads = AtomicInteger()

    @AfterEach
    fun destroy() = runtime.close()

    private fun plain(name: String = "plain") = runtime.create(cacheSpec<String, String>(name) { codec = StringCodec })

    @Test
    fun hitsReturnWithoutSuspending() {
        val cache = plain()
        cache.put("k", "v")
        val suspending = cache.suspending()
        suspending.name.assert().isEqualTo("plain")
        suspending.async().assert().isSameAs(cache.async())
        // UNDISPATCHED：L2 命中在第一个挂起点之前返回，协程就地完成
        runBlocking {
            val hit = async(start = CoroutineStart.UNDISPATCHED) { suspending.get("k") }
            hit.isCompleted.assert().isTrue()
            hit.await().assert().isEqualTo("v")
        }
        suspending.toString().assert().contains("SuspendingCache")
        runBlocking { cache.async().suspending().get("k") }.assert().isEqualTo("v")
    }

    @Test
    fun readsAndWritesGoThroughTheAsyncView(): Unit = runBlocking<Unit> {
        val cache = plain().suspending()
        cache.put("a", "1")
        cache.put("b", "2", Duration.ofMinutes(1))
        cache.putAll(mapOf("c" to "3"))
        cache.putAll(mapOf("d" to "4"), Duration.ofMinutes(1))
        cache.getAll(listOf("a", "b", "c", "d", "e")).assert()
            .isEqualTo(mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4"))
        (cache.lookup("a") as Lookup.Present).value.assert().isEqualTo("1")
        cache.invalidate("a")
        cache.invalidateAll(listOf("b", "c"))
        cache.get("a").assert().isNull()
        cache.getAll(listOf("b", "c", "d")).assert().isEqualTo(mapOf("d" to "4"))
    }

    @Test
    fun setCacheView(): Unit = runBlocking<Unit> {
        val set = runtime.create(setCacheSpec<String, String>("tags") { elementCodec = StringCodec })
        val suspending = set.suspending()
        suspending.add("k", listOf("a", "b"))
        suspending.remove("k", listOf("a"))
        suspending.get("k").assert().isEqualTo(setOf("b"))
        suspending.clear("k")
        suspending.get("k").assert().isEqualTo(emptySet<String>())
        suspending.async().assert().isSameAs(set.async())
        set.async().suspending().get("k").assert().isEqualTo(emptySet<String>())
    }

    @Test
    fun cancellingTheCallerDoesNotCancelTheSharedLoad(): Unit = runBlocking<Unit> {
        val cache = plain().suspending()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val loader: suspend (String) -> String? = {
            loads.incrementAndGet()
            started.complete(Unit)
            release.await()
            "v"
        }
        val cancelled = launch(Dispatchers.Default) { cache.get("k", loader) }
        started.await()
        val joined = async(Dispatchers.Default) { cache.get("k", loader) }
        cancelled.cancel()
        cancelled.join()
        cancelled.isCancelled.assert().isTrue()
        release.complete(Unit)
        withTimeout(10_000) { joined.await() }.assert().isEqualTo("v")
        loads.get().assert().isEqualTo(1)
        // 被取消的调用方发起的加载照常写入：之后的读取命中
        cache.get("k").assert().isEqualTo("v")
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun theLoaderExceptionIsThrownUnchanged(): Unit = runBlocking<Unit> {
        val cache = plain().suspending()
        val failure = IllegalStateException("source down")
        runCatching { cache.get("k") { throw failure } }.exceptionOrNull().assert().isSameAs(failure)
        val afterSuspending = IllegalArgumentException("after a suspension")
        runCatching {
            cache.get("j") {
                delay(1)
                throw afterSuspending
            }
        }.exceptionOrNull().assert().isSameAs(afterSuspending)
    }

    @Test
    fun suspendingSyncAndAsyncCallersShareOneLoad() {
        val cache = runtime.create(
            cacheSpec<String, String>("shared") {
                codec = StringCodec
                loader = CacheLoader {
                    loads.incrementAndGet()
                    "v"
                }
            }
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = fork("sync") {
            cache.get("k") { _ ->
                loads.incrementAndGet()
                entered.countDown()
                release.awaitOrFail("release the load")
                "shared"
            }
        }
        entered.awaitOrFail("sync load started")
        val async = cache.async().get("k")
        val suspendingDone = CountDownLatch(1)
        val suspending = fork("suspending") {
            runBlocking { cache.suspending().get("k") }.also { suspendingDone.countDown() }
        }
        awaitCondition("suspending caller waiting") {
            suspending.thread.state == Thread.State.WAITING || suspending.thread.state == Thread.State.TIMED_WAITING
        }
        // 加载完成之前，异步与挂起的调用方都没有返回：它们在等同一次加载
        async.isDone.assert().isFalse()
        suspendingDone.count.assert().isEqualTo(1L)
        loads.get().assert().isEqualTo(1)
        release.countDown()
        first.join().assert().isEqualTo("shared")
        async.get().assert().isEqualTo("shared")
        suspending.join().assert().isEqualTo("shared")
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun nestedLoadsOnASingleThreadDispatcherDoNotDeadlock() {
        val outer = plain("outer").suspending()
        val inner = plain("inner").suspending()
        val executor = Executors.newSingleThreadExecutor { Thread(it, "single").apply { isDaemon = true } }
        try {
            val threads = mutableListOf<String>()
            runBlocking(executor.asCoroutineDispatcher()) {
                withTimeout(10_000) {
                    outer.get("k") { key ->
                        threads.add(Thread.currentThread().name)
                        val nested = inner.get(key) {
                            threads.add(Thread.currentThread().name)
                            yield()
                            "i-$it"
                        }
                        "o-$nested"
                    }
                }
            }.assert().isEqualTo("o-i-k")
            // 调用级 loader 在调用方的 dispatcher 上执行
            threads.assert().containsExactly("single", "single")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun reentryOnTheSameKeyFailsFast(): Unit = runBlocking<Unit> {
        val cache = plain().suspending()
        withTimeout(10_000) {
            runCatching { cache.get("k") { key -> cache.get(key) } }.exceptionOrNull()
                .assert().isInstanceOf(IllegalStateException::class.java)
            // 挂起之后在别的线程上继续，同样快速失败（引擎基于线程的检查覆盖不到这里）
            runCatching {
                cache.get("j") { key ->
                    withContext(Dispatchers.Default) { cache.lookup(key) }
                    "never"
                }
            }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
            runCatching { cache.get("m") { key -> cache.getAll(listOf("other", key))[key] } }.exceptionOrNull()
                .assert().isInstanceOf(IllegalStateException::class.java)
        }
        // 其它 key 与其它缓存不受影响
        val other = plain("other").suspending()
        cache.get("n") { key -> other.get(key) { "o-$it" } + "|" + cache.get("x") { "x" } }.assert().isEqualTo("o-n|x")
    }

    @Test
    fun specLevelLoaderReentryFailsFast(): Unit = runBlocking<Unit> {
        lateinit var self: SuspendingCache<String, String>
        val cache = runtime.create(
            cacheSpec<String, String>("self") {
                codec = StringCodec
                suspendingLoader { key -> self.get(key) }
            }
        )
        self = cache.suspending()
        withTimeout(10_000) {
            runCatching { self.get("k") }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun callLevelLoaderInheritsTheCallerContextWithoutItsJob(): Unit = runBlocking<Unit> {
        val cache = plain().suspending()
        val seen = AtomicReference<String?>()
        val loaderJob = AtomicReference<Job?>()
        val callerJob = coroutineContext[Job]
        withContext(CoroutineName("caller")) {
            cache.get("k") {
                seen.set(coroutineContext[CoroutineName]?.name)
                loaderJob.set(coroutineContext[Job])
                "v"
            }
        }.assert().isEqualTo("v")
        seen.get().assert().isEqualTo("caller")
        // 调用方在 runBlocking 的事件循环上：loader 改在 Dispatchers.Default 上执行
        val thread = AtomicReference<String>()
        cache.get("event-loop") {
            thread.set(Thread.currentThread().name)
            "e"
        }
        thread.get().assert().startsWith("DefaultDispatcher-worker")
        // loader 的 Job 不是调用方 Job 的子 Job
        requireNotNull(loaderJob.get()).assert().isNotSameAs(callerJob)
        callerJob?.children?.toList().orEmpty().assert().doesNotContain(loaderJob.get())
    }

    @Test
    fun specLevelLoaderRunsOnItsContextAndLoadsAllConcurrently(): Unit = runBlocking<Unit> {
        val threads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val cache = runtime.create(
            cacheSpec<String, String>("spec") {
                codec = StringCodec
                suspendingLoader { key ->
                    threads.add(Thread.currentThread().name)
                    loads.incrementAndGet()
                    if (key == "absent") null else "v-$key"
                }
            }
        ).suspending()
        cache.get("k").assert().isEqualTo("v-k")
        // 默认 Dispatchers.Default
        threads.single().assert().startsWith("DefaultDispatcher-worker")
        // 默认的 loadAll 并发调用 load；返回 null 的 key 不在结果中
        cache.getAll(listOf("a", "b", "absent")).assert().isEqualTo(mapOf("a" to "v-a", "b" to "v-b"))
        loads.get().assert().isEqualTo(4)
    }

    @Test
    fun asAsyncCacheLoaderUsesTheGivenContext() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "loader-context").apply { isDaemon = true } }
        try {
            val loader = SuspendingCacheLoader<String, String> { "${Thread.currentThread().name}:$it" }
                .asAsyncCacheLoader(executor.asCoroutineDispatcher())
            loader.loadAsync("k").toCompletableFuture().get().assert().isEqualTo("loader-context:k")
            loader.loadAllAsync(
                listOf("a")
            ).toCompletableFuture().get().assert().isEqualTo(mapOf("a" to "loader-context:a"))
            loader.toString().assert().contains("SuspendingAsyncLoader")
            val defaults = SuspendingCacheLoader<String, String> { Thread.currentThread().name }.asAsyncCacheLoader()
            defaults.loadAsync("k").toCompletableFuture().get().assert().startsWith("DefaultDispatcher-worker")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun loadingKeysDescribeThemselves() {
        LoadingKeys.adding(null, "c", listOf("k")).toString().assert().contains("c")
    }

    /** 把一个 ThreadLocal 带进协程（与 kotlinx-coroutines-slf4j 的 MDCContext 同一机制）。 */
    private class TraceContext(private val value: String) : kotlinx.coroutines.ThreadContextElement<String?> {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<TraceContext> {
            val current = ThreadLocal<String?>()
        }

        override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

        override fun updateThreadContext(context: kotlin.coroutines.CoroutineContext): String? =
            current.get().also { current.set(value) }

        override fun restoreThreadContext(context: kotlin.coroutines.CoroutineContext, oldState: String?) = current.set(
            oldState
        )
    }

    @Test
    fun threadContextElementsReachTheCallLevelLoader(): Unit = runBlocking<Unit> {
        val cache = plain().suspending()
        val seen = AtomicReference<String?>()
        withContext(TraceContext("trace-1")) {
            cache.get("k") {
                withContext(Dispatchers.IO) { /* 换一个线程后仍在 */ }
                seen.set(TraceContext.current.get())
                "v"
            }
        }
        seen.get().assert().isEqualTo("trace-1")
    }

    @Test
    fun aCancellationExceptionFromTheLoaderIsAFailureForEveryCaller(): Unit = runBlocking<Unit> {
        val cache = plain().suspending()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val loader: suspend (String) -> String? = {
            started.complete(Unit)
            release.await()
            // 数据源内部的超时：协程本身仍处于活动状态
            withTimeout(1) { delay(1_000) }
            "never"
        }
        val first = async(Dispatchers.Default) { runCatching { cache.get("k", loader) }.exceptionOrNull() }
        started.await()
        val second = async(Dispatchers.Default) { runCatching { cache.get("k", loader) }.exceptionOrNull() }
        release.complete(Unit)
        listOf(first.await(), second.await()).forEach { error ->
            error.assert().isInstanceOf(SourceLoadCancelledException::class.java)
            requireNotNull(
                error
            ).cause.assert().isInstanceOf(kotlinx.coroutines.TimeoutCancellationException::class.java)
            (error as SourceLoadCancelledException).cacheName.assert().isEqualTo("plain")
        }
        // spec 级 loader 同样转换
        val spec = runtime.create(
            cacheSpec<String, String>("spec-cancel") {
                codec = StringCodec
                suspendingLoader {
                    withTimeout(1) { delay(1_000) }
                    "never"
                }
            }
        ).suspending()
        runCatching { spec.get("k") }.exceptionOrNull().assert().isInstanceOf(SourceLoadCancelledException::class.java)
    }

    @Test
    fun aFailingLoadDoesNotCancelLaterLoadsOfAContextWithAJob(): Unit = runBlocking<Unit> {
        val parent = Job()
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        val cache = runtime.create(
            cacheSpec<String, String>("job") {
                codec = StringCodec
                suspendingLoader(parent + Dispatchers.IO) { key ->
                    check(!failing.getAndSet(false)) { "first load fails" }
                    "v-$key"
                }
            }
        ).suspending()
        runCatching { cache.get("a") }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        // 一次失败不取消父 Job，之后的加载照常
        cache.get("b").assert().isEqualTo("v-b")
        parent.isActive.assert().isTrue()
        // 取消父 Job 会取消之后的加载
        parent.cancel()
        runCatching {
            cache.get(
                "c"
            )
        }.exceptionOrNull().assert().isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    @Test
    fun specLevelLoadsAreBoundedByAsyncTimeout(): Unit = runBlocking<Unit> {
        val stillRunning = CompletableDeferred<Unit>()
        val cache = runtime.create(
            cacheSpec<String, String>("slow") {
                codec = StringCodec
                policy = me.ahoo.cocache.core.policy.CachePolicy(
                    load = me.ahoo.cocache.core.policy.LoadPolicy(asyncTimeout = Duration.ofMillis(200))
                )
                suspendingLoader {
                    delay(500)
                    stillRunning.complete(Unit)
                    "late"
                }
            }
        ).suspending()
        runCatching { cache.get("k") }.exceptionOrNull()
            .assert().isInstanceOf(me.ahoo.cocache.core.SourceLoadTimeoutException::class.java)
        // 超时不取消加载协程（与 AsyncCacheLoader 相同），迟到的结果被丢弃
        withTimeout(10_000) { stillRunning.await() }
    }

    @Test
    fun cancellingTheCallerOfASpecLevelLoadLetsItFill(): Unit = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val cache = runtime.create(
            cacheSpec<String, String>("spec-cancel-caller") {
                codec = StringCodec
                suspendingLoader { key ->
                    loads.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    "v-$key"
                }
            }
        ).suspending()
        val caller = launch(Dispatchers.Default) { cache.get("k") }
        started.await()
        caller.cancel()
        caller.join()
        release.complete(Unit)
        awaitCondition(
            "the abandoned load filled the cache"
        ) { runBlocking { cache.async().lookup("k").get() } is Lookup.Present }
        cache.get("k").assert().isEqualTo("v-k")
        loads.get().assert().isEqualTo(1)
    }

    @Test
    fun reentryIsMatchedByCacheInstanceNotByName(): Unit = runBlocking<Unit> {
        // 另一个运行时上的同名缓存（多节点布局）不是同一个缓存，不误报
        val other = CacheRuntime.builder().backend(InMemoryBackend()).build()
        try {
            val here = plain("same").suspending()
            val there = other.create(cacheSpec<String, String>("same") { codec = StringCodec }).suspending()
            here.get("k") { key -> "here:" + there.get(key) { "there" } }.assert().isEqualTo("here:there")
        } finally {
            other.close()
        }
    }
}
