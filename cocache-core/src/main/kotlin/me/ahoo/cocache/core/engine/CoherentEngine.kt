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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.SourceLoadRejectedException
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * 数据源为权威的一致性缓存引擎（RFC 0003 §4.4–§4.6）。
 *
 * - L1 栅栏：未命中时由读脚本原子取得 lease，回源结果只能凭 lease 写回；失效销毁 lease。
 * - L2 栅栏：失效戳。填充方读 L1 前取戳；写入方在修改 L1 前后各递增一次。L2 只由读 L1 的路径填充。
 * - loader 总是在调用方（SingleFlight leader）线程上同步执行。
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class CoherentEngine<K : Any, V : Any>(
    private val spec: CacheSpec<K, V>,
    store: RemoteStore,
    context: EngineContext,
) : AbstractEngine<K, V>(spec.name, spec.namespace, spec.keyPrefix, spec.keyEncoder, spec.policy, store, context),
    Cache<K, V> {

    private val codec = spec.codec
    private val loader = spec.loader
    private val keyFilter = spec.keyFilter
    private val backendFormat = context.backend.format
    private val permits: Semaphore? = policy.load.maxConcurrent.takeIf { it > 0 }?.let { Semaphore(it) }
    private val loadObserver: LoadObserver = context.loadObserver

    /**
     * 提前刷新所用的数据源：与 [loader] 行为相同，只用于让回源观测区分刷新与未命中加载。
     */
    private val refreshLoader: CacheLoader<K, V>? = loader?.let { RefreshLoader(it) }

    /**
     * 失效写入 L1 失败、仍在重试中的存储 key：未命中时跳过 L1（L1 中仍是旧值）。
     */
    private val pendingInvalidations = ConcurrentHashMap<String, Int>()

    /**
     * 正在提前刷新的存储 key：每个节点每个 key 同一时刻至多一次。
     */
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

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
                    refreshAsync(key, storageKey)
                }
                return entry
            }
        }
        return miss(key, storageKey)
    }

    /**
     * 未命中路径，与命中路径分开以保证 [entryOf] 足够小、可内联。
     */
    private fun miss(key: K, storageKey: String, source: CacheLoader<K, V>? = loader): LocalEntry<V> {
        if (keyFilter?.notExist(storageKey) == true) {
            metrics.filtered()
            return LocalEntry(null, clock.nowMillis() + policy.absentTtl.toMillis())
        }
        metrics.miss()
        return loadMiss(key, storageKey, source)
    }

    /**
     * 未命中的加载（已计数、已过 key 过滤器）。
     */
    private fun loadMiss(key: K, storageKey: String, source: CacheLoader<K, V>?): LocalEntry<V> {
        // 先于本次读取发生的失效此时必已递增戳：不接受开始于这些失效之前的共享加载（读己之写）
        val observed = stamps.current(storageKey)
        val loaded = flights.execute(storageKey) { loadThrough(key, storageKey, source) }
        if (loaded.stamp >= observed && usable(loaded.entry, source)) {
            return loaded.entry
        }
        // 新一轮加载必然开始于上一轮结束之后（SingleFlight 先注销、再发布），因此至多重试一次
        val retried = flights.execute(storageKey) { loadThrough(key, storageKey, source) }.entry
        if (usable(retried, source)) {
            return retried
        }
        // 两次都合并到了不带数据源的加载（get(key) 与 get(key, loader) 并发）：自己回源，写回仍受戳保护
        return loadThrough(key, storageKey, source).entry
    }

    /**
     * 合并到的加载若没有数据源，而本次调用带有数据源，则其结果不可用。
     */
    private fun usable(
        entry: LocalEntry<V>,
        source: CacheLoader<K, V>?
    ): Boolean = !(entry.unresolved && source != null)

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
        val deadline = System.nanoTime() + minOf(leased.remaining, policy.lease.wait).toNanos()
        var backoff = INITIAL_BACKOFF_NANOS
        while (read is RemoteRead.Leased) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                break
            }
            LockSupport.parkNanos(minOf(backoff, remaining))
            backoff = minOf(backoff * 2, MAX_BACKOFF_NANOS)
            read = store.read(storageKey, lease, null)
        }
        return read
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onHit(
        key: K,
        storageKey: String,
        read: RemoteRead.Hit,
        stamp: Long,
        lease: LeaseRequest,
        source: CacheLoader<K, V>?
    ): LocalEntry<V> {
        if (read.format > backendFormat) {
            // 滚动升级期间更高版本写入的值：既不回收也不写回，否则新旧节点互相覆盖
            metrics.unknownFormat()
            return loadBypassingRemote(key, storageKey, stamp, source)
        }
        val value = try {
            check(read.format >= MIN_FORMAT) { "Unrecognized remote content (not written by this protocol)." }
            decode(read.envelope)
        } catch (e: Exception) {
            return onCorrupt(key, storageKey, read, stamp, lease, source, e)
        }
        metrics.remoteHit()
        val entry = localEntry(value, read.ttl, policy.localMaxAge)
        fillLocal(storageKey, entry, stamp)
        return entry
    }

    /**
     * 同版本载荷损坏：仅当内容仍是读到的那一份时转为 lease（条件回收，读路径从不无条件删除），再正常回源。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun onCorrupt(
        key: K,
        storageKey: String,
        read: RemoteRead.Hit,
        stamp: Long,
        lease: LeaseRequest,
        source: CacheLoader<K, V>?,
        error: Exception
    ): LocalEntry<V> {
        log.warn(error) { "Cache[$name] - key[$storageKey] - corrupted payload, reclaim and reload." }
        metrics.reclaim()
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

    private fun decode(envelope: Envelope): V? = when (envelope) {
        is Envelope.Scalar -> codec.decode(envelope.bytes)
        Envelope.Absent -> null
        is Envelope.Members -> throw IllegalStateException("Cache[$name] - expected a scalar but found a set.")
    }

    private fun envelopeOf(value: V?): Envelope = value?.let { Envelope.Scalar(codec.encode(it)) } ?: Envelope.Absent

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
            return absentWithoutLoader(storageKey, stamp, token)
        }
        // 计算寿命与编码也可能抛出（用户的 ttlOf、Codec）：与回源一起释放 lease
        var value: V? = null
        val ttl: Duration?
        val envelope: Envelope
        try {
            value = loadFromSource(key, source)
            ttl = ttlOf(key, value)
            envelope = envelopeOf(value)
        } catch (e: Throwable) {
            token?.let { release(storageKey, it) }
            throw e
        }
        val filled = token != null && fillRemote(Fill(storageKey, token, envelope, ttl))
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
     * 释放 lease，只在本地缓存不存在。
     */
    private fun absentWithoutLoader(storageKey: String, stamp: Long, token: Token?): LocalEntry<V> {
        token?.let { release(storageKey, it) }
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
    ): LocalEntry<V> {
        val value = loadFromSource(key, source)
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
        if (semaphore != null && !semaphore.tryAcquire(policy.load.acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
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
        // 存储 key → 映射到它的业务 key（KeyEncoder 可能把不同 key 映射为同一个存储 key）
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
        if (pending.isNotEmpty()) {
            for ((storageKey, entry) in loadPending(pending)) {
                pending.getValue(storageKey).forEach { resolved[it] = entry }
            }
        }
        val result = LinkedHashMap<K, V>()
        for ((key, entry) in resolved) {
            entry?.value?.let { result[key] = it }
        }
        return result
    }

    /**
     * 未过期的 L2 副本（进入刷新窗口时触发后台刷新）；没有则返回 `null`。
     */
    private fun localHit(key: K, storageKey: String): LocalEntry<V>? {
        val entry = local.getIfPresent(storageKey) ?: return null
        val now = clock.nowMillis()
        if (entry.expiresAt <= now) {
            return null
        }
        if (entry.refreshAt <= now) {
            refreshAsync(key, storageKey)
        }
        return entry
    }

    /**
     * 批量加载未命中的 key：与在途的单 key 加载合并；共享到的加载若开始于本次读取已观察到的失效之前，改走单 key 路径。
     */
    private fun loadPending(pending: Map<String, List<K>>): Map<String, LocalEntry<V>> {
        val observed = pending.keys.associateWith { stamps.current(it) }
        val loaded = flights.executeAll(pending.keys) { led ->
            loadAllThrough(
                led.associateWith {
                    pending.getValue(it).first()
                }
            )
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
    private fun loadAllThrough(led: Map<String, K>): Map<String, Loaded<V>> {
        val loadStamps = led.keys.associateWith { stamps.current(it) }
        val result = LinkedHashMap<String, Loaded<V>>()
        val toLoad = readAllForBatch(led, loadStamps, result)
        if (toLoad.isNotEmpty()) {
            loadAndFillBatch(led, toLoad, loadStamps, result)
        }
        return result
    }

    /**
     * 批量读 L1：命中的 key 直接填入 [result]；返回需要回源的存储 key → 取得的 lease 令牌（没有则不写 L1）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readAllForBatch(
        led: Map<String, K>,
        loadStamps: Map<String, Long>,
        result: MutableMap<String, Loaded<V>>
    ): Map<String, Token?> {
        val storageKeys = led.keys
        val readKeys = storageKeys.filterNot { pendingInvalidations.containsKey(it) }
        val leases = readKeys.associateWith { LeaseRequest(context.nextToken(), policy.lease.ttl) }
        val reads = try {
            if (readKeys.isEmpty()) emptyMap() else store.readAll(readKeys, leases)
        } catch (e: Exception) {
            onReadFailure("readAll", readKeys.first(), e)
            emptyMap()
        }
        val toLoad = LinkedHashMap<String, Token?>()
        for (storageKey in storageKeys) {
            val read = reads[storageKey]
            val stamp = loadStamps.getValue(storageKey)
            if (read !is RemoteRead.Hit) {
                toLoad[storageKey] = (read as? RemoteRead.Miss)?.takeIf { it.leaseGranted }?.let { leases.getValue(storageKey).token }
                continue
            }
            if (read.format > backendFormat) {
                // 更高版本写入的值：既不回收也不写回
                metrics.unknownFormat()
                toLoad[storageKey] = null
                continue
            }
            val value = try {
                check(read.format >= MIN_FORMAT) { "Unrecognized remote content (not written by this protocol)." }
                decode(read.envelope)
            } catch (e: Exception) {
                // 与单 key 路径一致：条件回收后回源（否则损坏的载荷会一直留到 L1 过期）
                result[storageKey] = Loaded(onCorrupt(led.getValue(storageKey), storageKey, read, stamp, leases.getValue(storageKey), loader, e), stamp)
                continue
            }
            metrics.remoteHit()
            val entry = localEntry(value, read.ttl, policy.localMaxAge)
            fillLocal(storageKey, entry, stamp)
            result[storageKey] = Loaded(entry, stamp)
        }
        return toLoad
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
            toLoad.forEach { (storageKey, token) ->
                result[storageKey] = Loaded(absentWithoutLoader(storageKey, loadStamps.getValue(storageKey), token), loadStamps.getValue(storageKey))
            }
            return
        }
        val values: Map<K, V>
        val ttls: Map<String, Duration?>
        val fills: List<Fill>
        try {
            values = loadAllFromSource(toLoad.keys.map { led.getValue(it) })
            ttls = toLoad.keys.associateWith { storageKey -> led.getValue(storageKey).let { ttlOf(it, values[it]) } }
            fills = toLoad.mapNotNull { (storageKey, token) ->
                token?.let { Fill(storageKey, it, envelopeOf(values[led.getValue(storageKey)]), ttls[storageKey]) }
            }
        } catch (e: Throwable) {
            toLoad.forEach { (storageKey, token) -> token?.let { release(storageKey, it) } }
            throw e
        }
        val filled = fillAllRemote(fills)
        for ((storageKey, token) in toLoad) {
            val ok = token != null && filled[storageKey] == true
            if (token != null && !ok) {
                metrics.fillRejected()
            }
            if (!ok) {
                metrics.degradedFill()
            }
            val entry = localEntry(
                values[led.getValue(storageKey)],
                ttls[storageKey],
                if (ok) policy.localMaxAge else policy.degradedLocalMaxAge
            )
            fillLocal(storageKey, entry, loadStamps.getValue(storageKey))
            result[storageKey] = Loaded(entry, loadStamps.getValue(storageKey))
        }
    }

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
        return fills.indices.associate { fills[it].key to (outcomes.getOrNull(it) == true) }
    }

    /**
     * 后台刷新：经 SingleFlight 与并发的未命中合并。只有在 L1 上取得刷新令牌的节点回源并凭令牌写回；
     * 其它节点只从 L1 重新填充 L2。刷新失败或被拒绝时只告警，值照常到期。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun refreshAsync(key: K, storageKey: String) {
        // 没有数据源的缓存（如只经 Spring Cache 写入）无法回源刷新
        if (loader == null || closed.get() || !refreshing.add(storageKey)) {
            return
        }
        try {
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

    @Suppress("TooGenericExceptionCaught")
    private fun refreshThrough(key: K, storageKey: String): Loaded<V> {
        val window = policy.refreshWindow ?: return loadThrough(key, storageKey, refreshLoader)
        if (pendingInvalidations.containsKey(storageKey)) {
            // 失效仍在重试：L1 可能还是旧值，不能从它重新填充 L2（loadThrough 会绕过 L1）
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
        if (read !is RemoteRead.Hit) {
            return loadThrough(key, storageKey, refreshLoader)
        }
        if (read.format > backendFormat || read.format < MIN_FORMAT) {
            if (read.refreshGranted) {
                release(storageKey, token)
            }
            return loadThrough(key, storageKey, refreshLoader)
        }
        if (!read.refreshGranted) {
            val value = runCatching { decode(read.envelope) }
                .getOrElse { return loadThrough(key, storageKey, refreshLoader) }
            val entry = localEntry(value, read.ttl, policy.localMaxAge)
            fillLocal(storageKey, entry, stamp)
            return Loaded(entry, stamp)
        }
        return Loaded(loadAndFill(key, storageKey, stamp, token, refreshLoader), stamp)
    }

    override fun put(key: K, value: V) {
        putWithTtl(key, value, spec.ttlOf?.ttlOf(key, value) ?: policy.valueTtl())
    }

    override fun put(key: K, value: V, ttl: Duration) {
        putWithTtl(key, value, ttl)
    }

    private fun putWithTtl(key: K, value: V, ttl: Duration?) {
        val storageKey = storageKey(key)
        val envelope = Envelope.Scalar(codec.encode(value))
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
     * 编码后碰撞的存储 key 以后出现的为准，每个存储 key 只编码一次。
     */
    private inline fun putAllWithTtl(entries: Map<K, V>, ttlOf: (K, V) -> Duration?) {
        if (entries.isEmpty()) {
            return
        }
        val latest = LinkedHashMap<String, Map.Entry<K, V>>(entries.size)
        entries.entries.forEach { latest[storageKey(it.key)] = it }
        val puts = latest.map { (storageKey, entry) ->
            Put(storageKey, Envelope.Scalar(codec.encode(entry.value)), ttlOf(entry.key, entry.value)?.boundedTtl())
        }
        write(latest.keys, { frame -> store.putAll(puts, frame) }, throwWriteFailure("putAll"))
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
        write(storageKeys, { frame -> store.invalidate(storageKeys, frame) }) { error ->
            if (policy.invalidateFailure == InvalidateFailureMode.THROW) {
                throw CacheWriteException(name, "invalidate", error)
            }
            scheduleRetry(storageKeys, error)
        }
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

    private companion object {
        val INITIAL_BACKOFF_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(2)
        val MAX_BACKOFF_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(50)
    }
}

/**
 * 委托给数据源的刷新数据源（见 `CoherentEngine.refreshLoader`）：只用于让回源观测区分刷新与未命中加载。
 */
private class RefreshLoader<K : Any, V : Any>(private val delegate: CacheLoader<K, V>) : CacheLoader<K, V> by delegate
