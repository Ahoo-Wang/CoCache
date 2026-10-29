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
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteConditionalRead
import me.ahoo.cocache.api.spi.RemoteConditionalWrite
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.RemoteVersionedWrite
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.internal.Futures
import me.ahoo.cocache.core.internal.handleNext
import me.ahoo.cocache.core.internal.thenNext
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * 数据源为权威的一致性缓存引擎（RFC 0003 §4.4–§4.6）。
 *
 * - L1 栅栏：未命中时由读脚本原子取得 lease，回源结果只能凭 lease 写回；失效销毁 lease。
 * - L2 栅栏：失效戳。填充方读 L1 前取戳；写入方在修改 L1 前后各递增一次。L2 只由读 L1 的路径填充。
 * - 同步路径上 loader 总是在调用方（SingleFlight leader）线程上同步执行。
 * - 异步视图（[async]，RFC 0004）共享同一个 L2、失效戳、SingleFlight 与 lease；协议决策（读到什么就做什么）
 *   由同步与异步共用的函数给出（[hitOutcome]、[completeLoad]、[batchStep]、[refreshStep] 等），两条路径只在“如何等待”上不同。
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class CoherentEngine<K : Any, V : Any>(
    private val spec: CacheSpec<K, V>,
    store: RemoteStore,
    context: EngineContext,
    private val format: ValueFormat<V> = CodecFormat(spec.codec),
) : AbstractEngine<K, V>(spec.name, spec.namespace, spec.keyPrefix, spec.keyEncoder, spec.policy, store, context),
    Cache<K, V> {

    private val keyFilter = spec.keyFilter
    private val backendFormat = context.backend.format
    private val permits: LoadPermits? = policy.load.maxConcurrent.takeIf { it > 0 }?.let {
        LoadPermits(
            it,
            context.async
        )
    }
    private val loadObserver: LoadObserver = context.loadObserver
    private val asyncTimeout: Duration = policy.load.asyncTimeout

    /**
     * 同步路径的数据源：声明的是异步数据源时，在调用方线程上等待它（受 [asyncTimeout] 约束）。
     */
    private val loader: CacheLoader<K, V>? = spec.loader ?: spec.asyncLoader?.let { AwaitingLoader(it) }

    /**
     * 提前刷新所用的数据源：与 [loader] 行为相同，只用于让回源观测区分刷新与未命中加载。
     */
    private val refreshLoader: CacheLoader<K, V>? = loader?.let { RefreshLoader(it) }

    /**
     * 异步路径的数据源：同步数据源只在阻塞执行器上执行。
     */
    private val asyncSource: AsyncSource<K, V>? = spec.asyncLoader?.let { AsyncSource.Native(it) }
        ?: spec.loader?.let { AsyncSource.Blocking(it) }

    /**
     * 失效写入 L1 失败、仍在重试中的存储 key：未命中时跳过 L1（L1 中仍是旧值）。
     */
    private val pendingInvalidations = ConcurrentHashMap<String, Int>()

    /**
     * 正在提前刷新的存储 key：每个节点每个 key 同一时刻至多一次。
     */
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    private val view = AsyncView()

    override fun async(): AsyncCache<K, V> = view

    override fun get(key: K): V? = entryOf(key).value

    override fun lookup(key: K): Lookup<V> = lookupOf(entryOf(key))

    /**
     * 以调用方给出的 [loader] 回源，与 [get] 走同一条受 lease 与失效戳保护的路径（供 Spring `@Cacheable(sync = true)` 等使用）。
     */
    override fun get(key: K, loader: CacheLoader<K, V>): V? {
        val storageKey = storageKey(key)
        val entry = local.getIfPresent(storageKey)
        // 无数据源缓存的“未命中即不存在”只是没有答案，不能挡住调用方给出的 loader
        if (entry != null && !entry.unresolved && entry.expiresAt > clock.nowMillis()) {
            countLocalHit()
            return entry.value
        }
        return miss(key, storageKey, loader).value
    }

    private fun entryOf(key: K): LocalEntry<V> {
        val storageKey = storageKey(key)
        val entry = local.getIfPresent(storageKey)
        if (entry != null) {
            val now = clock.nowMillis()
            if (entry.expiresAt > now) {
                if (entry.refreshAt <= now) {
                    refreshAsync(key, storageKey, entry)
                }
                countLocalHit()
                return entry
            }
        }
        return miss(key, storageKey)
    }

    /**
     * 未命中路径，与命中路径分开以保证 [entryOf] 足够小、可内联。
     */
    private fun miss(key: K, storageKey: String, source: CacheLoader<K, V>? = loader): LocalEntry<V> {
        filteredEntry(storageKey)?.let { return it }
        return loadMiss(key, storageKey, source)
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
     * 未命中的加载（已计数、已过 key 过滤器）。
     */
    private fun loadMiss(key: K, storageKey: String, source: CacheLoader<K, V>?): LocalEntry<V> {
        // 先于本次读取发生的失效此时必已递增戳：不接受开始于这些失效之前的共享加载（读己之写）
        val observed = stamps.current(storageKey)
        val loaded = flights.execute(storageKey) { loadThrough(key, storageKey, source) }
        if (loaded.stamp >= observed && usable(loaded.entry, source != null)) {
            return loaded.entry
        }
        // 新一轮加载必然开始于上一轮结束之后（SingleFlight 先注销、再发布），因此至多重试一次
        val retried = flights.execute(storageKey) { loadThrough(key, storageKey, source) }.entry
        if (usable(retried, source != null)) {
            return retried
        }
        // 两次都合并到了不带数据源的加载（get(key) 与 get(key, loader) 并发）：自己回源，写回仍受戳保护
        return loadThrough(key, storageKey, source).entry
    }

    /**
     * 合并到的加载若没有数据源，而本次调用带有数据源，则其结果不可用。
     */
    private fun usable(entry: LocalEntry<V>, hasSource: Boolean): Boolean = !(entry.unresolved && hasSource)

    @Suppress("TooGenericExceptionCaught")
    private fun loadThrough(key: K, storageKey: String, source: CacheLoader<K, V>? = loader): Loaded<V> {
        // 在 SingleFlight 注册之后取戳；整个调用（含等待 lease 的重读）只取这一次
        val stamp = stamps.current(storageKey)
        if (pendingInvalidations.containsKey(storageKey)) {
            return Loaded(loadBypassingRemote(key, storageKey, stamp, source), stamp)
        }
        val lease = LeaseRequest(context.nextToken(), policy.lease.ttl)
        val read = try {
            readWaitingLease(storageKey, lease)
        } catch (e: Exception) {
            onReadFailure("read", storageKey, e)
            return Loaded(loadBypassingRemote(key, storageKey, stamp, source), stamp)
        }
        val entry = when (read) {
            is RemoteRead.Hit -> onHit(key, storageKey, read, stamp, lease, source)
            is RemoteRead.Miss -> loadAndFill(key, storageKey, stamp, lease.token.takeIf { read.leaseGranted }, source)
            is RemoteRead.Leased -> {
                metrics.leaseWaitTimeout()
                loadBypassingRemote(key, storageKey, stamp, source)
            }
        }
        return Loaded(entry, stamp)
    }

    /**
     * 其它节点持有 lease 时，以短退避重读，最多等待 `min(剩余寿命, lease.wait)`。
     */
    private fun readWaitingLease(storageKey: String, lease: LeaseRequest): RemoteRead {
        var read = store.read(storageKey, lease, null)
        if (read !is RemoteRead.Leased) {
            return read
        }
        metrics.leaseWait()
        val leased: RemoteRead.Leased = read
        return observed(LoadOperation.LEASE_WAIT, 1) { awaitLease(storageKey, lease, leased) }
    }

    private fun awaitLease(storageKey: String, lease: LeaseRequest, leased: RemoteRead.Leased): RemoteRead {
        var read: RemoteRead = leased
        val deadline = leaseDeadline(leased)
        var backoff = INITIAL_BACKOFF_NANOS
        while (read is RemoteRead.Leased) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                break
            }
            LockSupport.parkNanos(minOf(backoff, remaining))
            backoff = nextBackoff(backoff)
            read = store.read(storageKey, lease, null)
        }
        return read
    }

    private fun leaseDeadline(leased: RemoteRead.Leased): Long = System.nanoTime() + minOf(leased.remaining, policy.lease.wait).toNanos()

    private fun onHit(
        key: K,
        storageKey: String,
        read: RemoteRead.Hit,
        stamp: Long,
        lease: LeaseRequest,
        source: CacheLoader<K, V>?
    ): LocalEntry<V> {
        return when (val outcome = hitOutcome(read)) {
            HitOutcome.Newer -> {
                newerFormatFailure(storageKey, read, source != null)?.let { throw it }
                metrics.unknownFormat()
                loadBypassingRemote(key, storageKey, stamp, source)
            }

            is HitOutcome.Corrupt -> {
                foreignFormatFailure(storageKey, read, outcome.error, source != null)?.let { throw it }
                onCorrupt(key, storageKey, read, stamp, lease, source, outcome.error)
            }

            is HitOutcome.Decoded -> onRemoteHit(storageKey, outcome.value, read, stamp)
        }
    }

    /**
     * 同步与异步共用：读到读不懂的内容（格式 0 或同版本解码失败）时按 [ForeignFormatMode] 决定。
     * `FAIL` 返回要抛出的异常（已计入 `unknownFormats`，不修改 L1、不写 L2）；`RECLAIM` 返回 `null`，调用方照常条件回收。
     * 未配置时按这次读取有没有数据源决定：没有数据源时 L1 是唯一副本，回收就是删除。与 `readFailure` 无关。
     */
    private fun foreignFormatFailure(
        storageKey: String,
        read: RemoteRead.Hit,
        error: Exception,
        hasSource: Boolean
    ): CacheReadException? {
        val mode = policy.foreignFormat ?: if (hasSource) ForeignFormatMode.RECLAIM else ForeignFormatMode.FAIL
        if (mode == ForeignFormatMode.RECLAIM) {
            return null
        }
        return readFailure(ForeignFormatException(name, storageKey, read.format, error))
    }

    /**
     * 同步与异步共用：读到更高版本写入的值（滚动升级期间）。有数据源时返回 `null`，调用方绕过 L1 回源（既不回收也不写回）。
     * 没有数据源时 L1 是唯一副本，绕过 L1 只能得到“不存在”：按 [ForeignFormatMode] 决定（未配置为 `FAIL`），
     * `FAIL` 返回要抛出的异常（已计入 `unknownFormats`，不修改 L1、不写 L2），`RECLAIM` 返回 `null`（按不存在处理）。
     */
    private fun newerFormatFailure(storageKey: String, read: RemoteRead.Hit, hasSource: Boolean): CacheReadException? {
        if (hasSource || policy.foreignFormat == ForeignFormatMode.RECLAIM) {
            return null
        }
        return readFailure(ForeignFormatException(name, storageKey, read.format, null, newer = true))
    }

    private fun readFailure(failure: ForeignFormatException): CacheReadException {
        metrics.unknownFormat()
        log.warn { failure.message }
        return CacheReadException(name, "read", failure)
    }

    /**
     * 同步与异步共用的命中判定：更高版本写入的值（滚动升级期间）既不回收也不写回；不是本协议写入的内容或解码失败按损坏处理。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun hitOutcome(read: RemoteRead.Hit): HitOutcome<V> {
        if (read.format > backendFormat) {
            return HitOutcome.Newer
        }
        return try {
            check(read.format >= MIN_FORMAT) { "Unrecognized remote content (not written by this protocol)." }
            HitOutcome.Decoded(decode(read.envelope))
        } catch (e: Exception) {
            HitOutcome.Corrupt(e)
        }
    }

    private fun onRemoteHit(storageKey: String, value: V?, read: RemoteRead.Hit, stamp: Long): LocalEntry<V> {
        metrics.remoteHit()
        val entry = localEntry(value, read.ttl, policy.localMaxAge)
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    /**
     * 同版本载荷损坏：仅当内容仍是读到的那一份时转为 lease（条件回收，读路径从不无条件删除），再正常回源。
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private fun onCorrupt(
        key: K,
        storageKey: String,
        read: RemoteRead.Hit,
        stamp: Long,
        lease: LeaseRequest,
        source: CacheLoader<K, V>?,
        error: Exception
    ): LocalEntry<V> {
        logCorrupt(storageKey, error)
        val reclaimed = try {
            store.reclaim(storageKey, Digest.of(read.envelope), lease)
        } catch (e: Exception) {
            onReadFailure("reclaim", storageKey, e)
            false
        }
        if (!reclaimed) {
            return loadBypassingRemote(key, storageKey, stamp, source)
        }
        return loadAndFill(key, storageKey, stamp, lease.token, source)
    }

    private fun logCorrupt(storageKey: String, error: Exception) {
        log.warn(error) { "Cache[$name] - key[$storageKey] - corrupted payload, reclaim and reload." }
        metrics.reclaim()
    }

    private fun decode(envelope: Envelope): V? = when (envelope) {
        is Envelope.Scalar -> format.decode(envelope)
        Envelope.Absent -> null
        is Envelope.Members -> throw IllegalStateException("Cache[$name] - expected a scalar but found a set.")
    }

    private fun envelopeOf(value: V?): Envelope = value?.let { format.encode(it) } ?: Envelope.Absent

    private fun ttlOf(key: K, value: V?): Duration? {
        if (value == null) {
            return policy.absentTtl
        }
        return (spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl())?.boundedTtl()
    }

    /**
     * 回源并凭 [token] 写回 L1。没有令牌或写回被拒绝（期间发生失效、或 loader 慢于 lease 寿命）时，
     * 结果照常返回，L2 以降级限龄填充（否则慢 key 会一直未命中）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun loadAndFill(
        key: K,
        storageKey: String,
        stamp: Long,
        token: Token?,
        source: CacheLoader<K, V>?
    ): LocalEntry<V> {
        if (source == null) {
            token?.let { release(storageKey, it) }
            return absentWithoutLoader(storageKey, stamp)
        }
        // 计算寿命与编码也可能抛出（用户的 ttlOf、Codec）：与回源一起释放 lease
        val value: V?
        val prepared: Prepared
        try {
            value = loadFromSource(key, source)
            prepared = prepare(key, value)
        } catch (e: Throwable) {
            token?.let { release(storageKey, it) }
            throw e
        }
        val filled = token != null && fillRemote(Fill(storageKey, token, prepared.envelope, prepared.ttl))
        return completeLoad(storageKey, stamp, token, value, prepared.ttl, filled)
    }

    private class Prepared(val ttl: Duration?, val envelope: Envelope)

    private fun prepare(key: K, value: V?): Prepared = Prepared(ttlOf(key, value), envelopeOf(value))

    /**
     * 同步与异步共用：回源结果写回 L1 之后（或没有令牌时）的计数与 L2 填充。
     */
    @Suppress("LongParameterList")
    private fun completeLoad(
        storageKey: String,
        stamp: Long,
        token: Token?,
        value: V?,
        ttl: Duration?,
        filled: Boolean
    ): LocalEntry<V> {
        if (token != null && !filled) {
            metrics.fillRejected()
        }
        if (!filled) {
            metrics.degradedFill()
        }
        val entry = localEntry(value, ttl, if (filled) policy.localMaxAge else policy.degradedLocalMaxAge)
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
     * 不经过 L1 的回源：L1 故障、等待 lease 超时、未知格式、失效重试中。L2 以降级限龄填充（仍受戳保护）。
     */
    private fun loadBypassingRemote(
        key: K,
        storageKey: String,
        stamp: Long,
        source: CacheLoader<K, V>?
    ): LocalEntry<V> = completeBypass(key, storageKey, stamp, loadFromSource(key, source))

    private fun completeBypass(key: K, storageKey: String, stamp: Long, value: V?): LocalEntry<V> {
        metrics.degradedFill()
        val entry = localEntry(value, ttlOf(key, value), policy.degradedLocalMaxAge)
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fillRemote(fill: Fill): Boolean {
        return try {
            store.fill(fill)
        } catch (e: Exception) {
            onRemoteFailure("fill", fill.key, e)
            false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun release(storageKey: String, token: Token) {
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

    private fun loadFromSource(key: K, source: CacheLoader<K, V>?): V? {
        val sourceLoader = source ?: return null
        val operation = if (sourceLoader === refreshLoader) LoadOperation.REFRESH else LoadOperation.LOAD
        return guarded(operation, 1) { sourceLoader.load(key) }
    }

    private fun loadAllFromSource(keys: Collection<K>): Map<K, V> {
        val sourceLoader = requireNotNull(loader)
        return guarded(LoadOperation.LOAD_ALL, keys.size) { sourceLoader.loadAll(keys) }
    }

    /**
     * 回源并发上限（只计 leader）、耗时统计与回源观测（只包住回源调用本身；默认的 [LoadObserver.NOOP] 不产生任何调用）。
     */
    private inline fun <R> guarded(operation: LoadOperation, keyCount: Int, crossinline block: () -> R): R {
        val semaphore = permits
        if (semaphore != null && !semaphore.tryAcquire(policy.load.acquireTimeout.toNanos())) {
            metrics.loadRejected()
            throw SourceLoadRejectedException(name, policy.load)
        }
        val start = System.nanoTime()
        var failed = true
        try {
            return observed(operation, keyCount, block).also { failed = false }
        } finally {
            metrics.load(System.nanoTime() - start, failed)
            semaphore?.release()
        }
    }

    /**
     * 回源观测：默认的 [LoadObserver.NOOP] 直接执行，不分配也不调用。
     */
    private inline fun <R> observed(operation: LoadOperation, keyCount: Int, crossinline block: () -> R): R {
        val observer = loadObserver
        return if (observer === LoadObserver.NOOP) block() else observer.observe(name, operation, keyCount) { block() }
    }

    override fun getAll(keys: Collection<K>): Map<K, V> {
        val resolved = LinkedHashMap<K, LocalEntry<V>?>()
        val pending = collectPending(keys, resolved)
        if (pending.isNotEmpty()) {
            for ((storageKey, entry) in loadPending(pending)) {
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
            val storageKey = storageKey(key)
            val entry = localHit(key, storageKey)
            resolved[key] = entry
            if (entry != null) {
                continue
            }
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
    private fun localHit(key: K, storageKey: String): LocalEntry<V>? {
        val entry = local.getIfPresent(storageKey) ?: return null
        val now = clock.nowMillis()
        if (entry.expiresAt <= now) {
            return null
        }
        if (entry.refreshAt <= now) {
            refreshAsync(key, storageKey, entry)
        }
        countLocalHit()
        return entry
    }

    /**
     * 批量加载未命中的 key：与在途的单 key 加载合并；共享到的加载若开始于本次读取已观察到的失效之前，改走单 key 路径。
     */
    private fun loadPending(pending: Map<String, List<K>>): Map<String, LocalEntry<V>> {
        val observed = pending.keys.associateWith { stamps.current(it) }
        val loaded = flights.executeAll(pending.keys) { led ->
            loadAllThrough(led.associateWith { pending.getValue(it).first() })
        }
        return pending.mapValues { (storageKey, sourceKeys) ->
            val result = loaded[storageKey]
            if (result != null && result.stamp >= observed.getValue(storageKey)) {
                result.entry
            } else {
                loadMiss(sourceKeys.first(), storageKey, loader)
            }
        }
    }

    /**
     * 批量：一次 L1 读取（为未命中的 key 申请 lease）→ 一次批量回源 → 一次批量写回。
     * 批量路径不等待其它节点的 lease（这些 key 直接回源且不写 L1）；损坏的载荷与单 key 路径一样条件回收。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun loadAllThrough(led: Map<String, K>): Map<String, Loaded<V>> {
        val loadStamps = led.keys.associateWith { stamps.current(it) }
        val result = LinkedHashMap<String, Loaded<V>>()
        val readKeys = batchReadKeys(led)
        val leases = batchLeases(readKeys)
        val reads = try {
            if (readKeys.isEmpty()) emptyMap() else store.readAll(readKeys, leases)
        } catch (e: Exception) {
            onReadFailure("readAll", readKeys.first(), e)
            emptyMap()
        }
        val toLoad = LinkedHashMap<String, Token?>()
        // FAIL 策略下的读不懂的 key：先完成其余 key（释放或凭 lease 写回），再抛出，与异步路径一致
        var foreign: CacheReadException? = null
        for (storageKey in led.keys) {
            val stamp = loadStamps.getValue(storageKey)
            when (val step = batchStep(storageKey, reads[storageKey], stamp, leases[storageKey], loader != null)) {
                is BatchStep.Load -> toLoad[storageKey] = step.token
                is BatchStep.Done -> result[storageKey] = Loaded(step.entry, stamp)
                is BatchStep.Failed -> foreign = foreign ?: step.error
                // 与单 key 路径一致：条件回收后回源（否则损坏的载荷会一直留到 L1 过期）
                is BatchStep.Corrupt -> {
                    val failure = foreignFormatFailure(storageKey, step.read, step.error, loader != null)
                    if (failure != null) {
                        foreign = foreign ?: failure
                        continue
                    }
                    result[storageKey] = Loaded(
                        onCorrupt(led.getValue(storageKey), storageKey, step.read, stamp, leases.getValue(storageKey), loader, step.error),
                        stamp
                    )
                }
            }
        }
        if (toLoad.isNotEmpty()) {
            loadAndFillBatch(led, toLoad, loadStamps, result)
        }
        foreign?.let { throw it }
        return result
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
        read: RemoteRead?,
        stamp: Long,
        lease: LeaseRequest?,
        hasSource: Boolean
    ): BatchStep<V> {
        if (read !is RemoteRead.Hit) {
            return BatchStep.Load((read as? RemoteRead.Miss)?.takeIf { it.leaseGranted }?.let { lease?.token })
        }
        return when (val outcome = hitOutcome(read)) {
            HitOutcome.Newer -> {
                // 更高版本写入的值：既不回收也不写回；没有数据源时按 foreignFormat 失败（命中没有取得 lease，无需释放）
                newerFormatFailure(storageKey, read, hasSource)?.let { return BatchStep.Failed(it) }
                metrics.unknownFormat()
                BatchStep.Load(null)
            }

            is HitOutcome.Corrupt -> BatchStep.Corrupt(read, outcome.error)
            is HitOutcome.Decoded -> BatchStep.Done(onRemoteHit(storageKey, outcome.value, read, stamp))
        }
    }

    /**
     * 一次批量回源并凭各自的 lease 一次批量写回；回源失败时释放全部 lease。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun loadAndFillBatch(
        led: Map<String, K>,
        toLoad: Map<String, Token?>,
        loadStamps: Map<String, Long>,
        result: MutableMap<String, Loaded<V>>
    ) {
        if (loader == null) {
            toLoad.forEach { (storageKey, token) -> token?.let { release(storageKey, it) } }
            completeBatchWithoutLoader(toLoad, loadStamps, result)
            return
        }
        val values: Map<K, V>
        val prepared: PreparedBatch
        try {
            values = loadAllFromSource(toLoad.keys.map { led.getValue(it) })
            prepared = prepareBatch(led, toLoad, values)
        } catch (e: Throwable) {
            toLoad.forEach { (storageKey, token) -> token?.let { release(storageKey, it) } }
            throw e
        }
        val filled = fillAllRemote(prepared.fills)
        completeBatch(led, toLoad, values, prepared.ttls, filled, loadStamps, result)
    }

    private class PreparedBatch(val ttls: Map<String, Duration?>, val fills: List<Fill>)

    private fun prepareBatch(led: Map<String, K>, toLoad: Map<String, Token?>, values: Map<K, V>): PreparedBatch {
        val ttls = toLoad.keys.associateWith { storageKey -> led.getValue(storageKey).let { ttlOf(it, values[it]) } }
        val fills = toLoad.mapNotNull { (storageKey, token) ->
            token?.let { Fill(storageKey, it, envelopeOf(values[led.getValue(storageKey)]), ttls[storageKey]) }
        }
        return PreparedBatch(ttls, fills)
    }

    @Suppress("LongParameterList")
    private fun completeBatch(
        led: Map<String, K>,
        toLoad: Map<String, Token?>,
        values: Map<K, V>,
        ttls: Map<String, Duration?>,
        filled: Map<String, Boolean>,
        loadStamps: Map<String, Long>,
        result: MutableMap<String, Loaded<V>>
    ) {
        for ((storageKey, token) in toLoad) {
            val stamp = loadStamps.getValue(storageKey)
            val ok = token != null && filled[storageKey] == true
            val entry = completeLoad(storageKey, stamp, token, values[led.getValue(storageKey)], ttls[storageKey], ok)
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

    private fun filledByKey(fills: List<Fill>, outcomes: List<Boolean>): Map<String, Boolean> =
        fills.indices.associate { fills[it].key to (outcomes.getOrNull(it) == true) }

    @Suppress("TooGenericExceptionCaught")
    private fun fillAllRemote(fills: List<Fill>): Map<String, Boolean> {
        if (fills.isEmpty()) {
            return emptyMap()
        }
        val outcomes = try {
            store.fillAll(fills)
        } catch (e: Exception) {
            onRemoteFailure("fillAll", fills.first().key, e)
            return emptyMap()
        }
        return filledByKey(fills, outcomes)
    }

    /**
     * 后台刷新：经 SingleFlight 与并发的未命中合并。只有在 L1 上取得刷新令牌的节点回源并凭令牌写回；
     * 其它节点只从 L1 重新填充 L2。刷新失败或被拒绝时只告警，值照常到期。
     * 异步数据源的刷新在刷新执行器上发起、以异步路径执行，不在刷新执行器的线程上等待（RFC 0004 §5.7）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun refreshAsync(key: K, storageKey: String, observed: LocalEntry<V>) {
        // 没有数据源的缓存（如只经 Spring Cache 写入）无法回源刷新
        if (loader == null || closed.get() || !refreshing.add(storageKey)) {
            return
        }
        // 调用方读到副本之后、取得守卫之前，上一次刷新可能已完成：它先替换副本再释放守卫，
        // 因此副本已不是读到的那一份时，这次触发来自旧的观察，不再刷新（否则同一节点对同一 key 多刷新一次）
        if (local.getIfPresent(storageKey) !== observed) {
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
                    flights.execute(storageKey) { refreshThrough(key, storageKey) }
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
        flights.executeAsync(storageKey, executor) { refreshThroughAsync(key, storageKey) }.whenComplete { _, error ->
            refreshing.remove(storageKey)
            if (error != null) {
                log.warn(error) { "Cache[$name] - key[$storageKey] - refresh failed." }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun refreshThrough(key: K, storageKey: String): Loaded<V> {
        // 失效仍在重试：L1 可能还是旧值，不能从它重新填充 L2（loadThrough 会绕过 L1）
        val window = policy.refreshWindow
        if (window == null || pendingInvalidations.containsKey(storageKey)) {
            return loadThrough(key, storageKey, refreshLoader)
        }
        val stamp = stamps.current(storageKey)
        val token = context.nextToken()
        val read = try {
            store.read(storageKey, null, RefreshRequest(window, token, policy.lease.ttl))
        } catch (e: Exception) {
            onRemoteFailure("read", storageKey, e)
            return loadThrough(key, storageKey, refreshLoader)
        }
        return when (val step = refreshStep(read)) {
            is RefreshStep.Reload -> {
                if (step.releaseToken) {
                    release(storageKey, token)
                }
                loadThrough(key, storageKey, refreshLoader)
            }

            is RefreshStep.Refill -> Loaded(refill(storageKey, step, stamp), stamp)
            RefreshStep.LoadWithToken -> Loaded(loadAndFill(key, storageKey, stamp, token, refreshLoader), stamp)
        }
    }

    /**
     * 同步与异步共用的刷新判定：取得令牌才回源；没取得令牌只从 L1 重新填充 L2；L1 已无值或内容不可读时退回普通加载。
     */
    private fun refreshStep(read: RemoteRead): RefreshStep<V> {
        if (read !is RemoteRead.Hit) {
            return RefreshStep.Reload(false)
        }
        if (read.format > backendFormat || read.format < MIN_FORMAT) {
            return RefreshStep.Reload(read.refreshGranted)
        }
        if (!read.refreshGranted) {
            val value = runCatching { decode(read.envelope) }.getOrElse { return RefreshStep.Reload(false) }
            return RefreshStep.Refill(value, read.ttl)
        }
        return RefreshStep.LoadWithToken
    }

    private fun refill(storageKey: String, step: RefreshStep.Refill<V>, stamp: Long): LocalEntry<V> {
        val entry = localEntry(step.value, step.ttl, policy.localMaxAge)
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    override fun put(key: K, value: V) {
        putWithTtl(key, value, spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl())
    }

    override fun put(key: K, value: V, ttl: Duration) {
        putWithTtl(key, value, ttl)
    }

    private fun putWithTtl(key: K, value: V, ttl: Duration?) {
        val storageKey = storageKey(key)
        val envelope = format.encode(value)
        val bounded = ttl?.boundedTtl()
        write(
            listOf(storageKey),
            { frame -> store.put(storageKey, envelope, bounded, frame) },
            throwWriteFailure("put")
        )
    }

    override fun putAll(entries: Map<K, V>) {
        putAllWithTtl(entries) { key, value -> spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl() }
    }

    override fun putAll(entries: Map<K, V>, ttl: Duration) {
        putAllWithTtl(entries) { _, _ -> ttl }
    }

    /**
     * 一次 [write] 覆盖全部存储 key：戳与 L2 淘汰的顺序对整批成立，L1 批量写入、整批广播一次。
     */
    private inline fun putAllWithTtl(entries: Map<K, V>, ttlOf: (K, V) -> Duration?) {
        if (entries.isEmpty()) {
            return
        }
        val puts = putsOf(entries, ttlOf)
        write(puts.map { it.key }, { frame -> store.putAll(puts, frame) }, throwWriteFailure("putAll"))
    }

    /**
     * 编码后碰撞的存储 key 以后出现的为准，每个存储 key 只编码一次（同步与异步共用）。
     */
    private inline fun putsOf(entries: Map<K, V>, ttlOf: (K, V) -> Duration?): List<Put> {
        val latest = LinkedHashMap<String, Map.Entry<K, V>>(entries.size)
        entries.entries.forEach { latest[storageKey(it.key)] = it }
        return latest.map { (storageKey, entry) ->
            Put(storageKey, format.encode(entry.value), ttlOf(entry.key, entry.value)?.boundedTtl())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 条件读写（按摘要比较）：只针对 L1，不读写 L2、不回源、不申请 lease。从不回源，因此读不懂的内容按“没有数据源”
    // 处理：未配置 foreignFormat 时为 FAIL。读与写对内容的分类相同：只有不是本协议写入的内容（格式 0）可以在 RECLAIM 下
    // 视为没有值；本协议的集合、更高格式版本、解码失败的值总是读不懂（读取抛出；写入抛出，或因摘要不符而冲突）。
    // 同步与异步共用 [conditionalGetOf]、[refuseUnreadable]、[conditionalPutOf]。
    // ---------------------------------------------------------------------------------------------

    /**
     * `RECLAIM` 之外（含未配置）条件读写遇到格式 0 的内容时拒绝（抛出）；`RECLAIM` 时视为没有值。
     */
    private val conditionalFailsOnForeign: Boolean = (policy.foreignFormat ?: ForeignFormatMode.FAIL) == ForeignFormatMode.FAIL

    @Suppress("TooGenericExceptionCaught")
    override fun getIfChanged(key: K, known: Digest?): ConditionalGet<V> {
        val storageKey = storageKey(key)
        val read = try {
            store.readIfChanged(storageKey, known)
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
     * 同步与异步共用：把后端的条件读取结果换算为用户结果。格式不在 [MIN_FORMAT, 本节点版本] 内、集合、解码失败都是读不懂的内容。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun conditionalGetOf(storageKey: String, read: RemoteConditionalRead): ConditionalGet<V> = when (read) {
        // 摘要只覆盖载荷：再确认这个值仍是本节点读得懂的格式
        is RemoteConditionalRead.Unchanged -> if (read.format < MIN_FORMAT || read.format > backendFormat) {
            unreadableGet(storageKey, read.format, null)
        } else {
            metrics.conditionalUnchanged()
            ConditionalGet.Unchanged
        }

        RemoteConditionalRead.Absent -> ConditionalGet.Absent
        is RemoteConditionalRead.Unreadable -> unreadableGet(storageKey, read.format, null)
        is RemoteConditionalRead.Changed -> if (read.format < MIN_FORMAT || read.format > backendFormat) {
            unreadableGet(storageKey, read.format, null)
        } else {
            try {
                ConditionalGet.Present(format.decode(read.envelope), Digest.of(read.envelope))
            } catch (e: Exception) {
                unreadableGet(storageKey, read.format, e)
            }
        }
    }

    /**
     * 读不懂的内容：计入 `unknownFormats`，抛出（不修改 L1）。唯一的例外是 `RECLAIM` 下格式 0 的内容：视为没有值（也不修改 L1），
     * 与 `PUT_IF_MATCH` 的 `foreignAsAbsent` 一致。
     */
    private fun unreadableGet(storageKey: String, format: Int, error: Exception?): ConditionalGet<V> {
        metrics.unknownFormat()
        if (format < MIN_FORMAT && !conditionalFailsOnForeign) {
            log.warn { "Cache[$name] - key[$storageKey] - unreadable remote content (format[$format]), treated as absent." }
            return ConditionalGet.Absent
        }
        val failure = ForeignFormatException(name, storageKey, format, error, newer = format > backendFormat)
        log.warn { failure.message }
        throw CacheReadException(name, "getIfChanged", failure)
    }

    override fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut =
        putIfMatchWithTtl(key, expected, value, spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl())

    override fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): ConditionalPut =
        putIfMatchWithTtl(key, expected, value, ttl)

    /**
     * 与 [putWithTtl] 相同的写入顺序（戳、L2 淘汰在 L1 前后各一次）；冲突与拒绝不修改 L1，非原子广播的后端也不广播。
     */
    private fun putIfMatchWithTtl(key: K, expected: Digest?, value: V, ttl: Duration?): ConditionalPut {
        val storageKey = storageKey(key)
        val envelope = format.encode(value)
        val bounded = ttl?.boundedTtl()
        var outcome: RemoteConditionalWrite? = null
        write(
            listOf(storageKey),
            { frame ->
                val result = store.putIfMatch(
                    storageKey,
                    expected,
                    envelope,
                    bounded,
                    frame,
                    !conditionalFailsOnForeign
                )
                outcome = result
                refuseUnreadable(storageKey, result)
            },
            { outcome == RemoteConditionalWrite.Written },
            conditionalWriteFailure
        )
        return conditionalPutOf(envelope, requireNotNull(outcome))
    }

    /**
     * 后端不支持条件写入（SPI 默认实现）时原样抛出 [UnsupportedOperationException]，与不支持的 `Cache` 实现一致；
     * 其余失败包装为 `CacheWriteException`。
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
    private fun refuseUnreadable(storageKey: String, result: RemoteConditionalWrite): Void? {
        if (result !is RemoteConditionalWrite.Unreadable) {
            return null
        }
        throw refusedFormat(storageKey, result.format)
    }

    private fun refusedFormat(storageKey: String, format: Int): ForeignFormatException {
        metrics.unknownFormat()
        val failure = ForeignFormatException(name, storageKey, format, null, newer = format > backendFormat)
        log.warn { failure.message }
        return failure
    }

    private fun conditionalPutOf(envelope: Envelope.Scalar, result: RemoteConditionalWrite): ConditionalPut = when (result) {
        RemoteConditionalWrite.Written -> ConditionalPut.Written(Digest.of(envelope))
        is RemoteConditionalWrite.Conflict -> {
            metrics.conditionalConflict()
            ConditionalPut.Conflict(result.current)
        }

        is RemoteConditionalWrite.Unreadable -> throw IllegalStateException(
            "Cache[$name] - unreachable: refused write."
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 按版本有序的写入（6.8）：比较与写入由后端在 L1 中原子完成，写入方顺序同 put（不写 L2）；版本不更新时不修改 L1、不广播。
    // 只由带版本的缓存（VersionedCache，引擎的值是 Versioned）调用；value 为 null 时写入墓碑（带版本的负缓存）。
    // 读不懂的内容的分类与条件写入相同。同步与异步共用 [refuseUnreadableVersion]、[versionedPutOf]。
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

    internal fun writeIfNewer(key: K, value: V?, version: Long, ttl: Duration?): VersionedPut {
        requireVersion(version)
        val storageKey = storageKey(key)
        val envelope = envelopeOf(value)
        val bounded = ttl?.boundedTtl()
        var outcome: RemoteVersionedWrite? = null
        write(
            listOf(storageKey),
            { frame ->
                val result = store.putIfNewer(storageKey, envelope, version, bounded, frame, !conditionalFailsOnForeign)
                outcome = result
                refuseUnreadableVersion(storageKey, result)
            },
            { outcome == RemoteVersionedWrite.Written },
            versionedWriteFailure(versionedOperation(value))
        )
        return versionedPutOf(requireNotNull(outcome))
    }

    internal fun writeIfNewerAsync(key: K, value: V?, version: Long, ttl: Duration?): CompletableFuture<VersionedPut> {
        requireVersion(version)
        val storageKey = storageKey(key)
        val envelope = envelopeOf(value)
        val bounded = ttl?.boundedTtl()
        val outcome = AtomicReference<RemoteVersionedWrite?>()
        return writeAsync(
            listOf(storageKey),
            { frame ->
                asyncStore.putIfNewerAsync(storageKey, envelope, version, bounded, frame, !conditionalFailsOnForeign)
                    .thenApply {
                        outcome.set(it)
                        refuseUnreadableVersion(storageKey, it)
                    }
            },
            { outcome.get() == RemoteVersionedWrite.Written },
            versionedWriteFailure(versionedOperation(value))
        ).thenApply { versionedPutOf(requireNotNull(outcome.get())) }
    }

    private fun requireVersion(version: Long) {
        require(
            version != Versioned.NO_VERSION
        ) { "Cache[$name] - version must not be Versioned.NO_VERSION (Long.MIN_VALUE)." }
    }

    private fun versionedOperation(value: V?): String = if (value == null) "invalidateIfNewer" else "putIfNewer"

    /**
     * 同 [conditionalWriteFailure]：后端不支持（SPI 默认实现）时原样抛出 [UnsupportedOperationException]，其余包装为 `CacheWriteException`。
     */
    private fun versionedWriteFailure(operation: String): (Exception) -> Unit = { error ->
        if (error is UnsupportedOperationException) {
            throw error
        }
        throw CacheWriteException(name, operation, error)
    }

    private fun refuseUnreadableVersion(storageKey: String, result: RemoteVersionedWrite): Void? {
        if (result !is RemoteVersionedWrite.Unreadable) {
            return null
        }
        throw refusedFormat(storageKey, result.format)
    }

    private fun versionedPutOf(result: RemoteVersionedWrite): VersionedPut = when (result) {
        RemoteVersionedWrite.Written -> VersionedPut.Written
        is RemoteVersionedWrite.Stale -> {
            metrics.staleVersion()
            VersionedPut.Stale(result.current)
        }

        is RemoteVersionedWrite.Unreadable -> throw IllegalStateException(
            "Cache[$name] - unreachable: refused write."
        )
    }

    override fun invalidate(key: K) {
        invalidateStorageKeys(listOf(storageKey(key)))
    }

    override fun invalidateAll(keys: Collection<K>) {
        val storageKeys = keys.map { storageKey(it) }.distinct()
        if (storageKeys.isNotEmpty()) {
            invalidateStorageKeys(storageKeys)
        }
    }

    private fun invalidateStorageKeys(storageKeys: List<String>) {
        write(
            storageKeys,
            { frame -> store.invalidate(storageKeys, frame) }
        ) { error -> onInvalidateFailure(storageKeys, error) }
    }

    /**
     * 同步与异步共用：配置为抛出时抛出 [CacheWriteException]，否则登记重试。
     */
    private fun onInvalidateFailure(storageKeys: List<String>, error: Exception) {
        if (policy.invalidateFailure == InvalidateFailureMode.THROW) {
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
            action = {
                val frame = frameOf(storageKeys)
                store.invalidate(storageKeys, frame)
                if (!context.backend.atomicBroadcast) {
                    context.backend.channel.publish(namespace, frame)
                }
            },
            onRetry = { metrics.invalidationRetry() },
            onSuccess = {
                storageKeys.forEach {
                    unregisterPending(it)
                    stamps.invalidate(it)
                    local.invalidate(it)
                }
            },
            onGiveUp = { giveUpError -> dropInvalidation(storageKeys, giveUpError) },
        )
        if (!accepted) {
            dropInvalidation(storageKeys, error)
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

    override fun describe(envelope: Envelope): Any? = decode(envelope)

    override fun invalidateEncoded(encodedKey: String) {
        invalidateStorageKeys(listOf(storageKeyOf(encodedKey)))
    }

    // ---------------------------------------------------------------------------------------------
    // 异步路径（RFC 0004 §5）：与上面的同步路径逐分支对应，只在“如何等待”上不同。
    // 续接经 handleNext / thenNext：上一步已完成时就地执行，否则在异步执行器上执行，从不在 I/O 线程上执行。
    // ---------------------------------------------------------------------------------------------

    private fun completed(entry: LocalEntry<V>): CompletableFuture<LocalEntry<V>> = CompletableFuture.completedFuture(
        entry
    )

    /**
     * 异步未命中（已计数、已过 key 过滤器）：与 [loadMiss] 相同的读己之写重试。
     */
    private fun loadMissAsync(
        key: K,
        storageKey: String,
        source: AsyncSource<K, V>?
    ): CompletableFuture<LocalEntry<V>> {
        val observed = stamps.current(storageKey)
        val hasSource = source != null
        return flights.executeAsync(
            storageKey,
            executor
        ) { loadThroughAsync(key, storageKey, source) }.thenNext(executor) { loaded ->
            if (loaded.stamp >= observed && usable(loaded.entry, hasSource)) {
                completed(loaded.entry)
            } else {
                flights.executeAsync(
                    storageKey,
                    executor
                ) { loadThroughAsync(key, storageKey, source) }.thenNext(executor) { retried ->
                    if (usable(retried.entry, hasSource)) {
                        completed(retried.entry)
                    } else {
                        loadThroughAsync(key, storageKey, source).thenNext(executor) { completed(it.entry) }
                    }
                }
            }
        }
    }

    /**
     * [loadThrough] 的异步版本：在 SingleFlight 注册之后、在调用方线程上开始，第一件事是取戳。
     */
    private fun loadThroughAsync(
        key: K,
        storageKey: String,
        source: AsyncSource<K, V>?,
        operation: LoadOperation = LoadOperation.LOAD
    ): CompletableFuture<Loaded<V>> {
        val stamp = stamps.current(storageKey)
        if (pendingInvalidations.containsKey(storageKey)) {
            return bypassAsync(key, storageKey, stamp, source, operation)
                .thenNext(executor) { CompletableFuture.completedFuture(Loaded(it, stamp)) }
        }
        val lease = LeaseRequest(context.nextToken(), policy.lease.ttl)
        val entry = run {
            readWaitingLeaseAsync(storageKey, lease).handleNext(executor) { read, error ->
                if (error != null) {
                    onReadFailureAsync(
                        "read",
                        storageKey,
                        error
                    ) { bypassAsync(key, storageKey, stamp, source, operation) }
                } else {
                    when (read) {
                        is RemoteRead.Hit -> onHitAsync(key, storageKey, read, stamp, lease, source, operation)
                        is RemoteRead.Miss -> loadAndFillAsync(
                            key,
                            storageKey,
                            stamp,
                            lease.token.takeIf { read.leaseGranted },
                            source,
                            operation
                        )

                        is RemoteRead.Leased -> {
                            metrics.leaseWaitTimeout()
                            bypassAsync(key, storageKey, stamp, source, operation)
                        }

                        null -> CompletableFuture.failedFuture(
                            IllegalStateException("Cache[$name] - remote read returned null.")
                        )
                    }
                }
            }
        }
        return entry.thenNext(executor) { CompletableFuture.completedFuture(Loaded(it, stamp)) }
            .releasingOnRejection { mapOf(storageKey to lease.token) }
    }

    /**
     * 执行器拒绝任务时，链上负责释放令牌的步骤会被跳过：此时在完成线程上尽力释放（只发出一条非阻塞命令）。
     * 令牌不匹配时 release 什么也不做，所以对已填充、已释放或从未取得的令牌重复释放是无害的。
     */
    private fun <T> CompletableFuture<T>.releasingOnRejection(tokens: () -> Map<String, Token>): CompletableFuture<T> {
        whenComplete { _, error ->
            if (error != null && Futures.unwrap(error) is RejectedExecutionException) {
                tokens().forEach { (storageKey, token) -> releaseQuietly(storageKey, token) }
            }
        }
        return this
    }

    @Suppress("TooGenericExceptionCaught")
    private fun releaseQuietly(storageKey: String, token: Token) {
        try {
            asyncStore.releaseAsync(storageKey, token).whenComplete { _, error ->
                if (error != null) {
                    log.warn(
                        error
                    ) { "Cache[$name] - key[$storageKey] - release after rejection failed, reclaimed after lease ttl." }
                }
            }
        } catch (e: Exception) {
            log.warn(
                e
            ) { "Cache[$name] - key[$storageKey] - release after rejection failed, reclaimed after lease ttl." }
        }
    }

    /**
     * [readWaitingLease] 的异步版本：退避与截止时间相同，沿用同一个令牌；重读经定时器调度，不挂起线程。
     */
    private fun readWaitingLeaseAsync(storageKey: String, lease: LeaseRequest): CompletableFuture<RemoteRead> {
        return Futures.attempt { asyncStore.readAsync(storageKey, lease, null) }.thenNext(executor) { read ->
            if (read !is RemoteRead.Leased) {
                CompletableFuture.completedFuture(read)
            } else {
                metrics.leaseWait()
                observedAsync(LoadOperation.LEASE_WAIT, 1) {
                    awaitLeaseAsync(storageKey, lease, read, leaseDeadline(read), INITIAL_BACKOFF_NANOS)
                }
            }
        }
    }

    private fun awaitLeaseAsync(
        storageKey: String,
        lease: LeaseRequest,
        last: RemoteRead,
        deadline: Long,
        backoff: Long
    ): CompletableFuture<RemoteRead> {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) {
            return CompletableFuture.completedFuture(last)
        }
        return context.async.after(minOf(backoff, remaining)) { asyncStore.readAsync(storageKey, lease, null) }
            .thenNext(executor) { read ->
                if (read is RemoteRead.Leased) {
                    awaitLeaseAsync(storageKey, lease, read, deadline, nextBackoff(backoff))
                } else {
                    CompletableFuture.completedFuture(read)
                }
            }
    }

    /**
     * [onReadFailure] 的异步版本：严格模式或运行时已关闭时失败，否则降级为 [degrade]。
     */
    private fun <R> onReadFailureAsync(
        operation: String,
        storageKey: String,
        error: Throwable,
        degrade: () -> CompletableFuture<R>
    ): CompletableFuture<R> {
        if (error !is Exception || context.async.closed) {
            return CompletableFuture.failedFuture(error)
        }
        onReadFailure(operation, storageKey, error)
        return degrade()
    }

    @Suppress("LongParameterList")
    private fun onHitAsync(
        key: K,
        storageKey: String,
        read: RemoteRead.Hit,
        stamp: Long,
        lease: LeaseRequest,
        source: AsyncSource<K, V>?,
        operation: LoadOperation
    ): CompletableFuture<LocalEntry<V>> {
        return when (val outcome = hitOutcome(read)) {
            HitOutcome.Newer -> newerFormatFailure(storageKey, read, source != null)
                ?.let { CompletableFuture.failedFuture(it) }
                ?: run {
                    metrics.unknownFormat()
                    bypassAsync(key, storageKey, stamp, source, operation)
                }

            is HitOutcome.Corrupt -> foreignFormatFailure(storageKey, read, outcome.error, source != null)
                ?.let { CompletableFuture.failedFuture(it) }
                ?: onCorruptAsync(key, storageKey, read, stamp, lease, source, outcome.error, operation)

            is HitOutcome.Decoded -> completed(onRemoteHit(storageKey, outcome.value, read, stamp))
        }
    }

    @Suppress("LongParameterList")
    private fun onCorruptAsync(
        key: K,
        storageKey: String,
        read: RemoteRead.Hit,
        stamp: Long,
        lease: LeaseRequest,
        source: AsyncSource<K, V>?,
        error: Exception,
        operation: LoadOperation = LoadOperation.LOAD
    ): CompletableFuture<LocalEntry<V>> {
        logCorrupt(storageKey, error)
        return Futures.attempt { asyncStore.reclaimAsync(storageKey, Digest.of(read.envelope), lease) }
            .handleNext(executor) { reclaimed, reclaimError ->
                if (reclaimError != null) {
                    onReadFailureAsync("reclaim", storageKey, reclaimError) { CompletableFuture.completedFuture(false) }
                } else {
                    CompletableFuture.completedFuture(reclaimed == true)
                }
            }
            .thenNext(executor) { reclaimed ->
                if (reclaimed) {
                    loadAndFillAsync(key, storageKey, stamp, lease.token, source, operation)
                } else {
                    bypassAsync(key, storageKey, stamp, source, operation)
                }
            }
    }

    /**
     * [loadAndFill] 的异步版本：失败（含超时、被拒绝、ttlOf 或 Codec 抛出）时等释放完成后再以原始异常失败。
     */
    @Suppress("LongParameterList")
    private fun loadAndFillAsync(
        key: K,
        storageKey: String,
        stamp: Long,
        token: Token?,
        source: AsyncSource<K, V>?,
        operation: LoadOperation
    ): CompletableFuture<LocalEntry<V>> {
        if (source == null) {
            return releaseAsync(
                storageKey,
                token
            ).thenNext(executor) { completed(absentWithoutLoader(storageKey, stamp)) }
        }
        return loadFromSourceAsync(key, storageKey, source, operation).handleNext(executor) { value, error ->
            val prepared = if (error == null) runCatching { prepare(key, value) } else Result.failure(error)
            prepared.fold(
                onSuccess = { fillAndComplete(storageKey, stamp, token, value, it) },
                onFailure = { failure ->
                    releaseAsync(
                        storageKey,
                        token
                    ).thenNext(executor) { CompletableFuture.failedFuture(failure) }
                }
            )
        }
    }

    private fun fillAndComplete(storageKey: String, stamp: Long, token: Token?, value: V?, prepared: Prepared): CompletableFuture<LocalEntry<V>> {
        if (token == null) {
            return completed(completeLoad(storageKey, stamp, null, value, prepared.ttl, false))
        }
        val fill = Fill(storageKey, token, prepared.envelope, prepared.ttl)
        return Futures.attempt { asyncStore.fillAsync(fill) }.handleNext(executor) { filled, error ->
            if (error is Exception) {
                onRemoteFailure("fill", storageKey, error)
            } else if (error != null) {
                throw error
            }
            completed(completeLoad(storageKey, stamp, token, value, prepared.ttl, filled == true))
        }
    }

    /**
     * 尽力释放令牌（失败只计数告警）；完成后才继续，与同步路径的顺序一致。
     */
    private fun releaseAsync(storageKey: String, token: Token?): CompletableFuture<Void?> {
        if (token == null) {
            return Futures.done()
        }
        return Futures.attempt { asyncStore.releaseAsync(storageKey, token) }.handleNext(executor) { _, error ->
            if (error is Exception) {
                onRemoteFailure("release", storageKey, error)
            } else if (error != null) {
                throw error
            }
            Futures.done()
        }
    }

    private fun bypassAsync(
        key: K,
        storageKey: String,
        stamp: Long,
        source: AsyncSource<K, V>?,
        operation: LoadOperation
    ): CompletableFuture<LocalEntry<V>> {
        return loadFromSourceAsync(key, storageKey, source, operation).thenNext(executor) { value ->
            completed(completeBypass(key, storageKey, stamp, value))
        }
    }

    private fun loadFromSourceAsync(
        key: K,
        storageKey: String,
        source: AsyncSource<K, V>?,
        operation: LoadOperation
    ): CompletableFuture<V?> {
        val sourceLoader = source ?: return CompletableFuture.completedFuture(null)
        return guardedAsync(operation, 1) {
            invoke(sourceLoader, listOf(storageKey), { it.loadAsync(key) }, { it.load(key) })
        }
    }

    private fun loadAllFromSourceAsync(keys: Collection<K>, storageKeys: Collection<String>): CompletableFuture<Map<K, V>> {
        val sourceLoader = requireNotNull(asyncSource)
        return guardedAsync(LoadOperation.LOAD_ALL, keys.size) {
            invoke(sourceLoader, storageKeys, { it.loadAllAsync(keys) }, { it.loadAll(keys) })
        }
    }

    /**
     * 调用数据源（RFC 0004 §5.2.1），调用期间本线程对这些 key 的重入快速失败：
     * - 异步数据源：在当前线程上调用，取其结果的副本再加 [asyncTimeout]（`orTimeout` 会修改 future 本身，不能作用于数据源的 future）；
     * - 同步数据源：只在阻塞执行器上执行，不加超时（阻塞的 loader 无法被打断）。
     */
    private fun <R> invoke(
        source: AsyncSource<K, V>,
        storageKeys: Collection<String>,
        native: (AsyncCacheLoader<K, V>) -> java.util.concurrent.CompletionStage<out R>,
        blocking: (CacheLoader<K, V>) -> R
    ): CompletableFuture<R> {
        return when (source) {
            is AsyncSource.Native -> {
                val started = Futures.attempt {
                    flights.loading(
                        storageKeys
                    ) { native(source.loader).toCompletableFuture() }
                }
                // 引擎的超时以私有哨兵值完成（JDK 的延迟线程触发），与数据源自己抛出的 TimeoutException 区分开（A8）
                val bounded = Futures.relay<Any?>(
                    started
                ).completeOnTimeout(TIMED_OUT, asyncTimeout.toNanos(), TimeUnit.NANOSECONDS)
                bounded.handleNext(executor) { value, error ->
                    when {
                        error != null -> CompletableFuture.failedFuture(error)
                        value === TIMED_OUT -> {
                            metrics.loadTimeout()
                            CompletableFuture.failedFuture(SourceLoadTimeoutException(name, asyncTimeout))
                        }

                        else -> CompletableFuture.completedFuture(uncheckedCast<R>(value))
                    }
                }
            }

            is AsyncSource.Blocking -> Futures.attempt {
                CompletableFuture.supplyAsync(
                    { flights.loading(storageKeys) { blocking(source.loader) } },
                    context.async.blocking
                )
            }
        }
    }

    /**
     * [guarded] 的异步版本：许可不阻塞地获取（拿不到时登记等待，释放时被唤醒，`acquireTimeout` 后被拒绝），在加载完成时释放恰好一次。
     */
    private fun <R> guardedAsync(
        operation: LoadOperation,
        keyCount: Int,
        block: () -> CompletableFuture<R>
    ): CompletableFuture<R> {
        val semaphore = permits ?: return timedAsync(operation, keyCount, block)
        val acquired = semaphore.acquireAsync(policy.load.acquireTimeout.toNanos()) {
            metrics.loadRejected()
            SourceLoadRejectedException(name, policy.load)
        }
        val released = AtomicBoolean()
        val result = acquired.thenNext(executor) {
            timedAsync(operation, keyCount, block).handleNext(executor) { value, error ->
                if (released.compareAndSet(false, true)) {
                    semaphore.release()
                }
                @Suppress("UNCHECKED_CAST")
                if (error != null) {
                    CompletableFuture.failedFuture(
                        error
                    )
                } else {
                    CompletableFuture.completedFuture(value as R)
                }
            }
        }
        // 执行器拒绝任务时上面的释放步骤会被跳过：拿到了许可就在完成线程上归还（恰好一次），否则许可永久泄漏、同步路径也被拒绝
        result.whenComplete { _, _ ->
            if (!acquired.isCompletedExceptionally && released.compareAndSet(false, true)) {
                semaphore.release()
            }
        }
        return result
    }

    /**
     * 回源耗时统计与观测。
     */
    private fun <R> timedAsync(
        operation: LoadOperation,
        keyCount: Int,
        block: () -> CompletableFuture<R>
    ): CompletableFuture<R> {
        val start = System.nanoTime()
        return Futures.attempt { observedAsync(operation, keyCount, block) }.handleNext(executor) { value, error ->
            metrics.load(System.nanoTime() - start, error != null)
            @Suppress("UNCHECKED_CAST")
            if (error != null) CompletableFuture.failedFuture(error) else CompletableFuture.completedFuture(value as R)
        }
    }

    private fun <R> observedAsync(operation: LoadOperation, keyCount: Int, block: () -> CompletableFuture<R>): CompletableFuture<R> {
        val observer = loadObserver
        return if (observer === LoadObserver.NOOP) {
            block()
        } else {
            observer.observeAsync(
                name,
                operation,
                keyCount
            ) { block() }
        }
    }

    /**
     * 批量的异步版本：与 [loadPending] 相同的合并与回退。
     */
    private fun loadPendingAsync(pending: Map<String, List<K>>): CompletableFuture<Map<String, LocalEntry<V>>> {
        val observed = pending.keys.associateWith { stamps.current(it) }
        return flights.executeAllAsync(pending.keys, executor) { led ->
            loadAllThroughAsync(led.associateWith { pending.getValue(it).first() })
        }.thenNext(executor) { loaded ->
            val entries = pending.mapValues { (storageKey, sourceKeys) ->
                val result = loaded[storageKey]
                if (result != null && result.stamp >= observed.getValue(storageKey)) {
                    completed(result.entry)
                } else {
                    loadMissAsync(sourceKeys.first(), storageKey, asyncSource)
                }
            }
            allOf(entries)
        }
    }

    private fun <T> allOf(futures: Map<String, CompletableFuture<T>>): CompletableFuture<Map<String, T>> {
        return CompletableFuture.allOf(*futures.values.toTypedArray()).handleNext(executor) { _, error ->
            if (error != null) {
                CompletableFuture.failedFuture(error)
            } else {
                CompletableFuture.completedFuture(futures.mapValues { it.value.join() })
            }
        }
    }

    /**
     * [loadAllThrough] 的异步版本。
     */
    private fun loadAllThroughAsync(led: Map<String, K>): CompletableFuture<Map<String, Loaded<V>>> {
        val loadStamps = led.keys.associateWith { stamps.current(it) }
        val readKeys = batchReadKeys(led)
        val leases = batchLeases(readKeys)
        val reads = if (readKeys.isEmpty()) {
            CompletableFuture.completedFuture(emptyMap())
        } else {
            Futures.attempt { asyncStore.readAllAsync(readKeys, leases) }.handleNext(executor) { reads, error ->
                if (error != null) {
                    onReadFailureAsync(
                        "readAll",
                        readKeys.first(),
                        error
                    ) { CompletableFuture.completedFuture(emptyMap()) }
                } else {
                    CompletableFuture.completedFuture(reads ?: emptyMap())
                }
            }
        }
        return reads.thenNext<Map<String, RemoteRead>, Map<String, Loaded<V>>>(executor) { readResults ->
            val result = LinkedHashMap<String, Loaded<V>>()
            val corrupt = LinkedHashMap<String, CompletableFuture<LocalEntry<V>>>()
            val toLoad = LinkedHashMap<String, Token?>()
            for (storageKey in led.keys) {
                val stamp = loadStamps.getValue(storageKey)
                val lease = leases[storageKey]
                when (val step = batchStep(storageKey, readResults[storageKey], stamp, lease, asyncSource != null)) {
                    is BatchStep.Load -> toLoad[storageKey] = step.token
                    is BatchStep.Done -> result[storageKey] = Loaded(step.entry, stamp)
                    is BatchStep.Failed -> corrupt[storageKey] = CompletableFuture.failedFuture(step.error)
                    is BatchStep.Corrupt -> corrupt[storageKey] = corruptStepAsync(
                        led.getValue(storageKey),
                        storageKey,
                        step,
                        stamp,
                        leases.getValue(storageKey)
                    )
                }
            }
            val loaded = if (toLoad.isEmpty()) {
                CompletableFuture.completedFuture(emptyMap())
            } else {
                loadAndFillBatchAsync(led, toLoad, loadStamps)
            }
            loaded.thenNext(executor) { batch ->
                result.putAll(batch)
                allOf(corrupt).thenNext(executor) { reclaimed ->
                    reclaimed.forEach { (storageKey, entry) -> result[storageKey] = Loaded(entry, loadStamps.getValue(storageKey)) }
                    CompletableFuture.completedFuture(result)
                }
            }
        }.releasingOnRejection { leases.mapValues { it.value.token } }
    }

    /**
     * 批量读取中读不懂的 key：`FAIL` 时以要抛出的异常失败，否则与单 key 路径一样条件回收后回源。
     */
    private fun corruptStepAsync(
        key: K,
        storageKey: String,
        step: BatchStep.Corrupt,
        stamp: Long,
        lease: LeaseRequest
    ): CompletableFuture<LocalEntry<V>> = foreignFormatFailure(storageKey, step.read, step.error, asyncSource != null)
        ?.let { CompletableFuture.failedFuture(it) }
        ?: onCorruptAsync(key, storageKey, step.read, stamp, lease, asyncSource, step.error)

    /**
     * [loadAndFillBatch] 的异步版本：回源失败时等全部 lease 释放后再以原始异常失败。
     */
    private fun loadAndFillBatchAsync(
        led: Map<String, K>,
        toLoad: Map<String, Token?>,
        loadStamps: Map<String, Long>
    ): CompletableFuture<Map<String, Loaded<V>>> {
        val result = LinkedHashMap<String, Loaded<V>>()
        if (asyncSource == null) {
            return releaseAllAsync(toLoad).thenNext(executor) {
                completeBatchWithoutLoader(toLoad, loadStamps, result)
                CompletableFuture.completedFuture(result)
            }
        }
        return loadAllFromSourceAsync(
            toLoad.keys.map { led.getValue(it) },
            toLoad.keys
        ).handleNext(executor) { values, error ->
            val prepared = if (error == null) {
                runCatching {
                    prepareBatch(
                        led,
                        toLoad,
                        values!!
                    )
                }
            } else {
                Result.failure(error)
            }
            prepared.fold(
                onSuccess = { batch ->
                    fillAllAsync(batch.fills).thenNext(executor) { filled ->
                        completeBatch(led, toLoad, values!!, batch.ttls, filled, loadStamps, result)
                        CompletableFuture.completedFuture(result)
                    }
                },
                onFailure = { failure ->
                    releaseAllAsync(
                        toLoad
                    ).thenNext(executor) { CompletableFuture.failedFuture(failure) }
                }
            )
        }
    }

    private fun releaseAllAsync(toLoad: Map<String, Token?>): CompletableFuture<Void?> {
        val releases = toLoad.mapNotNull { (storageKey, token) -> token?.let { releaseAsync(storageKey, it) } }
        return CompletableFuture.allOf(*releases.toTypedArray())
    }

    private fun fillAllAsync(fills: List<Fill>): CompletableFuture<Map<String, Boolean>> {
        if (fills.isEmpty()) {
            return CompletableFuture.completedFuture(emptyMap())
        }
        return Futures.attempt { asyncStore.fillAllAsync(fills) }.handleNext(executor) { outcomes, error ->
            if (error is Exception) {
                onRemoteFailure("fillAll", fills.first().key, error)
                CompletableFuture.completedFuture(emptyMap())
            } else if (error != null) {
                CompletableFuture.failedFuture(error)
            } else {
                CompletableFuture.completedFuture(filledByKey(fills, outcomes ?: emptyList()))
            }
        }
    }

    /**
     * [refreshThrough] 的异步版本（只用于异步数据源）。
     */
    private fun refreshThroughAsync(key: K, storageKey: String): CompletableFuture<Loaded<V>> {
        val window = policy.refreshWindow
        if (window == null || pendingInvalidations.containsKey(storageKey)) {
            return loadThroughAsync(key, storageKey, asyncSource, LoadOperation.REFRESH)
        }
        val stamp = stamps.current(storageKey)
        val token = context.nextToken()
        return Futures.attempt {
            asyncStore.readAsync(
                storageKey,
                null,
                RefreshRequest(window, token, policy.lease.ttl)
            )
        }
            .handleNext(executor) { read, error ->
                if (error != null) {
                    if (error is Exception) {
                        onRemoteFailure("read", storageKey, error)
                    }
                    return@handleNext loadThroughAsync(key, storageKey, asyncSource, LoadOperation.REFRESH)
                }
                when (val step = refreshStep(requireNotNull(read))) {
                    is RefreshStep.Reload -> {
                        val released = if (step.releaseToken) releaseAsync(storageKey, token) else Futures.done()
                        released.thenNext(
                            executor
                        ) { loadThroughAsync(key, storageKey, asyncSource, LoadOperation.REFRESH) }
                    }

                    is RefreshStep.Refill -> CompletableFuture.completedFuture(
                        Loaded(refill(storageKey, step, stamp), stamp)
                    )
                    RefreshStep.LoadWithToken -> loadAndFillAsync(
                        key,
                        storageKey,
                        stamp,
                        token,
                        asyncSource,
                        LoadOperation.REFRESH
                    )
                        .thenNext(executor) { CompletableFuture.completedFuture(Loaded(it, stamp)) }
                }
            }
            .releasingOnRejection { mapOf(storageKey to token) }
    }

    private fun putAsync(key: K, value: V, ttl: Duration?): CompletableFuture<Void?> {
        val storageKey = storageKey(key)
        val envelope = format.encode(value)
        val bounded = ttl?.boundedTtl()
        return writeAsync(
            listOf(storageKey),
            { frame -> asyncStore.putAsync(storageKey, envelope, bounded, frame) },
            throwWriteFailure("put")
        )
    }

    private fun putAllAsync(entries: Map<K, V>, ttlOf: (K, V) -> Duration?): CompletableFuture<Void?> {
        if (entries.isEmpty()) {
            return Futures.done()
        }
        val puts = putsOf(entries, ttlOf)
        return writeAsync(
            puts.map { it.key },
            { frame -> asyncStore.putAllAsync(puts, frame) },
            throwWriteFailure("putAll")
        )
    }

    private fun getIfChangedAsync(key: K, known: Digest?): CompletableFuture<ConditionalGet<V>> {
        val storageKey = storageKey(key)
        return Futures.attempt {
            asyncStore.readIfChangedAsync(
                storageKey,
                known
            )
        }.handleNext(executor) { read, error ->
            when {
                error is Exception -> CompletableFuture.failedFuture(conditionalReadFailure(error))
                error != null -> CompletableFuture.failedFuture(error)
                else -> CompletableFuture.completedFuture(conditionalGetOf(storageKey, requireNotNull(read)))
            }
        }
    }

    private fun putIfMatchAsync(key: K, expected: Digest?, value: V, ttl: Duration?): CompletableFuture<ConditionalPut> {
        val storageKey = storageKey(key)
        val envelope = format.encode(value)
        val bounded = ttl?.boundedTtl()
        val outcome = AtomicReference<RemoteConditionalWrite?>()
        return writeAsync(
            listOf(storageKey),
            { frame ->
                asyncStore.putIfMatchAsync(storageKey, expected, envelope, bounded, frame, !conditionalFailsOnForeign)
                    .thenApply {
                        outcome.set(it)
                        refuseUnreadable(storageKey, it)
                    }
            },
            { outcome.get() == RemoteConditionalWrite.Written },
            conditionalWriteFailure
        ).thenApply { conditionalPutOf(envelope, requireNotNull(outcome.get())) }
    }

    private fun invalidateAsync(storageKeys: List<String>): CompletableFuture<Void?> {
        if (storageKeys.isEmpty()) {
            return Futures.done()
        }
        return writeAsync(storageKeys, { frame -> asyncStore.invalidateAsync(storageKeys, frame) }) { error ->
            onInvalidateFailure(storageKeys, error)
        }
    }

    /**
     * 同步路径上等待异步数据源（在调用方线程上调用 `loadAsync` 并等待，受 [asyncTimeout] 约束，异常解包）。
     */
    private inner class AwaitingLoader(private val delegate: AsyncCacheLoader<K, V>) : CacheLoader<K, V> {
        override fun load(key: K): V? = await(delegate.loadAsync(key).toCompletableFuture())

        override fun loadAll(keys: Collection<K>): Map<K, V> = await(delegate.loadAllAsync(keys).toCompletableFuture())

        private fun <T> await(future: CompletableFuture<T>): T {
            val failure: Throwable = try {
                return future.get(asyncTimeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (e: ExecutionException) {
                Futures.unwrap(e)
            } catch (e: TimeoutException) {
                metrics.loadTimeout()
                SourceLoadTimeoutException(name, asyncTimeout).apply { initCause(e) }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                e
            }
            throw failure
        }
    }

    /**
     * 异步视图：命中判断与同步路径相同（同一个 L2），未命中走异步路径。返回给用户的 future 都由这里新建并显式完成。
     */
    private inner class AsyncView : AsyncCache<K, V> {
        override val name: String
            get() = this@CoherentEngine.name

        override fun get(key: K): CompletableFuture<V?> = Futures.attempt {
            val storageKey = storageKey(key)
            val entry = local.getIfPresent(storageKey)
            if (entry != null) {
                val now = clock.nowMillis()
                if (entry.expiresAt > now) {
                    if (entry.refreshAt <= now) {
                        refreshAsync(key, storageKey, entry)
                    }
                    countLocalHit()
                    return@attempt CompletableFuture.completedFuture(entry.value)
                }
            }
            toUser(missAsync(key, storageKey, asyncSource)) { it.value }
        }

        override fun get(key: K, loader: AsyncCacheLoader<K, V>): CompletableFuture<V?> = Futures.attempt {
            val storageKey = storageKey(key)
            val entry = local.getIfPresent(storageKey)
            if (entry != null && !entry.unresolved && entry.expiresAt > clock.nowMillis()) {
                countLocalHit()
                return@attempt CompletableFuture.completedFuture(entry.value)
            }
            toUser(missAsync(key, storageKey, AsyncSource.Native(loader))) { it.value }
        }

        override fun lookup(key: K): CompletableFuture<Lookup<V>> = Futures.attempt {
            val storageKey = storageKey(key)
            val entry = localHit(key, storageKey)
            if (entry != null) {
                return@attempt CompletableFuture.completedFuture(lookupOf(entry))
            }
            toUser(missAsync(key, storageKey, asyncSource)) { lookupOf(it) }
        }

        override fun getAll(keys: Collection<K>): CompletableFuture<Map<K, V>> = Futures.attempt {
            val resolved = LinkedHashMap<K, LocalEntry<V>?>()
            val pending = collectPending(keys, resolved)
            if (pending.isEmpty()) {
                return@attempt CompletableFuture.completedFuture(valuesOf(resolved))
            }
            toUser(loadPendingAsync(pending)) { entries ->
                for ((storageKey, entry) in entries) {
                    pending.getValue(storageKey).forEach { resolved[it] = entry }
                }
                valuesOf(resolved)
            }
        }

        override fun put(key: K, value: V): CompletableFuture<Void?> = writeToUser {
            putAsync(key, value, spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl())
        }

        override fun put(key: K, value: V, ttl: Duration): CompletableFuture<Void?> = writeToUser {
            putAsync(
                key,
                value,
                ttl
            )
        }

        override fun putAll(entries: Map<K, V>): CompletableFuture<Void?> = writeToUser {
            putAllAsync(entries) { key, value -> spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl() }
        }

        override fun putAll(entries: Map<K, V>, ttl: Duration): CompletableFuture<Void?> = writeToUser {
            putAllAsync(entries) { _, _ -> ttl }
        }

        override fun invalidate(key: K): CompletableFuture<Void?> = writeToUser {
            invalidateAsync(
                listOf(storageKey(key))
            )
        }

        override fun invalidateAll(keys: Collection<K>): CompletableFuture<Void?> = writeToUser {
            invalidateAsync(keys.map { storageKey(it) }.distinct())
        }

        override fun getIfChanged(key: K, known: Digest?): CompletableFuture<ConditionalGet<V>> = Futures.relay(
            Futures.attempt { getIfChangedAsync(key, known) }
        )

        override fun putIfMatch(key: K, expected: Digest?, value: V): CompletableFuture<ConditionalPut> = Futures.relay(
            Futures.attempt {
                putIfMatchAsync(
                    key,
                    expected,
                    value,
                    spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl()
                )
            }
        )

        override fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): CompletableFuture<ConditionalPut> =
            Futures.relay(Futures.attempt { putIfMatchAsync(key, expected, value, ttl) })

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

        private fun missAsync(key: K, storageKey: String, source: AsyncSource<K, V>?): CompletableFuture<LocalEntry<V>> {
            filteredEntry(storageKey)?.let { return completed(it) }
            return loadMissAsync(key, storageKey, source)
        }

        private fun writeToUser(block: () -> CompletableFuture<Void?>): CompletableFuture<Void?> = Futures.relay(
            Futures.attempt(block)
        )

        @Suppress("TooGenericExceptionCaught")
        private fun <T, R> toUser(source: CompletableFuture<T>, map: (T) -> R): CompletableFuture<R> {
            val target = CompletableFuture<R>()
            source.whenComplete { value, error ->
                if (error != null) {
                    target.completeExceptionally(Futures.unwrap(error))
                } else {
                    try {
                        target.complete(map(value))
                    } catch (e: Throwable) {
                        target.completeExceptionally(e)
                    }
                }
            }
            return target
        }
    }

    private companion object {
        val INITIAL_BACKOFF_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(2)
        val MAX_BACKOFF_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(50)

        fun nextBackoff(backoff: Long): Long = minOf(backoff * 2, MAX_BACKOFF_NANOS)

        /**
         * 异步数据源超时的哨兵值（不可能是数据源的结果）。
         */
        val TIMED_OUT = Any()

        @Suppress("UNCHECKED_CAST")
        fun <R> uncheckedCast(value: Any?): R = value as R
    }
}

/**
 * 委托给数据源的刷新数据源（见 `CoherentEngine.refreshLoader`）：只用于让回源观测区分刷新与未命中加载。
 */
private class RefreshLoader<K : Any, V : Any>(private val delegate: CacheLoader<K, V>) : CacheLoader<K, V> by delegate

/**
 * 异步路径的数据源（RFC 0004 §3.2）。
 */
private sealed interface AsyncSource<K : Any, V : Any> {
    class Native<K : Any, V : Any>(val loader: AsyncCacheLoader<K, V>) : AsyncSource<K, V>

    class Blocking<K : Any, V : Any>(val loader: CacheLoader<K, V>) : AsyncSource<K, V>
}

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
    class Load(val token: Token?) : BatchStep<Nothing>

    class Done<V>(val entry: LocalEntry<V>) : BatchStep<V>

    class Corrupt(val read: RemoteRead.Hit, val error: Exception) : BatchStep<Nothing>

    class Failed(val error: CacheReadException) : BatchStep<Nothing>
}

/**
 * 提前刷新读到 L1 之后的判定（同步与异步共用）。
 */
private sealed interface RefreshStep<out V> {
    class Reload(val releaseToken: Boolean) : RefreshStep<Nothing>

    class Refill<V>(val value: V?, val ttl: Duration?) : RefreshStep<V>

    data object LoadWithToken : RefreshStep<Nothing>
}
