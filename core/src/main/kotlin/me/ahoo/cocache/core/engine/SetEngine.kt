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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.AsyncSetCache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.internal.Futures
import me.ahoo.cocache.core.internal.awaitOriginal
import me.ahoo.cocache.core.internal.runSync
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * L1 是唯一副本的集合缓存引擎（RFC 0003 §4.8）。
 *
 * 没有数据源：元素增删在 L1 中原子完成并由 L1 串行化；读取只从 L1 填充 L2。
 * L1 故障时读取直接抛出原始异常（没有可降级的数据源）。
 */
internal class SetEngine<K : Any, E : Any>(
    spec: SetCacheSpec<K, E>,
    store: L1Store,
    context: EngineContext,
) : AbstractEngine<K, Set<E>>(spec.name, spec.keyspace, spec.keyEncoder, spec.policy, store, context),
    SetCache<K, E> {

    private val elementCodec = spec.elementCodec
    private val view = AsyncView()

    override fun async(): AsyncSetCache<K, E> = view

    override fun get(key: K): Set<E> = entryOf(key).value ?: emptySet()

    override fun lookup(key: K): Lookup<Set<E>> = lookupOf(entryOf(key))

    /**
     * L1 是唯一副本，没有可回源的数据源。
     */
    override fun get(key: K, loader: CacheLoader<K, Set<E>>): Set<E> {
        throw UnsupportedOperationException(
            "SetCache[$name] - the remote copy is authoritative; loading from a source is not supported."
        )
    }

    override fun getAll(keys: Collection<K>): Map<K, Set<E>> {
        val result = LinkedHashMap<K, Set<E>>()
        for (key in keys) {
            entryOf(key).value?.let { result[key] = it }
        }
        return result
    }

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

    private fun localHit(localKey: String): LocalEntry<Set<E>>? {
        val entry = local.getIfPresent(localKey) ?: return null
        if (entry.expiresAt <= clock.nowMillis()) {
            return null
        }
        countLocalHit()
        return entry
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

    private suspend fun loadThrough(storageKey: String): Loaded<Set<E>> {
        val stamp = stamps.current(storageKey)
        return loaded(storageKey, readRemote(storageKey, null, null), stamp)
    }

    /**
     * 读到 L1 之后的判定与 L2 填充。
     */
    private fun loaded(storageKey: String, read: L1Read, stamp: Long): Loaded<Set<E>> {
        val entry = when (read) {
            is L1Read.Hit -> onHit(storageKey, read)

            is L1Read.Miss, is L1Read.Leased -> {
                metrics.miss()
                localEntry(null, null, policy.localMaxAge)
            }
        }
        fillLocal(storageKey, entry, stamp)
        return Loaded(entry, stamp)
    }

    /**
     * L1 是唯一副本，没有数据源可以回源，因此格式检查与 [CoherentEngine] 不同：
     * - 更高的格式（[isNewerFormat]：更高的格式版本、`!m` 中有未知的位）读不懂，也不能当作别的值返回：
     *   抛出 [ForeignFormatException]（一个 [IllegalStateException]），且不写 L2；
     * - 不是本格式写入的内容（格式号低于本节点格式的集合、原生 Set 等其它 Redis 类型）：抛出 [CacheReadException]，不写 L2、不删除它——L1 是唯一副本，
     *   读成空集合会让调用方把“不存在”当作真实结果。集合缓存从不回收。
     */
    private fun onHit(storageKey: String, read: L1Read.Hit): LocalEntry<Set<E>> {
        if (isNewerFormat(read.format)) {
            metrics.unknownFormat()
            throw ForeignFormatException(name, storageKey, read.format, backendFormat, null)
        }
        if (read.format != backendFormat) {
            metrics.unknownFormat()
            val failure = ForeignFormatException(name, storageKey, read.format, backendFormat, null)
            log.warn { failure.message }
            throw CacheReadException(name, "read", failure)
        }
        // 先解码再计入命中：形状不符（集合 key 上是本协议的标量，通常是命名空间冲突）抛出，且不计为命中
        val value = decode(read.envelope)
        metrics.remoteHit()
        return localEntry(value, read.ttl, policy.localMaxAge)
    }

    private fun decode(envelope: Envelope): Set<E>? = when (envelope) {
        is Envelope.Members -> envelope.members.mapTo(LinkedHashSet()) { elementCodec.decode(it) }
        Envelope.Absent -> null
        is Envelope.Scalar -> throw IllegalStateException("SetCache[$name] - expected a set but found a scalar.")
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
                store.invalidate(plan.clears, clearFrame)
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
            val format = sets.addMembers(storageKey, members, ttl, frame)
            refuseForeign(storageKey, format)
        }, onFailure = throwWriteFailure("add"))
    }

    /**
     * 后端拒绝了写入（[format] 非空：key 中是读不懂的内容，未修改、未广播）：计入 `unknownFormats` 并抛出，
     * 由 [write] 包装为 `CacheWriteException`。
     */
    private fun refuseForeign(storageKey: String, format: Int?): Void? {
        if (format == null) {
            return null
        }
        metrics.unknownFormat()
        val failure = ForeignFormatException(name, storageKey, format, backendFormat, null)
        log.warn { failure.message }
        throw failure
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
            val format = sets.removeMembers(storageKey, members, ttl, frame)
            refuseForeign(storageKey, format)
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

    override fun invalidate(key: K) {
        unsupportedInvalidate()
    }

    override fun invalidateAll(keys: Collection<K>) {
        unsupportedInvalidate()
    }

    private fun unsupportedInvalidate(): Nothing =
        throw UnsupportedOperationException("SetCache[$name] - invalidate would destroy the only copy; use clear(key).")

    /**
     * 异步视图（RFC 0004）：与同步方法语义相同，L1 读失败以原始异常失败。
     */
    private inner class AsyncView : AsyncSetCache<K, E> {
        override val name: String
            get() = this@SetEngine.name

        override fun get(key: K): CompletableFuture<Set<E>?> = entryOfAsync(key) { it.value ?: emptySet() }

        override fun get(key: K, loader: AsyncCacheLoader<K, Set<E>>): CompletableFuture<Set<E>?> = CompletableFuture.failedFuture(
            UnsupportedOperationException(
                "SetCache[$name] - the remote copy is authoritative; loading from a source is not supported."
            )
        )

        /**
         * 每个未命中的 key 各自运行协程核心，并发读 L1（同步变体逐个读取），全部发出之后再按顺序组装。
         */
        override fun getAll(keys: Collection<K>): CompletableFuture<Map<K, Set<E>>> = Futures.attempt {
            val entries = LinkedHashMap<K, CompletableFuture<LocalEntry<Set<E>>>>()
            keys.forEach { key -> entries.getOrPut(key) { entryOfAsync(key) { it } } }
            context.async.runAsync {
                val result = LinkedHashMap<K, Set<E>>()
                entries.forEach { (key, entry) -> entry.awaitOriginal().value?.let { result[key] = it } }
                result
            }
        }

        override fun lookup(key: K): CompletableFuture<Lookup<Set<E>>> = entryOfAsync(key) { lookupOf(it) }

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

        override fun invalidate(key: K): CompletableFuture<Void?> = Futures.attempt { unsupportedInvalidate() }

        override fun invalidateAll(keys: Collection<K>): CompletableFuture<Void?> = Futures.attempt { unsupportedInvalidate() }

        override fun add(key: K, elements: Collection<E>): CompletableFuture<Void?> = runAsyncCommand {
            addMembers(key, elements)
        }

        override fun remove(key: K, elements: Collection<E>): CompletableFuture<Void?> = runAsyncCommand {
            removeMembers(key, elements)
        }

        override fun clear(key: K): CompletableFuture<Void?> = runAsyncCommand { clearStorageKey(storageKey(key)) }

        override fun sync(): SetCache<K, E> = this@SetEngine

        override fun toString(): String = "AsyncView(${this@SetEngine})"
    }
}
