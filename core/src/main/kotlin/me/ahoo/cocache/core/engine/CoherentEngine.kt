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
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
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
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.SourceLoadCancelledException
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.internal.Futures
import me.ahoo.cocache.core.internal.awaitOriginal
import me.ahoo.cocache.core.internal.awaitUncancellable
import me.ahoo.cocache.core.internal.ensureNotInterrupted
import me.ahoo.cocache.core.internal.runSync
import me.ahoo.cocache.core.internal.runsInline
import me.ahoo.cocache.core.internal.uncancellable
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.nanoseconds

/**
 * 数据源为权威的一致性缓存引擎（RFC 0003 §4.4–§4.6）。
 *
 * - L1 栅栏：未命中时由读脚本原子取得 lease，回源结果只能凭 lease 写回；失效销毁 lease。
 * - L2 栅栏：失效戳。填充方读 L1 前取戳；写入方在修改 L1 前后各递增一次。L2 只由读 L1 的路径填充。
 * - 同步路径上 loader 总是在调用方（SingleFlight leader）线程上同步执行。
 * - 异步视图（[async]，RFC 0004）共享同一个 L2、失效戳、SingleFlight 与 lease；协议决策（读到什么就做什么）
 *   由同步与异步共用的函数给出（[hitOutcome]、[completeLoad]、[batchStep]、[refreshStep] 等），两条路径只在“如何等待”上不同。
 * - 读取（单 key、批量）、提前刷新与写入运行在协程核心上（RFC 0005 V5）：同一份 `suspend` 实现（[loadMiss]、[loadPending]、
 *   [refreshThrough]、[write]），同步变体经 `runSync` 在调用方线程上运行，异步变体经 `AsyncContext.runAsync` 运行。
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class CoherentEngine<K : Any, V : Any>(
    private val spec: CacheSpec<K, V>,
    store: L1Store,
    context: EngineContext,
    private val format: ValueFormat<V> = CodecFormat(spec.codec),
) : AbstractEngine<K, V>(spec.name, spec.keyspace, spec.keyEncoder, spec.policy, store, context),
    Cache<K, V> {

    private val keyFilter = spec.keyFilter

    /**
     * 回源并发许可（bulkhead）：同步与异步变体共用一个按到达顺序交接的信号量，只计 SingleFlight leader。
     */
    private val permits: Semaphore? = policy.load.maxConcurrent.takeIf { it > 0 }?.let { Semaphore(it) }
    private val loadObserver: LoadObserver = context.loadObserver
    private val loadTimeout: Duration = policy.load.timeout
    private val ticker = context.ticker

    /**
     * 声明的数据源（同步或异步，二选一），同步与异步变体共用（[invoke] 按变体决定在哪里调用）。
     */
    private val source: AsyncSource<K, V>? = spec.asyncLoader?.let { AsyncSource.Native(it) }
        ?: spec.loader?.let { AsyncSource.Blocking(it) }

    /**
     * 协程核心的数据源（预先分配，未命中路径不再为它们分配）：未命中加载与提前刷新只在回源观测的操作类型上不同。
     */
    private val loadSource: CoreSource<K, V>? = source?.let { CoreSource(it, LoadOperation.LOAD) }
    private val refreshSource: CoreSource<K, V>? = source?.let { CoreSource(it, LoadOperation.REFRESH) }

    /**
     * 是否给标量写入带上写入标识（RFC 0005 §4.7，D2）：M5 引入 `Store` / `VersionedStore` 之前，没有数据源的缓存充当权威标量
     * （`docs/todo.md` §2 的 M4 约定），它的每次标量写入（`put`、`putAll`、填充、`putIfMatch`、`putIfNewer`）都带一个新的写入标识；
     * 有数据源的缓存不带，L1 头部保持 36 字节。
     */
    private val authoritative: Boolean = source == null

    /**
     * 带版本的缓存（[VersionedCache] 的引擎）：运维的按 key 删除保留版本（[evictKeepingVersion]）。
     */
    private val versionedValues: Boolean = format is VersionedFormat<*>

    /**
     * 类型不符（D4，[verifyDigest]）只在第一次告警，之后只计数：同一个 namespace 上的误声明会在每次 L1 读取时出现。
     */
    private val typeMismatchWarned = AtomicBoolean()

    /**
     * 失效写入 L1 失败时的处理方式：未配置时按有没有数据源决定。有数据源时删除总是安全的（之后回源），重试；
     * 没有数据源时 L1 是唯一副本，延迟的重试会删掉其间其它写入方写入的值（丢更新），因此抛出。
     */
    private val invalidateFailure: InvalidateFailureMode = policy.invalidateFailure
        ?: if (source != null) InvalidateFailureMode.RETRY else InvalidateFailureMode.THROW

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

    private val view = AsyncView()

    /**
     * 没有答案的加载（[unanswered]）的占位：从不写入 L2；`unresolved`，带 loader 的读取不接受它。
     */
    private val noAnswer = LocalEntry<V>(null, 0, unresolved = true)

    override fun async(): AsyncCache<K, V> = view

    override fun get(key: K): V? = entryOf(key).value

    override fun lookup(key: K): Lookup<V> = lookupOf(entryOf(key))

    /**
     * 以调用方给出的 [loader] 回源，与 [get] 走同一条受 lease 与失效戳保护的路径（供 Spring `@Cacheable(sync = true)` 等使用）。
     */
    override fun get(key: K, loader: CacheLoader<K, V>): V? {
        val localKey = localKey(key)
        val entry = local.getIfPresent(localKey)
        // 无数据源缓存的“未命中即不存在”只是没有答案，不能挡住调用方给出的 loader
        if (entry != null && !entry.unresolved && entry.expiresAt > clock.nowMillis()) {
            countLocalHit()
            return entry.value
        }
        return miss(
            key,
            storageKeyOfLocal(localKey),
            CoreSource(AsyncSource.Blocking(loader), LoadOperation.LOAD)
        ).value
    }

    private fun entryOf(key: K): LocalEntry<V> {
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
        return runSync { loadMiss(key, storageKey, source) }
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
        // 先于本次读取发生的失效此时必已递增戳：不接受开始于这些失效之前的共享加载（读己之写）
        val observed = stamps.current(storageKey)
        val hasSource = source != null
        val loaded = flights.executeSuspending(storageKey, scope) { loadThrough(key, storageKey, source) }
        if (loaded.stamp >= observed && usable(loaded.entry, hasSource)) {
            return loaded.answer()
        }
        // 新一轮加载必然开始于上一轮结束之后（SingleFlight 先注销、再发布），因此至多重试一次
        val retried = flights.executeSuspending(storageKey, scope) { loadThrough(key, storageKey, source) }
        if (usable(retried.entry, hasSource)) {
            return retried.answer()
        }
        // 两次都合并到了不带数据源的加载（get(key) 与 get(key, loader) 并发）：自己回源，写回仍受戳保护
        return loadThrough(key, storageKey, source).answer()
    }

    /**
     * 合并到的加载若没有数据源，而本次调用带有数据源，则其结果不可用。
     */
    private fun usable(entry: LocalEntry<V>, hasSource: Boolean): Boolean = !(entry.unresolved && hasSource)

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
        val lease = LeaseRequest(context.nextToken(), policy.lease.ttl)
        val read = try {
            readWaitingLease(storageKey, lease)
        } catch (e: Exception) {
            rethrowIfFatal(e)
            if (source == null) {
                return unanswered("read", storageKey, stamp, e)
            }
            onReadFailure("read", storageKey, e)
            return Loaded(loadBypassingRemote(key, storageKey, stamp, source), stamp)
        }
        val entry = when (read) {
            is L1Read.Hit -> onHit(key, storageKey, read, stamp, lease, source)
            is L1Read.Miss -> loadAndFill(
                key,
                storageKey,
                stamp,
                lease.token.takeIf { read.leaseGranted },
                source
            )
            is L1Read.Leased -> {
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
     * 其它节点持有 lease 时，以短退避重读，最多等待 `min(剩余寿命, lease.wait)`，沿用同一个令牌。
     */
    private suspend fun readWaitingLease(storageKey: String, lease: LeaseRequest): L1Read {
        val read = readRemote(storageKey, lease, null)
        if (read !is L1Read.Leased) {
            return read
        }
        metrics.leaseWait()
        return observedLoad(LoadOperation.LEASE_WAIT, 1) { awaitLease(storageKey, lease, read) }
    }

    private suspend fun awaitLease(storageKey: String, lease: LeaseRequest, leased: L1Read.Leased): L1Read {
        var read: L1Read = leased
        val deadline = leaseDeadline(leased)
        var backoff = INITIAL_BACKOFF_NANOS
        while (read is L1Read.Leased) {
            val remaining = deadline - ticker.nanoTime()
            if (remaining <= 0) {
                break
            }
            read = rereadAfter(storageKey, lease, minOf(backoff, remaining))
            backoff = nextBackoff(backoff)
        }
        return read
    }

    /**
     * 退避之后重读：挂起（`delay`）。同步变体停在调用方线程的事件循环上，线程被中断时立即结束；异步变体不占线程，运行时关闭时随作用域取消。
     */
    private suspend fun rereadAfter(storageKey: String, lease: LeaseRequest, delayNanos: Long): L1Read {
        delay(delayNanos.nanoseconds)
        return readRemote(storageKey, lease, null)
    }

    private fun leaseDeadline(leased: L1Read.Leased): Long = ticker.nanoTime() + minOf(leased.remaining, policy.lease.wait).toNanos()

    private suspend fun onHit(
        key: K,
        storageKey: String,
        read: L1Read.Hit,
        stamp: Long,
        lease: LeaseRequest,
        source: CoreSource<K, V>?
    ): LocalEntry<V> {
        return when (val outcome = hitOutcome(storageKey, read)) {
            HitOutcome.Newer -> {
                newerFormatFailure(storageKey, read, source != null)?.let { throw it }
                metrics.unknownFormat()
                // 读到未知标志位的副本不设刷新点（P14）：刷新只会再读到它、再绕过 L1 回源一次
                loadBypassingRemote(key, storageKey, stamp, source, refreshable = false)
            }

            is HitOutcome.Corrupt -> {
                foreignFormatFailure(storageKey, read, outcome.error, source != null)?.let { throw it }
                onCorrupt(key, storageKey, read, stamp, lease, source, outcome.error)
            }

            is HitOutcome.Decoded -> onRemoteHit(storageKey, outcome.value, read, stamp)
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
     * 有数据源时返回 `null`，调用方绕过 L1 回源。没有数据源时 L1 是唯一副本，绕过 L1 只能得到“不存在”：
     * 返回要抛出的异常（已计入 `unknownFormats`，不修改 L1、不写 L2）。
     */
    private fun newerFormatFailure(storageKey: String, read: L1Read.Hit, hasSource: Boolean): CacheReadException? {
        if (hasSource) {
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
     * 读取方的摘要校验（RFC 0005 §4.7，K3；同步与异步共用，对所有后端相同）：没有数据源的缓存（[authoritative]）从 L1 读到的不带版本的值
     * 必须带摘要段，且与载荷重算的 SHA-256 一致，否则抛出，调用方按损坏处理（没有数据源的读取失败；调用方给出 loader 时条件回收，
     * 而 `RECLAIM` 拒绝带摘要段的内容）。有数据源的缓存读到带摘要段的值是类型不符（D4）：计入 `typeMismatches` 并告警，照常读取。
     * 成本只在 L1 读取上，不在 L2 命中上。
     */
    private fun verifyDigest(storageKey: String, envelope: Envelope) {
        digestProblem(envelope)?.let { throw IllegalStateException("Cache[$name] - key[$storageKey] - $it") }
        if (!authoritative && (envelope as? Envelope.Scalar)?.digest != null) {
            typeMismatch(storageKey)
        }
    }

    /**
     * 没有数据源的缓存读到的值的问题（不计数，运维查看也用它）；没有问题时为 `null`。负缓存没有载荷，带版本的值不带摘要段，
     * 两者都只在带摘要段时校验。
     */
    private fun digestProblem(envelope: Envelope): String? {
        if (!authoritative || envelope !is Envelope.Scalar) {
            return null
        }
        val digest = envelope.digest
        if (digest == null) {
            return if (envelope.version != Versioned.NO_VERSION) {
                null
            } else {
                "value without a digest segment in a cache without a loader (not written by such a cache)."
            }
        }
        return if (Digest.of(envelope) == digest) null else "payload does not match its digest segment."
    }

    private fun typeMismatch(storageKey: String) {
        metrics.typeMismatch()
        val message = {
            "Cache[$name] - key[$storageKey] - holds a value written by a cache without a loader (digest segment): " +
                "the namespace is shared with an authoritative cache. It is read, but never reclaimed or refreshed."
        }
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

    private fun onRemoteHit(storageKey: String, value: V?, read: L1Read.Hit, stamp: Long): LocalEntry<V> {
        metrics.remoteHit()
        val entry = localEntry(value, read.ttl, policy.localMaxAge, refreshable = refreshable(read.envelope))
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    /**
     * 同版本载荷损坏：仅当内容仍是读到的那一份时转为 lease（条件回收，读路径从不无条件删除），再正常回源。批量读取也经这里。
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
        logCorrupt(storageKey, error)
        if ((read.envelope as? Envelope.Scalar)?.digest != null) {
            // 带摘要段的内容（权威标量，D4）从不回收：不发出注定被拒绝的 RECLAIM，绕过 L1 回源，副本不设刷新点（P14：刷新只会再读到它）
            return loadBypassingRemote(key, storageKey, stamp, source, refreshable = false)
        }
        val reclaimed = try {
            reclaimRemote(storageKey, ContentFingerprint.of(read.envelope), lease)
        } catch (e: Exception) {
            rethrowIfFatal(e)
            onReadFailure("reclaim", storageKey, e)
            false
        }
        if (!reclaimed) {
            return loadBypassingRemote(key, storageKey, stamp, source)
        }
        return loadAndFill(key, storageKey, stamp, lease.token, source)
    }

    private suspend fun reclaimRemote(storageKey: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean =
        uncancellable { store.reclaim(storageKey, observed, lease) }

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
     * 写入 L1 的内容：权威标量（[authoritative]）的值带一个新的写入标识，不带版本时另带载荷的摘要段（RFC 0005 §4.7，D1）：
     * M5 之前没有数据源的缓存充当 `Store`，它的每次标量写入（`put`、`putAll`、填充、`putIfMatch`）都带；带版本的值（`putIfNewer`、
     * 带版本的回源）不带，有数据源的缓存都不带（L1 头部保持 36 字节）。
     */
    private fun envelopeOf(
        value: V?
    ): Envelope = value?.let { format.envelopeOf(it, writerId(), authoritative) } ?: Envelope.Absent

    private fun encode(value: V): Envelope.Scalar = format.encode(value, writerId(), authoritative)

    /**
     * 一次标量写入的写入标识（与令牌同形，全局唯一）；有数据源的缓存为 `null`。
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
        return (spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl())?.boundedTtl()
    }

    /**
     * 回源并凭 [token] 写回 L1。没有令牌或写回被拒绝（期间发生失效、或 loader 慢于 lease 寿命）时，
     * 结果照常返回，L2 以降级限龄填充（否则慢 key 会一直未命中）。失败时等释放完成之后再抛出原始异常。
     *
     * 版本规则（RFC 0005 §4.7）：写回让版本前进（或填充了 `E`）是一次写入（[FillOutcome.ADVANCED]），本节点按写入处理、不填 L2；
     * 带版本的写回结果未知（[FillOutcome.UNKNOWN]）时同样处理：它可能已经或稍后让版本前进，而那一帧在本节点被过滤（TLA+ F2）；
     * 版本拒绝时，未命中路径（lease 在 `E` 上）照常返回回源值、以降级限龄写入 L2 且不设刷新点，刷新路径（[refreshed] 是取得刷新令牌的
     * 那次读取）不写回源得到的旧版本，保留 L1 中的副本并退避（[keepRefreshedCopy]）。
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun loadAndFill(
        key: K,
        storageKey: String,
        stamp: Long,
        token: Token?,
        source: CoreSource<K, V>?,
        refreshed: L1Read.Hit? = null
    ): LocalEntry<V> {
        if (source == null) {
            token?.let { releaseToken(storageKey, it) }
            return absentWithoutLoader(storageKey, stamp)
        }
        // 计算寿命与编码也可能抛出（用户的 ttlOf、Codec）：与回源一起释放 lease
        val value: V?
        val prepared: Prepared
        try {
            value = loadValue(key, storageKey, source)
            prepared = prepare(key, value)
        } catch (e: Throwable) {
            token?.let { releaseToken(storageKey, it) }
            throw e
        }
        val outcome = token?.let {
            fillRemote(fillOf(storageKey, it, value, prepared.envelope, prepared.ttl))
        } ?: FillOutcome.NONE
        if (outcome == FillOutcome.VERSION_REJECTED && refreshed != null) {
            keepRefreshedCopy(storageKey, refreshed, stamp)?.let { return it }
        }
        val entry = completeLoad(storageKey, stamp, token, value, prepared.ttl, outcome)
        if (outcome.possiblyAdvanced) {
            afterAdvancedFill(listOf(storageKey))
        }
        return entry
    }

    /**
     * 刷新路径的版本拒绝（RFC 0005 §4.7）：L1 中仍是取得令牌时读到的那个更新的版本（脚本已清零令牌），回源得到的更旧的值不写 L2，
     * 否则它会替换掉 L2 中的新值。改为以读到的值重新填充 L2，刷新点按退避（同没取得令牌的 [refill]）。读到的值解码失败时返回 `null`，
     * 调用方按未命中路径降级。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun keepRefreshedCopy(storageKey: String, refreshed: L1Read.Hit, stamp: Long): LocalEntry<V>? {
        val value = try {
            decode(refreshed.envelope)
        } catch (e: Exception) {
            return null
        }
        metrics.fillRejected()
        return refill(storageKey, RefreshStep.Refill(value, refreshed.ttl, refreshable(refreshed.envelope)), stamp)
    }

    private class Prepared(val ttl: Duration?, val envelope: Envelope)

    private fun prepare(key: K, value: V?): Prepared = Prepared(ttlOf(key, value), envelopeOf(value))

    /**
     * 同步与异步共用：回源结果写回 L1 之后（或没有令牌时）的计数与 L2 填充。
     * - [FillOutcome.FILLED]：L2 限龄 `localMaxAge`；
     * - [FillOutcome.ADVANCED]：这次填充是一次写入，不填 L2（调用方随后 [afterAdvancedFill]），返回回源值；
     * - [FillOutcome.UNKNOWN]：同上，不填 L2、返回回源值，计入 `fillRejected`（填充没有确认写入）；
     * - 其余：L2 以降级限龄填充；版本拒绝（lease 在 `E` 上）的副本不设刷新点（P14：刷新只会再读到 `E`）。
     */
    @Suppress("LongParameterList")
    private fun completeLoad(
        storageKey: String,
        stamp: Long,
        token: Token?,
        value: V?,
        ttl: Duration?,
        outcome: FillOutcome,
        refreshable: Boolean = true
    ): LocalEntry<V> {
        if (outcome == FillOutcome.ADVANCED) {
            return localEntry(value, ttl, policy.localMaxAge)
        }
        if (outcome == FillOutcome.UNKNOWN) {
            metrics.fillRejected()
            return localEntry(value, ttl, policy.degradedLocalMaxAge)
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
            refreshable = refreshable && outcome != FillOutcome.VERSION_REJECTED
        )
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    /**
     * 没有数据源的缓存（只用 put 写入）：未命中即不存在，不把负缓存写入 L1（否则会挡住其它节点的读取直到 absentTtl），
     * 只在本地缓存不存在。调用方先释放 lease。
     */
    private fun absentWithoutLoader(storageKey: String, stamp: Long): LocalEntry<V> {
        val absent = localEntry(null, policy.absentTtl, policy.localMaxAge)
        val entry = LocalEntry<V>(null, absent.expiresAt, absent.refreshAt, unresolved = true)
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
    ): LocalEntry<V> = completeBypass(
        key,
        storageKey,
        stamp,
        loadValue(key, storageKey, source),
        source != null,
        refreshable
    )

    /**
     * 同步与异步共用。没有数据源时绕过 L1 只能得到“不存在”：它只是没有答案（`unresolved`，带 loader 的读取不接受它），
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
        val entry = if (hasSource) degraded else LocalEntry<V>(null, degraded.expiresAt, unresolved = true)
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    /**
     * 没有数据源的读取遇到 L1 读失败：L1 是唯一副本，绕过它只能得到“不存在”。
     * - `readFailure` 未设置（没有数据源时即 `THROW`）或 `THROW`：没有可降级的答案，以 [CacheReadException] 失败（fail closed），
     *   与 `getIfChanged` 一致；
     * - 显式 `DEGRADE`：读到不存在，计入 `remoteFailures`，但**不写入任何一层**：它不是已决的负缓存，下一次读取重新访问 L1。
     *
     * 两者都不写 L2。结果经 SingleFlight 共享：它是 `unresolved` 的，合并到它的带 loader 的读取不接受它（[usable]），改用自己的数据源降级。
     */
    private fun unanswered(operation: String, storageKey: String, stamp: Long, error: Exception): Loaded<V> {
        if (policy.readFailure == ReadFailureMode.DEGRADE) {
            onRemoteFailure(operation, storageKey, error)
            return Loaded(absentNow(), stamp)
        }
        return Loaded(noAnswer, stamp, readFailureWithoutSource(operation, error))
    }

    /**
     * [unanswered] 的批量版本：整批计数一次，共享一个结果。
     */
    private fun unansweredAll(
        keys: Collection<String>,
        loadStamps: Map<String, Long>,
        error: Exception
    ): Map<String, Loaded<V>> {
        if (policy.readFailure == ReadFailureMode.DEGRADE) {
            onRemoteFailure("readAll", keys.first(), error)
            val absent = absentNow()
            return keys.associateWith { Loaded(absent, loadStamps.getValue(it)) }
        }
        val failure = readFailureWithoutSource("readAll", error)
        return keys.associateWith { Loaded(noAnswer, loadStamps.getValue(it), failure) }
    }

    /**
     * 不写入任何一层的“不存在”（`unresolved`）：只作为这次读取的返回值，到期时间是现在（`lookup` 返回的 `Absent.expiresAt`）。
     */
    private fun absentNow(): LocalEntry<V> = LocalEntry(null, clock.nowMillis(), unresolved = true)

    private fun readFailureWithoutSource(operation: String, error: Exception): CacheReadException {
        metrics.remoteFailure()
        return CacheReadException(name, operation, error)
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
            failedFillOutcome(fill)
        }
    }

    /**
     * 失败的填充（TLA+ F2）：带版本的填充按结果未知处理（[FillOutcome.UNKNOWN]）。超时或连接替换时被排空的 `FILL` 可能稍后才执行，
     * 让版本前进并广播，而那一帧在本节点被过滤；所以本节点现在就按写入递增戳、淘汰本地副本（[afterAdvancedFill]），读到旧版本的同节点
     * 读取者不能再把它写进 L2。区分“确定未执行”的异常类型随 M5 提供（`L1Store` 的“失败”），在此之前每个失败都算结果未知；
     * 多做的一次淘汰只多一次 L1 往返。没有版本的填充从不广播，迟到执行也只是 lease 上的写入，照常为 [FillOutcome.NONE]。
     */
    private fun failedFillOutcome(fill: Fill): FillOutcome =
        if (fill.version != Versioned.NO_VERSION) FillOutcome.UNKNOWN else FillOutcome.NONE

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

    /**
     * 读取 L1 失败：严格模式下抛出 [CacheReadException]，否则降级（调用方随后直接回源）。
     */
    private fun onReadFailure(operation: String, storageKey: String, error: Exception) {
        if (policy.readFailure == ReadFailureMode.THROW) {
            metrics.remoteFailure()
            throw CacheReadException(name, operation, error)
        }
        onRemoteFailure(operation, storageKey, error)
    }

    private fun onRemoteFailure(operation: String, storageKey: String, error: Exception) {
        metrics.remoteFailure()
        log.warn(error) { "Cache[$name] - key[$storageKey] - remote $operation failed, degrading." }
    }

    /**
     * 协程核心的单 key 回源（同步与异步共用）：许可、耗时统计与观测（[guardedLoad]）包住数据源调用（[invoke]）。
     */
    private suspend fun loadValue(key: K, storageKey: String, source: CoreSource<K, V>?): V? {
        val sourceLoader = source ?: return null
        return guardedLoad(sourceLoader.operation, 1) {
            invoke(sourceLoader.source, listOf(storageKey), { it.loadAsync(key) }, { it.load(key) })
        }
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
        blocking: (CacheLoader<K, V>) -> R
    ): R = try {
        invokeSource(source, storageKeys, native, blocking)
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
        blocking: (CacheLoader<K, V>) -> R
    ): R {
        val inline = runsInline()
        return when (source) {
            is AsyncSource.Blocking -> if (inline) {
                blocking(source.loader)
            } else {
                CompletableFuture.supplyAsync(
                    { flights.loading(storageKeys) { blocking(source.loader) } },
                    context.async.blocking
                )
                    .awaitOriginal()
            }

            is AsyncSource.Native -> {
                val started = Futures.attempt {
                    if (inline) {
                        native(source.loader).toCompletableFuture()
                    } else {
                        flights.loading(storageKeys) { native(source.loader).toCompletableFuture() }
                    }
                }
                val value = Futures.relay<Any?>(started)
                    .completeOnTimeout(TIMED_OUT, loadTimeout.toNanos(), TimeUnit.NANOSECONDS)
                    .awaitOriginal()
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
     * 协程核心的回源观测：默认的 [LoadObserver.NOOP] 直接执行。同步变体经 [LoadObserver.observe]（在调用方线程上），
     * 异步变体经 [LoadObserver.observeAsync]（从调用 block 到它完成）。
     *
     * 同步变体中调用方线程已被中断时不再调用观测器与 block 中的数据源（[ensureNotInterrupted]，RFC 0005 §4.3）：
     * 观测在嵌套的 `runSync` 中执行，外层协程的取消不会传到那里。
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
        // - 结果经 produced 交回：内层协程被取消时，即使 block 已经返回，它也以取消结束、丢掉返回值。
        //   交回之后，调用方在之后的挂起点被取消，持有令牌的步骤据此释放它
        val produced = Produced<R>()
        return try {
            observer.observeAsync(name, operation, keyCount) {
                scope.future(caller, CoroutineStart.UNDISPATCHED) { block().also { produced.value = it } }
            }.awaitUncancellable()
        } catch (e: CancellationException) {
            if (produced.isSet) produced.get() else throw e
        }
    }

    /**
     * [observedLoad] 中 block 的结果（可能为 `null`）：内层协程以取消结束时据此取回。
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

    override fun getAll(keys: Collection<K>): Map<K, V> {
        val resolved = LinkedHashMap<K, LocalEntry<V>?>()
        val pending = collectPending(keys, resolved)
        if (pending.isNotEmpty()) {
            for ((storageKey, entry) in runSync { loadPending(pending, loadSource) }) {
                pending.getValue(storageKey).forEach { resolved[it] = entry }
            }
        }
        return valuesOf(resolved)
    }

    /**
     * 批量读取的 L2 阶段（同步与异步共用）：命中的 key 填入 [resolved]；返回未命中的存储 key → 映射到它的业务 key
     * （KeyEncoder 可能把不同 key 映射为同一个存储 key）。
     */
    private fun collectPending(
        keys: Collection<K>,
        resolved: MutableMap<K, LocalEntry<V>?>
    ): LinkedHashMap<String, MutableList<K>> {
        val pending = LinkedHashMap<String, MutableList<K>>()
        for (key in keys) {
            if (resolved.containsKey(key)) {
                continue
            }
            val localKey = localKey(key)
            val entry = localHit(key, localKey)
            resolved[key] = entry
            if (entry != null) {
                continue
            }
            val storageKey = storageKeyOfLocal(localKey)
            if (keyFilter?.notExist(storageKey) == true) {
                metrics.filtered()
                continue
            }
            metrics.miss()
            pending.getOrPut(storageKey) { ArrayList(1) }.add(key)
        }
        return pending
    }

    private fun valuesOf(resolved: Map<K, LocalEntry<V>?>): Map<K, V> {
        val result = LinkedHashMap<K, V>()
        for ((key, entry) in resolved) {
            entry?.value?.let { result[key] = it }
        }
        return result
    }

    /**
     * 未过期的 L2 副本（进入刷新窗口时触发后台刷新，并计入 L2 命中）；没有则返回 `null`。
     */
    private fun localHit(key: K, localKey: String): LocalEntry<V>? {
        val entry = local.getIfPresent(localKey) ?: return null
        val now = clock.nowMillis()
        if (entry.expiresAt <= now) {
            return null
        }
        if (entry.refreshAt <= now) {
            refreshAsync(key, localKey, entry)
        }
        countLocalHit()
        return entry
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
        val observed = pending.keys.associateWith { stamps.current(it) }
        val loaded = flights.executeAllSuspending(pending.keys, scope) { led ->
            loadAllThrough(led.associateWith { pending.getValue(it).first() }, source)
        }
        return pending.mapValues { (storageKey, sourceKeys) ->
            val result = loaded[storageKey]
            if (result != null && result.stamp >= observed.getValue(storageKey) && usable(result.entry, source != null)) {
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
        val reads = try {
            if (readKeys.isEmpty()) emptyMap() else readAllRemote(readKeys, leases)
        } catch (e: Exception) {
            rethrowIfFatal(e)
            if (source == null) {
                return unansweredAll(led.keys, loadStamps, e)
            }
            onReadFailure("readAll", readKeys.first(), e)
            emptyMap()
        }
        return stepAll(led, reads, loadStamps, leases, source)
    }

    /**
     * 批量读取 L1，规则同 [readRemote]。
     */
    private suspend fun readAllRemote(keys: List<String>, leases: Map<String, LeaseRequest>): Map<String, L1Read> =
        uncancellable { store.readAll(keys, leases, policy.readTimeout) }

    /**
     * [loadAllThrough] 读到 L1 之后：逐 key 判定，先批量回源并写回，再逐个条件回收损坏的 key。
     * 读不懂的 key（`FAIL`）或回收失败：先完成其余 key（释放或凭 lease 写回），再抛出第一个失败。
     */
    private suspend fun stepAll(
        led: Map<String, K>,
        reads: Map<String, L1Read>,
        loadStamps: Map<String, Long>,
        leases: Map<String, LeaseRequest>,
        source: CoreSource<K, V>?
    ): Map<String, Loaded<V>> {
        val result = LinkedHashMap<String, Loaded<V>>()
        val toLoad = LinkedHashMap<String, Token?>()
        val unrefreshable = HashSet<String>()
        val corrupt = LinkedHashMap<String, BatchStep.Corrupt>()
        var failure: Exception? = null
        for (storageKey in led.keys) {
            val stamp = loadStamps.getValue(storageKey)
            when (val step = batchStep(storageKey, reads[storageKey], stamp, leases[storageKey], source != null)) {
                is BatchStep.Load -> {
                    toLoad[storageKey] = step.token
                    if (!step.refreshable) unrefreshable.add(storageKey)
                }
                is BatchStep.Done -> result[storageKey] = Loaded(step.entry, stamp)
                is BatchStep.Failed -> failure = failure ?: step.error
                is BatchStep.Corrupt -> {
                    val foreign = foreignFormatFailure(storageKey, step.read, step.error, source != null)
                    if (foreign == null) corrupt[storageKey] = step else failure = failure ?: foreign
                }
            }
        }
        if (toLoad.isNotEmpty()) {
            loadAndFillBatch(led, toLoad, loadStamps, unrefreshable, result, source)
        }
        val reclaimFailure = reclaimAll(led, corrupt, loadStamps, leases, source, result)
        (failure ?: reclaimFailure)?.let { throw it }
        return result
    }

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

    private fun batchLeases(readKeys: List<String>): Map<String, LeaseRequest> = readKeys.associateWith {
        LeaseRequest(context.nextToken(), policy.lease.ttl)
    }

    /**
     * 同步与异步共用的批量读取判定：命中的 key 直接填入 L2；其余 key 需要回源（取得 lease 时凭它写回 L1）或条件回收。
     */
    private fun batchStep(
        storageKey: String,
        read: L1Read?,
        stamp: Long,
        lease: LeaseRequest?,
        hasSource: Boolean
    ): BatchStep<V> {
        if (read !is L1Read.Hit) {
            return BatchStep.Load((read as? L1Read.Miss)?.takeIf { it.leaseGranted }?.let { lease?.token })
        }
        return when (val outcome = hitOutcome(storageKey, read)) {
            HitOutcome.Newer -> {
                // 更高格式的内容：既不回收也不写回，副本不设刷新点（P14）；没有数据源时失败（命中没有取得 lease，无需释放）
                newerFormatFailure(storageKey, read, hasSource)?.let { return BatchStep.Failed(it) }
                metrics.unknownFormat()
                BatchStep.Load(null, refreshable = false)
            }

            is HitOutcome.Corrupt -> BatchStep.Corrupt(read, outcome.error)
            is HitOutcome.Decoded -> BatchStep.Done(onRemoteHit(storageKey, outcome.value, read, stamp))
        }
    }

    /**
     * 一次批量回源并凭各自的 lease 一次批量写回；回源失败（含 `ttlOf` 与 `Codec` 抛出）时等全部 lease 释放之后再抛出原始异常。
     * 版本前进与结果未知的带版本写回按写入处理（[afterAdvancedFill]，整批一次）。
     *
     * @param unrefreshable 读到更高格式的 key：它们的副本不设刷新点（P14）
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun loadAndFillBatch(
        led: Map<String, K>,
        toLoad: Map<String, Token?>,
        loadStamps: Map<String, Long>,
        unrefreshable: Set<String>,
        result: MutableMap<String, Loaded<V>>,
        source: CoreSource<K, V>?
    ) {
        if (source == null) {
            releaseAll(toLoad)
            completeBatchWithoutLoader(toLoad, loadStamps, result)
            return
        }
        val values: Map<K, V>
        val prepared: PreparedBatch
        try {
            values = loadAllValues(toLoad.keys.map { led.getValue(it) }, toLoad.keys, source)
            prepared = prepareBatch(led, toLoad, values)
        } catch (e: Throwable) {
            releaseAll(toLoad)
            throw e
        }
        val filled = fillAllRemote(prepared.fills)
        completeBatch(led, toLoad, values, prepared.ttls, filled, loadStamps, unrefreshable, result)
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
            invoke(source.source, storageKeys, { it.loadAllAsync(keys) }, { it.loadAll(keys) })
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

    private class PreparedBatch(val ttls: Map<String, Duration?>, val fills: List<Fill>)

    private fun prepareBatch(led: Map<String, K>, toLoad: Map<String, Token?>, values: Map<K, V>): PreparedBatch {
        val ttls = toLoad.keys.associateWith { storageKey -> led.getValue(storageKey).let { ttlOf(it, values[it]) } }
        val fills = toLoad.mapNotNull { (storageKey, token) ->
            token?.let {
                val value = values[led.getValue(storageKey)]
                fillOf(storageKey, it, value, envelopeOf(value), ttls[storageKey])
            }
        }
        return PreparedBatch(ttls, fills)
    }

    @Suppress("LongParameterList")
    private fun completeBatch(
        led: Map<String, K>,
        toLoad: Map<String, Token?>,
        values: Map<K, V>,
        ttls: Map<String, Duration?>,
        filled: Map<String, FillOutcome>,
        loadStamps: Map<String, Long>,
        unrefreshable: Set<String>,
        result: MutableMap<String, Loaded<V>>
    ) {
        for ((storageKey, token) in toLoad) {
            val stamp = loadStamps.getValue(storageKey)
            val outcome = if (token != null) filled[storageKey] ?: FillOutcome.NONE else FillOutcome.NONE
            val value = values[led.getValue(storageKey)]
            val entry = completeLoad(
                storageKey,
                stamp,
                token,
                value,
                ttls[storageKey],
                outcome,
                refreshable = storageKey !in unrefreshable
            )
            result[storageKey] = Loaded(entry, stamp)
        }
    }

    private fun completeBatchWithoutLoader(
        toLoad: Map<String, Token?>,
        loadStamps: Map<String, Long>,
        result: MutableMap<String, Loaded<V>>
    ) {
        toLoad.keys.forEach { storageKey ->
            val stamp = loadStamps.getValue(storageKey)
            result[storageKey] = Loaded(absentWithoutLoader(storageKey, stamp), stamp)
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
            return fills.associate { it.key to failedFillOutcome(it) }
        }
        return filledByKey(fills, outcomes)
    }

    /**
     * 后台刷新：经 SingleFlight 与并发的未命中合并。只有在 L1 上取得刷新令牌的节点回源并凭令牌写回；
     * 其它节点只从 L1 重新填充 L2。刷新失败或被拒绝时只告警，值照常到期。
     * 异步数据源的刷新在刷新执行器上发起、以异步路径执行，不在刷新执行器的线程上等待（RFC 0004 §5.7）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun refreshAsync(key: K, localKey: String, observed: LocalEntry<V>) {
        val storageKey = storageKeyOfLocal(localKey)
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
            if (spec.asyncLoader != null) {
                // 在刷新执行器上发起，不在其线程上等待：随后的续接在异步执行器上推进
                context.refreshExecutor.execute { refreshWithAsyncSource(key, storageKey) }
                return
            }
            context.refreshExecutor.execute {
                try {
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
        val read = try {
            readRemote(storageKey, null, RefreshRequest(window, token, policy.lease.ttl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onRemoteFailure("read", storageKey, e)
            return loadThrough(key, storageKey, source)
        }
        return when (val step = refreshStep(storageKey, read)) {
            is RefreshStep.Reload -> {
                if (step.releaseToken) {
                    releaseToken(storageKey, token)
                }
                loadThrough(key, storageKey, source)
            }

            is RefreshStep.Refill -> Loaded(refill(storageKey, step, stamp), stamp)
            is RefreshStep.LoadWithToken -> Loaded(loadAndFill(key, storageKey, stamp, token, source, step.read), stamp)
        }
    }

    /**
     * 同步与异步共用的刷新判定：取得令牌才回源；没取得令牌只从 L1 重新填充 L2；L1 已无值或内容不可读时退回普通加载。
     */
    private fun refreshStep(storageKey: String, read: L1Read): RefreshStep<V> {
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
            return RefreshStep.Refill(value, read.ttl, refreshable(read.envelope))
        }
        return RefreshStep.LoadWithToken(read)
    }

    /**
     * 没取得刷新令牌时从 L1 重新填充 L2（同步与异步共用）。L1 仍在窗口内说明令牌在别的节点手里、它正在回源：
     * 刷新点退避 [refreshBackoff]（见 [localEntry]），否则本节点的每次命中都会再读一次 L1，持有者崩溃时持续到令牌签发超过 `lease.ttl`。
     */
    private fun refill(storageKey: String, step: RefreshStep.Refill<V>, stamp: Long): LocalEntry<V> {
        val entry = localEntry(step.value, step.ttl, policy.localMaxAge, refreshBackoff, step.refreshable)
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    override fun put(key: K, value: V) {
        runSync { putWithTtl(key, value, putTtl(key, value)) }
    }

    override fun put(key: K, value: V, ttl: Duration) {
        runSync { putWithTtl(key, value, ttl) }
    }

    /**
     * `put` 的默认寿命：`ttlOf` 优先，否则策略寿命加抖动。
     */
    private fun putTtl(key: K, value: V): Duration? = spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl()

    /**
     * 编码在第一次递增之前（异步变体在调用方线程上）完成。
     */
    private suspend fun putWithTtl(key: K, value: V, ttl: Duration?) {
        val storageKey = storageKey(key)
        val envelope = encode(value)
        val bounded = ttl?.boundedTtl()
        write(
            listOf(storageKey),
            { frame -> store.put(storageKey, envelope, bounded, frame) },
            onFailure = throwWriteFailure("put")
        )
    }

    override fun putAll(entries: Map<K, V>) {
        if (entries.isNotEmpty()) {
            runSync { putAllWithTtl(entries, ::putTtl) }
        }
    }

    override fun putAll(entries: Map<K, V>, ttl: Duration) {
        if (entries.isNotEmpty()) {
            runSync { putAllWithTtl(entries) { _, _ -> ttl } }
        }
    }

    /**
     * 一次 [write] 覆盖全部存储 key：戳与 L2 淘汰的顺序对整批成立，L1 批量写入、整批广播一次。
     */
    private suspend fun putAllWithTtl(entries: Map<K, V>, ttlOf: (K, V) -> Duration?) {
        if (entries.isEmpty()) {
            return
        }
        val puts = putsOf(entries, ttlOf)
        write(
            puts.map { it.key },
            { frame -> store.putAll(puts, frame) },
            onFailure = throwWriteFailure("putAll")
        )
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
    // 条件读写（按摘要比较）：只针对 L1，不读写 L2、不回源、不申请 lease。读不懂的内容从不视为没有值：
    // 不是本协议写入的内容（格式 0）、本协议的集合、更高格式版本、解码失败的值，读取抛出；写入抛出，或因摘要不符而冲突。
    // 同步与异步共用 [conditionalGetOf]、[refuseUnreadable]、[conditionalPutOf]。
    // ---------------------------------------------------------------------------------------------

    override fun getIfChanged(key: K, known: Digest?): ConditionalGet<V> = runSync { conditionalGet(key, known) }

    /**
     * 条件读取（同步与异步共用的协程核心）。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun conditionalGet(key: K, known: Digest?): ConditionalGet<V> {
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
     * 条件读取没有可降级的答案（它确认的就是 L1）：无论 `readFailure` 如何都抛出。
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

    override fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut =
        runSync { putIfMatchWithTtl(key, expected, value, putTtl(key, value)) }

    override fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): ConditionalPut =
        runSync { putIfMatchWithTtl(key, expected, value, ttl) }

    /**
     * 与 [putWithTtl] 相同的写入顺序（戳、L2 淘汰在 L1 前后各一次）；冲突与拒绝不修改 L1。非原子广播的后端上拒绝不广播，
     * 冲突只在可能是重放时广播（[conditionalChanged]）。
     */
    private suspend fun putIfMatchWithTtl(key: K, expected: Digest?, value: V, ttl: Duration?): ConditionalPut {
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
            conditionalWriteFailure
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
        is L1ConditionalWrite.Conflict -> result.current == digestOf(envelope)
        // 拒绝（Unreadable）已在 mutate 中抛出，这里只剩 Written
        else -> result == L1ConditionalWrite.Written
    }

    /**
     * 后端以 [UnsupportedOperationException] 拒绝时原样抛出，与不支持的 `Cache` 实现一致；其余失败包装为 `CacheWriteException`。
     */
    private val conditionalWriteFailure: (Exception) -> Unit = { error ->
        if (error is UnsupportedOperationException) {
            throw error
        }
        throw CacheWriteException(name, "putIfMatch", error)
    }

    /**
     * 后端拒绝了写入（key 中是读不懂的内容，未修改、未广播）：计入 `unknownFormats` 并抛出，由 [write] 包装为 `CacheWriteException`
     * （同时计入 `writeFailures`），与 `SetCache` 的元素写入相同。
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

    /**
     * 这次条件写入的摘要：权威标量的 [encode] 已算出（摘要段），不再对载荷重算；有数据源的缓存写入的值不带摘要段时才计算。
     */
    private fun digestOf(envelope: Envelope.Scalar): Digest = envelope.digest ?: Digest.of(envelope)

    private fun conditionalPutOf(envelope: Envelope.Scalar, result: L1ConditionalWrite): ConditionalPut = when (result) {
        L1ConditionalWrite.Written -> ConditionalPut.Written(digestOf(envelope))
        is L1ConditionalWrite.Conflict -> {
            metrics.conditionalConflict()
            ConditionalPut.Conflict(result.current)
        }

        is L1ConditionalWrite.Unreadable -> throw IllegalStateException(
            "Cache[$name] - unreachable: refused write."
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 按版本有序的写入（6.8）：比较与写入由后端在 L1 中原子完成，写入方顺序同 put（不写 L2）；版本不更新时不修改 L1，
    // 也不广播（非原子广播的后端上，相等的版本可能是重放，仍然广播，见 [versionedChanged]）。
    // 只由带版本的缓存（VersionedCache，引擎的值是 Versioned）调用；value 为 null 时写入墓碑（带版本的负缓存）。
    // 读不懂的内容的分类与条件写入相同：总是拒绝。同步与异步共用 [refuseUnreadableVersion]、[versionedPutOf]。
    // ---------------------------------------------------------------------------------------------

    override fun putIfNewer(key: K, value: V, version: Long): VersionedPut = throw notVersioned("putIfNewer")

    override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): VersionedPut = throw notVersioned(
        "putIfNewer"
    )

    override fun invalidateIfNewer(key: K, version: Long): VersionedPut = throw notVersioned("invalidateIfNewer")

    override fun invalidateIfNewer(key: K, version: Long, ttl: Duration): VersionedPut = throw notVersioned(
        "invalidateIfNewer"
    )

    override fun getVersioned(key: K): Versioned<V>? = throw notVersioned("getVersioned")

    /**
     * 引擎本身（普通缓存）不保存版本：按版本的操作只在声明为带版本的缓存（[VersionedCache]）上可用。
     */
    private fun notVersioned(operation: String) = UnsupportedOperationException(
        "Cache[$name] - $operation() needs a versioned cache: declare it with CacheSpec.versioned or a versioned loader."
    )

    /**
     * 按版本写入的默认寿命：值同 [put]（`ttlOf` 优先，否则策略寿命加抖动）；墓碑为策略寿命加抖动（`ttlOf` 需要值）。
     */
    internal fun versionedTtl(
        key: K,
        value: V?
    ): Duration? = value?.let { spec.ttlOf?.ttlOf(key, it) } ?: policy.valueTtl()

    internal fun writeIfNewer(key: K, value: V?, version: Long, ttl: Duration?): VersionedPut =
        runSync { writeVersioned(key, value, version, ttl) }

    internal fun writeIfNewerAsync(key: K, value: V?, version: Long, ttl: Duration?): CompletableFuture<VersionedPut> =
        context.async.runAsync { writeVersioned(key, value, version, ttl) }

    /**
     * 按版本写入（同步与异步共用的协程核心）。
     */
    private suspend fun writeVersioned(key: K, value: V?, version: Long, ttl: Duration?): VersionedPut {
        requireVersion(version)
        val versioned = requireVersioned(versionedOperation(value))
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
            versionedWriteFailure(versionedOperation(value))
        )
        return versionedPutOf(requireNotNull(outcome))
    }

    /**
     * 非原子广播的后端上，按版本写入之后是否广播（同步与异步共用），与原子广播的脚本相同。写入成功时广播；当前版本等于给出的版本时
     * 也广播：这个版本可能是不广播的填充（lease 上的回源）写入的，别的节点还持有更旧的副本（RFC 0005 §4.7，F1），也可能是按 §6.5
     * 重放的、已在 L1 生效而广播失败的写入（理由同 [conditionalChanged]）。版本更旧时不广播。
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

    private fun versionedOperation(value: V?): String = if (value == null) "invalidateIfNewer" else "putIfNewer"

    /**
     * 同 [conditionalWriteFailure]：后端以 [UnsupportedOperationException] 拒绝时原样抛出，其余包装为 `CacheWriteException`。
     */
    private fun versionedWriteFailure(operation: String): (Exception) -> Unit = { error ->
        if (error is UnsupportedOperationException) {
            throw error
        }
        throw CacheWriteException(name, operation, error)
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

    override fun invalidate(key: K) {
        val storageKey = storageKey(key)
        runSync { invalidateStorageKeys(listOf(storageKey)) }
    }

    override fun invalidateAll(keys: Collection<K>) {
        val storageKeys = keys.map { storageKey(it) }.distinct()
        if (storageKeys.isNotEmpty()) {
            runSync { invalidateStorageKeys(storageKeys) }
        }
    }

    private suspend fun invalidateStorageKeys(storageKeys: List<String>) {
        if (storageKeys.isEmpty()) {
            return
        }
        write(
            storageKeys,
            { frame -> store.invalidate(storageKeys, frame) }
        ) { error -> onInvalidateFailure(storageKeys, error) }
    }

    /**
     * 同步与异步共用：[invalidateFailure] 为抛出时抛出 [CacheWriteException]，否则登记重试。
     */
    private fun onInvalidateFailure(storageKeys: List<String>, error: Exception) {
        if (invalidateFailure == InvalidateFailureMode.THROW) {
            throw CacheWriteException(name, "invalidate", error)
        }
        scheduleRetry(storageKeys, error)
    }

    /**
     * 失效写入 L1 失败：登记为“重试中”（未命中时跳过 L1），交给重试队列；成功后本地再递增戳并淘汰 L2。
     */
    private fun scheduleRetry(storageKeys: List<String>, error: Exception) {
        log.warn(error) { "Cache[$name] - invalidate $storageKeys failed, retrying." }
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
     * 运维查看：写摘要段的缓存先校验摘要（不计数），不一致时按读不懂展示。
     */
    override fun describe(envelope: Envelope): Any? {
        digestProblem(envelope)?.let { throw IllegalStateException(it) }
        return decode(envelope)
    }

    /**
     * 带版本的缓存保留版本（[evictKeepingVersion]，RFC 0005 §4.7），其余缓存走正常的失效路径。
     */
    override fun invalidateEncoded(encodedKey: String) {
        val storageKey = storageKeyOf(encodedKey)
        if (versionedValues) {
            runSync { evictKeepingVersion(storageKey) }
        } else {
            runSync { invalidateStorageKeys(listOf(storageKey)) }
        }
    }

    /**
     * 保留版本栅栏的失效（RFC 0005 §4.7，P1）：带版本的内容转为 `E(当前版本)`，迟到的旧版本（数据源投递的事件、回源）仍被拒绝；
     * 没有版本的内容照常删除。写入方顺序同 [invalidateStorageKeys]；`E` 的寿命与 `invalidateIfNewer` 的墓碑相同（策略寿命加抖动）。
     * 失败时抛出 [CacheWriteException]，不进入失效重试：重试的 `DEL` 会丢掉版本。拒绝（带摘要段或读不懂的内容）不修改 L1、不广播。
     *
     * 带摘要段的内容是没有数据源的缓存写入的不带版本的值（D4）：本缓存有数据源时它属于同一 namespace 上的另一个缓存，拒绝
     * （[AuthoritativeContentException]，计入 `typeMismatches`）；本缓存没有数据源时它就是本缓存 `put` 的值，没有版本可保留，
     * 改走正常的失效路径。
     */
    private suspend fun evictKeepingVersion(storageKey: String) {
        val versioned = requireVersioned(EVICT)
        var outcome: L1Eviction? = null
        write(
            listOf(storageKey),
            { frame ->
                val result = versioned.evictKeepingVersion(storageKey, policy.valueTtl()?.boundedTtl(), frame)
                outcome = result
                refuseEviction(storageKey, result)
            },
            { outcome == L1Eviction.Evicted },
            throwWriteFailure(EVICT)
        )
        if (outcome == L1Eviction.Authoritative) {
            invalidateStorageKeys(listOf(storageKey))
        }
    }

    private fun refuseEviction(storageKey: String, result: L1Eviction): Void? = when (result) {
        L1Eviction.Evicted -> null
        is L1Eviction.Unreadable -> throw refusedFormat(storageKey, result.format)
        L1Eviction.Authoritative -> if (authoritative) {
            null
        } else {
            metrics.typeMismatch()
            throw AuthoritativeContentException(name, storageKey).also { log.warn { it.message } }
        }
    }

    /**
     * 异步视图：命中判断与同步路径相同（同一个 L2），未命中走异步路径。返回给用户的 future 都由这里新建并显式完成。
     */
    private inner class AsyncView : AsyncCache<K, V> {
        override val name: String
            get() = this@CoherentEngine.name

        override fun get(key: K): CompletableFuture<V?> = Futures.attempt {
            val localKey = localKey(key)
            val entry = local.getIfPresent(localKey)
            if (entry != null) {
                val now = clock.nowMillis()
                if (entry.expiresAt > now) {
                    if (entry.refreshAt <= now) {
                        refreshAsync(key, localKey, entry)
                    }
                    countLocalHit()
                    return@attempt CompletableFuture.completedFuture(entry.value)
                }
            }
            missAsync(key, storageKeyOfLocal(localKey), loadSource) { it.value }
        }

        override fun get(key: K, loader: AsyncCacheLoader<K, V>): CompletableFuture<V?> = Futures.attempt {
            val localKey = localKey(key)
            val entry = local.getIfPresent(localKey)
            if (entry != null && !entry.unresolved && entry.expiresAt > clock.nowMillis()) {
                countLocalHit()
                return@attempt CompletableFuture.completedFuture(entry.value)
            }
            missAsync(
                key,
                storageKeyOfLocal(localKey),
                CoreSource(AsyncSource.Native(loader), LoadOperation.LOAD)
            ) { it.value }
        }

        override fun lookup(key: K): CompletableFuture<Lookup<V>> = Futures.attempt {
            val localKey = localKey(key)
            val entry = localHit(key, localKey)
            if (entry != null) {
                return@attempt CompletableFuture.completedFuture(lookupOf(entry))
            }
            missAsync(key, storageKeyOfLocal(localKey), loadSource) { lookupOf(it) }
        }

        override fun getAll(keys: Collection<K>): CompletableFuture<Map<K, V>> = Futures.attempt {
            val resolved = LinkedHashMap<K, LocalEntry<V>?>()
            val pending = collectPending(keys, resolved)
            if (pending.isEmpty()) {
                return@attempt CompletableFuture.completedFuture(valuesOf(resolved))
            }
            context.async.runAsync {
                for ((storageKey, entry) in loadPending(pending, loadSource)) {
                    pending.getValue(storageKey).forEach { resolved[it] = entry }
                }
                valuesOf(resolved)
            }
        }

        override fun put(key: K, value: V): CompletableFuture<Void?> = runAsyncCommand {
            putWithTtl(key, value, putTtl(key, value))
        }

        override fun put(key: K, value: V, ttl: Duration): CompletableFuture<Void?> = runAsyncCommand {
            putWithTtl(key, value, ttl)
        }

        override fun putAll(entries: Map<K, V>): CompletableFuture<Void?> = runAsyncCommand {
            putAllWithTtl(entries, ::putTtl)
        }

        override fun putAll(entries: Map<K, V>, ttl: Duration): CompletableFuture<Void?> = runAsyncCommand {
            putAllWithTtl(entries) { _, _ -> ttl }
        }

        override fun invalidate(key: K): CompletableFuture<Void?> = runAsyncCommand {
            invalidateStorageKeys(listOf(storageKey(key)))
        }

        override fun invalidateAll(keys: Collection<K>): CompletableFuture<Void?> = runAsyncCommand {
            invalidateStorageKeys(keys.map { storageKey(it) }.distinct())
        }

        override fun getIfChanged(key: K, known: Digest?): CompletableFuture<ConditionalGet<V>> =
            context.async.runAsync { conditionalGet(key, known) }

        override fun putIfMatch(key: K, expected: Digest?, value: V): CompletableFuture<ConditionalPut> =
            context.async.runAsync { putIfMatchWithTtl(key, expected, value, putTtl(key, value)) }

        override fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): CompletableFuture<ConditionalPut> =
            context.async.runAsync { putIfMatchWithTtl(key, expected, value, ttl) }

        override fun putIfNewer(key: K, value: V, version: Long): CompletableFuture<VersionedPut> =
            CompletableFuture.failedFuture(notVersioned("putIfNewer"))

        override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): CompletableFuture<VersionedPut> =
            CompletableFuture.failedFuture(notVersioned("putIfNewer"))

        override fun invalidateIfNewer(key: K, version: Long): CompletableFuture<VersionedPut> =
            CompletableFuture.failedFuture(notVersioned("invalidateIfNewer"))

        override fun invalidateIfNewer(key: K, version: Long, ttl: Duration): CompletableFuture<VersionedPut> =
            CompletableFuture.failedFuture(notVersioned("invalidateIfNewer"))

        override fun getVersioned(key: K): CompletableFuture<Versioned<V>?> =
            CompletableFuture.failedFuture(notVersioned("getVersioned"))

        override fun sync(): Cache<K, V> = this@CoherentEngine

        override fun toString(): String = "AsyncView(${this@CoherentEngine})"

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
    }

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

        @Suppress("UNCHECKED_CAST")
        fun <R> uncheckedCast(value: Any?): R = value as R
    }
}

/**
 * 数据源（RFC 0004 §3.2）：同步数据源或异步数据源。
 */
private sealed interface AsyncSource<K : Any, V : Any> {
    class Native<K : Any, V : Any>(val loader: AsyncCacheLoader<K, V>) : AsyncSource<K, V>

    class Blocking<K : Any, V : Any>(val loader: CacheLoader<K, V>) : AsyncSource<K, V>
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
     * 回源：[token] 为取得的 lease；[refreshable] 为 `false` 时副本不设刷新点（读到更高格式，P14）。
     */
    class Load(val token: Token?, val refreshable: Boolean = true) : BatchStep<Nothing>

    class Done<V>(val entry: LocalEntry<V>) : BatchStep<V>

    class Corrupt(val read: L1Read.Hit, val error: Exception) : BatchStep<Nothing>

    class Failed(val error: CacheReadException) : BatchStep<Nothing>
}

/**
 * 提前刷新读到 L1 之后的判定（同步与异步共用）。
 */
private sealed interface RefreshStep<out V> {
    class Reload(val releaseToken: Boolean) : RefreshStep<Nothing>

    /**
     * 没取得刷新令牌：以读到的值重新填充 L2；[refreshable] 为 `false` 时不设刷新点（带摘要段的值，D4）。
     */
    class Refill<V>(val value: V?, val ttl: Duration?, val refreshable: Boolean = true) : RefreshStep<V>

    /**
     * 取得了刷新令牌：回源并凭它写回；[read] 是取得令牌的那次读取（版本拒绝时据此保留副本）。
     */
    class LoadWithToken(val read: L1Read.Hit) : RefreshStep<Nothing>
}

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
