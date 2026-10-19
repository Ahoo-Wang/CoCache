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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration

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
        val entry = when (val read = store.read(storageKey, null, null)) {
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
     * L1 是唯一副本，没有数据源可以回源，因此格式检查与 [CoherentEngine] 不同：
     * - 更高版本写入的集合读不懂，也不能当作别的值返回：抛出，且不写 L2；
     * - 不是本协议写入的内容（如旧版本留下的原生 Set）不是本缓存的数据：按不存在处理、以降级限龄进入 L2，
     *   读路径不删除它，下一次 `put` / `add` 会整体覆盖。
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
        throw UnsupportedOperationException("SetCache[$name] - invalidate would destroy the only copy; use clear(key).")
    }

    override fun invalidateAll(keys: Collection<K>) {
        throw UnsupportedOperationException("SetCache[$name] - invalidate would destroy the only copy; use clear(key).")
    }
}
