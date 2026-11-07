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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.FillKey
import me.ahoo.cocache.core.FillObserver
import me.ahoo.cocache.core.FillScope
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.internal.Futures
import me.ahoo.cocache.core.internal.SingleFlight
import me.ahoo.cocache.core.internal.runSync
import me.ahoo.cocache.core.internal.uncancellable
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
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
) : AbstractEngine<K, V>(spec.name, spec.keyspace, spec.keyEncoder, spec.policy, store, context, spec.unknownRead) {

    private val keyFilter = spec.keyFilter

    /**
     * 写入 L1 的寿命与按调用给出的寿命的上限（[TtlRules]）：上限只钳制派生副本（D2）。
     */
    internal val ttls = TtlRules(name, policy, spec.ttlOf, spec.format, metrics, log, spec.role == Role.DERIVED)

    /**
     * L1 中的标量内容（[ScalarContent]）：按副本类型写入哪些段、读取方的摘要校验与命中判定、编解码。
     */
    internal val content =
        ScalarContent(name, spec.role, spec.versioned, spec.format, metrics, log, backendFormat, context::nextToken)

    /**
     * 回源调用（[SourceCalls]）：并发许可、超时、观测与数据源自身取消的换算。
     */
    private val sources = SourceCalls<K, V>(
        this,
        name,
        policy.load,
        metrics,
        context.loadObserver,
        context.async,
        flights
    )
    private val fillObserver: FillObserver = context.fillObserver
    private val ticker = context.ticker

    /**
     * 声明的数据源（同步或异步，二选一），同步与异步变体共用（[SourceCalls.invoke] 按变体决定在哪里调用）。
     */
    private val source: AsyncSource<K, V>? = spec.source

    /**
     * 协程核心的数据源（预先分配，未命中路径不再为它们分配）：未命中加载与提前刷新只在回源观测的操作类型上不同。
     */
    private val loadSource: CoreSource<K, V>? = source?.let { CoreSource(it, LoadOperation.LOAD) }
    private val refreshSource: CoreSource<K, V>? = source?.let { CoreSource(it, LoadOperation.REFRESH) }

    /**
     * 带数据源的读取遇到 L1 读失败时：默认降级回源；显式 `THROW` 时为 `Unknown`。
     */
    private val readFailure: ReadFailureMode = policy.readFailure ?: ReadFailureMode.DEGRADE

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
     * L2 命中的判定（六个读取入口共用：三种变体，各自带或不带调用级 loader）：副本未过期时，到了刷新点就触发提前刷新（[refreshAsync]），
     * 计入 L2 命中，再交给 [hit]（入口在其中返回）。内联：入口的字节码与手写的判定相同，命中路径不多一次调用或分配。
     */
    private inline fun freshHit(key: K, localKey: String, entry: LocalEntry<V>, hit: (LocalEntry<V>) -> Unit) {
        val now = clock.nowMillis()
        if (entry.expiresAt > now) {
            if (entry.refreshAt <= now) {
                refreshAsync(key, localKey, entry)
            }
            countLocalHit()
            hit(entry)
        }
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
            freshHit(key, localKey, entry) { return it }
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
            freshHit(key, localKey, entry) { return it }
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
            return loaded.entry
        }
        // 新一轮加载必然开始于上一轮结束之后（SingleFlight 先注销、再发布），因此至多重试一次
        val retried = flights.executeSuspending(
            storageKey,
            scope
        ) { loadThrough(key, storageKey, source).ownedBy(fill) }
        if (usable(retried, hasSource, fill)) {
            return retried.entry
        }
        // 两次都合并到了不能接受的加载（不带数据源的加载，或写事务中的 leader）：自己回源，写回仍受戳保护
        return loadThrough(key, storageKey, source).entry
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
                mapOf(storageKey to LocalEntry(sources.loadValue(key, storageKey, source), now))
            } else {
                val values = sources.loadAllValues(keys.values.toList(), keys.keys, source)
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
        val lease = if (content.authoritative) null else LeaseRequest(context.nextToken(), policy.lease.ttl)
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
     * 第一次读取遇到其它节点的 lease（[leased]）：以短退避重读，最多等待 `min(剩余寿命, lease.wait)`，沿用同一个令牌。
     */
    private suspend fun readWaitingLease(storageKey: String, lease: LeaseRequest, leased: L1Read.Leased): LeaseWait {
        metrics.leaseWait()
        return sources.observedLoad(LoadOperation.LEASE_WAIT, 1) { awaitLease(storageKey, lease, leased) }
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
        return when (val outcome = content.hitOutcome(storageKey, read)) {
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
        return CacheReadException(name, "read", unknownFormat(storageKey, read.format, error))
    }

    /**
     * 同步与异步共用：读到更高的格式（[isNewerFormat]：滚动升级期间，或未知的必须理解的位），既不回收也不写回。
     * 派生副本返回 `null`：有数据源时调用方绕过 L1 回源，外部来源时绕过 L1 只能得到“未填充”（RFC 0005 §4.1）。
     * 权威副本的 L1 是唯一副本，绕过 L1 只能得到“不存在”：返回要抛出的异常（已计入 `unknownFormats`，不修改 L1、不写 L2）。
     */
    private fun newerFormatFailure(storageKey: String, read: L1Read.Hit): CacheReadException? {
        if (!content.authoritative) {
            return null
        }
        return CacheReadException(name, "read", unknownFormat(storageKey, read.format, null))
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
        if (!content.versioned || current.expiresAt <= now || content.versionOf(current.value) <= content.versionOf(entry.value)) {
            return null
        }
        if (current.refreshAt > now) {
            return current
        }
        return LocalEntry(current.value, current.expiresAt, minOf(now + refreshBackoff, current.expiresAt))
    }

    /**
     * L1 命中：副本的寿命从发出读取之前的时刻 [readAt] 算起（批量读取整批共用一个），解码耗费的时间从中扣除（#877）。
     */
    private fun onRemoteHit(storageKey: String, value: V?, read: L1Read.Hit, stamp: Long, readAt: Long): LocalEntry<V> {
        metrics.remoteHit()
        val entry = localEntry(
            value,
            read.ttl,
            policy.localMaxAge,
            refreshable = content.refreshable(read.envelope),
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
            value = sources.loadValue(key, storageKey, source)
            prepared = prepare(key, value)
            recordFills(listOf(storageKey))
        } catch (e: Throwable) {
            token?.let { releaseToken(storageKey, it) }
            throw e
        }
        // 发出写回之前取时刻：L1 从执行 `FILL` 时开始计算寿命，回复晚到的时间从副本的寿命中扣除（#877）
        val filledAt = clock.nowMillis()
        val outcome = token?.let {
            fillRemote(content.fillOf(storageKey, it, value, prepared.envelope, prepared.ttl))
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
            content.decode(read.envelope)
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
        return refill(storageKey, RefreshStep.Refill(value, remaining, now, content.refreshable(read.envelope)), stamp)
    }

    /**
     * K16（RFC 0005 §4.1）：带版本的数据源返回了不带版本的 `null`（“从来没有这个 key”），落在带版本的内容上（`E` 或带版本的值）被拒绝。
     * 带版本的数据源必须以 `Versioned(null, w)` 表达删除：否则已删除的实体在 `E` 到期之前每次未命中都回源，没有负缓存；
     * 刷新路径保留旧副本到 L1 的 `ttl`。计数，并在第一次时告警。
     */
    private fun unversionedAbsentRefused(storageKey: String, value: V?) {
        if (!content.versioned || value != null) {
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

    private fun prepare(key: K, value: V?): Prepared = Prepared(ttls.fillTtl(key, value), content.envelopeOf(value))

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
        val entry = if (content.authoritative) {
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
        val value = sources.loadValue(key, storageKey, source)
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
        val degraded =
            localEntry(value, ttls.fillTtl(key, value), policy.degradedLocalMaxAge, refreshable = refreshable)
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
     * 批量读取（同步变体）：每个不重复的 key → 副本；key 过滤器判定不存在的 key 为 `null`。结果由子类换算（[valuesOf]、[lookupsOf]）。
     */
    internal fun resolveAll(keys: Collection<K>): Map<K, LocalEntry<V>?> {
        val resolved = LinkedHashMap<K, LocalEntry<V>?>()
        val pending = collectPending(keys, resolved)
        if (pending.isNotEmpty()) {
            resolvePending(pending, runSync(enterFill(loadSource)) { loadPending(pending, loadSource) }, resolved)
        }
        return resolved
    }

    /**
     * 批量读取的加载结果（存储 key → 副本）填回 [resolved]：编码后碰撞的业务 key（[pending] 中同一个存储 key 下的几个）共享一个副本。
     * 三种变体共用。
     */
    private fun resolvePending(
        pending: Map<String, List<K>>,
        loaded: Map<String, LocalEntry<V>>,
        resolved: MutableMap<K, LocalEntry<V>?>
    ) {
        for ((storageKey, entry) in loaded) {
            pending.getValue(storageKey).forEach { resolved[it] = entry }
        }
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
                resolvePending(pending, loadPending(pending, loadSource), resolved)
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
                result.entry
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
    private fun batchLeases(readKeys: List<String>): Map<String, LeaseRequest> = if (content.authoritative) {
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
        return when (val outcome = content.hitOutcome(storageKey, read)) {
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
        val values = sources.loadAllValues(toLoad.keys.map { led.getValue(it) }, toLoad.keys, source)
        val lifetimes = toLoad.keys.associateWith { storageKey ->
            val key = led.getValue(storageKey)
            ttls.fillTtl(key, values[key])
        }
        val fills = toLoad.mapNotNull { (storageKey, token) ->
            token?.let {
                val value = values[led.getValue(storageKey)]
                content.fillOf(storageKey, it, value, content.envelopeOf(value), lifetimes[storageKey])
            }
        }
        recordFills(toLoad.keys)
        return LoadedBatch(values, lifetimes, fills)
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
        // 没有数据源的缓存（如只经 Spring Cache 写入）无法回源刷新：先于拼接存储 key 判定，窗口内的命中因此不分配（D4）
        if (source == null || closed.get()) {
            return
        }
        val storageKey = storageKeyOfChecked(localKey)
        if (!refreshing.add(storageKey)) {
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
    @Suppress("TooGenericExceptionCaught")
    private fun refreshStep(storageKey: String, read: L1Read, readAt: Long): RefreshStep<V> {
        if (read !is L1Read.Hit) {
            return RefreshStep.Reload(false)
        }
        if (read.format != backendFormat) {
            return RefreshStep.Reload(read.refreshGranted)
        }
        if (!read.refreshGranted) {
            // 只把 `Exception` 按读不懂处理（同命中的判定 [hitOutcome] 与 #886）：`Codec` 抛出的 `Error` 原样传出，此时不持有令牌（D3）
            val value = try {
                content.verifyDigest(storageKey, read.envelope)
                content.decode(read.envelope)
            } catch (e: Exception) {
                return RefreshStep.Reload(false)
            }
            return RefreshStep.Refill(value, read.ttl, readAt, content.refreshable(read.envelope))
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
     * 编码在第一次递增之前（异步变体在调用方线程上）完成。
     */
    internal suspend fun putWithTtl(key: K, value: V, ttl: Duration?) {
        val storageKey = storageKey(key)
        val envelope = content.encode(value)
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
        if (content.authoritative || invalidateFailure == InvalidateFailureMode.THROW || notExecuted(error)) {
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
            Put(storageKey, content.encode(entry.value), ttlOf(entry.key, entry.value)?.boundedTtl())
        }
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
    override fun describe(envelope: Envelope): Any? = content.describe(envelope)

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
            freshHit(key, localKey, entry) { return@attempt CompletableFuture.completedFuture(map(it)) }
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
            freshHit(key, localKey, entry) { return@attempt CompletableFuture.completedFuture(map(it)) }
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
            freshHit(key, localKey, entry) { return map(it) }
        }
        return missSuspending(key, storageKeyOfLocal(localKey), loadSource, map)
    }

    /**
     * 带调用级挂起 loader 的读取（`SuspendingCache.get(key, loader)`），规则同 [entryWith]：loader 在这次加载的协程中直接调用
     * （[SourceCalls.loadSuspending]）。
     */
    internal suspend fun <R> entryWithSuspending(key: K, loader: suspend (K) -> V?, map: (LocalEntry<V>) -> R): R {
        val localKey = localKey(key)
        val entry = local.getIfPresent(localKey)
        if (entry != null && !entry.unfilled) {
            freshHit(key, localKey, entry) { return map(it) }
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
            resolvePending(pending, loadPending(pending, loadSource), resolved)
            map(resolved)
        }
    }

    /**
     * 挂起变体的写入与条件读写入口：在运行时的调度器上运行 [block]（[AsyncContext.runSuspending]）。
     */
    internal suspend fun <R> runSuspending(block: suspend () -> R): R = context.async.runSuspending { block() }

    private companion object {
        val INITIAL_BACKOFF_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(2)
        val MAX_BACKOFF_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(50)

        fun nextBackoff(backoff: Long): Long = minOf(backoff * 2, MAX_BACKOFF_NANOS)

        /**
         * [refreshBackoff] 的下限：默认时钟（`TickingClock`）的刷新间隔，更短的退避在它看来与不退避相同。
         */
        val MIN_REFRESH_BACKOFF: Duration = Duration.ofMillis(100)
    }
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
