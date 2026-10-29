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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.clock.TickingClock
import me.ahoo.cocache.core.engine.AbstractEngine
import me.ahoo.cocache.core.engine.AsyncContext
import me.ahoo.cocache.core.engine.CoherentEngine
import me.ahoo.cocache.core.engine.EngineContext
import me.ahoo.cocache.core.engine.InvalidationRetrier
import me.ahoo.cocache.core.engine.SetEngine
import me.ahoo.cocache.core.engine.VersionedCache
import me.ahoo.cocache.core.engine.VersionedFormat
import me.ahoo.cocache.core.engine.versionedEngineSpec
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 缓存运行时（每个进程一个）：持有节点身份、时钟、后端与执行器，并按声明创建缓存。
 *
 * 关闭时按创建的逆序关闭所有缓存与订阅，并关闭运行时自己创建的时钟与执行器；后端由调用方管理。
 */
class CacheRuntime private constructor(builder: Builder) : AutoCloseable {
    val nodeId: NodeId = builder.nodeId ?: NodeId.random()
    val clock: CacheClock
    val backend: CoherenceBackend = requireNotNull(builder.backend) { "backend is required." }

    private val owned = ArrayList<AutoCloseable>()
    private val context: EngineContext

    /**
     * 阻塞执行器（RFC 0004 §5.1）：[Builder.blockingExecutor] 给出的，或运行时自建的那个。
     * 适配层可以把可能阻塞的用户代码（如 Spring `retrieve` 的方法体）派发到这里，不占用异步执行器。随运行时关闭（自建时）。
     */
    val blockingExecutor: Executor
        get() = context.async.blocking
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val names = ConcurrentHashMap<String, Registration>()
    private val namespaces = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)

    /**
     * @param cache 用户拿到的缓存：引擎本身，或带版本的缓存包在引擎外的 [VersionedCache]
     */
    private class Registration(
        val engine: AbstractEngine<*, *>,
        val store: RemoteStore,
        val cache: Any,
        var subscription: Subscription? = null
    )

    init {
        clock = builder.clock ?: TickingClock().also { owned.add(it) }
        val refreshExecutor = builder.refreshExecutor ?: defaultRefreshExecutor().also {
            owned.add(AutoCloseable { it.shutdownNow() })
        }
        val scheduler = defaultScheduler().also { owned.add(AutoCloseable { it.shutdownNow() }) }
        val retrier = InvalidationRetrier(scheduler, builder.retryPolicy)
        // 先于调度器关闭：未执行的重试计入丢弃并告警
        owned.add(AutoCloseable { retrier.close() })
        context = EngineContext(
            nodeId,
            clock,
            backend,
            refreshExecutor,
            retrier,
            builder.loadObserver,
            asyncContext(builder),
            builder.recordLocalHits
        )
    }

    /**
     * 异步路径的执行资源（RFC 0004 §5.1）。运行时自建的执行器与定时器随运行时关闭；调用方给出的执行器不关闭。
     * 关闭顺序（[owned] 逆序）：先让未触发的定时等待失败，再停定时器，最后关闭自建的执行器。
     */
    private fun asyncContext(builder: Builder): AsyncContext {
        val executor = builder.asyncExecutor ?: defaultAsyncExecutor().also { owned.add(AutoCloseable { it.shutdown() }) }
        val blocking = builder.blockingExecutor ?: defaultBlockingExecutor().also { owned.add(AutoCloseable { it.shutdown() }) }
        val timer = defaultTimer().also { owned.add(AutoCloseable { it.shutdownNow() }) }
        return AsyncContext(executor, blocking, timer).also { owned.add(it) }
    }

    /**
     * 按声明创建缓存。同一运行时内缓存名与命名空间都不得重复。
     *
     * 带版本的缓存（[CacheSpec.versioned]）要求后端支持版本（[CoherenceBackend.versionedWrites]），否则抛出
     * [UnsupportedOperationException]，不注册任何东西。
     */
    fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> {
        if (spec.versioned) {
            return createVersioned(spec)
        }
        return register(spec.name, spec.namespace, { it }) { store -> CoherentEngine(spec, store, context) }
    }

    private fun <K : Any, V : Any> createVersioned(spec: CacheSpec<K, V>): Cache<K, V> {
        if (!backend.versionedWrites) {
            throw UnsupportedOperationException(
                "Cache[${spec.name}] is versioned, but backend ${backend.javaClass.name} does not support versioned writes " +
                    "(CoherenceBackend.versionedWrites = false)."
            )
        }
        val engineSpec = spec.versionedEngineSpec()
        return register(spec.name, spec.namespace, { VersionedCache(it) }) { store ->
            CoherentEngine(engineSpec, store, context, VersionedFormat(spec.codec))
        }
    }

    /**
     * 按声明创建集合缓存。
     */
    fun <K : Any, E : Any> create(spec: SetCacheSpec<K, E>): SetCache<K, E> {
        return register(spec.name, spec.namespace, { it }) { store -> SetEngine(spec, store, context) }
    }

    /**
     * @param expose 用户拿到的缓存（[cache] 按名称返回的也是它）
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <C, R : Any> register(
        name: String,
        namespace: String,
        expose: (C) -> R,
        factory: (RemoteStore) -> C
    ): R where C : AbstractEngine<*, *> {
        check(!closed.get()) { "CacheRuntime is closed." }
        require(names[name] == null) { "Cache[$name] already exists." }
        require(namespaces.add(namespace)) { "Namespace[$namespace] is already used by another cache." }
        val store = backend.store(namespace)
        val engine = factory(store)
        val exposed = expose(engine)
        val registration = Registration(engine, store, exposed)
        if (names.putIfAbsent(name, registration) != null) {
            namespaces.remove(namespace)
            throw IllegalArgumentException("Cache[$name] already exists.")
        }
        registrations.add(registration)
        // 订阅建立时通道会回调 onReset（清空空的 L2，无害）
        registration.subscription = try {
            backend.channel.subscribe(namespace, engine)
        } catch (e: Exception) {
            // 回滚：否则同名重试会因“已存在”失败，而该命名空间永远收不到失效
            registrations.remove(registration)
            names.remove(name, registration)
            namespaces.remove(namespace)
            closeQuietly("cache $name") { engine.close() }
            closeQuietly("store of $name") { store.close() }
            throw e
        }
        log.info { "Cache[$name] - created on namespace[$namespace], node[$nodeId]." }
        return exposed
    }

    /**
     * 按名称查找已创建的缓存。
     */
    fun cache(name: String): Cache<*, *>? = names[name]?.cache as Cache<*, *>?

    /**
     * 按名称查看缓存的运行统计。
     */
    fun metrics(name: String): CacheMetrics? = names[name]?.engine?.metrics

    /**
     * 按名称取得运维接口。
     */
    fun admin(name: String): CacheAdmin? = names[name]?.engine

    val cacheNames: Set<String>
        get() = names.keys.toSet()

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        registrations.reversed().forEach { registration ->
            closeQuietly("subscription of ${registration.engine.name}") { registration.subscription?.close() }
            closeQuietly("cache ${registration.engine.name}") { registration.engine.close() }
            closeQuietly("store of ${registration.engine.name}") { registration.store.close() }
        }
        owned.reversed().forEach { resource -> closeQuietly(resource.toString()) { resource.close() } }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun closeQuietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn(e) { "Failed to close $what." }
        }
    }

    class Builder internal constructor() {
        var backend: CoherenceBackend? = null
        var clock: CacheClock? = null
        var nodeId: NodeId? = null
        var refreshExecutor: Executor? = null
        var retryPolicy: RetryPolicy = RetryPolicy()
        var loadObserver: LoadObserver = LoadObserver.NOOP
        var asyncExecutor: Executor? = null
        var blockingExecutor: Executor? = null
        var recordLocalHits: Boolean = false

        fun backend(backend: CoherenceBackend) = apply { this.backend = backend }

        /**
         * 不指定时运行时创建并管理一个 [TickingClock]。
         */
        fun clock(clock: CacheClock) = apply { this.clock = clock }
        fun nodeId(nodeId: NodeId) = apply { this.nodeId = nodeId }

        /**
         * 提前刷新执行器，由调用方管理：运行时关闭时不会关闭它。不指定时使用运行时自建并管理的有界守护线程池
         * （队列满时拒绝，刷新被跳过并计数）。
         */
        fun refreshExecutor(refreshExecutor: Executor) = apply { this.refreshExecutor = refreshExecutor }
        fun retryPolicy(retryPolicy: RetryPolicy) = apply { this.retryPolicy = retryPolicy }

        /**
         * 回源观测（如链路追踪），作用于本运行时创建的所有缓存。不指定时为 [LoadObserver.NOOP]。
         */
        fun loadObserver(loadObserver: LoadObserver) = apply { this.loadObserver = loadObserver }

        /**
         * 异步执行器（RFC 0004 §5.1）：运行异步视图的全部续接，只承载短小的非阻塞工作。由调用方管理。
         * 不指定时使用运行时自建并管理的 `ForkJoinPool`（并行度为 CPU 数，`cocache-async-*`）：
         * 它的线程在 `join()` 上阻塞时会补偿线程，替换为固定大小的普通线程池时没有这个保证。
         */
        fun asyncExecutor(asyncExecutor: Executor) = apply { this.asyncExecutor = asyncExecutor }

        /**
         * 阻塞执行器（RFC 0004 §5.1）：异步路径上的同步数据源与只实现同步 SPI 的后端只在这里执行。由调用方管理，不应排队。
         * 不指定时：JDK 21+ 每个任务一个虚拟线程；否则是运行时自建的不排队守护线程池（最多 512 线程，满时拒绝）。
         */
        fun blockingExecutor(blockingExecutor: Executor) = apply { this.blockingExecutor = blockingExecutor }

        /**
         * 是否把 L2 命中计入 `CacheMetrics.localHits`，作用于本运行时创建的所有缓存。默认不记录：
         * 开启后每次 L2 命中多一次 `LongAdder` 自增，在最热的路径上有可测的吞吐开销。
         */
        fun recordLocalHits(recordLocalHits: Boolean) = apply { this.recordLocalHits = recordLocalHits }

        fun build(): CacheRuntime = CacheRuntime(this)
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private const val REFRESH_QUEUE_CAPACITY = 1024

        @JvmStatic
        fun builder(): Builder = Builder()

        private fun daemonThreads(prefix: String): ThreadFactory {
            val counter = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        private fun defaultRefreshExecutor(): ExecutorService {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            return ThreadPoolExecutor(
                threads,
                threads,
                1,
                TimeUnit.MINUTES,
                ArrayBlockingQueue(REFRESH_QUEUE_CAPACITY),
                daemonThreads("cocache-refresh"),
                ThreadPoolExecutor.AbortPolicy(),
            ).apply { allowCoreThreadTimeOut(true) }
        }

        internal const val MAX_BLOCKING_THREADS = 512
        private const val BLOCKING_KEEP_ALIVE_SECONDS = 60L

        private fun defaultAsyncExecutor(): ForkJoinPool {
            val counter = AtomicInteger()
            val factory = ForkJoinPool.ForkJoinWorkerThreadFactory { pool ->
                object : ForkJoinWorkerThread(pool) {}.apply {
                    name = "cocache-async-${counter.incrementAndGet()}"
                    isDaemon = true
                }
            }
            return ForkJoinPool(Runtime.getRuntime().availableProcessors(), factory, null, true)
        }

        /**
         * JDK 21+ 经反射取得 `Executors.newVirtualThreadPerTaskExecutor()`（基线为 JDK 17）；否则为不排队的守护线程池。
         * 满时拒绝而不是由调用方执行：调用方是异步执行器的线程，同步数据源不得在那里执行。
         */
        internal fun defaultBlockingExecutor(
            virtual: () -> ExecutorService? = ::virtualThreadExecutor
        ): ExecutorService = virtual() ?: ThreadPoolExecutor(
            0,
            MAX_BLOCKING_THREADS,
            BLOCKING_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            daemonThreads("cocache-blocking"),
            ThreadPoolExecutor.AbortPolicy(),
        )

        private fun virtualThreadExecutor(): ExecutorService? = runCatching {
            Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor").invoke(null) as ExecutorService
        }.getOrNull()

        private fun defaultTimer(): ScheduledExecutorService {
            return ScheduledThreadPoolExecutor(1, daemonThreads("cocache-timer")).apply { removeOnCancelPolicy = true }
        }

        private fun defaultScheduler(): ScheduledExecutorService {
            return Executors.newSingleThreadScheduledExecutor(daemonThreads("cocache-retry"))
        }
    }
}

/**
 * Kotlin DSL：`cacheRuntime { backend = …; clock = … }`。
 */
fun cacheRuntime(block: CacheRuntime.Builder.() -> Unit): CacheRuntime = CacheRuntime.builder().apply(block).build()
