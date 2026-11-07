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

import me.ahoo.cocache.api.AsyncSetStore
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.SuspendingSetStore
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1SetWrite
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.internal.Futures
import me.ahoo.cocache.core.internal.LoadingKeys
import me.ahoo.cocache.core.internal.runSync
import me.ahoo.cocache.core.internal.uncancellable
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.coroutineContext

/**
 * 权威集合 `SetStore` 的引擎（RFC 0003 §4.8，RFC 0005 §4.1）。
 *
 * 没有数据源：元素增删在 L1 中原子完成并由 L1 串行化；读取只从 L1 填充 L2，不申请 lease。
 * L1 读失败时读取的结果为 `Unknown`（[Unanswered]）：`get` 抛出 [CacheReadException]，`lookup` 返回 `Lookup.Unknown`；
 * 不允许 `unknownRead`。
 */
internal class SetEngine<K : Any, E : Any>(
    spec: SetStoreSpec<K, E>,
    store: L1Store,
    context: EngineContext,
) : AbstractEngine<K, Set<E>>(
    spec.name,
    spec.keyspace,
    spec.keyEncoder,
    spec.policy,
    store,
    context,
    UnknownReadMode.THROW
),
    SetStore<K, E> {

    private val elementCodec = spec.elementCodec
    private val view = AsyncView()
    private val suspendingView = SuspendingView()

    override fun async(): AsyncSetStore<K, E> = view

    override fun suspending(): SuspendingSetStore<K, E> = suspendingView

    override fun get(key: K): Set<E> = membersOf(entryOf(key))

    override fun lookup(key: K): Lookup<Set<E>> = lookupAnswerOf(entryOf(key))

    override fun getAll(keys: Collection<K>): Map<K, Set<E>> = membersOf(resolveAll(keys))

    override fun lookupAll(keys: Collection<K>): Map<K, Lookup<Set<E>>> = lookupsOf(resolveAll(keys))

    /**
     * 批量读取（同步变体）：命中的 key 内联取得，未命中的 key 在调用方线程上经一次批量读取取得（[loadEntries]）。
     */
    private fun resolveAll(keys: Collection<K>): Map<K, LocalEntry<Set<E>>> {
        val found = HashMap<K, LocalEntry<Set<E>>>()
        val misses = collectMisses(keys, found)
        if (misses.isNotEmpty()) {
            val loaded = runSync { loadEntries(misses.values) }
            misses.forEach { (key, storageKey) -> found[key] = loaded.getValue(storageKey) }
        }
        return inOrder(keys, found)
    }

    /**
     * 批量读取的 L2 阶段（三种变体共用）：命中的 key 填入 [found]；返回未命中的 key → 存储 key。
     * 整批拒绝（#866）：未命中的 key 在遍历中检查（[storageKeyOfLocal]），整批都检查过之后才计入 L2 命中。
     */
    private fun collectMisses(keys: Collection<K>, found: MutableMap<K, LocalEntry<Set<E>>>): LinkedHashMap<K, String> {
        val misses = LinkedHashMap<K, String>()
        var hits = 0
        for (key in keys) {
            if (key in found || key in misses) {
                continue
            }
            val localKey = localKey(key)
            val hit = freshLocal(localKey)
            if (hit != null) {
                found[key] = hit
                hits++
            } else {
                misses[key] = storageKeyOfLocal(localKey)
            }
        }
        countLocalHits(hits)
        return misses
    }

    /**
     * 集合的成员：不存在为空集合；`Unknown` 时抛出（计入 `readsUnknown`，[answerOrThrow]：`SetStore` 不允许 `unknownRead`）。
     */
    private fun membersOf(entry: LocalEntry<Set<E>>): Set<E> = answerOrThrow(entry) ?: emptySet()

    /**
     * 批量读取：结果只包含存在的集合；部分 key 为 `Unknown` 时，抛出携带这些 key 与部分结果的 [CacheReadException]（[valuesOf]）。
     */
    private fun membersOf(resolved: Map<K, LocalEntry<Set<E>>>): Map<K, Set<E>> = valuesOf(resolved, IDENTITY())

    /**
     * 同步变体：L2 命中就地返回；未命中在调用方线程上运行协程核心（[loadEntry]）。
     */
    private fun entryOf(key: K): LocalEntry<Set<E>> {
        val localKey = localKey(key)
        return localHit(localKey) ?: runSync { loadEntry(storageKeyOfLocal(localKey)) }
    }

    /**
     * 异步变体：L2 命中返回已完成的 future，不启动协程；未命中在运行时作用域中运行同一份协程核心（[loadEntry]），再经 [map] 换算。
     * L1 读失败以原始异常失败（没有可降级的数据源）；`KeyEncoder` 抛出同样以失败的 future 交出，从不同步抛出。
     */
    private fun <R> entryOfAsync(key: K, map: (LocalEntry<Set<E>>) -> R): CompletableFuture<R> = Futures.attempt {
        val localKey = localKey(key)
        val entry = localHit(localKey) ?: return@attempt context.async.runAsync { map(loadEntry(storageKeyOfLocal(localKey))) }
        CompletableFuture.completedFuture(map(entry))
    }

    /**
     * 挂起变体（RFC 0005 §4.4，K2、K6）：L2 命中内联返回；未命中在运行时的调度器上运行同一份协程核心（[loadEntry]），元素取自调用方。
     */
    private suspend fun <R> entryOfSuspending(key: K, map: (LocalEntry<Set<E>>) -> R): R {
        val localKey = localKey(key)
        val entry = localHit(localKey) ?: return missOfSuspending(storageKeyOfLocal(localKey), map)
        return map(entry)
    }

    private suspend fun <R> missOfSuspending(storageKey: String, map: (LocalEntry<Set<E>>) -> R): R =
        context.async.runSuspending { map(loadEntry(storageKey)) }

    /**
     * 异步变体的批量读取：全部命中 L2 时返回已完成的 future；否则在运行时作用域中经一次批量读取取得未命中的 key（[loadEntries]）。
     */
    private fun <R> resolveAllAsync(keys: Collection<K>, map: (Map<K, LocalEntry<Set<E>>>) -> R): CompletableFuture<R> =
        Futures.attempt {
            val found = HashMap<K, LocalEntry<Set<E>>>()
            val misses = collectMisses(keys, found)
            if (misses.isEmpty()) {
                return@attempt CompletableFuture.completedFuture(map(inOrder(keys, found)))
            }
            context.async.runAsync { map(loadedInOrder(keys, found, misses)) }
        }

    /**
     * 挂起变体的批量读取：命中的 key 内联取得，未命中的 key 经一次批量读取取得（[loadEntries]），结果按 [keys] 的顺序组装。
     */
    private suspend fun <R> resolveAllSuspending(keys: Collection<K>, map: (Map<K, LocalEntry<Set<E>>>) -> R): R {
        val found = HashMap<K, LocalEntry<Set<E>>>()
        val misses = collectMisses(keys, found)
        if (misses.isEmpty()) {
            return map(inOrder(keys, found))
        }
        return context.async.runSuspending { map(loadedInOrder(keys, found, misses)) }
    }

    private suspend fun loadedInOrder(
        keys: Collection<K>,
        found: MutableMap<K, LocalEntry<Set<E>>>,
        misses: Map<K, String>
    ): Map<K, LocalEntry<Set<E>>> {
        val loaded = loadEntries(misses.values)
        misses.forEach { (key, storageKey) -> found[key] = loaded.getValue(storageKey) }
        return inOrder(keys, found)
    }

    private fun inOrder(keys: Collection<K>, found: Map<K, LocalEntry<Set<E>>>): Map<K, LocalEntry<Set<E>>> {
        val resolved = LinkedHashMap<K, LocalEntry<Set<E>>>()
        keys.forEach { key -> found[key]?.let { resolved.putIfAbsent(key, it) } }
        return resolved
    }

    private fun localHit(localKey: String): LocalEntry<Set<E>>? = freshLocal(localKey)?.also { countLocalHit() }

    /**
     * 未过期的 L2 副本，不计数；没有则返回 `null`。
     */
    private fun freshLocal(localKey: String): LocalEntry<Set<E>>? {
        val entry = local.getIfPresent(localKey) ?: return null
        return entry.takeIf { it.expiresAt > clock.nowMillis() }
    }

    /**
     * 未命中（同步与异步共用的协程核心，RFC 0005 V5）：经 SingleFlight 合并，读己之写至多重试一次。
     * 合并之前记下戳（observed），合并到的结果取戳早于它时，说明读取开始于本节点之前的一次写入，重新加载。
     */
    private suspend fun loadEntry(storageKey: String): LocalEntry<Set<E>> {
        val observed = stamps.current(storageKey)
        val loaded = flights.executeSuspending(storageKey, scope) { loadThrough(storageKey) }
        if (loaded.stamp >= observed) {
            return loaded.entry
        }
        return flights.executeSuspending(storageKey, scope) { loadThrough(storageKey) }.entry
    }

    /**
     * 批量未命中（三种变体共用的协程核心，RFC 0005 §9 A.L2）：经 SingleFlight 合并（[SingleFlight.executeAllSuspending]），
     * 本调用负责的 key 由一次 L1 批量读取取得（[loadAllThrough]），不逐个读取。合并规则逐 key 同单 key 路径（[loadEntry]）：
     * 合并到的结果取戳早于本次读取观察到的戳（读取开始于本节点之前的一次写入），或合并到的批量没有这个 key 的结果，改走单 key 路径。
     */
    private suspend fun loadEntries(storageKeys: Collection<String>): Map<String, LocalEntry<Set<E>>> {
        val distinct = storageKeys.distinct()
        val observed = distinct.associateWith { stamps.current(it) }
        val loaded = flights.executeAllSuspending(distinct, scope) { led -> loadAllThrough(led) }
        return observed.mapValues { (storageKey, stamp) ->
            val result = loaded[storageKey]
            if (result != null && result.stamp >= stamp) result.entry else loadEntry(storageKey)
        }
    }

    /**
     * 一次 L1 批量读取（不申请 lease）。整批读失败时每个 key 都没有答案（[Unanswered]），不写任何一层，计入一次 `remoteFailures`。
     * 某个 key 的内容读不懂（[onHit] 抛出）时，先完成其余 key（填充 L2），再抛出第一个失败，同单 key 路径的读取抛出它。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadAllThrough(storageKeys: List<String>): Map<String, Loaded<Set<E>>> {
        val loadStamps = storageKeys.associateWith { stamps.current(it) }
        // 读取之前取时刻，整批共用（#877）：副本的寿命从它算起，前面 key 的解码时间不累加到后面 key 上
        val readAt = clock.nowMillis()
        val reads = readAllRemote(storageKeys).getOrElse { error ->
            metrics.remoteFailure()
            log.warn(error) { "SetStore[$name] - keys$storageKeys - remote read failed: no answer (Unknown)." }
            val unknown = Unanswered<Set<E>>(CacheReadException(name, "readAll", error))
            return storageKeys.associateWith { Loaded(unknown, loadStamps.getValue(it)) }
        }
        val result = LinkedHashMap<String, Loaded<Set<E>>>()
        var failure: Exception? = null
        for (storageKey in storageKeys) {
            try {
                val read = checkNotNull(reads[storageKey]) { "SetStore[$name] - readAll returned no $storageKey." }
                result[storageKey] = loaded(storageKey, read, loadStamps.getValue(storageKey), readAt)
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
        return result
    }

    /**
     * 一次 L1 批量读取（不申请 lease）。取消、中断与运行时关闭之后的失败原样抛出（[rethrowIfFatal]，同标量引擎），其余失败作为结果交回。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun readAllRemote(storageKeys: List<String>): Result<Map<String, L1Read>> = try {
        Result.success(uncancellable { store.readAll(storageKeys, emptyMap(), policy.readTimeout) })
    } catch (e: Exception) {
        rethrowIfFatal(e)
        Result.failure(e)
    }

    /**
     * L1 读失败时这次读取没有答案（[Unanswered]），不写任何一层，计入 `remoteFailures`。取消、中断与运行时关闭之后的失败原样抛出
     * （[rethrowIfFatal]，同标量引擎）。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadThrough(storageKey: String): Loaded<Set<E>> {
        val stamp = stamps.current(storageKey)
        // 读取之前取时刻（#877），同批量路径
        val readAt = clock.nowMillis()
        val read = try {
            readRemote(storageKey, null, null)
        } catch (e: Exception) {
            rethrowIfFatal(e)
            metrics.remoteFailure()
            log.warn(e) { "SetStore[$name] - key[$storageKey] - remote read failed: the read has no answer (Unknown)." }
            return Loaded(Unanswered(CacheReadException(name, "read", e)), stamp)
        }
        return loaded(storageKey, read, stamp, readAt)
    }

    /**
     * 读到 L1 之后的判定与 L2 填充：副本的寿命从发出读取之前的时刻 [readAt] 算起，解码耗费的时间从中扣除，L2 不活过 L1（#877）。
     */
    private fun loaded(storageKey: String, read: L1Read, stamp: Long, readAt: Long): Loaded<Set<E>> {
        val entry = when (read) {
            is L1Read.Hit -> onHit(storageKey, read, readAt)

            is L1Read.Miss, is L1Read.Leased -> {
                metrics.miss()
                localEntry(null, policy.absentTtl, policy.localMaxAge, since = readAt)
            }
        }
        fillLocal(storageKey, entry, stamp)
        return Loaded(entry, stamp)
    }

    /**
     * L1 是唯一副本，没有数据源可以回源，读不懂的内容一律以 [CacheReadException] 失败（`cause` 为 [ForeignFormatException]），
     * 计入 `unknownFormats`，不写 L2、不删除它（读成空集合会让调用方把“不存在”当作真实结果；集合缓存从不回收），同 `Store`（[CoherentEngine]）：
     * - 更高的格式（[isNewerFormat]：更高的格式版本、`!m` 中有未知的位）：`newer = true`；
     * - 不是本格式写入的内容（格式号低于本节点格式的集合、原生 Set 等其它 Redis 类型）：报告它的格式；
     * - 形状不符（集合 key 上是本协议的标量，通常是命名空间冲突）或元素解码失败：报告本节点的格式，`cause` 为原始错误。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun onHit(storageKey: String, read: L1Read.Hit, readAt: Long): LocalEntry<Set<E>> {
        if (read.format != backendFormat) {
            throw CacheReadException(name, "read", unknownFormat(storageKey, read.format, null))
        }
        // 先解码再计入命中：读不懂的内容不计为命中
        val value = try {
            decode(read.envelope)
        } catch (e: Exception) {
            throw CacheReadException(name, "read", unknownFormat(storageKey, read.format, e))
        }
        metrics.remoteHit()
        return localEntry(value, read.ttl, policy.localMaxAge, since = readAt)
    }

    private fun decode(envelope: Envelope): Set<E>? = when (envelope) {
        is Envelope.Members -> envelope.members.mapTo(LinkedHashSet()) { elementCodec.decode(it) }
        Envelope.Absent -> null
        is Envelope.Scalar -> throw IllegalStateException("SetStore[$name] - expected a set but found a scalar.")
    }

    private fun encode(elements: Collection<E>): List<ByteArray> = elements.map { elementCodec.encode(it) }

    override fun put(key: K, value: Set<E>) {
        runSync { putWithTtl(key, value, policy.valueTtl()) }
    }

    override fun put(key: K, value: Set<E>, ttl: Duration) {
        runSync { putWithTtl(key, value, ttl) }
    }

    /**
     * 写入（同步与异步共用的协程核心）：非空集合整体覆盖，空集合等价于 [clear]。
     */
    private suspend fun putWithTtl(key: K, value: Set<E>, ttl: Duration?) {
        val storageKey = storageKey(key)
        if (value.isEmpty()) {
            clearStorageKey(storageKey)
            return
        }
        val envelope = Envelope.Members(encode(value))
        val bounded = ttl?.boundedTtl()
        write(
            listOf(storageKey),
            { frame -> store.put(storageKey, envelope, bounded, frame) },
            onFailure = throwWriteFailure("put")
        )
    }

    override fun putAll(entries: Map<K, Set<E>>) {
        if (entries.isNotEmpty()) {
            runSync { putAllWithTtl(entries, policy.valueTtl()) }
        }
    }

    override fun putAll(entries: Map<K, Set<E>>, ttl: Duration) {
        if (entries.isNotEmpty()) {
            runSync { putAllWithTtl(entries, ttl) }
        }
    }

    /**
     * 一次 [write] 覆盖全部存储 key，语义同逐个 [put]：非空集合整体覆盖（批量），空集合等价于 [clear]（批量删除）。
     * 编码后碰撞的存储 key 以后出现的为准。两类都有时各自只广播自己的 key。
     *
     * 两类都有时先整体覆盖、再删除：删除时整体覆盖已经生效，删除失败（即使在写出之前被拒绝）整批也是部分执行（[afterAppliedStage]），
     * `outcomeUnknown = true`，非原子广播时补发整批的帧，否则其它节点保留已覆盖的 key 的旧副本。
     */
    private suspend fun putAllWithTtl(entries: Map<K, Set<E>>, ttl: Duration?) {
        if (entries.isEmpty()) {
            return
        }
        val plan = putAllPlan(entries, ttl)
        write(plan.keys, { frame ->
            if (plan.puts.isNotEmpty()) {
                val putFrame = plan.putFrame(frame)
                store.putAll(plan.puts, putFrame)
            }
            if (plan.clears.isNotEmpty()) {
                val clearFrame = plan.clearFrame(frame)
                if (plan.puts.isEmpty()) {
                    store.invalidate(plan.clears, clearFrame)
                } else {
                    afterAppliedStage { store.invalidate(plan.clears, clearFrame) }
                }
            }
        }, onFailure = throwWriteFailure("putAll"))
    }

    private inner class PutAllPlan(val keys: Collection<String>, val puts: List<Put>, val clears: List<String>) {
        fun putFrame(frame: ByteArray): ByteArray = if (clears.isEmpty()) frame else frameOf(puts.map { it.key })

        fun clearFrame(frame: ByteArray): ByteArray = if (puts.isEmpty()) frame else frameOf(clears)
    }

    /**
     * 同步与异步共用：非空集合整体覆盖（批量），空集合等价于 [clear]（批量删除）；编码后碰撞的存储 key 以后出现的为准。
     */
    private fun putAllPlan(entries: Map<K, Set<E>>, ttl: Duration?): PutAllPlan {
        val latest = LinkedHashMap<String, Set<E>>(entries.size)
        entries.forEach { (key, value) -> latest[storageKey(key)] = value }
        val bounded = ttl?.boundedTtl()
        val puts = latest.filterValues { it.isNotEmpty() }.map { (storageKey, value) ->
            Put(storageKey, Envelope.Members(encode(value)), bounded)
        }
        val clears = latest.filterValues { it.isEmpty() }.keys.toList()
        return PutAllPlan(latest.keys, puts, clears)
    }

    override fun add(key: K, elements: Collection<E>) {
        if (elements.isNotEmpty()) {
            runSync { addMembers(key, elements) }
        }
    }

    /**
     * 加入元素（同步与异步共用的协程核心）：后端拒绝落在读不懂的内容上（[refuseForeign]），从不整体覆盖它。
     */
    private suspend fun addMembers(key: K, elements: Collection<E>) {
        if (elements.isEmpty()) {
            return
        }
        val sets = requireSets("add")
        val storageKey = storageKey(key)
        val members = encode(elements)
        val ttl = policy.valueTtl()
        write(listOf(storageKey), { frame ->
            refuseForeign(storageKey, sets.addMembers(storageKey, members, ttl, frame))
        }, onFailure = throwWriteFailure("add"))
    }

    /**
     * 后端拒绝了写入（[L1SetWrite.Unreadable]：key 中是读不懂的内容，未修改、未广播）：计入 `unknownFormats` 并抛出，
     * 由 [write] 包装为 `CacheWriteException`。
     */
    private fun refuseForeign(storageKey: String, outcome: L1SetWrite): Void? {
        val format = when (outcome) {
            L1SetWrite.Written -> return null
            is L1SetWrite.Unreadable -> outcome.format
        }
        throw unknownFormat(storageKey, format, null)
    }

    override fun remove(key: K, elements: Collection<E>) {
        if (elements.isNotEmpty()) {
            runSync { removeMembers(key, elements) }
        }
    }

    /**
     * 移除元素（同步与异步共用的协程核心），规则同 [addMembers]。
     */
    private suspend fun removeMembers(key: K, elements: Collection<E>) {
        if (elements.isEmpty()) {
            return
        }
        val sets = requireSets("remove")
        val storageKey = storageKey(key)
        val members = encode(elements)
        val ttl = policy.valueTtl()
        write(listOf(storageKey), { frame ->
            refuseForeign(storageKey, sets.removeMembers(storageKey, members, ttl, frame))
        }, onFailure = throwWriteFailure("remove"))
    }

    override fun clear(key: K) {
        val storageKey = storageKey(key)
        runSync { clearStorageKey(storageKey) }
    }

    /**
     * 删除整个集合（同步与异步共用的协程核心）。
     */
    private suspend fun clearStorageKey(storageKey: String) {
        val keys = listOf(storageKey)
        write(
            keys,
            { frame -> store.invalidate(keys, frame) },
            onFailure = throwWriteFailure("clear")
        )
    }

    override fun describe(envelope: Envelope): Any? = decode(envelope)

    /**
     * 运维的强制失效：L1 是唯一副本，等价于 [clear]。
     */
    override fun invalidateEncoded(encodedKey: String) {
        val storageKey = storageKeyOf(encodedKey)
        runSync { clearStorageKey(storageKey) }
    }

    override val keyDeletion: CacheAdmin.KeyDeletion
        get() = CacheAdmin.KeyDeletion.CLEAR

    /**
     * 异步视图（RFC 0004）：与同步方法语义相同，L1 读失败以 [CacheReadException] 失败。
     */
    private inner class AsyncView : AsyncSetStore<K, E> {
        override val name: String
            get() = this@SetEngine.name

        override fun get(key: K): CompletableFuture<Set<E>?> = entryOfAsync(key) { membersOf(it) }

        /**
         * 未命中的 key 经一次 L1 批量读取取得（[loadEntries]），结果按顺序组装。
         */
        override fun getAll(
            keys: Collection<K>
        ): CompletableFuture<Map<K, Set<E>>> = resolveAllAsync(keys) { membersOf(it) }

        override fun lookup(key: K): CompletableFuture<Lookup<Set<E>>> = entryOfAsync(key) { lookupAnswerOf(it) }

        override fun lookupAll(keys: Collection<K>): CompletableFuture<Map<K, Lookup<Set<E>>>> =
            resolveAllAsync(keys) { lookupsOf(it) }

        override fun put(key: K, value: Set<E>): CompletableFuture<Void?> = runAsyncCommand {
            putWithTtl(key, value, policy.valueTtl())
        }

        override fun put(key: K, value: Set<E>, ttl: Duration): CompletableFuture<Void?> = runAsyncCommand {
            putWithTtl(key, value, ttl)
        }

        override fun putAll(entries: Map<K, Set<E>>): CompletableFuture<Void?> = runAsyncCommand {
            putAllWithTtl(entries, policy.valueTtl())
        }

        override fun putAll(entries: Map<K, Set<E>>, ttl: Duration): CompletableFuture<Void?> = runAsyncCommand {
            putAllWithTtl(entries, ttl)
        }

        override fun add(key: K, elements: Collection<E>): CompletableFuture<Void?> = runAsyncCommand {
            addMembers(key, elements)
        }

        override fun remove(key: K, elements: Collection<E>): CompletableFuture<Void?> = runAsyncCommand {
            removeMembers(key, elements)
        }

        override fun clear(key: K): CompletableFuture<Void?> = runAsyncCommand { clearStorageKey(storageKey(key)) }

        override fun sync(): SetStore<K, E> = this@SetEngine

        override fun toString(): String = "AsyncView(${this@SetEngine})"
    }

    /**
     * 挂起视图：直接调用协程核心（RFC 0005 §4.4），读取先检查同 key 重入（[LoadingKeys]）。
     */
    private inner class SuspendingView : SuspendingSetStore<K, E> {
        override val name: String
            get() = this@SetEngine.name

        override suspend fun get(key: K): Set<E> {
            LoadingKeys.check(coroutineContext, this@SetEngine, name, key)
            return entryOfSuspending(key) { membersOf(it) }
        }

        override suspend fun getAll(keys: Collection<K>): Map<K, Set<E>> {
            LoadingKeys.checkAll(coroutineContext, this@SetEngine, name, keys)
            return resolveAllSuspending(keys) { membersOf(it) }
        }

        override suspend fun lookup(key: K): Lookup<Set<E>> {
            LoadingKeys.check(coroutineContext, this@SetEngine, name, key)
            return entryOfSuspending(key) { lookupAnswerOf(it) }
        }

        override suspend fun lookupAll(keys: Collection<K>): Map<K, Lookup<Set<E>>> {
            LoadingKeys.checkAll(coroutineContext, this@SetEngine, name, keys)
            return resolveAllSuspending(keys) { lookupsOf(it) }
        }

        override suspend fun put(key: K, value: Set<E>) =
            context.async.runSuspending { putWithTtl(key, value, policy.valueTtl()) }

        override suspend fun put(key: K, value: Set<E>, ttl: Duration) =
            context.async.runSuspending { putWithTtl(key, value, ttl) }

        override suspend fun putAll(entries: Map<K, Set<E>>) =
            context.async.runSuspending { putAllWithTtl(entries, policy.valueTtl()) }

        override suspend fun putAll(entries: Map<K, Set<E>>, ttl: Duration) =
            context.async.runSuspending { putAllWithTtl(entries, ttl) }

        override suspend fun add(key: K, elements: Collection<E>) =
            context.async.runSuspending { addMembers(key, elements) }

        override suspend fun remove(key: K, elements: Collection<E>) =
            context.async.runSuspending { removeMembers(key, elements) }

        override suspend fun clear(key: K) = context.async.runSuspending { clearStorageKey(storageKey(key)) }

        override fun async(): AsyncSetStore<K, E> = view

        override fun toString(): String = "Suspending(${this@SetEngine})"
    }
}
