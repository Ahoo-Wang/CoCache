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
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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

    /**
     * 本运行时的缓存所在的后端。需要后端的代码（如库自己检查驱逐策略）从这里取（经 [CoCacheFactory] 时为 `factory.runtime.backend`），
     * 不要在 Spring 中注入 `CoherenceBackend` bean：应用自建运行时、或装饰了后端时，bean 可能是另一个后端（#874）。
     */
    val backend: CoherenceBackend = requireNotNull(builder.backend) { "backend is required." }

    /**
     * 关闭时等待在途 L1 步骤的上限（[Builder.closeTimeout]，RFC 0005 §4.4、K17）。
     */
    val closeTimeout: Duration = builder.closeTimeout.also {
        require(!it.isNegative) { "closeTimeout[$it] must not be negative." }
    }

    private val owned = ArrayList<AutoCloseable>()
    private val context: EngineContext

    /**
     * 运行时的执行器（阻塞调度器，RFC 0005 §4.4、§5）：[Builder.executor] 给出的，或运行时自建的那个（每个任务一个虚拟线程）。
     * 适配层可以把可能阻塞的用户代码（如 Spring `retrieve` 的方法体）派发到这里。随运行时关闭（自建时）。
     */
    val executor: Executor
        get() = context.async.executor

    /**
     * 已创建完成的缓存（订阅已确认）：按创建顺序，[close] 逆序关闭。
     */
    private val registrations = CopyOnWriteArrayList<Registration>()

    /**
     * 已创建完成的缓存，按名称（[cache]、[admin]、[cacheNames] 等公开查询只看这里）。创建中的缓存不在其中：订阅失败而回滚的缓存
     * 从不对外可见，同名重试之后查询与指标绑定（starter 的 `CoCacheMeterBinder`）只会拿到新的引擎（#830）。
     */
    private val names = ConcurrentHashMap<String, Registration>()
    private val creationListeners = CopyOnWriteArrayList<CacheCreationListener>()

    /**
     * 注册与关闭的交接（#815）：[closed]、[reserved] 与 [keyspaces] 只在持有它时读写，[names] 与 [registrations] 只在持有它时追加。
     * 只在内存中检查与登记时持有，从不跨越创建存储、订阅这类网络等待。
     */
    private val lifecycle = Any()
    private var closed = false

    /**
     * 创建中与已创建的缓存名：同名的并发创建只有一个能继续。
     */
    private val reserved = HashSet<String>()

    /**
     * 创建中与已创建的缓存的 keyspace（前缀与命名空间）→ 缓存名：存储前缀相等或互为前缀的两个 keyspace 不能共存（#819）；
     * 命名空间相同、前缀不同的两个缓存 key 与频道都不重叠，可以共存。
     */
    private val keyspaces = HashMap<Keyspace, String>()

    /**
     * @param cache 用户拿到的缓存（就是引擎本身）
     */
    private class Registration(
        val engine: AbstractEngine<*, *>,
        val cache: ReadableCache<*, *>,
        val subscription: Subscription,
    )

    init {
        // 没有显式时钟时沿用内存后端的显式时钟（X8）：测试只需把 ManualClock 交给后端一次，L1 与 L2 按同一个时间判断到期。
        // 经 unwrap 看穿包装（HookedBackend(InMemoryBackend(clock))），否则包装过的测试后端仍然两个时钟错位。
        // 其余情况为运行时自建的 TickingClock（基准依赖它）
        clock = builder.clock ?: inMemoryClockOf(backend) ?: TickingClock().also { owned.add(it) }
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
     * 异步与挂起变体的执行资源（RFC 0005 §4.4）。运行时自建的执行器随运行时关闭；调用方给出的执行器不关闭。
     * 关闭顺序（[owned] 逆序）：先取消运行时作用域（进行中的异步调用以关闭失败）、有界地等待其中在途的步骤结束，再关闭自建的执行器。
     * 等待 lease 的退避与等待回源许可都是挂起（`delay`、信号量），不需要专门的定时器线程。
     */
    private fun asyncContext(builder: Builder): AsyncContext {
        val executor = builder.executor ?: defaultExecutor().also { owned.add(AutoCloseable { it.shutdown() }) }
        return AsyncContext(executor, builder.dispatcher, closeTimeout).also { owned.add(it) }
    }

    /**
     * 按声明创建派生副本 `Cache`（RFC 0005 §4.1）。同一运行时内缓存名与 keyspace（[Spec.keyspace]，前缀加命名空间）都不得重复。
     * 不要求可选的 L1 能力。
     *
     * 直接经本方法（及其它 `create` 重载）创建的缓存是“未分层”的（[CacheAdmin.layered] 为 `false`，S20）：声明原样生效，
     * 配置的各层（starter 的 `cocache.defaults`、`cocache.caches.[name]` 与定制器）对它都不生效，启动日志与端点标注出来。
     * 库与应用应经 [CoCacheFactory] 创建缓存。
     */
    fun <K : Any, V : Any> create(spec: CacheSpec<K, V>): Cache<K, V> = create(spec, report = null)

    internal fun <K : Any, V : Any> create(spec: CacheSpec<K, V>, report: PolicyReport?): Cache<K, V> =
        register(spec.name, spec.keyspace, NONE, report) { store -> CacheEngine(spec.engineSpec(), store, context) }

    /**
     * 按声明创建派生的带版本副本 `VersionedCache`。存储必须支持按版本有序的写入（[L1Store.versioned]）。
     *
     * 创建时检查缓存需要的 L1 能力（RFC 0005 §4.3）：存储缺少任何一个时抛出 [UnsupportedOperationException]，不注册任何东西。
     */
    fun <K : Any, V : Any> create(spec: VersionedCacheSpec<K, V>): VersionedCache<K, V> = create(spec, report = null)

    internal fun <K : Any, V : Any> create(spec: VersionedCacheSpec<K, V>, report: PolicyReport?): VersionedCache<K, V> =
        register(spec.name, spec.keyspace, listOf(Capability.VERSIONED), report) { store ->
            VersionedCacheEngine(spec.engineSpec(), store, context)
        }

    /**
     * 按声明创建权威副本 `Store`。存储必须支持条件读写（[L1Store.conditional]）：`getIfChanged`、`putIfMatch` 与 `deleteIfMatch`
     * 是 `Store` 的操作。
     */
    fun <K : Any, V : Any> create(spec: StoreSpec<K, V>): Store<K, V> = create(spec, report = null)

    internal fun <K : Any, V : Any> create(spec: StoreSpec<K, V>, report: PolicyReport?): Store<K, V> =
        register(spec.name, spec.keyspace, listOf(Capability.CONDITIONAL), report) { store ->
            StoreEngine(spec.engineSpec(), store, context)
        }

    /**
     * 按声明创建权威的带版本副本 `VersionedStore`。存储必须支持按版本有序的写入（[L1Store.versioned]）。
     */
    fun <K : Any, V : Any> create(spec: VersionedStoreSpec<K, V>): VersionedStore<K, V> = create(spec, report = null)

    internal fun <K : Any, V : Any> create(spec: VersionedStoreSpec<K, V>, report: PolicyReport?): VersionedStore<K, V> =
        register(spec.name, spec.keyspace, listOf(Capability.VERSIONED), report) { store ->
            VersionedStoreEngine(spec.engineSpec(), store, context)
        }

    /**
     * 按声明创建权威集合 `SetStore`。存储必须支持集合成员的增删（[L1Store.sets]）。
     */
    fun <K : Any, E : Any> create(spec: SetStoreSpec<K, E>): SetStore<K, E> = create(spec, report = null)

    internal fun <K : Any, E : Any> create(spec: SetStoreSpec<K, E>, report: PolicyReport?): SetStore<K, E> =
        register(spec.name, spec.keyspace, listOf(Capability.SETS), report) { store ->
            SetEngine(spec, store, context)
        }

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
     * @param report 经 [CoCacheFactory] 创建时的生效值报告（[CacheAdmin.policyReport]）；`null` 即未分层
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <C> register(
        name: String,
        keyspace: Keyspace,
        required: List<Capability>,
        report: PolicyReport?,
        factory: (L1Store) -> C
    ): C where C : AbstractEngine<*, *>, C : ReadableCache<*, *> {
        reserve(name, keyspace)
        var engine: C? = null
        var subscription: Subscription? = null
        val (provided, created) = try {
            val (provided, created) = createEngine(name, keyspace, required, factory)
            engine = created
            created.layered = report != null
            created.policyReport = report
            // 订阅建立时通道会回调 onReset（清空空的 L2，无害）
            subscription = backend.channel.subscribe(keyspace, created)
            publish(name, Registration(created, created, subscription))
            provided to created
        } catch (e: Throwable) {
            // 回滚：否则同名重试会因“已存在”失败，而该 keyspace 永远收不到失效；关闭已经发生时，建立的订阅与引擎由这里关闭。
            // SPI 抛出的 `Error`（未实现方法中的 `TODO()`、类初始化失败）同样回滚，原样抛出
            subscription?.let { closeQuietly("subscription of $name") { it.close() } }
            engine?.let { closeQuietly("cache $name") { it.close() } }
            release(name, keyspace)
            throw e
        }
        log.info {
            val capabilities = provided.joinToString(prefix = "[", postfix = "]")
            "Cache[$name] - created as ${created.apiType.simpleName} on namespace[${keyspace.namespace}], " +
                "keyPrefix[${keyspace.keyPrefix}], node[$nodeId], L1 capabilities$capabilities" +
                if (report != null) "." else ", $UNLAYERED_NOTE"
        }
        report?.let { logPolicy(name, it) }
        if (keyspace.keyPrefix.isEmpty()) {
            log.warn { emptyKeyPrefixWarning(name, keyspace) }
        }
        creationListeners.forEach { listener ->
            try {
                listener.created(created)
            } catch (e: Exception) {
                log.warn(e) { "Cache[$name] - creation listener $listener failed." }
            }
        }
        return created
    }

    /**
     * 预留缓存名与 keyspace（持有 [lifecycle]）：运行时已关闭、同名、keyspace 已被占用或存储前缀与已有的 keyspace 重叠时失败，不登记任何东西。
     * 存储前缀（`{keyPrefix}{namespace}:`）相等或互为前缀时，一方的存储 key 落在另一方的范围内（如 `app:` + `users` 的 key `v2:k`
     * 与 `app:users:` + `v2` 的 key `k` 都是 `app:users:v2:k`），两个缓存会相互覆盖，失效却只发往各自的频道（#819）。
     * 跨运行时、跨应用的重叠这里看不到，由部署保证（`docs/architecture.md` §4.1）。
     */
    private fun reserve(name: String, keyspace: Keyspace) = synchronized(lifecycle) {
        check(!closed) { "CacheRuntime is closed." }
        require(name !in reserved) { "Cache[$name] already exists." }
        keyspaces[keyspace]?.let {
            throw IllegalArgumentException(
                "Namespace[${keyspace.namespace}] with keyPrefix[${keyspace.keyPrefix}] is already used by Cache[$it]."
            )
        }
        keyspaces.entries.firstOrNull { overlaps(it.key, keyspace) }?.let { (other, owner) ->
            throw IllegalArgumentException(
                "Cache[$name] - its storage keys (${keyspace.storagePrefix}…) overlap those of Cache[$owner] " +
                    "(${other.storagePrefix}…): one prefix starts the other, so the two caches would overwrite each other's keys. " +
                    "Give one of them a different keyPrefix or namespace."
            )
        }
        reserved.add(name)
        keyspaces[keyspace] = name
    }

    private fun overlaps(a: Keyspace, b: Keyspace): Boolean =
        a.storagePrefix.startsWith(b.storagePrefix) || b.storagePrefix.startsWith(a.storagePrefix)

    /**
     * 把创建完成的缓存交给运行时（持有 [lifecycle]）：之后它对公开查询可见，由 [close] 关闭。关闭已经开始时失败（[IllegalStateException]），
     * 由调用方关闭它的订阅与引擎：[close] 看不到它（#815）。
     */
    private fun publish(name: String, registration: Registration) = synchronized(lifecycle) {
        check(!closed) { "CacheRuntime is closed." }
        registrations.add(registration)
        names[name] = registration
    }

    private fun release(name: String, keyspace: Keyspace) = synchronized(lifecycle) {
        reserved.remove(name)
        keyspaces.remove(keyspace)
    }

    /**
     * 来源不是类型默认值的字段及其生效值（一行 INFO），被跳过的配置条目、被夹紧与有意解锁的字段（各一行 WARN）。
     */
    private fun logPolicy(name: String, report: PolicyReport) {
        val changed = report.entries.filter { it.source != PolicyReport.Source.TYPE_DEFAULT }
        if (changed.isNotEmpty()) {
            log.info { "Cache[$name] - policy: ${changed.joinToString { describe(it) }}." }
        }
        report.skipped.forEach { skipped ->
            log.warn { "Cache[$name] - ${skipped.key} skipped: ${skipped.reason}." }
        }
        report.entries.filter { it.source == PolicyReport.Source.CLAMPED || it.source == PolicyReport.Source.UNLOCKED }
            .forEach { entry -> log.warn { "Cache[$name] - ${describe(entry)}." } }
    }

    /**
     * 之后创建的每个缓存（无论是否经 [CoCacheFactory]）注册之后回调 [listener]，在创建它的线程上。已经创建的缓存不回放：
     * 需要时遍历 [cacheNames]。监听者抛出的异常只记录告警，不影响缓存的创建。
     */
    fun addCreationListener(listener: CacheCreationListener) {
        creationListeners.add(listener)
    }

    /**
     * 创建存储、检查能力并创建引擎，返回存储提供的能力（启动日志输出）。失败时由 [register] 回滚：释放缓存名与 keyspace，否则同名、
     * 同 keyspace 永远无法重试（RFC 0005 附录 A.L9）。存储没有 `close`：连接等资源属于后端。创建存储时后端探测实际频道（K8），
     * 无权发布时在这里失败。
     */
    private fun <C : AbstractEngine<*, *>> createEngine(
        name: String,
        keyspace: Keyspace,
        required: List<Capability>,
        factory: (L1Store) -> C
    ): Pair<List<Capability>, C> {
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

    /**
     * 幂等。关闭开始之后创建中的缓存不再交给运行时（[publish] 失败），由创建它的调用自己关闭（#815）。
     */
    override fun close() {
        val created = synchronized(lifecycle) {
            if (closed) {
                return
            }
            closed = true
            registrations.toList()
        }
        created.reversed().forEach { registration ->
            closeQuietly("subscription of ${registration.engine.name}") { registration.subscription.close() }
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
        var executor: Executor? = null
        var recordLocalHits: Boolean = false

        /**
         * 截止时刻的计时源（模块内部）：确定性仿真换成虚拟时间（RFC 0005 §4.10）。
         */
        internal var ticker: Ticker = Ticker.SYSTEM

        /**
         * 运行时作用域的调度器（模块内部）：确定性仿真传入测试调度器，`delay` 与 `withTimeout` 随之走虚拟时间。
         */
        internal var dispatcher: CoroutineDispatcher? = null

        var closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT

        fun backend(backend: CoherenceBackend) = apply { this.backend = backend }

        /**
         * 不指定时：后端是（或经 [CoherenceBackend.unwrap] 包装着）构造时给出了时钟的 [InMemoryBackend]，沿用它的时钟
         * （[InMemoryBackend.clock]）；否则运行时创建并管理一个 [TickingClock]。
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
         * 运行时的执行器（阻塞调度器，RFC 0005 §4.4、§5）：异步与挂起变体在运行时作用域中的每一步（L1 命令完成之后的续接、共享加载），
         * 以及异步路径上的阻塞数据源都在这里执行，因此它必须容得下阻塞的任务。
         * 由调用方管理，运行时关闭时不关闭它。它不应排队，也不应只有少量线程：阻塞的数据源会占住线程，续接就排不上。
         *
         * 不指定时使用运行时自建并管理的执行器：每个任务一个虚拟线程（`cocache-worker-*`）。JDK 21–23 上数据源在 `synchronized` 块内阻塞
         * 会钉住载体线程（JEP 491 在 JDK 24 消除），生产环境建议 JDK 24 及以上，或换成平台线程池。同步变体不经过它。
         */
        fun executor(executor: Executor) = apply { this.executor = executor }

        /**
         * 是否把 L2 命中计入 `CacheMetrics.localHits`，作用于本运行时创建的所有缓存。默认不记录：
         * 开启后每次 L2 命中多一次 `LongAdder` 自增，在最热的路径上有可测的吞吐开销。
         */
        fun recordLocalHits(recordLocalHits: Boolean) = apply { this.recordLocalHits = recordLocalHits }

        /**
         * 关闭时等待在途 L1 步骤的上限（RFC 0005 §4.4、K17）：[CacheRuntime.close] 取消运行时作用域之后至多等这么久，
         * 让在途的不可取消步骤（L1 命令、令牌的释放、写入之后的补发广播）完成，再关闭自建的执行器与后端；等不到时告警、照常关闭，
         * 那些步骤随后可能失败（其它节点陈旧到 `localMaxAge`，lease 留到 `lease.ttl`）。
         *
         * 不应小于后端的命令超时：在途的那条命令要等满它，之后还可能有一条释放或补发广播。starter 取
         * `cocache.redis.command-timeout` 的两倍与默认值中较大的一个。默认 [DEFAULT_CLOSE_TIMEOUT]；为 0 时不等待
         * （在测试线程上手动推进执行器与 L1 命令的测试：推进它们的正是关闭的线程）。不得为负。
         */
        fun closeTimeout(closeTimeout: Duration) = apply { this.closeTimeout = closeTimeout }

        fun build(): CacheRuntime = CacheRuntime(this)
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private const val REFRESH_QUEUE_CAPACITY = 1024

        /**
         * 默认执行器的线程名前缀（[defaultExecutor]）。
         */
        internal const val WORKER_THREAD_PREFIX = "cocache-worker-"

        /**
         * 不要求任何可选能力（`Cache`，见 [create]）。
         */
        private val NONE: List<Capability> = emptyList()

        /**
         * 未分层的缓存在启动日志中的说明（S20）。
         */
        private fun describe(entry: PolicyReport.Entry): String {
            val source = entry.source.name.lowercase().replace('_', ' ')
            return "${entry.field}=${entry.value} ($source${entry.detail?.let { ", $it" } ?: ""})"
        }

        internal const val UNLAYERED_NOTE =
            "unlayered: created directly through CacheRuntime.create, not CoCacheFactory, so configuration layers " +
                "(cocache.defaults, cocache.caches.[name], customizers) don't apply."

        @JvmStatic
        fun builder(): Builder = Builder()

        /**
         * [Builder.closeTimeout] 的默认值：容得下几条 L1 命令（Redis 后端的 `commandTimeout` 默认 2 s）。
         */
        @JvmField
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)

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

        /**
         * 后端是、或包装着构造时给出了时钟的 [InMemoryBackend] 时，它的时钟（[CoherenceBackend.unwrap]）。按 `Any?` 取回再安全转换：
         * 不守约的 `unwrap`（如 relaxed mock 返回任意对象）只是取不到时钟，不让运行时创建失败。
         */
        private fun inMemoryClockOf(backend: CoherenceBackend): CacheClock? {
            val type: Class<*> = InMemoryBackend::class.java
            return (backend.unwrap(type) as? InMemoryBackend)?.clock
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

        /**
         * 默认的执行器：每个任务一个虚拟线程，线程名 `cocache-worker-N`。
         */
        private fun defaultExecutor(): ExecutorService =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(WORKER_THREAD_PREFIX, 0).factory())

        private fun defaultScheduler(): ScheduledExecutorService {
            return Executors.newSingleThreadScheduledExecutor(daemonThreads("cocache-retry"))
        }
    }
}

/**
 * Kotlin DSL：`cacheRuntime { backend = …; clock = … }`。
 */
fun cacheRuntime(block: CacheRuntime.Builder.() -> Unit): CacheRuntime = CacheRuntime.builder().apply(block).build()
