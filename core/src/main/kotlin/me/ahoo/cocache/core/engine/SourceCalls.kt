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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.SourceLoadCancelledException
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.internal.Futures
import me.ahoo.cocache.core.internal.LoadingKeys
import me.ahoo.cocache.core.internal.SingleFlight
import me.ahoo.cocache.core.internal.awaitOriginal
import me.ahoo.cocache.core.internal.awaitUncancellable
import me.ahoo.cocache.core.internal.ensureNotInterrupted
import me.ahoo.cocache.core.internal.joinOriginal
import me.ahoo.cocache.core.internal.runSync
import me.ahoo.cocache.core.internal.runsInline
import me.ahoo.cocache.core.internal.startInPlace
import me.ahoo.cocache.core.policy.LoadPolicy
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.nanoseconds

/**
 * 一个标量引擎的回源调用（RFC 0005 V5），由 [CoherentEngine] 持有：回源并发许可（bulkhead，只计 SingleFlight leader）、耗时统计、
 * 回源观测（[LoadObserver]）、数据源的调用规则（同步变体在调用方线程上调用，异步变体在运行时的执行器上调用阻塞的数据源）、
 * 异步与挂起数据源的超时，以及数据源自身的取消换算为 [SourceLoadCancelledException]。没有失效戳状态：栅栏、lease 与 L2 留在引擎中。
 *
 * @param owner 持有它的引擎：调用级挂起 loader 的重入记录（[LoadingKeys]）按它区分缓存
 * @param flights 引擎的 SingleFlight：异步变体在别的线程上调用数据源时，以它守卫这些 key 的同线程重入
 */
@Suppress("LongParameterList")
internal class SourceCalls<K : Any, V : Any>(
    private val owner: Any,
    private val name: String,
    private val load: LoadPolicy,
    private val metrics: CacheMetrics,
    private val loadObserver: LoadObserver,
    private val async: AsyncContext,
    private val flights: SingleFlight<String, *>,
) {
    /**
     * 回源并发许可（bulkhead）：同步与异步变体共用一个按到达顺序交接的信号量，只计 SingleFlight leader。
     */
    private val permits: Semaphore? = load.maxConcurrent?.let { Semaphore(it) }
    private val loadTimeout: Duration = load.timeout
    private val scope = async.scope

    /**
     * 协程核心的单 key 回源（同步与异步共用）：许可、耗时统计与观测（[guardedLoad]）包住数据源调用（[invoke]）。
     *
     * 调用级的挂起 loader 另加重入记录（[LoadingKeys]，K7），在许可与观测之外装上（#876）：`withContext` 改变上下文时重新装上
     * 上下文中的全部 `ThreadContextElement`，在观测器设置了它的线程局部状态（Micrometer 的当前观测）之后再装，会把它盖回调用方的状态。
     */
    suspend fun loadValue(key: K, storageKey: String, source: CoreSource<K, V>?): V? {
        val sourceLoader = source ?: return null
        if (sourceLoader.source is AsyncSource.Suspending) {
            val loading = LoadingKeys.adding(currentCoroutineContext()[LoadingKeys], owner, listOf(key))
            return withContext(loading) { guardedLoadValue(key, storageKey, sourceLoader) }
        }
        return guardedLoadValue(key, storageKey, sourceLoader)
    }

    private suspend fun guardedLoadValue(key: K, storageKey: String, source: CoreSource<K, V>): V? =
        guardedLoad(source.operation, 1) {
            invoke(source.source, listOf(storageKey), { it.loadAsync(key) }, { it.load(key) }) {
                loadSuspending(it, key)
            }
        }

    /**
     * 调用级的挂起 loader（挂起视图的 `get(key) { … }`，RFC 0005 §4.4）：直接调用、不切换调度器，在这次加载的协程中执行，
     * 元素取自调用方（K6）。调用期间有重入记录（[LoadingKeys]，K7，由 [loadValue] 装上）：loader 中经挂起视图读取本缓存的
     * 同一个 key 立即失败，而不是等待自己的加载。loader 经 `runBlocking` 或 future 读取同一个 key 时检测不到，以 `load.timeout` 为界。
     * 超过 [loadTimeout] 时取消 loader（协作式），以 [SourceLoadTimeoutException] 失败。
     */
    private suspend fun loadSuspending(loader: suspend (K) -> V?, key: K): V? {
        val loaded = withTimeoutOrNull(loadTimeout.toNanos().nanoseconds) {
            Result.success(loader(key))
        }
        if (loaded == null) {
            metrics.loadTimeout()
            throw SourceLoadTimeoutException(name, loadTimeout)
        }
        return loaded.getOrThrow()
    }

    /**
     * 调用数据源，调用期间本线程对这些 key 的重入快速失败：
     * - 同步数据源：同步变体在调用方线程上调用（不变量 6，loader 拿得到调用方线程的 ThreadLocal）；异步变体只在阻塞执行器上执行。
     *   不加超时（阻塞的 loader 无法被打断）。
     * - 异步数据源：在当前线程上调用 `loadAsync`，等待其结果的副本并加 [loadTimeout]（`orTimeout` 会修改 future 本身，
     *   不能作用于数据源的 future）。超时以 [SourceLoadTimeoutException] 失败，数据源的 future 不被取消、迟到的结果被丢弃。
     *   引擎的超时以私有哨兵值完成（JDK 的延迟线程触发），与数据源自己抛出的 `TimeoutException` 区分开（A8）。
     *
     * 同步变体的重入由 SingleFlight 的拥有线程守卫；异步变体在别的线程上继续，另以 [SingleFlight.loading] 守卫这些 key。
     *
     * 数据源自己以取消结束（抛出或以 `CancellationException` 完成）时转为 [SourceLoadCancelledException]（[sourceCancellation]）。
     */
    private suspend fun <R> invoke(
        source: AsyncSource<K, V>,
        storageKeys: Collection<String>,
        native: (AsyncCacheLoader<K, V>) -> CompletionStage<out R>,
        blocking: (CacheLoader<K, V>) -> R,
        suspending: suspend (suspend (K) -> V?) -> R
    ): R = try {
        invokeSource(source, storageKeys, native, blocking, suspending)
    } catch (e: CancellationException) {
        throw sourceCancellation(e)
    }

    /**
     * 数据源自己的取消（其中的 `withTimeout`、被 HTTP 客户端取消的 future）是普通的回源失败（RFC 0005 第三轮 K5，同 RFC 0004）：
     * 当前协程仍处于活动状态时转为 [SourceLoadCancelledException]，与其它失败一样交给合并到这次加载的全部调用方。
     * 原样传出的话，引擎各处会把它当作本协程被取消，跟随者会各自回源，挂起的调用方会把它当作自己被取消。
     * 当前协程已被取消（运行时关闭、执行器拒绝派发、同步变体的线程被中断）时，它就是这次取消，原样传出。
     */
    private suspend fun sourceCancellation(error: CancellationException): Throwable =
        if (currentCoroutineContext().isActive) SourceLoadCancelledException(name, error) else error

    private suspend fun <R> invokeSource(
        source: AsyncSource<K, V>,
        storageKeys: Collection<String>,
        native: (AsyncCacheLoader<K, V>) -> CompletionStage<out R>,
        blocking: (CacheLoader<K, V>) -> R,
        suspending: suspend (suspend (K) -> V?) -> R
    ): R {
        val inline = runsInline()
        return when (source) {
            is AsyncSource.Suspending -> suspending(source.loader)

            is AsyncSource.Blocking -> if (inline) {
                blocking(source.loader)
            } else {
                CompletableFuture.supplyAsync(
                    { flights.loading(storageKeys) { blocking(source.loader) } },
                    async.executor
                )
                    .awaitOriginal()
            }

            is AsyncSource.Native -> {
                // 同步变体在调用方线程上调用 loadAsync：它同步抛出的异常与阻塞的 loader 抛出的一样，原样传出（其中的中断即调用方线程被中断）
                val started = if (inline) {
                    native(source.loader).toCompletableFuture()
                } else {
                    Futures.attempt { flights.loading(storageKeys) { native(source.loader).toCompletableFuture() } }
                }
                val value = try {
                    Futures.relay<Any?>(started)
                        .completeOnTimeout(TIMED_OUT, loadTimeout.toNanos(), TimeUnit.NANOSECONDS)
                        .awaitOriginal()
                } catch (e: InterruptedException) {
                    // 数据源返回的 future 以中断失败：数据源在它自己的线程上被中断，调用方线程没有（#835），照常交给跟随者
                    throw SingleFlight.sourceFailure(e)
                }
                if (value === TIMED_OUT) {
                    metrics.loadTimeout()
                    throw SourceLoadTimeoutException(name, loadTimeout)
                }
                uncheckedCast(value)
            }
        }
    }

    /**
     * 回源并发上限（只计 leader）、耗时统计与回源观测，只包住回源调用本身。许可在回源结束时恰好归还一次（含失败、取消）。
     */
    private suspend fun <R> guardedLoad(operation: LoadOperation, keyCount: Int, block: suspend () -> R): R {
        val semaphore = permits
        semaphore?.let { acquirePermit(it) }
        try {
            val start = System.nanoTime()
            var failed = true
            try {
                return observedLoad(operation, keyCount, block).also { failed = false }
            } finally {
                metrics.load(System.nanoTime() - start, failed)
            }
        } finally {
            semaphore?.release()
        }
    }

    /**
     * 在 `acquireTimeout` 内取得回源许可，否则以 [SourceLoadRejectedException] 拒绝（计入 `loadRejected`）。
     * 等待挂起协程：同步变体停在调用方线程上（`runBlocking` 的事件循环），异步变体不占线程；同步与异步的等待者按到达顺序交接。
     * 等待被取消（运行时关闭、线程中断）时不取得许可，也不泄漏许可。
     */
    private suspend fun acquirePermit(semaphore: Semaphore) {
        if (semaphore.tryAcquire()) {
            return
        }
        val acquired = withTimeoutOrNull(load.acquireTimeout.toMillis()) {
            semaphore.acquire()
            true
        }
        if (acquired == null) {
            metrics.loadRejected()
            throw SourceLoadRejectedException(name, load)
        }
    }

    /**
     * 协程核心的回源观测：默认的 [LoadObserver.NOOP] 直接执行。观测器只有同步的 [LoadObserver.observe]（RFC 0005 §5）：
     * - 同步变体在调用方线程上调用它。调用方线程已被中断时不再调用观测器与 block 中的数据源（[ensureNotInterrupted]，RFC 0005 §4.3）：
     *   观测在嵌套的 `runSync` 中执行，外层协程的取消不会传到那里。
     * - 异步与挂起变体在专用的虚拟线程上调用它（[OBSERVATION]，每次观测一个，从不占用调用方线程），这个线程在观测期间等待 block 结束。
     *   不用运行时的执行器：等待的线程占住执行器，而 block 的续接要经同一个执行器派发，有界的执行器会因此死锁。
     *   观测器在调用方的上下文元素（`ThreadContextElement`：MDC、追踪上下文）之下调用，挂起调用方的观测因此接在它自己的观测之下。
     *   block 在运行时作用域中、从这个线程上开始执行，开始时不再重新装上调用方的元素（[startInPlace]，#876）：第一次挂起之前的部分
     *   （如调用数据源）看得到观测器在调用方的元素之上设置的线程局部状态（Micrometer 的当前观测是新建的子观测，不被调用方传播的
     *   父观测盖回去），也看得到调用方的其它元素；之后的恢复照常装上调用方的元素。运行时关闭时随作用域取消。
     *   这个线程带有作用域的标记（`AsyncContext.inScope`）：观测器在其中关闭运行时不等待自己的观测返回（#892）。
     */
    suspend fun <R> observedLoad(operation: LoadOperation, keyCount: Int, block: suspend () -> R): R {
        ensureNotInterrupted()
        val observer = loadObserver
        if (observer === LoadObserver.NOOP) {
            return block()
        }
        if (runsInline()) {
            return observer.observe(name, operation, keyCount) { runSync { block() } }
        }
        val caller = currentCoroutineContext().minusKey(Job)
        // 等待 lease 的重读可能已为本调用取得令牌，它的结果不能丢（否则令牌要到 `lease.ttl` 才回收）：
        // - 不可取消地等待。block 在运行时作用域中执行，运行时关闭时随作用域取消，所以这里的等待有界；
        // - 结果经 produced 交回：外层协程被取消时，即使 block 已经返回，它也以取消结束、丢掉返回值。
        //   交回之后，调用方在之后的挂起点被取消，持有令牌的步骤据此释放它
        val produced = Produced<R>()
        return try {
            CompletableFuture.supplyAsync(
                {
                    // 外层协程不挂起，只为在调用观测器期间装上调用方的 ThreadContextElement；block 是它的子 Job。
                    // 这个线程在观测期间执行作用域中的协程：观测器在其中关闭运行时不等待自己（#892）
                    async.inScope {
                        scope.future(caller, CoroutineStart.UNDISPATCHED) {
                            val outer = this.coroutineContext
                            observer.observe(name, operation, keyCount) {
                                startInPlace(outer) { block().also { produced.value = it } }.joinOriginal()
                            }
                        }.joinOriginal()
                    }
                },
                OBSERVATION
            ).awaitUncancellable()
        } catch (e: CancellationException) {
            if (produced.isSet) produced.get() else throw e
        }
    }

    /**
     * [observedLoad] 中 block 的结果（可能为 `null`）：外层协程以取消结束时据此取回。
     */
    private class Produced<R> {
        @Volatile
        var value: Any? = UNSET

        val isSet: Boolean
            get() = value !== UNSET

        fun get(): R = uncheckedCast(value)

        private companion object {
            val UNSET = Any()
        }
    }

    /**
     * 协程核心的批量回源：与 [loadValue] 相同的许可、统计、观测与调用规则，一次 `loadAll`。
     */
    suspend fun loadAllValues(
        keys: List<K>,
        storageKeys: Collection<String>,
        source: CoreSource<K, V>
    ): Map<K, V> =
        guardedLoad(LoadOperation.LOAD_ALL, keys.size) {
            invoke(source.source, storageKeys, { it.loadAllAsync(keys) }, { it.loadAll(keys) }) {
                // 调用级 loader 只加载一个 key，批量回源只用声明的数据源
                error("Cache[$name] - a call-level suspending loader loads one key.")
            }
        }

    private companion object {
        /**
         * 异步数据源超时的哨兵值（不可能是数据源的结果）。
         */
        val TIMED_OUT = Any()

        /**
         * 异步与挂起变体调用回源观测的线程（[observedLoad]）：每次观测一个虚拟线程，`cocache-observe-N`。
         */
        val OBSERVATION: Executor = Thread.ofVirtual().name(OBSERVATION_THREAD_PREFIX, 0).factory().let { threads ->
            Executor { task -> threads.newThread(task).start() }
        }

        @Suppress("UNCHECKED_CAST")
        fun <R> uncheckedCast(value: Any?): R = value as R
    }
}

/**
 * 异步与挂起变体中回源观测线程的名字前缀（[SourceCalls.observedLoad]）。
 */
internal const val OBSERVATION_THREAD_PREFIX = "cocache-observe-"

/**
 * 数据源（RFC 0004 §3.2）：同步数据源或异步数据源。
 */
internal sealed interface AsyncSource<K : Any, V : Any> {
    class Native<K : Any, V : Any>(val loader: AsyncCacheLoader<K, V>) : AsyncSource<K, V>

    class Blocking<K : Any, V : Any>(val loader: CacheLoader<K, V>) : AsyncSource<K, V>

    /**
     * 调用级的挂起 loader（挂起视图的 `get(key) { … }`）：在这次加载的协程中直接调用（[SourceCalls.loadSuspending]）。
     */
    class Suspending<K : Any, V : Any>(val loader: suspend (K) -> V?) : AsyncSource<K, V>
}

/**
 * 协程核心的数据源（RFC 0005 V5）：声明的或调用级的同步或异步数据源；[operation] 供回源观测区分未命中加载与刷新。
 */
internal class CoreSource<K : Any, V : Any>(val source: AsyncSource<K, V>, val operation: LoadOperation)
