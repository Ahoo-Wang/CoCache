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
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.internal.Futures
import me.ahoo.cocache.core.internal.thenNext
import me.ahoo.cocache.core.policy.ForeignFormatMode
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
    store: RemoteStore,
    context: EngineContext,
) : AbstractEngine<K, Set<E>>(spec.name, spec.namespace, spec.keyPrefix, spec.keyEncoder, spec.policy, store, context),
    SetCache<K, E> {

    private val elementCodec = spec.elementCodec
    private val backendFormat = context.backend.format
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

    private fun entryOf(key: K): LocalEntry<Set<E>> {
        val storageKey = storageKey(key)
        val entry = local.getIfPresent(storageKey)
        if (entry != null && entry.expiresAt > clock.nowMillis()) {
            countLocalHit()
            return entry
        }
        val observed = stamps.current(storageKey)
        val loaded = flights.execute(storageKey) { loadThrough(storageKey) }
        if (loaded.stamp >= observed) {
            return loaded.entry
        }
        return flights.execute(storageKey) { loadThrough(storageKey) }.entry
    }

    private fun loadThrough(storageKey: String): Loaded<Set<E>> {
        val stamp = stamps.current(storageKey)
        return loaded(storageKey, store.read(storageKey, null, null), stamp)
    }

    /**
     * 同步与异步共用：读到 L1 之后的判定与 L2 填充。
     */
    private fun loaded(storageKey: String, read: RemoteRead, stamp: Long): Loaded<Set<E>> {
        val entry = when (read) {
            is RemoteRead.Hit -> onHit(storageKey, read)

            is RemoteRead.Miss, is RemoteRead.Leased -> {
                metrics.miss()
                localEntry(null, null, policy.localMaxAge)
            }
        }
        fillLocal(storageKey, entry, stamp)
        return Loaded(entry, stamp)
    }

    /**
     * [entryOf] 的异步版本：读己之写重试相同；L1 读失败以原始异常失败（没有可降级的数据源）。
     */
    private fun entryOfAsync(key: K): CompletableFuture<LocalEntry<Set<E>>> {
        val storageKey = storageKey(key)
        val entry = local.getIfPresent(storageKey)
        if (entry != null && entry.expiresAt > clock.nowMillis()) {
            countLocalHit()
            return CompletableFuture.completedFuture(entry)
        }
        val observed = stamps.current(storageKey)
        return flights.executeAsync(
            storageKey,
            executor
        ) { loadThroughAsync(storageKey) }.thenNext(executor) { loaded ->
            if (loaded.stamp >= observed) {
                CompletableFuture.completedFuture(loaded.entry)
            } else {
                flights.executeAsync(storageKey, executor) { loadThroughAsync(storageKey) }.thenNext(executor) {
                    CompletableFuture.completedFuture(it.entry)
                }
            }
        }
    }

    private fun loadThroughAsync(storageKey: String): CompletableFuture<Loaded<Set<E>>> {
        val stamp = stamps.current(storageKey)
        return Futures.attempt { asyncStore.readAsync(storageKey, null, null) }.thenNext(executor) { read ->
            CompletableFuture.completedFuture(loaded(storageKey, read, stamp))
        }
    }

    /**
     * L1 是唯一副本，没有数据源可以回源，因此格式检查与 [CoherentEngine] 不同：
     * - 更高版本写入的集合读不懂，也不能当作别的值返回：抛出，且不写 L2；
     * - 不是本协议写入的内容（如 5.x 留下的原生 Set）：按 [ForeignFormatMode] 处理（没有数据源，默认 `FAIL`）。
     *   `FAIL` 抛出 [CacheReadException]，不写 L2——L1 是唯一副本，读成空集合会让调用方把“不存在”当作真实结果；
     *   `RECLAIM` 按不存在处理、以降级限龄进入 L2，读路径不删除它，下一次 `put` / `add` 会整体覆盖。
     */
    private fun onHit(storageKey: String, read: RemoteRead.Hit): LocalEntry<Set<E>> {
        if (read.format > backendFormat) {
            metrics.unknownFormat()
            throw IllegalStateException(
                "SetCache[$name] - key[$storageKey] was written in format[${read.format}], newer than this node's " +
                    "format[$backendFormat]; upgrade this node."
            )
        }
        if (read.format < MIN_FORMAT) {
            metrics.unknownFormat()
            if ((policy.foreignFormat ?: ForeignFormatMode.FAIL) == ForeignFormatMode.FAIL) {
                val failure = ForeignFormatException(name, storageKey, read.format, null)
                log.warn { failure.message }
                throw CacheReadException(name, "read", failure)
            }
            log.warn {
                "SetCache[$name] - key[$storageKey] - unrecognized remote content, treated as absent until it is rewritten."
            }
            return localEntry(null, null, policy.degradedLocalMaxAge)
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
        putWithTtl(key, value, policy.valueTtl())
    }

    override fun put(key: K, value: Set<E>, ttl: Duration) {
        putWithTtl(key, value, ttl)
    }

    private fun putWithTtl(key: K, value: Set<E>, ttl: Duration?) {
        if (value.isEmpty()) {
            clear(key)
            return
        }
        val storageKey = storageKey(key)
        val envelope = Envelope.Members(encode(value))
        val bounded = ttl?.boundedTtl()
        write(
            listOf(storageKey),
            { frame -> store.put(storageKey, envelope, bounded, frame) },
            throwWriteFailure("put")
        )
    }

    override fun putAll(entries: Map<K, Set<E>>) {
        putAllWithTtl(entries, policy.valueTtl())
    }

    override fun putAll(entries: Map<K, Set<E>>, ttl: Duration) {
        putAllWithTtl(entries, ttl)
    }

    /**
     * 一次 [write] 覆盖全部存储 key，语义同逐个 [put]：非空集合整体覆盖（批量），空集合等价于 [clear]（批量删除）。
     * 编码后碰撞的存储 key 以后出现的为准。两类都有时各自只广播自己的 key。
     */
    private fun putAllWithTtl(entries: Map<K, Set<E>>, ttl: Duration?) {
        if (entries.isEmpty()) {
            return
        }
        val plan = putAllPlan(entries, ttl)
        write(plan.keys, { frame ->
            if (plan.puts.isNotEmpty()) {
                store.putAll(plan.puts, plan.putFrame(frame))
            }
            if (plan.clears.isNotEmpty()) {
                store.invalidate(plan.clears, plan.clearFrame(frame))
            }
        }, throwWriteFailure("putAll"))
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
        if (elements.isEmpty()) {
            return
        }
        val storageKey = storageKey(key)
        val members = encode(elements)
        write(
            listOf(storageKey),
            { frame -> store.addMembers(storageKey, members, policy.valueTtl(), frame) },
            throwWriteFailure("add")
        )
    }

    override fun remove(key: K, elements: Collection<E>) {
        if (elements.isEmpty()) {
            return
        }
        val storageKey = storageKey(key)
        val members = encode(elements)
        write(
            listOf(storageKey),
            { frame -> store.removeMembers(storageKey, members, policy.valueTtl(), frame) },
            throwWriteFailure("remove")
        )
    }

    override fun clear(key: K) {
        val storageKey = storageKey(key)
        write(listOf(storageKey), { frame -> store.invalidate(listOf(storageKey), frame) }, throwWriteFailure("clear"))
    }

    override fun describe(envelope: Envelope): Any? = decode(envelope)

    /**
     * 运维的强制失效：L1 是唯一副本，等价于 [clear]。
     */
    override fun invalidateEncoded(encodedKey: String) {
        val storageKey = storageKeyOf(encodedKey)
        write(listOf(storageKey), { frame -> store.invalidate(listOf(storageKey), frame) }, throwWriteFailure("clear"))
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

        override fun get(key: K): CompletableFuture<Set<E>?> = toUser {
            entryOfAsync(
                key
            ).map { it.value ?: emptySet() }
        }

        override fun get(key: K, loader: AsyncCacheLoader<K, Set<E>>): CompletableFuture<Set<E>?> = CompletableFuture.failedFuture(
            UnsupportedOperationException(
                "SetCache[$name] - the remote copy is authoritative; loading from a source is not supported."
            )
        )

        override fun getAll(keys: Collection<K>): CompletableFuture<Map<K, Set<E>>> = toUser {
            val futures = LinkedHashMap<K, CompletableFuture<LocalEntry<Set<E>>>>()
            keys.forEach { key -> futures.getOrPut(key) { entryOfAsync(key) } }
            CompletableFuture.allOf(*futures.values.toTypedArray()).thenNext(executor) {
                val result = LinkedHashMap<K, Set<E>>()
                futures.forEach { (key, future) -> future.join().value?.let { result[key] = it } }
                CompletableFuture.completedFuture(result)
            }
        }

        override fun lookup(key: K): CompletableFuture<Lookup<Set<E>>> = toUser {
            entryOfAsync(
                key
            ).map { lookupOf(it) }
        }

        override fun put(key: K, value: Set<E>): CompletableFuture<Void?> = toUser {
            putAsync(
                key,
                value,
                policy.valueTtl()
            )
        }

        override fun put(key: K, value: Set<E>, ttl: Duration): CompletableFuture<Void?> = toUser {
            putAsync(
                key,
                value,
                ttl
            )
        }

        override fun putAll(entries: Map<K, Set<E>>): CompletableFuture<Void?> = toUser {
            putAllAsync(entries, policy.valueTtl())
        }

        override fun putAll(entries: Map<K, Set<E>>, ttl: Duration): CompletableFuture<Void?> = toUser {
            putAllAsync(entries, ttl)
        }

        override fun invalidate(key: K): CompletableFuture<Void?> = toUser { unsupportedInvalidate() }

        override fun invalidateAll(keys: Collection<K>): CompletableFuture<Void?> = toUser { unsupportedInvalidate() }

        override fun add(key: K, elements: Collection<E>): CompletableFuture<Void?> = toUser {
            if (elements.isEmpty()) {
                return@toUser Futures.done()
            }
            val storageKey = storageKey(key)
            val members = encode(elements)
            writeAsync(
                listOf(storageKey),
                { frame -> asyncStore.addMembersAsync(storageKey, members, policy.valueTtl(), frame) },
                throwWriteFailure("add")
            )
        }

        override fun remove(key: K, elements: Collection<E>): CompletableFuture<Void?> = toUser {
            if (elements.isEmpty()) {
                return@toUser Futures.done()
            }
            val storageKey = storageKey(key)
            val members = encode(elements)
            writeAsync(
                listOf(storageKey),
                { frame -> asyncStore.removeMembersAsync(storageKey, members, policy.valueTtl(), frame) },
                throwWriteFailure("remove")
            )
        }

        override fun clear(key: K): CompletableFuture<Void?> = toUser { clearAsync(storageKey(key)) }

        override fun sync(): SetCache<K, E> = this@SetEngine

        override fun toString(): String = "AsyncView(${this@SetEngine})"

        private fun putAsync(key: K, value: Set<E>, ttl: Duration?): CompletableFuture<Void?> {
            val storageKey = storageKey(key)
            if (value.isEmpty()) {
                return clearAsync(storageKey)
            }
            val envelope = Envelope.Members(encode(value))
            val bounded = ttl?.boundedTtl()
            return writeAsync(
                listOf(storageKey),
                { frame -> asyncStore.putAsync(storageKey, envelope, bounded, frame) },
                throwWriteFailure("put")
            )
        }

        private fun putAllAsync(entries: Map<K, Set<E>>, ttl: Duration?): CompletableFuture<Void?> {
            if (entries.isEmpty()) {
                return Futures.done()
            }
            val plan = putAllPlan(entries, ttl)
            return writeAsync(plan.keys, { frame ->
                val written = if (plan.puts.isEmpty()) {
                    Futures.done()
                } else {
                    asyncStore.putAllAsync(
                        plan.puts,
                        plan.putFrame(frame)
                    )
                }
                written.thenNext(executor) {
                    if (plan.clears.isEmpty()) {
                        Futures.done()
                    } else {
                        asyncStore.invalidateAsync(
                            plan.clears,
                            plan.clearFrame(frame)
                        )
                    }
                }
            }, throwWriteFailure("putAll"))
        }

        private fun clearAsync(storageKey: String): CompletableFuture<Void?> = writeAsync(
            listOf(storageKey),
            { frame -> asyncStore.invalidateAsync(listOf(storageKey), frame) },
            throwWriteFailure("clear")
        )

        private fun <T, R> CompletableFuture<T>.map(fn: (T) -> R): CompletableFuture<R> =
            thenNext(executor) { CompletableFuture.completedFuture(fn(it)) }

        private fun <T> toUser(block: () -> CompletableFuture<T>): CompletableFuture<T> = Futures.relay(
            Futures.attempt(block)
        )
    }
}
