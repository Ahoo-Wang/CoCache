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
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SuspendingCache
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.core.internal.runSync
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 派生副本 `Cache`（RFC 0005 §4.1）：有数据源，或外部来源。协议全部在 [CoherentEngine] 中，本类只把用户 API 换算过去：
 * 命中路径与引擎本身是同一个对象，不多一次委托。
 */
internal class CacheEngine<K : Any, V : Any>(
    spec: EngineSpec<K, V>,
    store: L1Store,
    context: EngineContext,
) : CoherentEngine<K, V>(spec, store, context), Cache<K, V> {
    private val asyncView = AsyncView()
    private val suspendingView = SuspendingCacheView(asyncView)

    override fun get(key: K): V? = answerOf(entryOf(key))

    override fun get(key: K, loader: CacheLoader<K, V>): V? = answerWithLoader(entryWith(key, loader))

    override fun getAll(keys: Collection<K>): Map<K, V> = valuesOf(resolveAll(keys), IDENTITY())

    override fun lookup(key: K): Lookup<V> = lookupAnswerOf(entryOf(key))

    override fun lookupAll(keys: Collection<K>): Map<K, Lookup<V>> = lookupsOf(resolveAll(keys))

    override fun put(key: K, value: V) {
        runSync { putWithTtl(key, value, putTtl(key, value)) }
    }

    override fun put(key: K, value: V, ttl: Duration) {
        runSync { putWithTtl(key, value, ttl) }
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

    override fun invalidate(key: K) {
        val storageKey = storageKey(key)
        runSync { invalidateStorageKeys(listOf(storageKey)) }
    }

    override fun invalidateAll(keys: Collection<K>) {
        val storageKeys = storageKeysOf(keys)
        if (storageKeys.isNotEmpty()) {
            runSync { invalidateStorageKeys(storageKeys) }
        }
    }

    /**
     * 运维的按 key 删除：派生副本照常失效（失败时按 `invalidateFailure`）。
     */
    override fun invalidateEncoded(encodedKey: String) {
        val storageKey = storageKeyOf(encodedKey)
        runSync { invalidateStorageKeys(listOf(storageKey)) }
    }

    /**
     * 事务内记录的填充（[me.ahoo.cocache.core.FillKey.invalidateAll]）：一次失效写入。
     */
    override fun invalidateEncodedKeys(encodedKeys: List<String>) {
        val storageKeys = encodedKeys.map { storageKeyOf(it) }
        if (storageKeys.isNotEmpty()) {
            runSync { invalidateStorageKeys(storageKeys) }
        }
    }

    override fun async(): AsyncCache<K, V> = asyncView

    override fun suspending(): SuspendingCache<K, V> = suspendingView

    private inner class AsyncView : AsyncCache<K, V> {
        private val answer: (LocalEntry<V>) -> V? = { answerOf(it) }
        private val loaderAnswer: (LocalEntry<V>) -> V? = { answerWithLoader(it) }
        private val lookupAnswer: (LocalEntry<V>) -> Lookup<V> = { lookupAnswerOf(it) }
        private val values: (Map<K, LocalEntry<V>?>) -> Map<K, V> = { valuesOf(it, IDENTITY()) }
        private val lookups: (Map<K, LocalEntry<V>?>) -> Map<K, Lookup<V>> = { lookupsOf(it) }

        override val name: String
            get() = this@CacheEngine.name

        override fun get(key: K): CompletableFuture<V?> = entryAsync(key, answer)

        override fun get(key: K, loader: AsyncCacheLoader<K, V>): CompletableFuture<V?> =
            entryWithAsync(key, loader, loaderAnswer)

        override fun getAll(keys: Collection<K>): CompletableFuture<Map<K, V>> = resolveAllAsync(keys, values)

        override fun lookup(key: K): CompletableFuture<Lookup<V>> = entryAsync(key, lookupAnswer)

        override fun lookupAll(keys: Collection<K>): CompletableFuture<Map<K, Lookup<V>>> =
            resolveAllAsync(keys, lookups)

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
            invalidateStorageKeys(storageKeysOf(keys))
        }

        override fun sync(): Cache<K, V> = this@CacheEngine

        override fun toString(): String = "AsyncView(${this@CacheEngine})"
    }
}

/**
 * 不换算的值（`Cache` 与 `Store` 的引擎值就是用户的值）。
 */
@Suppress("UNCHECKED_CAST", "FunctionName")
internal fun <V : Any> IDENTITY(): (V) -> V? = Identity as (V) -> V?

private object Identity : (Any) -> Any? {
    override fun invoke(value: Any): Any = value
}
