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
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.clock.TickingClock
import me.ahoo.cocache.core.engine.AbstractEngine
import me.ahoo.cocache.core.engine.AsyncContext
import me.ahoo.cocache.core.engine.CacheEngine
import me.ahoo.cocache.core.engine.EngineContext
import me.ahoo.cocache.core.engine.InvalidationRetrier
import me.ahoo.cocache.core.engine.SetEngine
import me.ahoo.cocache.core.engine.StoreEngine
import me.ahoo.cocache.core.engine.VersionedCacheEngine
import me.ahoo.cocache.core.engine.VersionedStoreEngine
import me.ahoo.cocache.core.engine.engineSpec
import me.ahoo.cocache.core.internal.Ticker
import me.ahoo.cocache.core.policy.CachePolicy
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 缓存运行时（每个进程一个）：持有节点身份、时钟、后端与执行器，并按声明创建缓存。
 *
 * 关闭时按创建的逆序关闭所有缓存与订阅，并关闭运行时自己创建的时钟与执行器；后端由调用方管理，**必须晚于运行时关闭**：
 * [close] 取消运行时作用域之后、关闭执行器之前，至多等待 5 s，让异步变体在途的不可取消步骤（L1 命令、令牌的释放、
 * 非原子广播的补发）结束。先关闭后端会让这些步骤失败：其它节点读旧的 L2 直到 `localMaxAge`，lease 留到 `lease.ttl`。
 * 同步调用在调用方线程上运行，不在等待之列，应先停止发起调用。
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

    /**
     * 已登记的 keyspace（前缀与命名空间）：命名空间相同、前缀不同的两个缓存 key 与频道都不重叠，可以共存。
     */
    private val keyspaces = ConcurrentHashMap.newKeySet<Keyspace>()
    private val closed = AtomicBoolean(false)

    /**
     * @param cache 用户拿到的缓存（就是引擎本身）
     */
    private class Registration(
        val engine: AbstractEngine<*, *>,
        val cache: ReadableCache<*, *>,
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
            builder.recordLocalHits,
            builder.ticker,
            builder.fillObserver
        )
    }

    /**
     * 异步路径的执行资源（RFC 0004 §5.1）。运行时自建的执行器随运行时关闭；调用方给出的执行器不关闭。
     * 关闭顺序（[owned] 逆序）：先取消运行时作用域（进行中的异步调用以关闭失败）、有界地等待其中在途的步骤结束，再关闭自建的执行器。
     * 等待 lease 的退避与等待回源许可都是挂起（`delay`、信号量），不再需要专门的定时器线程。
     */
    private fun asyncContext(builder: Builder): AsyncContext {
        val executor = builder.asyncExecutor ?: defaultAsyncExecutor().also { owned.add(AutoCloseable { it.shutdown() }) }
        val blocking = builder.blockingExecutor ?: Executors.newVirtualThreadPerTaskExecutor().also {
            owned.add(AutoCloseable { it.shutdown() })
        }
        return AsyncContext(executor, blocking, builder.dispatcher, builder.drainTimeout).also { owned.add(it) }
    }

    /**
     * 按声明创建派生副本 `Cache`（RFC 0005 §4.1）。同一运行时内缓存名与 keyspace（[Spec.keyspace]，前缀加命名空间）都不得重复。
     * 不要求可选的 L1 能力。
     */
    fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> =
        register(spec.name, spec.keyspace, NONE) { store -> CacheEngine(spec.engineSpec(), store, context) }

    /**
     * 按声明创建派生的带版本副本 `VersionedCache`。存储必须支持按版本有序的写入（[L1Store.versioned]）。
     *
     * 创建时检查缓存需要的 L1 能力（RFC 0005 §4.3）：存储缺少任何一个时抛出 [UnsupportedOperationException]，不注册任何东西。
     */
    fun <K : Any, V : Any> create(spec: VersionedCacheSpec<K, V>): VersionedCache<K, V> =
        register(spec.name, spec.keyspace, listOf(Capability.VERSIONED)) { store ->
            VersionedCacheEngine(spec.engineSpec(), store, context)
        }

    /**
     * 按声明创建权威副本 `Store`。存储必须支持条件读写（[L1Store.conditional]）：`getIfChanged`、`putIfMatch` 与 `deleteIfMatch`
     * 是 `Store` 的操作。
     */
    fun <K : Any, V : Any> create(spec: StoreSpec<K, V>): Store<K, V> =
        register(spec.name, spec.keyspace, listOf(Capability.CONDITIONAL)) { store ->
            StoreEngine(spec.engineSpec(), store, context)
        }

    /**
     * 按声明创建权威的带版本副本 `VersionedStore`。存储必须支持按版本有序的写入（[L1Store.versioned]）。
     */
    fun <K : Any, V : Any> create(spec: VersionedStoreSpec<K, V>): VersionedStore<K, V> =
        register(spec.name, spec.keyspace, listOf(Capability.VERSIONED)) { store ->
            VersionedStoreEngine(spec.engineSpec(), store, context)
        }

    /**
     * 按声明创建权威集合 `SetStore`。存储必须支持集合成员的增删（[L1Store.sets]）。
     */
    fun <K : Any, E : Any> create(spec: SetStoreSpec<K, E>): SetStore<K, E> =
        register(spec.name, spec.keyspace, listOf(Capability.SETS)) { store -> SetEngine(spec, store, context) }

    /**
     * 按声明创建与 spec 类型对应的缓存（[Spec] 的五种实现，见各自的重载）。
     */
    fun create(spec: Spec<*, *>): ReadableCache<*, *> = when (spec) {
        is CacheSpec<*, *> -> create(spec)
        is VersionedCacheSpec<*, *> -> create(spec)
        is StoreSpec<*, *> -> create(spec)
        is VersionedStoreSpec<*, *> -> create(spec)
        is SetStoreSpec<*, *> -> create(spec)
    }

    /**
     * @param required 缓存需要的 L1 能力：存储缺少其中任何一个时抛出，不注册任何东西
     * @param expose 用户拿到的缓存（[cache] 按名称返回的也是它）
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <C> register(
        name: String,
        keyspace: Keyspace,
        required: List<Capability>,
        factory: (L1Store) -> C
    ): C where C : AbstractEngine<*, *>, C : ReadableCache<*, *> {
        check(!closed.get()) { "CacheRuntime is closed." }
        require(names[name] == null) { "Cache[$name] already exists." }
        require(keyspaces.add(keyspace)) {
            "Namespace[${keyspace.namespace}] with keyPrefix[${keyspace.keyPrefix}] is already used by another cache."
        }
        val (provided, engine) = createEngine(name, keyspace, required, factory)
        val registration = Registration(engine, engine)
        if (names.putIfAbsent(name, registration) != null) {
            keyspaces.remove(keyspace)
            throw IllegalArgumentException("Cache[$name] already exists.")
        }
        registrations.add(registration)
        // 订阅建立时通道会回调 onReset（清空空的 L2，无害）
        registration.subscription = try {
            backend.channel.subscribe(keyspace, engine)
        } catch (e: Exception) {
            // 回滚：否则同名重试会因“已存在”失败，而该 keyspace 永远收不到失效
            registrations.remove(registration)
            names.remove(name, registration)
            keyspaces.remove(keyspace)
            closeQuietly("cache $name") { engine.close() }
            throw e
        }
        log.info {
            "Cache[$name] - created as ${typeOf(engine)} on namespace[${keyspace.namespace}], keyPrefix[${keyspace.keyPrefix}], " +
                "node[$nodeId], L1 capabilities${provided.joinToString(
                    prefix = "[",
                    postfix = "]"
                )}."
        }
        if (keyspace.keyPrefix.isEmpty()) {
            log.warn { emptyKeyPrefixWarning(name, keyspace) }
        }
        return engine
    }

    /**
     * 创建存储、检查能力并创建引擎，返回存储提供的能力（启动日志输出）。失败时回滚：释放 keyspace，否则同名、同 keyspace 永远无法重试
     * （RFC 0005 附录 A.L9）。存储没有 `close`：连接等资源属于后端。创建存储时后端探测实际频道（K8），无权发布时在这里失败。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <C : AbstractEngine<*, *>> createEngine(
        name: String,
        keyspace: Keyspace,
        required: List<Capability>,
        factory: (L1Store) -> C
    ): Pair<List<Capability>, C> {
        try {
            val store = backend.store(keyspace)
            val provided = Capability.of(store)
            val missing = required - provided.toSet()
            if (missing.isNotEmpty()) {
                throw UnsupportedOperationException(
                    "Cache[$name] needs the L1 capabilities$missing, but the store of backend ${backend.javaClass.name} " +
                        "on namespace[${keyspace.namespace}] provides only $provided."
                )
            }
            return provided to factory(store)
        } catch (e: Exception) {
            keyspaces.remove(keyspace)
            throw e
        }
    }

    /**
     * 存储的可选能力（RFC 0005 §4.3），只用于创建时的检查与启动日志：能力只由存储的访问器表达，不进入 SPI。
     */
    private enum class Capability(private val label: String) {
        CONDITIONAL("conditional"),
        VERSIONED("versioned"),
        SETS("sets");

        override fun toString(): String = label

        companion object {
            /**
             * [store] 提供的能力，按声明顺序。
             */
            fun of(store: L1Store): List<Capability> = listOfNotNull(
                CONDITIONAL.takeIf { store.conditional() != null },
                VERSIONED.takeIf { store.versioned() != null },
                SETS.takeIf { store.sets() != null },
            )
        }
    }

    /**
     * 按名称查找已创建的缓存（五种用户 API 类型之一）。
     */
    fun cache(name: String): ReadableCache<*, *>? = names[name]?.cache

    /**
     * 按名称查看缓存的运行统计。
     */
    fun metrics(name: String): CacheMetrics? = names[name]?.engine?.metrics

    /**
     * 按名称取得运维接口。
     */
    fun admin(name: String): CacheAdmin? = names[name]?.engine

    /**
     * 按名称查看缓存生效的策略（创建时 spec 中的策略，如 starter 中经定制器与配置文件合并之后的结果）。
     */
    fun policy(name: String): CachePolicy? = names[name]?.engine?.policy

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
        var fillObserver: FillObserver = FillObserver.NONE
        var asyncExecutor: Executor? = null
        var blockingExecutor: Executor? = null
        var recordLocalHits: Boolean = false

        /**
         * 截止时刻的计时源（模块内部）：确定性仿真换成虚拟时间（RFC 0005 §4.10）。
         */
        internal var ticker: Ticker = Ticker.SYSTEM

        /**
         * 运行时作用域的调度器（模块内部）：确定性仿真传入测试调度器，`delay` 与 `withTimeout` 随之走虚拟时间。
         */
        internal var dispatcher: CoroutineDispatcher? = null

        /**
         * 关闭时等待运行时作用域中在途步骤的上限（模块内部）：在测试线程上手动推进执行器与 L1 命令的测试设为 `0`，
         * 否则关闭会等满上限（推进它们的正是关闭的线程）。
         */
        internal var drainTimeout: Duration = AsyncContext.DRAIN_TIMEOUT

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
         * 写事务内回源的钩子（RFC 0005 §4.9），作用于本运行时创建的所有缓存。不指定时为 [FillObserver.NONE]（不门控、不记录）；
         * Spring 的事务门控由 `cocache-spring` 提供，starter 自动装配。
         */
        fun fillObserver(fillObserver: FillObserver) = apply { this.fillObserver = fillObserver }

        /**
         * 异步执行器（RFC 0004 §5.1）：运行异步视图的全部续接，只承载短小的非阻塞工作。由调用方管理。
         * 不指定时使用运行时自建并管理的 `ForkJoinPool`（并行度为 CPU 数，`cocache-async-*`）：
         * 它的线程在 `join()` 上阻塞时会补偿线程，替换为固定大小的普通线程池时没有这个保证。
         */
        fun asyncExecutor(asyncExecutor: Executor) = apply { this.asyncExecutor = asyncExecutor }

        /**
         * 阻塞执行器（RFC 0004 §5.1）：异步路径上的同步数据源只在这里执行。由调用方管理，不应排队。
         * 不指定时每个任务一个虚拟线程。
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

        /**
         * 不要求任何可选能力（`Cache`，见 [create]）。
         */
        private val NONE: List<Capability> = emptyList()

        /**
         * 缓存的用户 API 类型（启动日志与端点输出，供人工核对同一 namespace 的声明，RFC 0005 §4.1）。
         */
        internal fun typeOf(cache: ReadableCache<*, *>): String = when (cache) {
            is Cache<*, *> -> "Cache"
            is VersionedCache<*, *> -> "VersionedCache"
            is Store<*, *> -> "Store"
            is VersionedStore<*, *> -> "VersionedStore"
            is SetStore<*, *> -> "SetStore"
            else -> cache.javaClass.simpleName
        }

        @JvmStatic
        fun builder(): Builder = Builder()

        /**
         * `keyPrefix` 为空时的启动告警（RFC 0005 §4.7，S4）：存储 key 与频道都没有公共前缀，ACL 与按前缀的运维只能按 namespace 逐个写出。
         */
        internal fun emptyKeyPrefixWarning(name: String, keyspace: Keyspace): String =
            "Cache[$name] - keyPrefix is empty: the keys and the channel of namespace[${keyspace.namespace}] share no prefix, " +
                "so ACLs and prefix-based operations cannot be scoped by prefix and must name each namespace."

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

        private fun defaultScheduler(): ScheduledExecutorService {
            return Executors.newSingleThreadScheduledExecutor(daemonThreads("cocache-retry"))
        }
    }
}

/**
 * Kotlin DSL：`cacheRuntime { backend = …; clock = … }`。
 */
fun cacheRuntime(block: CacheRuntime.Builder.() -> Unit): CacheRuntime = CacheRuntime.builder().apply(block).build()
