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

import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.SuspendingStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.core.internal.runSync
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 权威副本 `Store`（RFC 0005 §4.1）：没有数据源，读取不申请 lease，每次写入带写入标识与摘要段，删除同步完成或抛出。
 * 协议全部在 [CoherentEngine] 中（[Role.STORE]），本类只把用户 API 换算过去。
 */
internal class StoreEngine<K : Any, V : Any>(
    spec: EngineSpec<K, V>,
    store: L1Store,
    context: EngineContext,
) : CoherentEngine<K, V>(spec, store, context), Store<K, V> {
    private val asyncView = AsyncView()
    private val suspendingView = SuspendingStoreView(asyncView)

    override fun get(key: K): V? = answerOf(entryOf(key))

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

    override fun delete(key: K) {
        val storageKey = storageKey(key)
        runSync { deleteStorageKeys(listOf(storageKey), DELETE) }
    }

    override fun deleteIfMatch(key: K, expected: Digest): ConditionalDelete =
        runSync { conditionalDelete(key, expected) }

    override fun getIfChanged(key: K, known: Digest?): ConditionalGet<V> = runSync { conditionalGet(key, known) }

    override fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut =
        runSync { putIfMatchWithTtl(key, expected, value, putTtl(key, value)) }

    override fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): ConditionalPut =
        runSync { putIfMatchWithTtl(key, expected, value, ttl) }

    /**
     * 运维的按 key 删除：同 [delete]，失败时抛出。
     */
    override fun invalidateEncoded(encodedKey: String) {
        val storageKey = storageKeyOf(encodedKey)
        runSync { deleteStorageKeys(listOf(storageKey), DELETE) }
    }

    override fun async(): AsyncStore<K, V> = asyncView

    override fun suspending(): SuspendingStore<K, V> = suspendingView

    private inner class AsyncView : AsyncStore<K, V> {
        private val answer: (LocalEntry<V>) -> V? = { answerOf(it) }
        private val lookupAnswer: (LocalEntry<V>) -> Lookup<V> = { lookupAnswerOf(it) }
        private val values: (Map<K, LocalEntry<V>?>) -> Map<K, V> = { valuesOf(it, IDENTITY()) }
        private val lookups: (Map<K, LocalEntry<V>?>) -> Map<K, Lookup<V>> = { lookupsOf(it) }

        override val name: String
            get() = this@StoreEngine.name

        override fun get(key: K): CompletableFuture<V?> = entryAsync(key, answer)

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

        override fun delete(key: K): CompletableFuture<Void?> = runAsyncCommand {
            deleteStorageKeys(listOf(storageKey(key)), DELETE)
        }

        override fun deleteIfMatch(key: K, expected: Digest): CompletableFuture<ConditionalDelete> =
            runAsync { conditionalDelete(key, expected) }

        override fun getIfChanged(key: K, known: Digest?): CompletableFuture<ConditionalGet<V>> =
            runAsync { conditionalGet(key, known) }

        override fun putIfMatch(key: K, expected: Digest?, value: V): CompletableFuture<ConditionalPut> =
            runAsync { putIfMatchWithTtl(key, expected, value, putTtl(key, value)) }

        override fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): CompletableFuture<ConditionalPut> =
            runAsync { putIfMatchWithTtl(key, expected, value, ttl) }

        override fun sync(): Store<K, V> = this@StoreEngine

        override fun toString(): String = "AsyncView(${this@StoreEngine})"
    }

    private companion object {
        const val DELETE = "delete"
    }
}
