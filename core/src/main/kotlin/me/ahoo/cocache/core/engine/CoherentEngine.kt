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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.api.spi.L1Eviction
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.FillKey
import me.ahoo.cocache.core.FillObserver
import me.ahoo.cocache.core.FillScope
import me.ahoo.cocache.core.ForeignFormatException
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
import me.ahoo.cocache.core.internal.uncancellable
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.nanoseconds

/**
 * 标量的一致性协议（RFC 0003 §4.4–§4.6，RFC 0005 §4.1）：四种标量类型（[CacheEngine]、[StoreEngine]、[VersionedCacheEngine]、
 * [VersionedStoreEngine]）共用的读写路径，子类只把用户 API 换算到这里，不包含协议逻辑。副本类型（[Role]）决定写入哪些段、
 * 读取是否申请 lease、读取方是否校验摘要。
 *
 * - L1 栅栏：派生副本未命中时由读脚本原子取得 lease，回源结果只能凭 lease 写回；失效销毁 lease。权威副本的读取不申请 lease，
 *   L1 中没有（含 lease 占位）就是不存在。
 * - L2 栅栏：失效戳。填充方读 L1 前取戳；写入方在修改 L1 前后各递增一次。L2 只由读 L1 的路径填充。
 * - 同步路径上 loader 总是在调用方（SingleFlight leader）线程上同步执行。
 * - 读取（单 key、批量）、提前刷新与写入运行在协程核心上（RFC 0005 V5）：同一份 `suspend` 实现（[loadMiss]、[loadPending]、
 *   [refreshThrough]、[write]），同步变体经 `runSync` 在调用方线程上运行，异步变体经 `AsyncContext.runAsync` 运行。
 * - 读取的结果是 L2 副本（[LocalEntry]）；这一次读取没有答案（L1 故障，`Lookup.Unknown`）时是 [Unanswered]，它从不进入 L2，
 *   由子类在调用方返回之前换算（[answerOf]、[lookupOf]、[valuesOf]、[lookupsOf]）。
 */
@Suppress("TooManyFunctions", "LargeClass")
internal abstract class CoherentEngine<K : Any, V : Any>(
    private val spec: EngineSpec<K, V>,
    store: L1Store,
    context: EngineContext,
) : AbstractEngine<K, V>(spec.name, spec.keyspace, spec.keyEncoder, spec.policy, store, context) {

    private val keyFilter = spec.keyFilter
    private val format: ValueFormat<V> = spec.format

    /**
     * 回源并发许可（bulkhead）：同步与异步变体共用一个按到达顺序交接的信号量，只计 SingleFlight leader。
     */
    private val ttlCeiling: Duration? = policy.ttlCeiling
    private val ttlClampWarned = AtomicBoolean(false)
    private val permits: Semaphore? = policy.load.maxConcurrent?.let { Semaphore(it) }
    private val loadObserver: LoadObserver = context.loadObserver
    private val fillObserver: FillObserver = context.fillObserver
    private val loadTimeout: Duration = policy.load.timeout
    private val ticker = context.ticker

    /**
     * 声明的数据源（同步或异步，二选一），同步与异步变体共用（[invoke] 按变体决定在哪里调用）。
     */
    private val source: AsyncSource<K, V>? = spec.source

    /**
     * 协程核心的数据源（预先分配，未命中路径不再为它们分配）：未命中加载与提前刷新只在回源观测的操作类型上不同。
     */
    private val loadSource: CoreSource<K, V>? = source?.let { CoreSource(it, LoadOperation.LOAD) }
    private val refreshSource: CoreSource<K, V>? = source?.let { CoreSource(it, LoadOperation.REFRESH) }

    /**
     * 权威副本（[Role.authoritative]，RFC 0005 §4.7）：每次标量写入都带一个新的写入标识，读取不申请 lease，读取方校验摘要（[digestProblem]）。
     * 派生副本不带任何段，L1 头部保持 36 字节。
     */
    private val authoritative: Boolean = spec.role.authoritative

    /**
     * 不带版本的标量写入是否带载荷的摘要段：只有 `Store`（D1）。
     */
    private val writesDigest: Boolean = spec.role == Role.STORE

    /**
     * 引擎的值是否为 `Versioned<V?>`（带版本的类型）。
     */
    protected val versionedValues: Boolean = spec.versioned

    /**
     * 不带 loader 的读取遇到 L1 读失败时返回什么（RFC 0005 §4.1，S16）。
     */
    private val unknownRead: UnknownReadMode = spec.unknownRead

    /**
     * 带数据源的读取遇到 L1 读失败时：默认降级回源；显式 `THROW` 时为 `Unknown`。
     */
    private val readFailure: ReadFailureMode = policy.readFailure ?: ReadFailureMode.DEGRADE

    /**
     * 类型不符（D4，[verifyDigest]）只在第一次告警，之后只计数：同一个 namespace 上的误声明会在每次 L1 读取时出现。
     */
    private val typeMismatchWarned = AtomicBoolean()

    /**
     * 带版本的数据源以不带版本的 `null` 表达删除、落在带版本的内容上被拒绝（K16）：只在第一次告警，之后只计数。
     */
    private val unversionedAbsentWarned = AtomicBoolean()

    /**
     * 派生副本的失效写入 L1 失败时的处理方式：默认重试（删除副本在任何时刻都安全）。
     */
    private val invalidateFailure: InvalidateFailureMode = policy.invalidateFailure ?: InvalidateFailureMode.RETRY

    /**
     * 失效写入 L1 失败、仍在重试中的存储 key：未命中时跳过 L1（L1 中仍是旧值）。
     */
    private val pendingInvalidations = ConcurrentHashMap<String, Int>()

    /**
     * 正在提前刷新的存储 key：每个节点每个 key 同一时刻至多一次。
     */
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    /**
     * 没取得刷新令牌、L1 仍在窗口内时，再读 L1 之前的退避（毫秒，见 [refill]）：`lease.wait`，即未命中等待别的节点回源的时长，
     * 至少 [MIN_REFRESH_BACKOFF]（`lease.wait` 可以为 0，为 0 的退避等于每次命中都读 L1）。
     */
    private val refreshBackoff: Long = maxOf(policy.lease.wait, MIN_REFRESH_BACKOFF).toMillis()

    // ---------------------------------------------------------------------------------------------
    // 读取：命中路径与未命中路径。子类的 `get` / `lookup` / `getAll` / `lookupAll` 经这里，再换算结果。
    // ---------------------------------------------------------------------------------------------

    /**
     * 不带 loader 的读取的值：[Unanswered] 按 [unknownRead] 换算（计入 `readsUnknown`），否则是副本的值。
     */
    internal fun answerOf(entry: LocalEntry<V>): V? = if (entry is Unanswered) unknownAnswer(entry) else entry.value

    /**
     * 带 loader 的读取的值：[Unanswered]（`readFailure = THROW`）总是抛出，`unknownRead` 不作用于它。
     */
    internal fun answerWithLoader(entry: LocalEntry<V>): V? {
        if (entry is Unanswered) {
            metrics.unknownRead()
            throw entry.error
        }
        return entry.value
    }

    private fun unknownAnswer(entry: Unanswered<V>): V? {
        metrics.unknownRead()
        if (unknownRead == UnknownReadMode.ABSENT) {
            return null
        }
        throw entry.error
    }

    /**
     * 不带 loader 的读取的 [Lookup]：[Unanswered] 按 [unknownRead] 为 `Unknown` 或 `Absent`（到期时间是现在，不写任何一层）。
     */
    internal fun lookupAnswerOf(entry: LocalEntry<V>, withLoader: Boolean = false): Lookup<V> {
        if (entry !is Unanswered) {
            return lookupOf(entry)
        }
        metrics.unknownRead()
        if (!withLoader && unknownRead == UnknownReadMode.ABSENT) {
            return Lookup.Absent(Instant.ofEpochMilli(clock.nowMillis()))
        }
        return Lookup.Unknown(entry.error)
    }

    /**
     * 批量读取的结果（[resolveAll]）换算为存在的值（经 [transform] 换算为用户的值，返回 `null` 的不在结果中）：有 key 为 [Unanswered]
     * 而 [unknownRead] 为 `THROW` 时，其余 key 已完成，抛出携带这些 key 与部分结果的 [CacheReadException]（RFC 0005 §4.1）。
     */
    internal fun <R : Any> valuesOf(resolved: Map<K, LocalEntry<V>?>, transform: (V) -> R?): Map<K, R> {
        val result = LinkedHashMap<K, R>()
        var unknown: LinkedHashMap<K, Unanswered<V>>? = null
        for ((key, entry) in resolved) {
            if (entry is Unanswered) {
                metrics.unknownRead()
                (unknown ?: LinkedHashMap<K, Unanswered<V>>().also { unknown = it })[key] = entry
                continue
            }
            entry?.value?.let(transform)?.let { result[key] = it }
        }
        val failed = unknown ?: return result
        if (unknownRead == UnknownReadMode.ABSENT) {
            return result
        }
        val first = failed.values.first().error
        throw CacheReadException(name, "getAll", first.cause ?: first, failed.keys.toSet(), result.toMap())
    }

    /**
     * 批量读取的结果换算为逐 key 的 [Lookup]：结果包含每个 key（key 过滤器判定不存在的为 `Absent`）。
     */
    internal fun lookupsOf(resolved: Map<K, LocalEntry<V>?>): Map<K, Lookup<V>> {
        val result = LinkedHashMap<K, Lookup<V>>(resolved.size)
        for ((key, entry) in resolved) {
            result[key] = entry?.let { lookupAnswerOf(it) } ?: Lookup.Absent(Instant.ofEpochMilli(clock.nowMillis()))
        }
        return result
    }

    /**
     * 以调用方给出的 [loader] 回源，与 [entryOf] 走同一条受 lease 与失效戳保护的路径（供 Spring `@Cacheable(sync = true)` 等使用）。
     * 外部来源的“未填充”副本挡不住调用方给出的 loader。L2 命中进入刷新窗口时同 [entryOf] 触发提前刷新：刷新用声明的数据源
     * （[refreshAsync]，没有声明的数据源时不刷新），调用方给出的 loader 只用于这次未命中。
     */
    internal fun entryWith(key: K, loader: CacheLoader<K, V>): LocalEntry<V> {
        val localKey = localKey(key)
        val entry = local.getIfPresent(localKey)
        if (entry != null && !entry.unfilled) {
            val now = clock.nowMillis()
            if (entry.expiresAt > now) {
                if (entry.refreshAt <= now) {
                    refreshAsync(key, localKey, entry)
                }
                countLocalHit()
                return entry
            }
        }
        return miss(
            key,
            storageKeyOfLocal(localKey),
            CoreSource(AsyncSource.Blocking(loader), LoadOperation.LOAD)
        )
    }

    internal fun entryOf(key: K): LocalEntry<V> {
        val localKey = localKey(key)
        val entry = local.getIfPresent(localKey)
        if (entry != null) {
            val now = clock.nowMillis()
            if (entry.expiresAt > now) {
                if (entry.refreshAt <= now) {
                    refreshAsync(key, localKey, entry)
                }
                countLocalHit()
                return entry
            }
        }
        return miss(key, storageKeyOfLocal(localKey))
    }

    /**
     * 未命中路径，与命中路径分开以保证 [entryOf] 足够小、可内联。同步变体：在调用方线程上运行协程核心（[loadMiss]）。
     */
    private fun miss(key: K, storageKey: String, source: CoreSource<K, V>? = loadSource): LocalEntry<V> {
        filteredEntry(storageKey)?.let { return it }
        return runSync(enterFill(source)) { loadMiss(key, storageKey, source) }
    }

    /**
     * 同步变体在调用入口（调用方线程）捕获写事务一次（RFC 0005 §4.9 S7），作为协程上下文元素（[FillContext]）交给未命中路径：
     * 门控与记录都只用它，不在填充时读线程绑定的状态。没有数据源的读取不回源，不需要它。
     */
    private fun enterFill(source: CoreSource<K, V>?): CoroutineContext {
        if (source == null) {
            return EmptyCoroutineContext
        }
        return fillObserver.enter(name)?.let { FillContext(it) } ?: EmptyCoroutineContext
    }

    /**
     * 这次调用在入口捕获的写事务；异步与挂起变体、提前刷新没有（不门控、不记录）。
     */
    private suspend fun fillScope(): FillScope? = currentCoroutineContext()[FillContext]?.scope

    internal fun fillKeyOf(key: Any): FillKey {
        @Suppress("UNCHECKED_CAST")
        return FillKey(this, localKey(key as K))
    }

    private fun fillKeyOfStorage(storageKey: String): FillKey = FillKey(this, localKeyOf(storageKey))

    /**
     * 机制 1（RFC 0005 §4.9）：数据源为这些 key 产生的值即将写入 L1 或 L2，先记入调用入口捕获的写事务。提前刷新、异步与挂起变体没有。
     */
    private suspend fun recordFills(storageKeys: Collection<String>) {
        val fill = fillScope() ?: return
        storageKeys.forEach { fill.filled(fillKeyOfStorage(it)) }
    }

    /**
     * 按编码后的 key 失效事务内记录的填充（[FillKey.invalidateAll]）：默认逐个 key 走 [invalidateEncoded]。
     * 有数据源、会记录填充的两个类型（`Cache`、`VersionedCache`）覆盖它，接受业务 key 编码出的任何 key（包括空串），
     * 不经运维入口的输入校验；权威副本没有数据源，不记录填充。
     */
    internal open fun invalidateEncodedKeys(encodedKeys: List<String>) {
        encodedKeys.forEach { invalidateEncoded(it) }
    }

    /**
     * key 过滤器判定不存在时的负缓存（不写任何层）；否则计入未命中并返回 `null`。
     */
    private fun filteredEntry(storageKey: String): LocalEntry<V>? {
        if (keyFilter?.notExist(storageKey) == true) {
            metrics.filtered()
            return LocalEntry(null, clock.nowMillis() + policy.absentTtl.toMillis())
        }
        metrics.miss()
        return null
    }

    /**
     * 未命中的加载（已计数、已过 key 过滤器）。同步与异步共用的协程核心（RFC 0005 V5）。
     */
    private suspend fun loadMiss(key: K, storageKey: String, source: CoreSource<K, V>?): LocalEntry<V> {
        val fill = fillScope()
        if (fill != null && source != null && fill.isPrivate(fillKeyOfStorage(storageKey))) {
            return loadPrivately(mapOf(storageKey to key), source).getValue(storageKey)
        }
        // 先于本次读取发生的失效此时必已递增戳：不接受开始于这些失效之前的共享加载（读己之写）
        val observed = stamps.current(storageKey)
        val hasSource = source != null
        val loaded = flights.executeSuspending(storageKey, scope) { loadThrough(key, storageKey, source).ownedBy(fill) }
        if (loaded.stamp >= observed && usable(loaded, hasSource, fill)) {
            return loaded.answer()
        }
        // 新一轮加载必然开始于上一轮结束之后（SingleFlight 先注销、再发布），因此至多重试一次
        val retried = flights.executeSuspending(
            storageKey,
            scope
        ) { loadThrough(key, storageKey, source).ownedBy(fill) }
        if (usable(retried, hasSource, fill)) {
            return retried.answer()
        }
        // 两次都合并到了不能接受的加载（不带数据源的加载，或写事务中的 leader）：自己回源，写回仍受戳保护
        return loadThrough(key, storageKey, source).answer()
    }

    /**
     * 私有回源（RFC 0005 §4.9 机制 2）：写事务内已登记失效的 key，或 `fill-in-transaction = private` 时的全部 key。
     * 不申请 lease、不读也不写 L1、不写 L2、不经 SingleFlight 共享：数据源的结果只交给这次调用（到期时间为现在，不保存任何副本），
     * 计入 `fillsGated`。回源照常受许可与观测约束；本线程对这些 key 的重入快速失败（[SingleFlight.loadingPrivately]）。
     *
     * @param keys 存储 key → 业务 key
     */
    private suspend fun loadPrivately(keys: Map<String, K>, source: CoreSource<K, V>): Map<String, LocalEntry<V>> {
        metrics.fillGated(keys.size)
        return flights.loadingPrivately(keys.keys) {
            val now = clock.nowMillis()
            if (keys.size == 1) {
                val (storageKey, key) = keys.entries.single()
                mapOf(storageKey to LocalEntry(loadValue(key, storageKey, source), now))
            } else {
                val values = loadAllValues(keys.values.toList(), keys.keys, source)
                keys.mapValues { (_, key) -> LocalEntry(values[key], now) }
            }
        }
    }

    /**
     * 合并规则（RFC 0005 §4.1、§4.9）：
     * - 写事务中的 leader（[Loaded.owner]）的结果只交给它自己：跟随者不在它的事务内，经 flight 拿到未提交的值，回滚之后撤不回（S15）。
     * - 带数据源的调用方不接受“未填充”（[LocalEntry.unfilled]）与 `Unknown`（[Unanswered]）：它们来自不带数据源的读取，
     *   这次调用本可以自己回源。例外是 `readFailure = THROW`：带数据源的读取遇到同样的故障也只能得到 `Unknown`。
     */
    private fun usable(loaded: Loaded<V>, hasSource: Boolean, fill: FillScope?): Boolean {
        if (loaded.owner != null && loaded.owner !== fill) {
            return false
        }
        val entry = loaded.entry
        if (!hasSource || !entry.unfilled) {
            return true
        }
        return entry is Unanswered && readFailure == ReadFailureMode.THROW
    }

    /**
     * 一次受 lease 与失效戳保护的加载，在 SingleFlight 注册之后执行。
     * 协程被取消（运行时关闭、异步执行器拒绝派发）时尽力释放申请的 lease：令牌不匹配时释放什么也不做。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadThrough(key: K, storageKey: String, source: CoreSource<K, V>?): Loaded<V> {
        // 在 SingleFlight 注册之后取戳；整个调用（含等待 lease 的重读）只取这一次
        val stamp = stamps.current(storageKey)
        if (pendingInvalidations.containsKey(storageKey)) {
            return Loaded(loadBypassingRemote(key, storageKey, stamp, source), stamp)
        }
        // 权威副本不申请 lease（RFC 0005 §4.1，C11）：没有数据源可以回源，lease 只会让其它节点白等
        val lease = if (authoritative) null else LeaseRequest(context.nextToken(), policy.lease.ttl)
        // 读取之前取时刻（#877）：读到的剩余寿命从这一刻算起，L2 副本不活过 L1；等待 lease 时换成找到结果的那次重读之前的时刻
        var readAt = clock.nowMillis()
        val read = try {
            val first = readRemote(storageKey, lease, null)
            if (first is L1Read.Leased && lease != null) {
                val waited = readWaitingLease(storageKey, lease, first)
                readAt = waited.readAt
                waited.read
            } else {
                first
            }
        } catch (e: Exception) {
            rethrowIfFatal(e)
            if (source == null || readFailure == ReadFailureMode.THROW) {
                return Loaded(unanswered("read", storageKey, e), stamp)
            }
            onRemoteFailure("read", storageKey, e)
            return Loaded(loadBypassingRemote(key, storageKey, stamp, source), stamp)
        }
        val entry = when (read) {
            is L1Read.Hit -> onHit(key, storageKey, read, stamp, lease, source, readAt)
            is L1Read.Miss -> {
                val token = lease?.token?.takeIf { read.leaseGranted }
                loadAndFill(key, storageKey, stamp, token, source)
            }
            is L1Read.Leased -> if (lease == null) {
                // 权威副本读到 lease 占位就是不存在（同未命中）
                loadAndFill(key, storageKey, stamp, null, source)
            } else {
                metrics.leaseWaitTimeout()
                loadBypassingRemote(key, storageKey, stamp, source)
            }
        }
        return Loaded(entry, stamp)
    }

    /**
     * 协程核心中不能按 L1 故障降级的异常：取消（运行时关闭、执行器拒绝派发、同步变体的线程被中断）；中断（同步变体中有观测器时，
     * 等待 lease 在嵌套的 `runSync` 中被中断）；异步变体在运行时关闭之后的失败。中断不是 L1 故障，
     * 不能降级为绕过 L1 回源。
     */
    private suspend fun rethrowIfFatal(error: Exception) {
        val interrupted = error is CancellationException || error is InterruptedException
        if (interrupted || (!runsInline() && context.async.closed)) {
            throw error
        }
    }

    /**
     * 第一次读取遇到其它节点的 lease（[leased]）：以短退避重读，最多等待 `min(剩余寿命, lease.wait)`，沿用同一个令牌。
     */
    private suspend fun readWaitingLease(storageKey: String, lease: LeaseRequest, leased: L1Read.Leased): LeaseWait {
        metrics.leaseWait()
        return observedLoad(LoadOperation.LEASE_WAIT, 1) { awaitLease(storageKey, lease, leased) }
    }

    /**
     * 退避之后重读：挂起（`delay`）。同步变体停在调用方线程的事件循环上，线程被中断时立即结束；异步变体不占线程，运行时关闭时随作用域取消。
     * 每次重读之前取时刻，结果带上最后一次重读的时刻（[LeaseWait.readAt]）：命中的寿命从它算起，不从第一次读取算起。
     */
    private suspend fun awaitLease(storageKey: String, lease: LeaseRequest, leased: L1Read.Leased): LeaseWait {
        var read: L1Read = leased
        var readAt = clock.nowMillis()
        val deadline = leaseDeadline(leased)
        var backoff = INITIAL_BACKOFF_NANOS
        while (read is L1Read.Leased) {
            val remaining = deadline - ticker.nanoTime()
            if (remaining <= 0) {
                break
            }
            delay(minOf(backoff, remaining).nanoseconds)
            readAt = clock.nowMillis()
            read = readRemote(storageKey, lease, null)
            backoff = nextBackoff(backoff)
        }
        return LeaseWait(read, readAt)
    }

    private fun leaseDeadline(leased: L1Read.Leased): Long = ticker.nanoTime() + minOf(leased.remaining, policy.lease.wait).toNanos()

    private suspend fun onHit(
        key: K,
        storageKey: String,
        read: L1Read.Hit,
        stamp: Long,
        lease: LeaseRequest?,
        source: CoreSource<K, V>?,
        readAt: Long
    ): LocalEntry<V> {
        return when (val outcome = hitOutcome(storageKey, read)) {
            HitOutcome.Newer -> {
                newerFormatFailure(storageKey, read)?.let { throw it }
                metrics.unknownFormat()
                // 读到未知标志位的副本不设刷新点（P14）：刷新只会再读到它、再绕过 L1 回源一次
                loadBypassingRemote(key, storageKey, stamp, source, refreshable = false)
            }

            is HitOutcome.Corrupt -> {
                // 没有数据源的读取在这里失败；带数据源的读取一定是派生副本，读取申请了 lease
                foreignFormatFailure(storageKey, read, outcome.error, source != null)?.let { throw it }
                onCorrupt(key, storageKey, read, stamp, requireNotNull(lease), source, outcome.error)
            }

            is HitOutcome.Decoded -> onRemoteHit(storageKey, outcome.value, read, stamp, readAt)
        }
    }

    /**
     * 同步与异步共用：读到读不懂的内容（格式号低于本节点格式的内容，含不是本协议写入的格式 0，或同格式解码失败）时按这次读取有没有数据源决定
     * （RFC 0005 §4.7）。
     * 有数据源时返回 `null`，调用方照常条件回收（回收后回源）；没有数据源时 L1 是唯一副本，回收就是删除：
     * 返回要抛出的异常（已计入 `unknownFormats`，不修改 L1、不写 L2）。与 `readFailure` 无关。
     */
    private fun foreignFormatFailure(
        storageKey: String,
        read: L1Read.Hit,
        error: Exception,
        hasSource: Boolean
    ): CacheReadException? {
        if (hasSource) {
            return null
        }
        return readFailure(ForeignFormatException(name, storageKey, read.format, backendFormat, error))
    }

    /**
     * 同步与异步共用：读到更高的格式（[isNewerFormat]：滚动升级期间，或未知的必须理解的位），既不回收也不写回。
     * 派生副本返回 `null`：有数据源时调用方绕过 L1 回源，外部来源时绕过 L1 只能得到“未填充”（RFC 0005 §4.1）。
     * 权威副本的 L1 是唯一副本，绕过 L1 只能得到“不存在”：返回要抛出的异常（已计入 `unknownFormats`，不修改 L1、不写 L2）。
     */
    private fun newerFormatFailure(storageKey: String, read: L1Read.Hit): CacheReadException? {
        if (!authoritative) {
            return null
        }
        return readFailure(ForeignFormatException(name, storageKey, read.format, backendFormat, null))
    }

    private fun readFailure(failure: ForeignFormatException): CacheReadException {
        metrics.unknownFormat()
        log.warn { failure.message }
        return CacheReadException(name, "read", failure)
    }

    /**
     * 同步与异步共用的命中判定：更高的格式（[isNewerFormat]）既不回收也不写回；格式号低于本节点格式的内容（不是本格式写入的）、
     * 摘要校验失败（[verifyDigest]）或解码失败按损坏处理。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun hitOutcome(storageKey: String, read: L1Read.Hit): HitOutcome<V> {
        if (isNewerFormat(read.format)) {
            return HitOutcome.Newer
        }
        return try {
            check(
                read.format == backendFormat
            ) { "Unrecognized remote content (format[${read.format}], not written in this node's format)." }
            verifyDigest(storageKey, read.envelope)
            HitOutcome.Decoded(decode(read.envelope))
        } catch (e: Exception) {
            HitOutcome.Corrupt(e)
        }
    }

    /**
     * 读取方的摘要校验（RFC 0005 §4.7，K3；同步与异步共用，对所有后端相同）：权威副本（[authoritative]）从 L1 读到的不带版本的值
     * 必须带摘要段，且与载荷重算的 SHA-256 一致；`Store` 读到带版本的值同样不接受。否则抛出，调用方按损坏处理（权威副本没有数据源，读取失败）。
     * 派生副本读到带摘要段的值是类型不符（D4）：计入 `typeMismatches` 并告警，照常读取。成本只在 L1 读取上，不在 L2 命中上。
     */
    private fun verifyDigest(storageKey: String, envelope: Envelope) {
        digestProblem(envelope)?.let { throw IllegalStateException("Cache[$name] - key[$storageKey] - $it") }
        if (!authoritative && (envelope as? Envelope.Scalar)?.digest != null) {
            typeMismatch(storageKey)
        }
    }

    /**
     * 权威副本读到的值的问题（不计数，运维查看也用它）；没有问题时为 `null`。负缓存没有载荷，不校验；带版本的值不带摘要段，
     * 只有带版本的类型（`VersionedStore`）读到它是正常的，`Store` 读到它说明同一 namespace 上另有带版本的类型，按读不懂处理。
     */
    private fun digestProblem(envelope: Envelope): String? {
        if (!authoritative || envelope !is Envelope.Scalar) {
            return null
        }
        val digest = envelope.digest
        if (digest == null) {
            return when {
                envelope.version == Versioned.NO_VERSION ->
                    "value without a digest segment in an authoritative cache (not written by a Store)."
                versionedValues -> null
                else -> "versioned value in a Store (written by a VersionedCache or a VersionedStore)."
            }
        }
        return if (Digest.of(envelope) == digest) null else "payload does not match its digest segment."
    }

    private fun typeMismatch(storageKey: String) {
        typeMismatch {
            "Cache[$name] - key[$storageKey] - holds a value written by a Store (digest segment): " +
                "the namespace is shared with an authoritative cache. It is read, but never reclaimed or refreshed."
        }
    }

    /**
     * 类型不符（D4）：计入 `typeMismatches`；每个缓存只在第一次告警，之后记为 DEBUG（命名空间配置错误时每次读取或 `evict` 都会遇到它）。
     */
    private fun typeMismatch(message: () -> Any?) {
        metrics.typeMismatch()
        if (typeMismatchWarned.compareAndSet(false, true)) log.warn(message) else log.debug(message)
    }

    /**
     * 带版本的缓存的 L2 不按版本后退（RFC 0005 I-K 的 L2 条款，TLA+ F3）：L2 中未过期的副本的版本大于要填充的值的版本时保留它。
     * 能走到这里的填充，其戳在读取之后没有失效：本节点没有处理过这个 key 的写入或失效，已有的副本不是被失效的内容。
     * 更旧的值来自落后的 loader（读从库）与没有写入 L1 的回源：刷新的填充超时、令牌被抢占，或版本拒绝（[keepRefreshedCopy]
     * 只覆盖刷新路径上读得到 L1 副本的情形）。没有版本的值（[Versioned.NO_VERSION]、负缓存与墓碑读出的 `null`）排在任何版本之前，
     * 所以协议之外抹掉版本的变化（L1 到期、`purge`）不经刷新替换版本更新的副本，由它自己的限龄兜底。
     *
     * 保留的副本已过刷新点时（这次填充就是它触发的刷新），刷新点按 [refreshBackoff] 退避，同 [keepRefreshedCopy]：
     * 否则此后每次命中都再触发一次刷新、再读一次落后的数据源。
     */
    override fun keptCopy(current: LocalEntry<V>, entry: LocalEntry<V>): LocalEntry<V>? {
        val now = clock.nowMillis()
        if (!versionedValues || current.expiresAt <= now || versionOf(current.value) <= versionOf(entry.value)) {
            return null
        }
        if (current.refreshAt > now) {
            return current
        }
        return LocalEntry(current.value, current.expiresAt, minOf(now + refreshBackoff, current.expiresAt))
    }

    private fun versionOf(value: V?): Long = value?.let { format.versionOf(it) } ?: Versioned.NO_VERSION

    /**
     * 带摘要段的值（权威标量）不设刷新点：`READ` 从不为它授予刷新令牌（D4），刷新只会一次次重读 L1。
     */
    private fun refreshable(envelope: Envelope): Boolean = (envelope as? Envelope.Scalar)?.digest == null

    /**
     * L1 命中：副本的寿命从发出读取之前的时刻 [readAt] 算起（批量读取整批共用一个），解码耗费的时间从中扣除（#877）。
     */
    private fun onRemoteHit(storageKey: String, value: V?, read: L1Read.Hit, stamp: Long, readAt: Long): LocalEntry<V> {
        metrics.remoteHit()
        val entry = localEntry(
            value,
            read.ttl,
            policy.localMaxAge,
            refreshable = refreshable(read.envelope),
            since = readAt
        )
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    /**
     * 同版本载荷损坏：仅当内容仍是读到的那一份时转为 lease（条件回收，读路径从不无条件删除），再正常回源。批量读取也经这里。
     * 指纹带读到的格式（[ContentFingerprint.of]）：读取之后按本格式写入的同一段文本不是读到的那一份，不被回收（#834）。
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun onCorrupt(
        key: K,
        storageKey: String,
        read: L1Read.Hit,
        stamp: Long,
        lease: LeaseRequest,
        source: CoreSource<K, V>?,
        error: Exception
    ): LocalEntry<V> {
        if ((read.envelope as? Envelope.Scalar)?.digest != null) {
            // 带摘要段的内容（权威标量，D4）从不回收：不发出注定被拒绝的 RECLAIM，不计入 reclaims（已计入 typeMismatches），
            // 绕过 L1 回源，副本不设刷新点（P14：刷新只会再读到它）
            log.warn(error) {
                "Cache[$name] - key[$storageKey] - undecodable payload written by a Store (digest segment): kept as is, " +
                    "reload bypassing the remote store."
            }
            return loadBypassingRemote(key, storageKey, stamp, source, refreshable = false)
        }
        logCorrupt(storageKey, error)
        val reclaimed = try {
            reclaimRemote(storageKey, ContentFingerprint.of(read.envelope, read.format), lease)
        } catch (e: Exception) {
            rethrowIfFatal(e)
            if (readFailure == ReadFailureMode.THROW) {
                return unanswered("reclaim", storageKey, e)
            }
            onRemoteFailure("reclaim", storageKey, e)
            false
        }
        if (!reclaimed) {
            return loadBypassingRemote(key, storageKey, stamp, source)
        }
        return loadAndFill(key, storageKey, stamp, lease.token, source)
    }

    private suspend fun reclaimRemote(storageKey: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean =
        uncancellable { store.reclaim(storageKey, observed, lease) }

    /**
     * 将要回收一份损坏的载荷：告警并计入 `reclaims`（每次发出 `RECLAIM` 计一次，回收是否成功都计）。
     */
    private fun logCorrupt(storageKey: String, error: Exception) {
        log.warn(error) { "Cache[$name] - key[$storageKey] - corrupted payload, reclaim and reload." }
        metrics.reclaim()
    }

    private fun decode(envelope: Envelope): V? = when (envelope) {
        is Envelope.Scalar -> format.decode(envelope)
        Envelope.Absent -> null
        is Envelope.Members -> throw IllegalStateException("Cache[$name] - expected a scalar but found a set.")
    }

    /**
     * 写入 L1 的内容（RFC 0005 §4.7“写入哪些段”）：权威副本（[authoritative]）的值带一个新的写入标识，`Store` 的值另带载荷的摘要段
     * （[writesDigest]，D1）；带版本的值从不带摘要段。派生副本的值不带任何段（L1 头部保持 36 字节）。
     */
    private fun envelopeOf(
        value: V?
    ): Envelope = value?.let { format.envelopeOf(it, writerId(), writesDigest) } ?: Envelope.Absent

    private fun encode(value: V): Envelope.Scalar = format.encode(value, writerId(), writesDigest)

    /**
     * 一次标量写入的写入标识（与令牌同形，全局唯一）；派生副本为 `null`。
     */
    private fun writerId(): Token? = if (authoritative) context.nextToken() else null

    /**
     * 回源写回 L1 的填充：与内容一起带上值的版本（带版本的缓存；墓碑的版本只能经 [Fill.version] 传递）。
     */
    private fun fillOf(storageKey: String, token: Token, value: V?, envelope: Envelope, ttl: Duration?): Fill =
        Fill(storageKey, token, envelope, ttl, value?.let { format.versionOf(it) } ?: Versioned.NO_VERSION)

    private fun ttlOf(key: K, value: V?): Duration? {
        if (value == null) {
            return policy.absentTtl
        }
        if (format.isTombstone(value)) {
            return policy.tombstoneTtl()?.boundedTtl()
        }
        return (spec.ttlOf?.ttlOf(key, value)?.let(::perCallTtl) ?: policy.valueTtl())?.boundedTtl()
    }

    /**
     * 回源并凭 [token] 写回 L1。没有令牌或写回被拒绝（期间发生失效、或 loader 慢于 lease 寿命）时，
     * 结果照常返回，L2 以降级限龄填充（否则慢 key 会一直未命中）。失败时等释放完成之后再抛出原始异常。
     *
     * 版本规则（RFC 0005 §4.7）：写回让版本前进（或填充了 `E`）是一次写入（[FillOutcome.ADVANCED]），本节点按写入处理、不填 L2；
     * 带版本的写回结果未知（[FillOutcome.UNKNOWN]）时同样处理：它可能已经或稍后让版本前进，而那一帧在本节点被过滤（TLA+ F2）；
     * 版本拒绝时，未命中路径（lease 在 `E` 上）照常返回回源值、以降级限龄写入 L2 且不设刷新点，刷新路径（[refreshed] 是取得刷新令牌的
     * 那次读取与它的 L1 到期时刻）不写回源得到的旧版本，保留 L1 中的副本并退避，副本不活过 L1（[keepRefreshedCopy]）。
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun loadAndFill(
        key: K,
        storageKey: String,
        stamp: Long,
        token: Token?,
        source: CoreSource<K, V>?,
        refreshed: RefreshStep.LoadWithToken? = null
    ): LocalEntry<V> {
        if (source == null) {
            token?.let { releaseToken(storageKey, it) }
            return absentWithoutLoader(storageKey, stamp)
        }
        // 计算寿命与编码（用户的 ttlOf、Codec）与记入写事务（公开的 FillScope.filled，先于任何共享层的填充）也可能抛出：
        // 与回源一起释放 lease（I-F）
        val value: V?
        val prepared: Prepared
        try {
            value = loadValue(key, storageKey, source)
            prepared = prepare(key, value)
            recordFills(listOf(storageKey))
        } catch (e: Throwable) {
            token?.let { releaseToken(storageKey, it) }
            throw e
        }
        // 发出写回之前取时刻：L1 从执行 `FILL` 时开始计算寿命，回复晚到的时间从副本的寿命中扣除（#877）
        val filledAt = clock.nowMillis()
        val outcome = token?.let {
            fillRemote(fillOf(storageKey, it, value, prepared.envelope, prepared.ttl))
        } ?: FillOutcome.NONE
        if (outcome == FillOutcome.VERSION_REJECTED) {
            unversionedAbsentRefused(storageKey, value)
            if (refreshed != null) {
                keepRefreshedCopy(storageKey, refreshed, stamp)?.let { return it }
            }
        }
        val entry = completeLoad(storageKey, stamp, token, value, prepared.ttl, outcome, filledAt)
        if (outcome == FillOutcome.UNKNOWN) {
            // 先于递增戳登记：迟到的 FILL 在登记之前执行的话，它的帧被过滤，但随后的递增戳挡住已经读过 L1 的读取者（P12）
            unfilterOwnFrames(listOf(storageKey))
        }
        if (outcome.possiblyAdvanced) {
            afterAdvancedFill(listOf(storageKey))
        }
        return entry
    }

    /**
     * 刷新路径的版本拒绝（RFC 0005 §4.7）：L1 中仍是取得令牌时读到的那个更新的版本（脚本已清零令牌），回源得到的更旧的值不写 L2，
     * 否则它会替换掉 L2 中的新值。改为以读到的值重新填充 L2，刷新点按退避（同没取得令牌的 [refill]）。读到的值解码失败时返回 `null`，
     * 调用方按未命中路径降级。
     *
     * 版本拒绝不改变 L1 的到期时间，保留的副本因此以取得令牌时读到的到期时刻（[RefreshStep.LoadWithToken.expiresAt]）为界：剩余寿命扣除
     * 回源期间消耗的时间，L2 不活过 L1（§5）。回源期间副本已经到期时不再填入 L2，读到的值只交给合并到这次刷新的调用方（到期时间为现在）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun keepRefreshedCopy(
        storageKey: String,
        refreshed: RefreshStep.LoadWithToken,
        stamp: Long
    ): LocalEntry<V>? {
        val read = refreshed.read
        val value = try {
            decode(read.envelope)
        } catch (e: Exception) {
            return null
        }
        metrics.fillRejected()
        val now = clock.nowMillis()
        if (refreshed.expiresAt <= now) {
            metrics.refreshSkipped()
            return LocalEntry(value, now)
        }
        val remaining = if (refreshed.expiresAt == FOREVER) null else Duration.ofMillis(refreshed.expiresAt - now)
        return refill(storageKey, RefreshStep.Refill(value, remaining, now, refreshable(read.envelope)), stamp)
    }

    /**
     * K16（RFC 0005 §4.1）：带版本的数据源返回了不带版本的 `null`（“从来没有这个 key”），落在带版本的内容上（`E` 或带版本的值）被拒绝。
     * 带版本的数据源必须以 `Versioned(null, w)` 表达删除：否则已删除的实体在 `E` 到期之前每次未命中都回源，没有负缓存；
     * 刷新路径保留旧副本到 L1 的 `ttl`。计数，并在第一次时告警。
     */
    private fun unversionedAbsentRefused(storageKey: String, value: V?) {
        if (!versionedValues || value != null) {
            return
        }
        metrics.unversionedAbsentRefused()
        val message = {
            "Cache[$name] - key[$storageKey] - the versioned loader returned null (\"never existed\") for a key holding versioned content: " +
                "L1 refused it. Express a deletion as Versioned(null, version), or every miss reloads until the version fence expires."
        }
        if (unversionedAbsentWarned.compareAndSet(false, true)) log.warn(message) else log.debug(message)
    }

    private class Prepared(val ttl: Duration?, val envelope: Envelope)

    private fun prepare(key: K, value: V?): Prepared = Prepared(ttlOf(key, value), envelopeOf(value))

    /**
     * 同步与异步共用：回源结果写回 L1 之后（或没有令牌时）的计数与 L2 填充。
     * - [FillOutcome.FILLED]：L2 限龄 `localMaxAge`；
     * - [FillOutcome.ADVANCED]：这次填充是一次写入，不填 L2（调用方随后 [afterAdvancedFill]），返回回源值；
     * - [FillOutcome.UNKNOWN]：同上，不填 L2、返回回源值，计入 `fillRejected`（填充没有确认写入）；
     * - 其余：L2 以降级限龄填充；版本拒绝（lease 在 `E` 上）的副本不设刷新点（P14：刷新只会再读到 `E`）。
     *
     * 寿命从发出写回之前的时刻 [filledAt] 算起（[localEntry]）。
     */
    @Suppress("LongParameterList")
    private fun completeLoad(
        storageKey: String,
        stamp: Long,
        token: Token?,
        value: V?,
        ttl: Duration?,
        outcome: FillOutcome,
        filledAt: Long,
        refreshable: Boolean = true
    ): LocalEntry<V> {
        if (outcome == FillOutcome.ADVANCED) {
            return localEntry(value, ttl, policy.localMaxAge, since = filledAt)
        }
        if (outcome == FillOutcome.UNKNOWN) {
            metrics.fillRejected()
            return localEntry(value, ttl, policy.degradedLocalMaxAge, since = filledAt)
        }
        val filled = outcome == FillOutcome.FILLED
        if (token != null && !filled) {
            metrics.fillRejected()
        }
        if (!filled) {
            metrics.degradedFill()
        }
        val entry = localEntry(
            value,
            ttl,
            if (filled) policy.localMaxAge else policy.degradedLocalMaxAge,
            refreshable = refreshable && outcome != FillOutcome.VERSION_REJECTED,
            since = filledAt
        )
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    /**
     * 没有数据源的读取在 L1 中没有值：不把负缓存写入 L1（否则会挡住其它节点的读取直到 absentTtl），只在 L2 中记下（限龄 `absentTtl`
     * 与 `localMaxAge`）。调用方先释放 lease。
     * - 权威副本：L1 是唯一副本，这就是 `Absent`（RFC 0005 §4.1）。
     * - 外部来源的派生副本：“未填充”（[LocalEntry.unfilled]），不带 loader 的读取看到不存在，带 loader 的调用方不接受它。
     */
    private fun absentWithoutLoader(storageKey: String, stamp: Long): LocalEntry<V> {
        val absent = localEntry(null, policy.absentTtl, policy.localMaxAge)
        val entry = if (authoritative) {
            absent
        } else {
            LocalEntry<V>(null, absent.expiresAt, absent.refreshAt, unfilled = true)
        }
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    /**
     * 不经过 L1 的回源：L1 故障、等待 lease 超时、未知格式、失效重试中、回收失败。L2 以降级限龄填充（仍受戳保护）。
     *
     * @param refreshable 是否给副本设刷新点：读到更高格式（未知标志位）的副本不设（P14）
     */
    private suspend fun loadBypassingRemote(
        key: K,
        storageKey: String,
        stamp: Long,
        source: CoreSource<K, V>?,
        refreshable: Boolean = true
    ): LocalEntry<V> {
        val value = loadValue(key, storageKey, source)
        if (source != null) {
            recordFills(listOf(storageKey))
        }
        return completeBypass(key, storageKey, stamp, value, source != null, refreshable)
    }

    /**
     * 同步与异步共用。没有数据源时绕过 L1 只能得到“不存在”：它只是没有答案（“未填充”，带 loader 的读取不接受它），
     * 不是已决的负缓存。L1 读失败不走这里，见 [unanswered]。
     */
    @Suppress("LongParameterList")
    private fun completeBypass(
        key: K,
        storageKey: String,
        stamp: Long,
        value: V?,
        hasSource: Boolean,
        refreshable: Boolean
    ): LocalEntry<V> {
        metrics.degradedFill()
        val degraded = localEntry(value, ttlOf(key, value), policy.degradedLocalMaxAge, refreshable = refreshable)
        val entry = if (hasSource) degraded else LocalEntry<V>(null, degraded.expiresAt, unfilled = true)
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    /**
     * 这一次读取没有答案（RFC 0005 §4.1，V2）：L1 读失败，而读取没有数据源（权威副本、外部来源的缓存上不带 loader 的读取），
     * 或显式 `readFailure = THROW`。结果是 `Unknown`（[Unanswered]）：不写入任何一层，经 SingleFlight 共享时带数据源的调用方不接受它
     * （[usable]），改用自己的数据源；调用方返回之前按 `unknownRead` 换算（[answerOf]）。计入 `remoteFailures`。
     */
    private fun unanswered(operation: String, storageKey: String, error: Exception): Unanswered<V> {
        metrics.remoteFailure()
        log.warn(
            error
        ) { "Cache[$name] - key[$storageKey] - remote $operation failed: the read has no answer (Unknown)." }
        return Unanswered(CacheReadException(name, operation, error))
    }

    /**
     * [unanswered] 的批量版本：整批计数一次，共享一个结果。
     */
    private fun unansweredAll(
        keys: Collection<String>,
        loadStamps: Map<String, Long>,
        error: Exception
    ): Map<String, Loaded<V>> {
        val unknown = unanswered("readAll", keys.first(), error)
        return keys.associateWith { Loaded(unknown, loadStamps.getValue(it)) }
    }

    /**
     * 令牌保护的填充（[fillOutcomeOf]）；L1 失败时只计数告警，结果见 [failedFillOutcome]。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun fillRemote(fill: Fill): FillOutcome {
        return try {
            fillOutcomeOf(uncancellable { store.fill(fill) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onRemoteFailure("fill", fill.key, e)
            failedFillOutcome(fill, e)
        }
    }

    /**
     * 失败的填充（TLA+ F2）：带版本的填充按结果未知处理（[FillOutcome.UNKNOWN]）。超时或连接替换时被排空的 `FILL` 可能稍后才执行，
     * 让版本前进并广播，而那一帧在本节点被过滤；所以本节点现在就按写入递增戳、淘汰本地副本（[afterAdvancedFill]），读到旧版本的同节点
     * 读取者不能再把它写进 L2。多做的一次淘汰只多一次 L1 往返。存储在写出之前就拒绝的填充（[notExecuted]，如断线期间的
     * `L1RejectedException`）确定没有执行，也不会迟到执行，为 [FillOutcome.NONE]。没有版本的填充从不广播，迟到执行也只是
     * lease 上的写入，照常为 [FillOutcome.NONE]。
     */
    private fun failedFillOutcome(fill: Fill, error: Exception): FillOutcome =
        if (fill.version != Versioned.NO_VERSION && !notExecuted(error)) FillOutcome.UNKNOWN else FillOutcome.NONE

    /**
     * 协程核心释放令牌（失败只计数告警），完成之后才继续。释放不可取消（[uncancellable]）：被取消的加载也要交还令牌。
     */
    private suspend fun releaseToken(storageKey: String, token: Token) {
        uncancellable { releaseQuietly(storageKey, token) }
    }

    /**
     * 释放令牌，失败只计数告警（调用方已在 `NonCancellable` 中）。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun releaseQuietly(storageKey: String, token: Token) {
        try {
            store.release(storageKey, token)
        } catch (e: Exception) {
            onRemoteFailure("release", storageKey, e)
        }
    }

    private fun onRemoteFailure(operation: String, storageKey: String, error: Exception) {
        metrics.remoteFailure()
        log.warn(error) { "Cache[$name] - key[$storageKey] - remote $operation failed, degrading." }
    }

    /**
     * 协程核心的单 key 回源（同步与异步共用）：许可、耗时统计与观测（[guardedLoad]）包住数据源调用（[invoke]）。
     *
     * 调用级的挂起 loader 另加重入记录（[LoadingKeys]，K7），在许可与观测之外装上（#876）：`withContext` 改变上下文时重新装上
     * 上下文中的全部 `ThreadContextElement`，在观测器设置了它的线程局部状态（Micrometer 的当前观测）之后再装，会把它盖回调用方的状态。
     */
    private suspend fun loadValue(key: K, storageKey: String, source: CoreSource<K, V>?): V? {
        val sourceLoader = source ?: return null
        if (sourceLoader.source is AsyncSource.Suspending) {
            val loading = LoadingKeys.adding(currentCoroutineContext()[LoadingKeys], this, listOf(key))
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
                    context.async.executor
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
        val acquired = withTimeoutOrNull(policy.load.acquireTimeout.toMillis()) {
            semaphore.acquire()
            true
        }
        if (acquired == null) {
            metrics.loadRejected()
            throw SourceLoadRejectedException(name, policy.load)
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
     */
    private suspend fun <R> observedLoad(operation: LoadOperation, keyCount: Int, block: suspend () -> R): R {
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
                    // 外层协程不挂起，只为在调用观测器期间装上调用方的 ThreadContextElement；block 是它的子 Job
                    scope.future(caller, CoroutineStart.UNDISPATCHED) {
                        val outer = this.coroutineContext
                        observer.observe(name, operation, keyCount) {
                            startInPlace(outer) { block().also { produced.value = it } }.joinOriginal()
                        }
                    }.joinOriginal()
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
     * 批量读取（同步变体）：每个不重复的 key → 副本；key 过滤器判定不存在的 key 为 `null`。结果由子类换算（[valuesOf]、[lookupsOf]）。
     */
    internal fun resolveAll(keys: Collection<K>): Map<K, LocalEntry<V>?> {
        val resolved = LinkedHashMap<K, LocalEntry<V>?>()
        val pending = collectPending(keys, resolved)
        if (pending.isNotEmpty()) {
            for ((storageKey, entry) in runSync(enterFill(loadSource)) { loadPending(pending, loadSource) }) {
                pending.getValue(storageKey).forEach { resolved[it] = entry }
            }
        }
        return resolved
    }

    /**
     * 批量读取（异步变体）：语义同 [resolveAll]，全部命中 L2 时返回已完成的 future。
     */
    internal fun <R> resolveAllAsync(keys: Collection<K>, map: (Map<K, LocalEntry<V>?>) -> R): CompletableFuture<R> =
        Futures.attempt {
            val resolved = LinkedHashMap<K, LocalEntry<V>?>()
            val pending = collectPending(keys, resolved)
            if (pending.isEmpty()) {
                return@attempt CompletableFuture.completedFuture(map(resolved))
            }
            context.async.runAsync {
                for ((storageKey, entry) in loadPending(pending, loadSource)) {
                    pending.getValue(storageKey).forEach { resolved[it] = entry }
                }
                map(resolved)
            }
        }

    /**
     * 批量读取的 L2 阶段（同步、异步与挂起变体共用）：命中的 key 填入 [resolved]；返回未命中的存储 key → 映射到它的业务 key
     * （KeyEncoder 可能把不同 key 映射为同一个存储 key）。
     *
     * 整批拒绝（#866）：遍历只读 L2 并检查未命中的 key（[storageKeyOfLocal]；命中的 key 进入 L2 之前已检查过），没有副作用。
     * 整批都检查过之后才计数、经 key 过滤器判定、触发提前刷新（[refreshAsync]）：含不能无损编码的 key 的批量因此在任何 key
     * 回源之前抛出，与 key 的顺序无关。命中路径上只多一个整批一次的计数，进入刷新窗口的 key 才分配列表。
     */
    private fun collectPending(
        keys: Collection<K>,
        resolved: MutableMap<K, LocalEntry<V>?>
    ): LinkedHashMap<String, MutableList<K>> {
        val pending = LinkedHashMap<String, MutableList<K>>()
        var hits = 0
        var refreshes: MutableList<K>? = null
        for (key in keys) {
            if (resolved.containsKey(key)) {
                continue
            }
            val localKey = localKey(key)
            val entry = local.getIfPresent(localKey)
            if (entry != null) {
                val now = clock.nowMillis()
                if (entry.expiresAt > now) {
                    resolved[key] = entry
                    hits++
                    if (entry.refreshAt <= now) {
                        (refreshes ?: ArrayList<K>(1).also { refreshes = it }).add(key)
                    }
                    continue
                }
            }
            resolved[key] = null
            pending.getOrPut(storageKeyOfLocal(localKey)) { ArrayList(1) }.add(key)
        }
        countLocalHits(hits)
        refreshes?.forEach { refreshAsync(it, localKey(it), requireNotNull(resolved[it])) }
        if (pending.isNotEmpty()) {
            countPending(pending)
        }
        return pending
    }

    /**
     * [collectPending] 中未命中的 key 计入未命中，key 过滤器判定不存在的计入 `filtered` 并移出 [pending]（结果中为 `null`）。
     */
    private fun countPending(pending: MutableMap<String, MutableList<K>>) {
        val filter = keyFilter
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (storageKey, sourceKeys) = iterator.next()
            if (filter?.notExist(storageKey) == true) {
                repeat(sourceKeys.size) { metrics.filtered() }
                iterator.remove()
            } else {
                repeat(sourceKeys.size) { metrics.miss() }
            }
        }
    }

    /**
     * 批量加载未命中的 key（同步与异步共用的协程核心）：与在途的单 key 加载合并。合并规则逐 key 应用（RFC 0005 §4.1），
     * 与单 key 路径相同：共享到的加载开始于本次读取已观察到的失效之前、结果不可用（带数据源的读取不接受未决的结果，[usable]），
     * 或合并到的批量没有这个 key 的结果（含合并到的 leader 被中断或取消），都改走单 key 路径。
     */
    private suspend fun loadPending(
        pending: Map<String, List<K>>,
        source: CoreSource<K, V>?
    ): Map<String, LocalEntry<V>> {
        val fill = fillScope()
        if (fill == null || source == null) {
            return loadShared(pending, source, null)
        }
        val private = pending.keys.filter { fill.isPrivate(fillKeyOfStorage(it)) }
        if (private.isEmpty()) {
            return loadShared(pending, source, fill)
        }
        val privately = loadPrivately(private.associateWith { pending.getValue(it).first() }, source)
        val shared = pending.filterKeys { it !in privately }
        return if (shared.isEmpty()) privately else privately + loadShared(shared, source, fill)
    }

    /**
     * [loadPending] 中经 SingleFlight 合并的 key：[fill] 是这次调用在入口捕获的写事务。
     */
    private suspend fun loadShared(
        pending: Map<String, List<K>>,
        source: CoreSource<K, V>?,
        fill: FillScope?
    ): Map<String, LocalEntry<V>> {
        val observed = pending.keys.associateWith { stamps.current(it) }
        val loaded = flights.executeAllSuspending(pending.keys, scope) { led ->
            val results = loadAllThrough(led.associateWith { pending.getValue(it).first() }, source)
            if (fill == null) results else results.mapValues { it.value.ownedBy(fill) }
        }
        return pending.mapValues { (storageKey, sourceKeys) ->
            val result = loaded[storageKey]
            if (result != null && result.stamp >= observed.getValue(storageKey) && usable(result, source != null, fill)) {
                result.answer()
            } else {
                loadMiss(sourceKeys.first(), storageKey, source)
            }
        }
    }

    /**
     * 批量：一次 L1 读取（为未命中的 key 申请 lease）→ 一次批量回源 → 一次批量写回。
     * 批量路径不等待其它节点的 lease（这些 key 直接回源且不写 L1）；损坏的载荷与单 key 路径一样条件回收。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadAllThrough(led: Map<String, K>, source: CoreSource<K, V>?): Map<String, Loaded<V>> {
        val loadStamps = led.keys.associateWith { stamps.current(it) }
        val readKeys = batchReadKeys(led)
        val leases = batchLeases(readKeys)
        // 读取之前取时刻，整批共用（#877）：前面 key 的解码时间不累加到后面 key 的寿命上
        val readAt = clock.nowMillis()
        val reads = try {
            if (readKeys.isEmpty()) emptyMap() else readAllRemote(readKeys, leases)
        } catch (e: Exception) {
            rethrowIfFatal(e)
            if (source == null || readFailure == ReadFailureMode.THROW) {
                return unansweredAll(led.keys, loadStamps, e)
            }
            onRemoteFailure("readAll", readKeys.first(), e)
            emptyMap()
        }
        return stepAll(led, reads, loadStamps, leases, source, readAt)
    }

    /**
     * 批量读取 L1，规则同 [readRemote]。
     */
    private suspend fun readAllRemote(keys: List<String>, leases: Map<String, LeaseRequest>): Map<String, L1Read> =
        uncancellable { store.readAll(keys, leases, policy.readTimeout) }

    /**
     * [loadAllThrough] 读到 L1 之后：逐 key 判定，先批量回源并写回，再逐个条件回收损坏的 key。
     * 读不懂的 key（`FAIL`）或回收失败：先完成其余 key（释放或凭 lease 写回），再抛出第一个失败。
     *
     * 清理边界（I-F，#886）：从批量读取返回起，到这次读取取得的 lease 交出（凭它写回、或释放）为止，任何异常（含 `Error` 与取消）
     * 都先释放全部 lease，再原样抛出，同单 key 路径（[loadAndFill]）。边界内有逐 key 判定（解码命中的 key，`Codec` 抛出的 `Error`
     * 不按损坏处理）与一次批量回源（数据源、`ttlOf`、`Codec` 与 [FillScope.filled]）。判定可能在未命中的 key 分类之前就抛出，
     * 所以按读取结果（[grantedLeases]）而不是按待回源的 key 释放；已经完成的 key（命中填入的 L2）保留。
     * 回收损坏的 key 在边界之外：命中没有取得 lease，回收取得的 lease 由单 key 路径（[onCorrupt]）交还。
     *
     * @param readAt 发出批量读取之前的时刻：命中的 key 的寿命从它算起
     */
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    private suspend fun stepAll(
        led: Map<String, K>,
        reads: Map<String, L1Read>,
        loadStamps: Map<String, Long>,
        leases: Map<String, LeaseRequest>,
        source: CoreSource<K, V>?,
        readAt: Long
    ): Map<String, Loaded<V>> {
        val plan = BatchPlan<V>()
        val toLoad = plan.toLoad
        val loaded: LoadedBatch<K, V>?
        try {
            classifyAll(led, reads, loadStamps, leases, source, readAt, plan)
            loaded = if (source == null || toLoad.isEmpty()) null else loadBatch(led, toLoad, source)
        } catch (e: Throwable) {
            releaseAll(grantedLeases(reads, leases))
            throw e
        }
        if (loaded != null) {
            fillBatch(led, toLoad, loaded, loadStamps, plan.unrefreshable, plan.result)
        } else if (toLoad.isNotEmpty()) {
            releaseAll(toLoad)
            completeBatchWithoutLoader(led, toLoad, loadStamps, plan.unrefreshable, plan.bypassed, plan.result)
        }
        val reclaimFailure = reclaimAll(led, plan.corrupt, loadStamps, leases, source, plan.result)
        (plan.failure ?: reclaimFailure)?.let { throw it }
        return plan.result
    }

    /**
     * 逐 key 判定（[batchStep]），记入 [plan]：命中的 key 完成（填入 L2），其余 key 待回源或待回收，读不懂的 key 记下第一个失败。
     * 在 [stepAll] 的清理边界内：判定抛出时（如解码命中的 key 时 `Codec` 抛出 `Error`），这次读取取得的 lease 由它释放。
     */
    @Suppress("LongParameterList")
    private fun classifyAll(
        led: Map<String, K>,
        reads: Map<String, L1Read>,
        loadStamps: Map<String, Long>,
        leases: Map<String, LeaseRequest>,
        source: CoreSource<K, V>?,
        readAt: Long,
        plan: BatchPlan<V>
    ) {
        for (storageKey in led.keys) {
            val stamp = loadStamps.getValue(storageKey)
            when (val step = batchStep(storageKey, reads[storageKey], stamp, leases[storageKey], readAt)) {
                is BatchStep.Load -> {
                    plan.toLoad[storageKey] = step.token
                    if (!step.refreshable) plan.unrefreshable.add(storageKey)
                    if (!step.absent) plan.bypassed.add(storageKey)
                }
                is BatchStep.Done -> plan.result[storageKey] = Loaded(step.entry, stamp)
                is BatchStep.Failed -> plan.fail(step.error)
                is BatchStep.Corrupt -> {
                    val foreign = foreignFormatFailure(storageKey, step.read, step.error, source != null)
                    if (foreign == null) plan.corrupt[storageKey] = step else plan.fail(foreign)
                }
            }
        }
    }

    /**
     * 批量读取取得的 lease：读到未命中、且原子地取得了请求中的 lease 的 key（[grantedToken]，同 [batchStep]）。
     */
    private fun grantedLeases(reads: Map<String, L1Read>, leases: Map<String, LeaseRequest>): Map<String, Token> =
        buildMap {
            reads.forEach { (storageKey, read) -> grantedToken(read, leases[storageKey])?.let { put(storageKey, it) } }
        }

    private fun grantedToken(read: L1Read?, lease: LeaseRequest?): Token? =
        lease?.token?.takeIf { read is L1Read.Miss && read.leaseGranted }

    /**
     * 逐个条件回收损坏的 key 后回源（与单 key 路径一致，否则损坏的载荷会一直留到 L1 过期）；返回第一个失败，不中断其余 key。
     * 命中没有取得 lease，回收之前不持有令牌。
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun reclaimAll(
        led: Map<String, K>,
        corrupt: Map<String, BatchStep.Corrupt>,
        loadStamps: Map<String, Long>,
        leases: Map<String, LeaseRequest>,
        source: CoreSource<K, V>?,
        result: MutableMap<String, Loaded<V>>
    ): Exception? {
        var failure: Exception? = null
        for ((storageKey, step) in corrupt) {
            val stamp = loadStamps.getValue(storageKey)
            val lease = leases.getValue(storageKey)
            try {
                val entry = onCorrupt(led.getValue(storageKey), storageKey, step.read, stamp, lease, source, step.error)
                result[storageKey] = Loaded(entry, stamp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        return failure
    }

    private fun batchReadKeys(led: Map<String, K>): List<String> = led.keys.filterNot {
        pendingInvalidations.containsKey(
            it
        )
    }

    /**
     * 批量读取为每个 key 申请的 lease；权威副本不申请（同 [loadThrough]）。
     */
    private fun batchLeases(readKeys: List<String>): Map<String, LeaseRequest> = if (authoritative) {
        emptyMap()
    } else {
        readKeys.associateWith { LeaseRequest(context.nextToken(), policy.lease.ttl) }
    }

    /**
     * 同步与异步共用的批量读取判定：命中的 key 直接填入 L2；其余 key 需要回源（取得 lease 时凭它写回 L1）或条件回收。
     */
    private fun batchStep(
        storageKey: String,
        read: L1Read?,
        stamp: Long,
        lease: LeaseRequest?,
        readAt: Long
    ): BatchStep<V> {
        if (read !is L1Read.Hit) {
            // 同单 key 路径（[loadThrough]）：权威副本不申请 lease，读到 lease 占位就是不存在
            val absent = read is L1Read.Miss || (read is L1Read.Leased && lease == null)
            return BatchStep.Load(grantedToken(read, lease), absent = absent)
        }
        return when (val outcome = hitOutcome(storageKey, read)) {
            HitOutcome.Newer -> {
                // 更高格式的内容：既不回收也不写回，副本不设刷新点（P14）；没有数据源时失败（命中没有取得 lease，无需释放）
                newerFormatFailure(storageKey, read)?.let { return BatchStep.Failed(it) }
                metrics.unknownFormat()
                BatchStep.Load(null, refreshable = false)
            }

            is HitOutcome.Corrupt -> BatchStep.Corrupt(read, outcome.error)
            is HitOutcome.Decoded -> BatchStep.Done(onRemoteHit(storageKey, outcome.value, read, stamp, readAt))
        }
    }

    /**
     * 一次批量回源，计算寿命并编码，记入写事务（[FillScope.filled]，在任何共享层的填充之前）。在 [stepAll] 的清理边界内：
     * 数据源、`ttlOf`、`Codec` 或 [FillScope.filled] 对任何一个 key 抛出时，整批 lease 释放之后再抛出原始异常。
     */
    private suspend fun loadBatch(
        led: Map<String, K>,
        toLoad: Map<String, Token?>,
        source: CoreSource<K, V>
    ): LoadedBatch<K, V> {
        val values = loadAllValues(toLoad.keys.map { led.getValue(it) }, toLoad.keys, source)
        val ttls = toLoad.keys.associateWith { storageKey -> led.getValue(storageKey).let { ttlOf(it, values[it]) } }
        val fills = toLoad.mapNotNull { (storageKey, token) ->
            token?.let {
                val value = values[led.getValue(storageKey)]
                fillOf(storageKey, it, value, envelopeOf(value), ttls[storageKey])
            }
        }
        recordFills(toLoad.keys)
        return LoadedBatch(values, ttls, fills)
    }

    /**
     * 凭各自的 lease 一次批量写回 [loadBatch] 的结果：lease 由此交出（写回失败时由存储交还，I-F）。
     * 版本前进与结果未知的带版本写回按写入处理（[afterAdvancedFill]，整批一次）。
     *
     * @param unrefreshable 读到更高格式的 key：它们的副本不设刷新点（P14）
     */
    @Suppress("LongParameterList")
    private suspend fun fillBatch(
        led: Map<String, K>,
        toLoad: Map<String, Token?>,
        loaded: LoadedBatch<K, V>,
        loadStamps: Map<String, Long>,
        unrefreshable: Set<String>,
        result: MutableMap<String, Loaded<V>>
    ) {
        // 发出批量写回之前取时刻，同单 key 路径（[loadAndFill]）
        val filledAt = clock.nowMillis()
        val filled = fillAllRemote(loaded.fills)
        completeBatch(led, toLoad, loaded.values, loaded.ttls, filled, filledAt, loadStamps, unrefreshable, result)
        val unknown = filled.filterValues { it == FillOutcome.UNKNOWN }.keys
        if (unknown.isNotEmpty()) {
            unfilterOwnFrames(unknown)
        }
        val advanced = filled.filterValues { it.possiblyAdvanced }.keys
        if (advanced.isNotEmpty()) {
            afterAdvancedFill(advanced)
        }
    }

    /**
     * 协程核心的批量回源：与 [loadValue] 相同的许可、统计、观测与调用规则，一次 `loadAll`。
     */
    private suspend fun loadAllValues(
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

    /**
     * 释放批量取得的令牌（失败只计数告警），全部完成之后才继续。并发发出全部释放（每个释放一个子协程，执行到第一次挂起再发出下一个），
     * 再一起等待；释放不可取消。
     */
    private suspend fun releaseAll(tokens: Map<String, Token?>) {
        if (tokens.values.all { it == null }) {
            return
        }
        uncancellable {
            coroutineScope {
                tokens.forEach { (storageKey, token) ->
                    token?.let { launch(start = CoroutineStart.UNDISPATCHED) { releaseQuietly(storageKey, it) } }
                }
            }
        }
    }

    /**
     * [loadBatch] 的结果：回源得到的值、各 key 的寿命，与凭 lease 写回的填充（每个取得的 lease 一个）。
     */
    private class LoadedBatch<K, V>(val values: Map<K, V>, val ttls: Map<String, Duration?>, val fills: List<Fill>)

    @Suppress("LongParameterList")
    private fun completeBatch(
        led: Map<String, K>,
        toLoad: Map<String, Token?>,
        values: Map<K, V>,
        ttls: Map<String, Duration?>,
        filled: Map<String, FillOutcome>,
        filledAt: Long,
        loadStamps: Map<String, Long>,
        unrefreshable: Set<String>,
        result: MutableMap<String, Loaded<V>>
    ) {
        for ((storageKey, token) in toLoad) {
            val stamp = loadStamps.getValue(storageKey)
            val outcome = if (token != null) filled[storageKey] ?: FillOutcome.NONE else FillOutcome.NONE
            val value = values[led.getValue(storageKey)]
            if (outcome == FillOutcome.VERSION_REJECTED) {
                unversionedAbsentRefused(storageKey, value)
            }
            val entry = completeLoad(
                storageKey,
                stamp,
                token,
                value,
                ttls[storageKey],
                outcome,
                filledAt,
                refreshable = storageKey !in unrefreshable
            )
            result[storageKey] = Loaded(entry, stamp)
        }
    }

    /**
     * 没有数据源的批量读取，规则与单 key 路径（[loadThrough]）相同：L1 确认不存在的 key 是 [absentWithoutLoader]（限龄 `localMaxAge`）；
     * 其余 key（[bypassed]：派生副本读到其它节点的 lease、更高格式、失效重试中）绕过 L1，是 [completeBypass] 的“未填充”，
     * 以 `degradedLocalMaxAge` 限龄并计入 `degradedFills`。
     */
    @Suppress("LongParameterList")
    private fun completeBatchWithoutLoader(
        led: Map<String, K>,
        toLoad: Map<String, Token?>,
        loadStamps: Map<String, Long>,
        unrefreshable: Set<String>,
        bypassed: Set<String>,
        result: MutableMap<String, Loaded<V>>
    ) {
        toLoad.keys.forEach { storageKey ->
            val stamp = loadStamps.getValue(storageKey)
            val entry = if (storageKey in bypassed) {
                completeBypass(led.getValue(storageKey), storageKey, stamp, null, false, storageKey !in unrefreshable)
            } else {
                absentWithoutLoader(storageKey, stamp)
            }
            result[storageKey] = Loaded(entry, stamp)
        }
    }

    private fun filledByKey(fills: List<Fill>, outcomes: List<L1Fill>): Map<String, FillOutcome> =
        fills.indices.associate {
            fills[it].key to (outcomes.getOrNull(it)?.let { outcome -> fillOutcomeOf(outcome) } ?: FillOutcome.NONE)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fillAllRemote(fills: List<Fill>): Map<String, FillOutcome> {
        if (fills.isEmpty()) {
            return emptyMap()
        }
        val outcomes = try {
            uncancellable { store.fillAll(fills) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onRemoteFailure("fillAll", fills.first().key, e)
            return fills.associate { it.key to failedFillOutcome(it, e) }
        }
        return filledByKey(fills, outcomes)
    }

    /**
     * 后台刷新：经 SingleFlight 与并发的未命中合并。只有在 L1 上取得刷新令牌的节点回源并凭令牌写回；
     * 其它节点只从 L1 重新填充 L2。刷新失败或被拒绝时只告警，值照常到期。
     * 异步数据源的刷新在刷新执行器上发起、以异步路径执行，不在刷新执行器的线程上等待（RFC 0004 §5.7）。
     * 关闭之前排进执行器、关闭之后才开始的刷新不读 L1、不回源，只释放守卫（#850）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun refreshAsync(key: K, localKey: String, observed: LocalEntry<V>) {
        val storageKey = storageKeyOfChecked(localKey)
        // 没有数据源的缓存（如只经 Spring Cache 写入）无法回源刷新
        if (source == null || closed.get() || !refreshing.add(storageKey)) {
            return
        }
        // 调用方读到副本之后、取得守卫之前，上一次刷新可能已完成：它先替换副本再释放守卫，
        // 因此副本已不是读到的那一份时，这次触发来自旧的观察，不再刷新（否则同一节点对同一 key 多刷新一次）
        if (local.getIfPresent(localKey) !== observed) {
            refreshing.remove(storageKey)
            return
        }
        try {
            if (source is AsyncSource.Native) {
                // 在刷新执行器上发起，不在其线程上等待：随后的续接在异步执行器上推进
                context.refreshExecutor.execute { refreshWithAsyncSource(key, storageKey) }
                return
            }
            context.refreshExecutor.execute {
                try {
                    // 关闭之前排队、关闭之后才开始的刷新跳过（#850）：执行器可能归调用方、关闭之后仍在运行，
                    // runSync 又不受已取消的运行时作用域约束；守卫在 finally 中照常释放
                    if (closed.get()) {
                        return@execute
                    }
                    metrics.refresh()
                    runSync { refreshShared(key, storageKey, refreshSource) }
                } catch (e: Exception) {
                    log.warn(e) { "Cache[$name] - key[$storageKey] - refresh failed." }
                } finally {
                    refreshing.remove(storageKey)
                }
            }
        } catch (e: RejectedExecutionException) {
            refreshing.remove(storageKey)
            metrics.refreshRejected()
            log.warn(e) { "Cache[$name] - key[$storageKey] - refresh rejected." }
        }
    }

    private fun refreshWithAsyncSource(key: K, storageKey: String) {
        if (closed.get()) {
            refreshing.remove(storageKey)
            return
        }
        metrics.refresh()
        context.async.runAsync { refreshShared(key, storageKey, refreshSource) }.whenComplete { _, error ->
            refreshing.remove(storageKey)
            if (error != null) {
                log.warn(error) { "Cache[$name] - key[$storageKey] - refresh failed." }
            }
        }
    }

    /**
     * 提前刷新经 SingleFlight 与同一 key 的未命中加载合并（刷新执行器上的同步变体与异步数据源的异步变体共用）。
     */
    private suspend fun refreshShared(key: K, storageKey: String, source: CoreSource<K, V>?): Loaded<V> =
        flights.executeSuspending(storageKey, scope) { refreshThrough(key, storageKey, source) }

    /**
     * 提前刷新（同步与异步共用的协程核心）：取得令牌才回源；没取得令牌只从 L1 重新填充 L2；L1 已无值或内容不可读时退回普通加载。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshThrough(key: K, storageKey: String, source: CoreSource<K, V>?): Loaded<V> {
        // 失效仍在重试：L1 可能还是旧值，不能从它重新填充 L2（loadThrough 会绕过 L1）
        val window = policy.refreshWindow
        if (window == null || pendingInvalidations.containsKey(storageKey)) {
            return loadThrough(key, storageKey, source)
        }
        val stamp = stamps.current(storageKey)
        val token = context.nextToken()
        // 读取之前取时刻：读到的剩余寿命从这一刻算起，算出的 L1 到期时刻不晚于实际（重新填充的副本与版本拒绝时保留的副本据此计算寿命，
        // [refill]、[keepRefreshedCopy]）
        val readAt = clock.nowMillis()
        val read = try {
            readRemote(storageKey, null, RefreshRequest(window, token, policy.lease.ttl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onRemoteFailure("read", storageKey, e)
            return loadThrough(key, storageKey, source)
        }
        return when (val step = refreshStep(storageKey, read, readAt)) {
            is RefreshStep.Reload -> {
                if (step.releaseToken) {
                    releaseToken(storageKey, token)
                }
                loadThrough(key, storageKey, source)
            }

            is RefreshStep.Refill -> Loaded(refill(storageKey, step, stamp), stamp)
            is RefreshStep.LoadWithToken -> Loaded(loadAndFill(key, storageKey, stamp, token, source, step), stamp)
        }
    }

    /**
     * 同步与异步共用的刷新判定：取得令牌才回源；没取得令牌只从 L1 重新填充 L2；L1 已无值或内容不可读时退回普通加载。
     *
     * @param readAt 发出读取之前的时刻：没取得令牌时重新填充的副本的寿命从它算起（#877），取得令牌时据此记下读到的副本在 L1 中的到期时刻
     */
    private fun refreshStep(storageKey: String, read: L1Read, readAt: Long): RefreshStep<V> {
        if (read !is L1Read.Hit) {
            return RefreshStep.Reload(false)
        }
        if (read.format != backendFormat) {
            return RefreshStep.Reload(read.refreshGranted)
        }
        if (!read.refreshGranted) {
            val value = runCatching {
                verifyDigest(storageKey, read.envelope)
                decode(read.envelope)
            }.getOrElse { return RefreshStep.Reload(false) }
            return RefreshStep.Refill(value, read.ttl, readAt, refreshable(read.envelope))
        }
        return RefreshStep.LoadWithToken(read, read.ttl?.let { readAt + it.toMillis() } ?: FOREVER)
    }

    /**
     * 没取得刷新令牌时从 L1 重新填充 L2（同步与异步共用）。L1 仍在窗口内说明令牌在别的节点手里、它正在回源：
     * 刷新点退避 [refreshBackoff]（见 [localEntry]），否则本节点的每次命中都会再读一次 L1，持有者崩溃时持续到令牌签发超过 `lease.ttl`。
     */
    private fun refill(storageKey: String, step: RefreshStep.Refill<V>, stamp: Long): LocalEntry<V> {
        metrics.refreshSkipped()
        val entry = localEntry(step.value, step.ttl, policy.localMaxAge, refreshBackoff, step.refreshable, step.readAt)
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    // ---------------------------------------------------------------------------------------------
    // 写入：同步与异步共用的协程核心，子类经 `runSync` / `AsyncContext.runAsync` 调用。
    // ---------------------------------------------------------------------------------------------

    /**
     * `put` 的默认寿命：`ttlOf` 优先，否则策略寿命加抖动。
     */
    internal fun putTtl(
        key: K,
        value: V
    ): Duration? = spec.ttlOf?.ttlOf(key, value)?.let(::perCallTtl) ?: policy.valueTtl()

    /**
     * 按调用给出的寿命（`ttlOf` 的结果、`put(k, v, ttl)` 的参数）不超过 [CachePolicy.ttlCeiling]（`cocache.overrides` 的 `ttl`，S8）：
     * 超过时钳制到它，计入 `cocache.ttl.clamped`，每个缓存第一次告警。没有上限时原样返回。策略自身的寿命（含抖动）不经过它。
     */
    internal fun perCallTtl(ttl: Duration): Duration {
        val ceiling = ttlCeiling ?: return ttl
        if (ttl <= ceiling) {
            return ttl
        }
        metrics.ttlClamped()
        if (ttlClampWarned.compareAndSet(false, true)) {
            log.warn {
                "Cache[$name] - per-call ttl[$ttl] exceeds the ceiling[$ceiling] from cocache.overrides and was clamped " +
                    "(further clamps are counted in cocache.ttl.clamped)."
            }
        }
        return ceiling
    }

    /**
     * 编码在第一次递增之前（异步变体在调用方线程上）完成。
     */
    internal suspend fun putWithTtl(key: K, value: V, ttl: Duration?) {
        val storageKey = storageKey(key)
        val envelope = encode(value)
        val bounded = ttl?.boundedTtl()
        val storageKeys = listOf(storageKey)
        write(
            storageKeys,
            { frame -> store.put(storageKey, envelope, bounded, frame) },
            onFailure = putFailure("put", storageKeys)
        )
    }

    /**
     * 一次 [write] 覆盖全部存储 key：戳与 L2 淘汰的顺序对整批成立，L1 批量写入、整批广播一次。
     */
    internal suspend fun putAllWithTtl(entries: Map<K, V>, ttlOf: (K, V) -> Duration?) {
        if (entries.isEmpty()) {
            return
        }
        val puts = putsOf(entries, ttlOf)
        val storageKeys = puts.map { it.key }
        write(
            storageKeys,
            { frame -> store.putAll(puts, frame) },
            onFailure = putFailure("putAll", storageKeys)
        )
    }

    /**
     * `put` / `putAll` 失败的处理（同步与异步共用，RFC 0005 §4.6，V7、U8）：
     * - 派生副本（`Cache`）结果未知、失效失败模式为 RETRY 时登记补偿失效（[scheduleRetry]，复用失效的重试），不抛出，与重试的
     *   `invalidate` 相同：L1 中可能已经、也可能稍后出现这次写入的值，补偿的删除让这个 key 收敛到“从数据源重新加载”，重试期间
     *   本节点的未命中绕过 L1。迟到的写入晚于补偿的删除执行时，陈旧以这次写入的 `ttl` 为界。
     * - 其余情形照常抛出 [CacheWriteException]：确定未执行的写入（[notExecuted]）不会迟到生效，不需要补偿，`outcomeUnknown = false`；
     *   失效失败模式为 THROW 时由调用方决定；权威副本的唯一副本上延迟的删除会删掉之后写入的值，从不补偿。
     */
    private fun putFailure(operation: String, storageKeys: List<String>): (Exception) -> Unit = { error ->
        if (authoritative || invalidateFailure == InvalidateFailureMode.THROW || notExecuted(error)) {
            throwWriteFailure(operation)(error)
        }
        scheduleRetry(storageKeys, error, operation)
    }

    /**
     * 编码后碰撞的存储 key 以后出现的为准，每个存储 key 只编码一次（同步与异步共用）。
     */
    private inline fun putsOf(entries: Map<K, V>, ttlOf: (K, V) -> Duration?): List<Put> {
        val latest = LinkedHashMap<String, Map.Entry<K, V>>(entries.size)
        entries.entries.forEach { latest[storageKey(it.key)] = it }
        return latest.map { (storageKey, entry) ->
            Put(storageKey, encode(entry.value), ttlOf(entry.key, entry.value)?.boundedTtl())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 条件读写（按摘要比较，`Store`）：只针对 L1，不读写 L2、不回源、不申请 lease。读不懂的内容从不视为没有值：
    // 不是本协议写入的内容（格式 0）、本协议的集合、更高格式版本、解码失败的值，读取抛出；写入抛出，或因摘要不符而冲突。
    // 同步与异步共用 [conditionalGetOf]、[refuseUnreadable]、[conditionalPutOf]。
    // ---------------------------------------------------------------------------------------------

    /**
     * 条件读取（同步与异步共用的协程核心）。
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun conditionalGet(key: K, known: Digest?): ConditionalGet<V> {
        val conditional = requireConditional("getIfChanged")
        val storageKey = storageKey(key)
        val read = try {
            uncancellable { conditional.readIfChanged(storageKey, known, policy.readTimeout) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw conditionalReadFailure(e)
        }
        return conditionalGetOf(storageKey, read)
    }

    /**
     * 条件读取没有可降级的答案（它确认的就是 L1）：无论 `unknownRead` 如何都抛出。
     */
    private fun conditionalReadFailure(error: Exception): CacheReadException {
        metrics.remoteFailure()
        return CacheReadException(name, "getIfChanged", error)
    }

    /**
     * 同步与异步共用：把后端的条件读取结果换算为用户结果。格式不是本节点的格式、集合、解码失败都是读不懂的内容。
     * 拿到载荷时（[L1ConditionalRead.Changed]）先重算摘要并与存储返回的摘要段比较（K3，[verifyConditional]）：不一致或缺摘要段按损坏失败，
     * 否则写入方或外部工具的缺陷留下的不一致，会让之后的 `getIfChanged` 对已知摘要一直回答 `Unchanged`。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun conditionalGetOf(storageKey: String, read: L1ConditionalRead): ConditionalGet<V> = when (read) {
        // 摘要只覆盖载荷：再确认这个值仍是本节点读得懂的格式
        is L1ConditionalRead.Unchanged -> if (read.format != backendFormat) {
            unreadableGet(storageKey, read.format, null)
        } else {
            metrics.conditionalUnchanged()
            ConditionalGet.Unchanged
        }

        L1ConditionalRead.Absent -> ConditionalGet.Absent
        is L1ConditionalRead.Unreadable -> unreadableGet(storageKey, read.format, null)
        is L1ConditionalRead.Changed -> if (read.format != backendFormat) {
            unreadableGet(storageKey, read.format, null)
        } else {
            try {
                ConditionalGet.Present(format.decode(read.envelope), verifyConditional(read.envelope))
            } catch (e: Exception) {
                unreadableGet(storageKey, read.format, e)
            }
        }
    }

    /**
     * 条件读取拿到的值的摘要段（K3）：必须存在，且等于载荷重算的 SHA-256；否则抛出。
     */
    private fun verifyConditional(envelope: Envelope.Scalar): Digest {
        val digest = checkNotNull(envelope.digest) { "Conditional read returned a value without a digest segment." }
        check(Digest.of(envelope) == digest) { "Payload does not match its digest segment." }
        return digest
    }

    /**
     * 读不懂的内容：计入 `unknownFormats`，抛出（不修改 L1），与条件写入的拒绝一致。
     */
    private fun unreadableGet(storageKey: String, format: Int, error: Exception?): ConditionalGet<V> {
        metrics.unknownFormat()
        val failure = ForeignFormatException(name, storageKey, format, backendFormat, error)
        log.warn { failure.message }
        throw CacheReadException(name, "getIfChanged", failure)
    }

    /**
     * 与 [putWithTtl] 相同的写入顺序（戳、L2 淘汰在 L1 前后各一次）；冲突与拒绝不修改 L1。非原子广播的后端上拒绝不广播，
     * 冲突只在可能是重放时广播（[conditionalChanged]）。
     */
    internal suspend fun putIfMatchWithTtl(key: K, expected: Digest?, value: V, ttl: Duration?): ConditionalPut {
        val conditional = requireConditional("putIfMatch")
        val storageKey = storageKey(key)
        val envelope = encode(value)
        val bounded = ttl?.boundedTtl()
        var outcome: L1ConditionalWrite? = null
        write(
            listOf(storageKey),
            { frame ->
                val result = conditional.putIfMatch(storageKey, expected, envelope, bounded, frame)
                outcome = result
                refuseUnreadable(storageKey, result)
            },
            { conditionalChanged(envelope, outcome) },
            throwWriteFailure("putIfMatch")
        )
        return conditionalPutOf(envelope, requireNotNull(outcome))
    }

    /**
     * 非原子广播的后端上，条件写入之后是否广播（同步与异步共用）。写入成功时广播；冲突摘要等于这次写入的摘要时也广播：
     * 这可能是重放，上一次同样的写入已在 L1 生效，单独的广播却失败了（调用方因此得到 `CacheWriteException`），
     * 不广播的话其它节点的 L2 陈旧至 `localMaxAge`。多余的广播只淘汰副本，无害。
     */
    private fun conditionalChanged(
        envelope: Envelope.Scalar,
        result: L1ConditionalWrite?
    ): Boolean = when (result) {
        is L1ConditionalWrite.Conflict -> result.current == envelope.digest
        // 拒绝（Unreadable）已在 mutate 中抛出，这里只剩 Written
        else -> result == L1ConditionalWrite.Written
    }

    /**
     * 后端拒绝了写入（key 中是读不懂的内容，未修改、未广播）：计入 `unknownFormats` 并抛出，由 [write] 包装为 `CacheWriteException`
     * （同时计入 `writeFailures`），与 `SetStore` 的元素写入相同。
     */
    private fun refuseUnreadable(storageKey: String, result: L1ConditionalWrite): Void? {
        if (result !is L1ConditionalWrite.Unreadable) {
            return null
        }
        throw refusedFormat(storageKey, result.format)
    }

    private fun refusedFormat(storageKey: String, format: Int): ForeignFormatException {
        metrics.unknownFormat()
        val failure = ForeignFormatException(name, storageKey, format, backendFormat, null)
        log.warn { failure.message }
        return failure
    }

    private fun conditionalPutOf(envelope: Envelope.Scalar, result: L1ConditionalWrite): ConditionalPut = when (result) {
        L1ConditionalWrite.Written -> ConditionalPut.Written(requireNotNull(envelope.digest))
        is L1ConditionalWrite.Conflict -> {
            metrics.conditionalConflict()
            ConditionalPut.Conflict(result.current)
        }

        is L1ConditionalWrite.Unreadable -> throw IllegalStateException(
            "Cache[$name] - unreachable: refused write."
        )
    }

    /**
     * 条件删除（`Store.deleteIfMatch`，同步与异步共用的协程核心）：写入方顺序同 [putWithTtl]，同步完成或抛出，从不重试。
     * 非原子广播的后端上，冲突且当前没有值时也广播（RFC 0005 §4.6）：这可能是重放，上一次同样的删除已在 L1 生效，单独的广播却失败了。
     */
    internal suspend fun conditionalDelete(key: K, expected: Digest): ConditionalDelete {
        val conditional = requireConditional("deleteIfMatch")
        val storageKey = storageKey(key)
        var outcome: L1ConditionalWrite? = null
        write(
            listOf(storageKey),
            { frame ->
                val result = conditional.deleteIfMatch(storageKey, expected, frame)
                outcome = result
                refuseUnreadable(storageKey, result)
            },
            { outcome == L1ConditionalWrite.Written || (outcome as? L1ConditionalWrite.Conflict)?.current == null },
            throwWriteFailure("deleteIfMatch")
        )
        return when (val result = requireNotNull(outcome)) {
            L1ConditionalWrite.Written -> ConditionalDelete.Deleted
            is L1ConditionalWrite.Conflict -> {
                metrics.conditionalConflict()
                ConditionalDelete.Conflict(result.current)
            }

            is L1ConditionalWrite.Unreadable -> throw IllegalStateException(
                "Cache[$name] - unreachable: refused write."
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 按版本有序的写入（`VersionedCache`、`VersionedStore`）：比较与写入由后端在 L1 中原子完成，写入方顺序同 put（不写 L2）；
    // 版本不更新时不修改 L1（相等的版本仍广播，见 [versionedChanged]）。引擎的值是 Versioned，value 为 null 时写入墓碑。
    // 读不懂的内容的分类与条件写入相同：总是拒绝。同步与异步共用 [refuseUnreadableVersion]、[versionedPutOf]。
    // ---------------------------------------------------------------------------------------------

    /**
     * 按版本写入的默认寿命：值同 [putTtl]（`ttlOf` 优先，否则策略寿命加抖动）；墓碑为墓碑寿命（`tombstoneTtl`，未设置时同值）。
     */
    internal fun versionedTtl(
        key: K,
        value: V?
    ): Duration? = if (value == null) policy.tombstoneTtl() else spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl()

    /**
     * 按版本写入（同步与异步共用的协程核心）。
     *
     * @param operation 写入失败时 [CacheWriteException.operation] 的名字
     */
    internal suspend fun writeVersioned(
        key: K,
        value: V?,
        version: Long,
        ttl: Duration?,
        operation: String
    ): VersionedPut {
        requireVersion(version)
        val versioned = requireVersioned(operation)
        val storageKey = storageKey(key)
        val envelope = envelopeOf(value)
        val bounded = ttl?.boundedTtl()
        var outcome: L1VersionedWrite? = null
        write(
            listOf(storageKey),
            { frame ->
                val result = versioned.putIfNewer(storageKey, envelope, version, bounded, frame)
                outcome = result
                refuseUnreadableVersion(storageKey, result)
            },
            { versionedChanged(version, outcome) },
            throwWriteFailure(operation)
        )
        return versionedPutOf(requireNotNull(outcome))
    }

    /**
     * 非原子广播的后端上，按版本写入之后是否广播（同步与异步共用），与原子广播的脚本相同。写入成功时广播；当前版本等于给出的版本时
     * 也广播：这个版本可能是不广播的填充（lease 上的回源）写入的，别的节点还持有更旧的副本（RFC 0005 §4.7，F1），也可能是重放的、
     * 已在 L1 生效而广播失败的写入（理由同 [conditionalChanged]）。版本更旧时不广播。
     */
    private fun versionedChanged(version: Long, result: L1VersionedWrite?): Boolean = when (result) {
        is L1VersionedWrite.Stale -> result.current == version
        // 拒绝（Unreadable）已在 mutate 中抛出，这里只剩 Written
        else -> result == L1VersionedWrite.Written
    }

    private fun requireVersion(version: Long) {
        require(
            version != Versioned.NO_VERSION
        ) { "Cache[$name] - version must not be Versioned.NO_VERSION (Long.MIN_VALUE)." }
    }

    private fun refuseUnreadableVersion(storageKey: String, result: L1VersionedWrite): Void? {
        if (result !is L1VersionedWrite.Unreadable) {
            return null
        }
        throw refusedFormat(storageKey, result.format)
    }

    private fun versionedPutOf(result: L1VersionedWrite): VersionedPut = when (result) {
        L1VersionedWrite.Written -> VersionedPut.Written
        is L1VersionedWrite.Stale -> {
            metrics.staleVersion()
            VersionedPut.Stale(result.current)
        }

        is L1VersionedWrite.Unreadable -> throw IllegalStateException(
            "Cache[$name] - unreachable: refused write."
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 删除：派生副本的失效（默认重试），权威副本与运维的删除（同步完成或抛出，从不重试）。
    // ---------------------------------------------------------------------------------------------

    internal fun storageKeysOf(keys: Collection<K>): List<String> = keys.map { storageKey(it) }.distinct()

    /**
     * 派生副本的失效（同步与异步共用的协程核心）：失败时按 [invalidateFailure] 抛出或登记重试。
     */
    internal suspend fun invalidateStorageKeys(storageKeys: List<String>) {
        if (storageKeys.isEmpty()) {
            return
        }
        write(
            storageKeys,
            { frame -> store.invalidate(storageKeys, frame) }
        ) { error -> onInvalidateFailure(storageKeys, error) }
    }

    /**
     * 同步完成的删除（`Store.delete`、`purge`，同步与异步共用的协程核心）：失败时抛出 [CacheWriteException]，从不排队重试
     * （RFC 0005 §4.1，A.D2）：唯一副本上延迟的删除会删掉失败之后写入的值，`purge` 的重试会越过之后按版本的写入。
     */
    internal suspend fun deleteStorageKeys(storageKeys: List<String>, operation: String) {
        if (storageKeys.isEmpty()) {
            return
        }
        write(
            storageKeys,
            { frame -> store.invalidate(storageKeys, frame) },
            onFailure = throwWriteFailure(operation)
        )
    }

    /**
     * 同步与异步共用：[invalidateFailure] 为抛出时抛出 [CacheWriteException]，否则登记重试。
     */
    private fun onInvalidateFailure(storageKeys: List<String>, error: Exception) {
        if (invalidateFailure == InvalidateFailureMode.THROW) {
            throwWriteFailure("invalidate")(error)
        }
        scheduleRetry(storageKeys, error, "invalidate")
    }

    /**
     * 失效写入 L1 失败，或派生副本的写入结果未知（[putFailure]）：登记为“重试中”（未命中时跳过 L1），交给重试队列重试失效；
     * 成功后本地再递增戳并淘汰 L2。
     */
    private fun scheduleRetry(storageKeys: List<String>, error: Exception, operation: String) {
        log.warn(error) { "Cache[$name] - $operation $storageKeys failed, retrying the invalidation." }
        // 计数：同一 key 的多次失败失效各自登记，任何一次放弃都不能提前解除其它仍在重试的
        storageKeys.forEach { pendingInvalidations.merge(it, 1, Int::plus) }
        val accepted = context.retrier.submit(
            action = { runSync { uncancellable { retryInvalidate(storageKeys) } } },
            onRetry = { metrics.invalidationRetry() },
            onSuccess = {
                storageKeys.forEach {
                    unregisterPending(it)
                    stamps.invalidate(it)
                    local.invalidate(localKeyOf(it))
                }
            },
            onGiveUp = { giveUpError -> dropInvalidation(storageKeys, giveUpError) },
        )
        if (!accepted) {
            dropInvalidation(storageKeys, error)
        }
    }

    /**
     * 重试一次失效（在重试线程上，规则同同步变体）：删除，非原子广播的后端上再发布。
     */
    private suspend fun retryInvalidate(storageKeys: List<String>) {
        val frame = frameOf(storageKeys)
        store.invalidate(storageKeys, frame)
        if (!context.backend.atomicBroadcast) {
            context.backend.channel.publish(keyspace, frame)
        }
    }

    private fun dropInvalidation(storageKeys: List<String>, error: Throwable) {
        storageKeys.forEach { unregisterPending(it) }
        metrics.invalidationDropped()
        // L1 仍是旧值，其它节点的 L2 到期后会从 L1 重新填充：陈旧度的上界是 L1 的剩余寿命
        log.error(error) {
            "Cache[$name] - invalidate $storageKeys dropped: L1 keeps the stale value until its remote TTL[${policy.ttl ?: "never"}] expires."
        }
    }

    private fun unregisterPending(storageKey: String) {
        pendingInvalidations.computeIfPresent(storageKey) { _, count -> if (count > 1) count - 1 else null }
    }

    /**
     * 失效仍在重试中的 key 数（供运维与测试查看）。
     */
    override val pendingInvalidationCount: Int
        get() = pendingInvalidations.size

    override fun isInvalidationPending(storageKey: String): Boolean = pendingInvalidations.containsKey(storageKey)

    /**
     * 运维查看：权威副本先校验摘要（不计数），不一致时按读不懂展示。
     */
    override fun describe(envelope: Envelope): Any? {
        digestProblem(envelope)?.let { throw IllegalStateException(it) }
        return decode(envelope)
    }

    /**
     * 保留版本栅栏的失效（`VersionedCache.evict`，RFC 0005 §4.7，P1）：带版本的内容转为 `E(当前版本)`，迟到的旧版本（数据源投递的事件、
     * 回源）仍被拒绝；没有版本的内容照常删除。写入方顺序同 [invalidateStorageKeys]；`E` 的寿命同墓碑（`tombstoneTtl`）。
     * 失败时抛出 [CacheWriteException]，不进入失效重试：重试的 `DEL` 会丢掉版本。拒绝（带摘要段或读不懂的内容）不修改 L1、不广播。
     *
     * 带摘要段的内容是 `Store` 写入的值（D4）：它属于同一 namespace 上的另一个缓存，拒绝（[AuthoritativeContentException]，计入 `typeMismatches`）。
     */
    internal suspend fun evictKeepingVersion(storageKey: String) {
        val versioned = requireVersioned(EVICT)
        var outcome: L1Eviction? = null
        write(
            listOf(storageKey),
            { frame ->
                val result = versioned.evictKeepingVersion(storageKey, policy.tombstoneTtl()?.boundedTtl(), frame)
                outcome = result
                refuseEviction(storageKey, result)
            },
            { outcome == L1Eviction.Evicted },
            throwWriteFailure(EVICT)
        )
    }

    private fun refuseEviction(storageKey: String, result: L1Eviction): Void? = when (result) {
        L1Eviction.Evicted -> null
        is L1Eviction.Unreadable -> throw refusedFormat(storageKey, result.format)
        L1Eviction.Authoritative -> {
            val refused = AuthoritativeContentException(name, storageKey)
            typeMismatch { refused.message }
            throw refused
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 异步变体的读取入口：命中判断与同步路径相同（同一个 L2），未命中在运行时作用域中运行同一份协程核心。
    // 返回给用户的 future 都由这里新建并显式完成，从不同步抛出（`KeyEncoder` 抛出也以失败的 future 交出）。
    // ---------------------------------------------------------------------------------------------

    /**
     * 不带 loader 的异步读取：L2 命中返回已完成的 future，不启动协程；否则经 [missAsync]。
     */
    internal fun <R> entryAsync(key: K, map: (LocalEntry<V>) -> R): CompletableFuture<R> = Futures.attempt {
        val localKey = localKey(key)
        val entry = local.getIfPresent(localKey)
        if (entry != null) {
            val now = clock.nowMillis()
            if (entry.expiresAt > now) {
                if (entry.refreshAt <= now) {
                    refreshAsync(key, localKey, entry)
                }
                countLocalHit()
                return@attempt CompletableFuture.completedFuture(map(entry))
            }
        }
        missAsync(key, storageKeyOfLocal(localKey), loadSource, map)
    }

    /**
     * 带调用级异步 loader 的异步读取（`AsyncCache.get(key, loader)`），规则同 [entryWith]。
     */
    internal fun <R> entryWithAsync(
        key: K,
        loader: AsyncCacheLoader<K, V>,
        map: (LocalEntry<V>) -> R
    ): CompletableFuture<R> = Futures.attempt {
        val localKey = localKey(key)
        val entry = local.getIfPresent(localKey)
        if (entry != null && !entry.unfilled) {
            val now = clock.nowMillis()
            if (entry.expiresAt > now) {
                if (entry.refreshAt <= now) {
                    refreshAsync(key, localKey, entry)
                }
                countLocalHit()
                return@attempt CompletableFuture.completedFuture(map(entry))
            }
        }
        missAsync(key, storageKeyOfLocal(localKey), CoreSource(AsyncSource.Native(loader), LoadOperation.LOAD), map)
    }

    /**
     * 异步未命中：过了 key 过滤器之后在运行时作用域中运行协程核心（[loadMiss]），返回结果的副本。
     */
    private fun <R> missAsync(
        key: K,
        storageKey: String,
        source: CoreSource<K, V>?,
        map: (LocalEntry<V>) -> R
    ): CompletableFuture<R> {
        filteredEntry(storageKey)?.let { return CompletableFuture.completedFuture(map(it)) }
        return context.async.runAsync { map(loadMiss(key, storageKey, source)) }
    }

    /**
     * 异步变体的写入与条件读取入口：在运行时作用域中运行 [block]，从不同步抛出。
     */
    internal fun <R> runAsync(block: suspend () -> R): CompletableFuture<R> = context.async.runAsync { block() }

    // ---------------------------------------------------------------------------------------------
    // 挂起变体的入口（RFC 0005 §4.4，K2、K6）：命中判断与同步、异步路径相同（同一个 L2），L2 命中内联返回、不切换调度器、
    // 不分配状态机（未命中的部分都在尾调用中）。未命中、写入与条件读写经 AsyncContext.runSuspending 在运行时的调度器上
    // 运行同一份协程核心，元素取自调用方。
    // ---------------------------------------------------------------------------------------------

    /**
     * 不带 loader 的挂起读取：L2 命中内联返回；否则经 [missSuspending]。
     */
    internal suspend fun <R> entrySuspending(key: K, map: (LocalEntry<V>) -> R): R {
        val localKey = localKey(key)
        val entry = local.getIfPresent(localKey)
        if (entry != null) {
            val now = clock.nowMillis()
            if (entry.expiresAt > now) {
                if (entry.refreshAt <= now) {
                    refreshAsync(key, localKey, entry)
                }
                countLocalHit()
                return map(entry)
            }
        }
        return missSuspending(key, storageKeyOfLocal(localKey), loadSource, map)
    }

    /**
     * 带调用级挂起 loader 的读取（`SuspendingCache.get(key, loader)`），规则同 [entryWith]：loader 在这次加载的协程中直接调用
     * （[loadSuspending]）。
     */
    internal suspend fun <R> entryWithSuspending(key: K, loader: suspend (K) -> V?, map: (LocalEntry<V>) -> R): R {
        val localKey = localKey(key)
        val entry = local.getIfPresent(localKey)
        if (entry != null && !entry.unfilled) {
            val now = clock.nowMillis()
            if (entry.expiresAt > now) {
                if (entry.refreshAt <= now) {
                    refreshAsync(key, localKey, entry)
                }
                countLocalHit()
                return map(entry)
            }
        }
        val source = CoreSource(AsyncSource.Suspending(loader), LoadOperation.LOAD)
        return missSuspending(key, storageKeyOfLocal(localKey), source, map)
    }

    private suspend fun <R> missSuspending(
        key: K,
        storageKey: String,
        source: CoreSource<K, V>?,
        map: (LocalEntry<V>) -> R
    ): R {
        filteredEntry(storageKey)?.let { return map(it) }
        return context.async.runSuspending { map(loadMiss(key, storageKey, source)) }
    }

    /**
     * 批量读取（挂起变体）：语义同 [resolveAll]，全部命中 L2 时内联返回。
     */
    internal suspend fun <R> resolveAllSuspending(keys: Collection<K>, map: (Map<K, LocalEntry<V>?>) -> R): R {
        val resolved = LinkedHashMap<K, LocalEntry<V>?>()
        val pending = collectPending(keys, resolved)
        if (pending.isEmpty()) {
            return map(resolved)
        }
        return context.async.runSuspending {
            for ((storageKey, entry) in loadPending(pending, loadSource)) {
                pending.getValue(storageKey).forEach { resolved[it] = entry }
            }
            map(resolved)
        }
    }

    /**
     * 挂起变体的写入与条件读写入口：在运行时的调度器上运行 [block]（[AsyncContext.runSuspending]）。
     */
    internal suspend fun <R> runSuspending(block: suspend () -> R): R = context.async.runSuspending { block() }

    private companion object {
        /**
         * 保留版本的失效的操作名（[CacheWriteException.operation]）。
         */
        const val EVICT = "evictKeepingVersion"

        val INITIAL_BACKOFF_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(2)
        val MAX_BACKOFF_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(50)

        fun nextBackoff(backoff: Long): Long = minOf(backoff * 2, MAX_BACKOFF_NANOS)

        /**
         * [refreshBackoff] 的下限：默认时钟（`TickingClock`）的刷新间隔，更短的退避在它看来与不退避相同。
         */
        val MIN_REFRESH_BACKOFF: Duration = Duration.ofMillis(100)

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
 * 异步与挂起变体中回源观测线程的名字前缀（`CoherentEngine.observedLoad`）。
 */
internal const val OBSERVATION_THREAD_PREFIX = "cocache-observe-"

/**
 * 数据源（RFC 0004 §3.2）：同步数据源或异步数据源。
 */
internal sealed interface AsyncSource<K : Any, V : Any> {
    class Native<K : Any, V : Any>(val loader: AsyncCacheLoader<K, V>) : AsyncSource<K, V>

    class Blocking<K : Any, V : Any>(val loader: CacheLoader<K, V>) : AsyncSource<K, V>

    /**
     * 调用级的挂起 loader（挂起视图的 `get(key) { … }`）：在这次加载的协程中直接调用（[CoherentEngine.loadSuspending]）。
     */
    class Suspending<K : Any, V : Any>(val loader: suspend (K) -> V?) : AsyncSource<K, V>
}

/**
 * 协程核心的数据源（RFC 0005 V5）：声明的或调用级的同步或异步数据源；[operation] 供回源观测区分未命中加载与刷新。
 */
private class CoreSource<K : Any, V : Any>(val source: AsyncSource<K, V>, val operation: LoadOperation)

/**
 * 读到值时的判定（同步与异步共用）。
 */
private sealed interface HitOutcome<out V> {
    data object Newer : HitOutcome<Nothing>

    class Corrupt(val error: Exception) : HitOutcome<Nothing>

    class Decoded<V>(val value: V?) : HitOutcome<V>
}

/**
 * 批量读取中每个 key 的判定（同步与异步共用）。
 */
private sealed interface BatchStep<out V> {
    /**
     * 回源：[token] 为取得的 lease；[refreshable] 为 `false` 时副本不设刷新点（读到更高格式，P14）；[absent] 为 L1 确认没有值
     * （未命中，或权威副本读到 lease 占位）。不是确认的不存在时（派生副本读到其它节点的 lease、更高格式、失效重试中没有读 L1），
     * 没有数据源的读取同单 key 路径一样绕过 L1（[CoherentEngine.completeBatchWithoutLoader]）。
     */
    class Load(val token: Token?, val refreshable: Boolean = true, val absent: Boolean = false) : BatchStep<Nothing>

    class Done<V>(val entry: LocalEntry<V>) : BatchStep<V>

    class Corrupt(val read: L1Read.Hit, val error: Exception) : BatchStep<Nothing>

    class Failed(val error: CacheReadException) : BatchStep<Nothing>
}

/**
 * 批量读取逐 key 判定的结果（[CoherentEngine.stepAll]）：已完成的 key（[result]），待回源的 key 与它取得的 lease（[toLoad]，
 * 含 [unrefreshable] 与 [bypassed] 两个标记，见 [BatchStep.Load]），待条件回收的 key（[corrupt]），以及读不懂的 key 的第一个失败。
 */
private class BatchPlan<V> {
    val result = LinkedHashMap<String, Loaded<V>>()
    val toLoad = LinkedHashMap<String, Token?>()
    val unrefreshable = HashSet<String>()
    val bypassed = HashSet<String>()
    val corrupt = LinkedHashMap<String, BatchStep.Corrupt>()
    var failure: Exception? = null
        private set

    fun fail(error: Exception) {
        failure = failure ?: error
    }
}

/**
 * 提前刷新读到 L1 之后的判定（同步与异步共用）。
 */
private sealed interface RefreshStep<out V> {
    class Reload(val releaseToken: Boolean) : RefreshStep<Nothing>

    /**
     * 没取得刷新令牌：以读到的值重新填充 L2，寿命 [ttl] 从 [readAt]（发出读取之前的时刻，纪元毫秒）算起；
     * [refreshable] 为 `false` 时不设刷新点（带摘要段的值，D4）。
     */
    class Refill<V>(
        val value: V?,
        val ttl: Duration?,
        val readAt: Long,
        val refreshable: Boolean = true
    ) : RefreshStep<V>

    /**
     * 取得了刷新令牌：回源并凭它写回；[read] 是取得令牌的那次读取，[expiresAt] 是读到的副本在 L1 中的到期时刻（纪元毫秒，
     * [FOREVER] 为永不过期）。版本拒绝时据此保留副本：回源期间消耗的时间从它的剩余寿命中扣除。
     */
    class LoadWithToken(val read: L1Read.Hit, val expiresAt: Long) : RefreshStep<Nothing>
}

/**
 * 等待其它节点的 lease 之后的读取结果：[read] 是最后一次重读（仍是 `L` 时等待超时），[readAt] 是发出它之前的时刻（纪元毫秒）。
 */
private class LeaseWait(val read: L1Read, val readAt: Long)

/**
 * 一次令牌保护的填充对 L2 的含义（同步与异步共用，RFC 0005 §4.7）。
 */
private enum class FillOutcome(
    /**
     * 本节点是否按写入处理（[AbstractEngine.afterAdvancedFill]）：这次填充已经或可能让 L1 的版本前进。
     */
    val possiblyAdvanced: Boolean = false
) {
    /**
     * 没有令牌，或不带版本的填充时 L1 失败：L2 以降级限龄填充。
     */
    NONE,

    /**
     * 已写入：L2 限龄 `localMaxAge`。
     */
    FILLED,

    /**
     * 已写入且是一次写入（版本前进，或填充了 `E`，[L1Fill.Filled.advanced]）：本节点按写入处理，不填 L2。
     */
    ADVANCED(possiblyAdvanced = true),

    /**
     * 令牌不匹配：L2 以降级限龄填充。
     */
    REJECTED,

    /**
     * 版本拒绝（[L1Fill.Rejected.currentVersion] 非 `null`）：未命中路径降级且不设刷新点，刷新路径保留副本并退避。
     */
    VERSION_REJECTED,

    /**
     * 带版本的填充失败，结果未知（TLA+ F2）：可能已经或稍后让版本前进。本节点按写入处理，不填 L2。
     */
    UNKNOWN(possiblyAdvanced = true),
}

private fun fillOutcomeOf(fill: L1Fill): FillOutcome = when (fill) {
    is L1Fill.Filled -> if (fill.advanced) FillOutcome.ADVANCED else FillOutcome.FILLED
    is L1Fill.Rejected -> if (fill.currentVersion != null) FillOutcome.VERSION_REJECTED else FillOutcome.REJECTED
}

/**
 * 同步变体在调用入口捕获的写事务（RFC 0005 §4.9 S7，[FillObserver.enter] 的结果），作为协程上下文元素交给门控与记录。
 * 与执行变体（[me.ahoo.cocache.core.internal.ExecutionMode]）分开，只在调用方处于写事务中时出现。
 */
private class FillContext(val scope: FillScope) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FillContext>
}
