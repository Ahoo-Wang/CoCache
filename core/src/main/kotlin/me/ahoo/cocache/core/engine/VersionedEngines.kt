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

import me.ahoo.cocache.api.AsyncVersionedCache
import me.ahoo.cocache.api.AsyncVersionedStore
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SuspendingVersionedCache
import me.ahoo.cocache.api.SuspendingVersionedStore
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.internal.LoadingKeys
import me.ahoo.cocache.core.internal.runSync
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.coroutineContext

/**
 * 带版本的类型共用的部分：引擎的值是 `Versioned<V?>`，读写路径（栅栏、合并回源、批量、异步）全部沿用 [CoherentEngine]，
 * 版本随值进入 L1（[VersionedFormat]）与 L2 副本。子类只在用户的 `V` 与引擎的 `Versioned<V?>` 之间转换，不包含协议逻辑。
 *
 * `value` 为 `null` 的 `Versioned` 是墓碑（带版本的数据源给出的“在这个版本时已删除”，或按版本的删除）：对用户就是不存在。
 * 命中路径与引擎本身是同一个对象，只多一次字段读取。
 */
internal abstract class VersionedEngine<K : Any, V : Any>(
    spec: EngineSpec<K, Versioned<V?>>,
    store: L1Store,
    context: EngineContext,
) : CoherentEngine<K, Versioned<V?>>(spec, store, context) {
    /**
     * 按版本的写入与保留版本的失效（[VersionedWrites]）：只有带版本的类型有。
     */
    internal val writes = VersionedWrites(this)
    internal val unwrap: (Versioned<V?>) -> V? = { it.value }
    internal val lookupAnswer: (LocalEntry<Versioned<V?>>) -> Lookup<V> = { lookupOf(lookupAnswerOf(it)) }
    internal val values: (Map<K, LocalEntry<Versioned<V?>>?>) -> Map<K, V> = { valuesOf(it, unwrap) }
    internal val lookups: (Map<K, LocalEntry<Versioned<V?>>?>) -> Map<K, Lookup<V>> = { lookupsOfValues(it) }
    internal val answer: (LocalEntry<Versioned<V?>>) -> V? = { answerOf(it)?.value }
    internal val versionedAnswer: (LocalEntry<Versioned<V?>>) -> Versioned<V>? = { presentOf(answerOf(it)) }

    internal fun valueOf(key: K): V? = answerOf(entryOf(key))?.value

    internal fun versionedOf(key: K): Versioned<V>? = presentOf(answerOf(entryOf(key)))

    internal fun valuesOf(keys: Collection<K>): Map<K, V> = valuesOf(resolveAll(keys), unwrap)

    internal fun lookupValueOf(key: K): Lookup<V> = lookupOf(lookupAnswerOf(entryOf(key)))

    internal fun lookupValuesOf(keys: Collection<K>): Map<K, Lookup<V>> = lookupsOfValues(resolveAll(keys))

    private fun lookupsOfValues(resolved: Map<K, LocalEntry<Versioned<V?>>?>): Map<K, Lookup<V>> =
        lookupsOf(resolved).mapValues { lookupOf(it.value) }

    /**
     * 墓碑展示为不存在：冷加载的 `Versioned(null, v)` 以非 `null` 的值留在 L2，查看时与读取的结果（`Absent`）一致。
     * 墓碑的版本不展示（`KeyInspection.LocalCopy` 没有版本）。
     */
    override fun shownValueOf(value: Versioned<V?>): Any? = presentOf(value)

    internal suspend fun writeIfNewer(key: K, value: V, version: Long, ttl: Duration?, operation: String): VersionedPut {
        val versioned = Versioned<V?>(value, version)
        val lifetime = ttl?.let(ttls::perCall) ?: ttls.versionedTtl(key, versioned)
        return writes.write(key, versioned, version, lifetime, operation)
    }

    internal suspend fun deleteIfNewer(key: K, version: Long, ttl: Duration?, operation: String): VersionedPut =
        writes.write(key, null, version, ttl?.let(ttls::perCall) ?: ttls.versionedTtl(key, null), operation)

    internal suspend fun purgeKey(key: K) {
        deleteStorageKeys(listOf(storageKey(key)), PURGE)
    }

    internal companion object {
        const val PURGE = "purge"

        /**
         * 有值时就是同一个对象（`value` 不为 `null` 的 `Versioned<V?>` 即 `Versioned<V>`），不分配；墓碑为 `null`。
         */
        @Suppress("UNCHECKED_CAST")
        fun <V : Any> presentOf(versioned: Versioned<V?>?): Versioned<V>? =
            versioned?.takeIf { it.value != null } as Versioned<V>?

        fun <V : Any> lookupOf(lookup: Lookup<Versioned<V?>>): Lookup<V> = when (lookup) {
            is Lookup.Present -> lookup.value.value?.let { Lookup.Present(it, lookup.expiresAt) } ?: Lookup.Absent(lookup.expiresAt)
            is Lookup.Absent -> lookup
            is Lookup.Unknown -> lookup
        }
    }
}

/**
 * 派生的带版本副本 `VersionedCache`（RFC 0005 §4.1、§4.2）：带版本的数据源，按版本有序的写入，保留版本栅栏的 [evict]。
 */
internal class VersionedCacheEngine<K : Any, V : Any>(
    spec: EngineSpec<K, Versioned<V?>>,
    store: L1Store,
    context: EngineContext,
) : VersionedEngine<K, V>(spec, store, context), VersionedCache<K, V> {
    private val asyncView = AsyncView()
    private val suspendingView = SuspendingView()

    override fun get(key: K): V? = valueOf(key)

    override fun getAll(keys: Collection<K>): Map<K, V> = valuesOf(keys)

    override fun lookup(key: K): Lookup<V> = lookupValueOf(key)

    override fun lookupAll(keys: Collection<K>): Map<K, Lookup<V>> = lookupValuesOf(keys)

    override fun getVersioned(key: K): Versioned<V>? = versionedOf(key)

    override fun putIfNewer(key: K, value: V, version: Long): VersionedPut =
        runSync { writeIfNewer(key, value, version, null, PUT_IF_NEWER) }

    override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): VersionedPut =
        runSync { writeIfNewer(key, value, version, ttl, PUT_IF_NEWER) }

    override fun invalidateIfNewer(key: K, version: Long): VersionedPut =
        runSync { deleteIfNewer(key, version, null, INVALIDATE_IF_NEWER) }

    override fun invalidateIfNewer(key: K, version: Long, ttl: Duration): VersionedPut =
        runSync { deleteIfNewer(key, version, ttl, INVALIDATE_IF_NEWER) }

    override fun evict(key: K) {
        val storageKey = storageKey(key)
        runSync { writes.evictKeepingVersion(storageKey) }
    }

    override fun purge(key: K) {
        runSync { purgeKey(key) }
    }

    /**
     * 运维的按 key 删除：保留版本（[evict]），不进入失效重试（重试的 `DEL` 会丢掉版本），失败时抛出。
     */
    override fun invalidateEncoded(encodedKey: String) {
        val storageKey = storageKeyOf(encodedKey)
        runSync { writes.evictKeepingVersion(storageKey) }
    }

    override val keyDeletion: CacheAdmin.KeyDeletion
        get() = CacheAdmin.KeyDeletion.EVICT

    /**
     * 事务内记录的填充（[me.ahoo.cocache.core.FillKey.invalidateAll]）：逐个 key 保留版本地淘汰（同 [evict]），不是无条件删除。
     * 编码 key 来自业务 key 的编码，可以是空串，因此不经运维入口的输入校验（[invalidateEncoded]）。一个 key 失败不影响其余 key：
     * 全部执行之后抛出第一个异常，其余作为它的 suppressed。
     */
    @Suppress("TooGenericExceptionCaught")
    override fun invalidateEncodedKeys(encodedKeys: List<String>) {
        var failure: Exception? = null
        for (encodedKey in encodedKeys) {
            try {
                runSync { writes.evictKeepingVersion(storageKeyOfLocal(encodedKey)) }
            } catch (e: Exception) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    override fun purgeEncoded(encodedKey: String) {
        val storageKey = storageKeyOf(encodedKey)
        runSync { deleteStorageKeys(listOf(storageKey), PURGE) }
    }

    override fun async(): AsyncVersionedCache<K, V> = asyncView

    override fun suspending(): SuspendingVersionedCache<K, V> = suspendingView

    override fun toString(): String = "VersionedCache(${super.toString()})"

    private inner class AsyncView : AsyncVersionedCache<K, V> {
        override val name: String
            get() = this@VersionedCacheEngine.name

        override fun get(key: K): CompletableFuture<V?> = entryAsync(key, answer)

        override fun getAll(keys: Collection<K>): CompletableFuture<Map<K, V>> = resolveAllAsync(keys, values)

        override fun lookup(key: K): CompletableFuture<Lookup<V>> = entryAsync(key, lookupAnswer)

        override fun lookupAll(keys: Collection<K>): CompletableFuture<Map<K, Lookup<V>>> =
            resolveAllAsync(keys, lookups)

        override fun getVersioned(key: K): CompletableFuture<Versioned<V>?> = entryAsync(key, versionedAnswer)

        override fun putIfNewer(key: K, value: V, version: Long): CompletableFuture<VersionedPut> =
            runAsync { writeIfNewer(key, value, version, null, PUT_IF_NEWER) }

        override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): CompletableFuture<VersionedPut> =
            runAsync { writeIfNewer(key, value, version, ttl, PUT_IF_NEWER) }

        override fun invalidateIfNewer(key: K, version: Long): CompletableFuture<VersionedPut> =
            runAsync { deleteIfNewer(key, version, null, INVALIDATE_IF_NEWER) }

        override fun invalidateIfNewer(key: K, version: Long, ttl: Duration): CompletableFuture<VersionedPut> =
            runAsync { deleteIfNewer(key, version, ttl, INVALIDATE_IF_NEWER) }

        override fun evict(key: K): CompletableFuture<Void?> = runAsyncCommand {
            writes.evictKeepingVersion(
                storageKey(key)
            )
        }

        override fun purge(key: K): CompletableFuture<Void?> = runAsyncCommand { purgeKey(key) }

        override fun sync(): VersionedCache<K, V> = this@VersionedCacheEngine

        override fun toString(): String = "AsyncView(${this@VersionedCacheEngine})"
    }

    /**
     * 挂起视图：直接调用协程核心（[SuspendingReads]，RFC 0005 §4.4）。
     */
    private inner class SuspendingView :
        SuspendingReads<K, V, Versioned<V?>>(this@VersionedCacheEngine, answer, lookupAnswer, values, lookups),
        SuspendingVersionedCache<K, V> {
        override suspend fun getVersioned(key: K): Versioned<V>? {
            LoadingKeys.check(coroutineContext, this@VersionedCacheEngine, name, key)
            return entrySuspending(key, versionedAnswer)
        }

        override suspend fun putIfNewer(key: K, value: V, version: Long): VersionedPut =
            runSuspending { writeIfNewer(key, value, version, null, PUT_IF_NEWER) }

        override suspend fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): VersionedPut =
            runSuspending { writeIfNewer(key, value, version, ttl, PUT_IF_NEWER) }

        override suspend fun invalidateIfNewer(key: K, version: Long): VersionedPut =
            runSuspending { deleteIfNewer(key, version, null, INVALIDATE_IF_NEWER) }

        override suspend fun invalidateIfNewer(key: K, version: Long, ttl: Duration): VersionedPut =
            runSuspending { deleteIfNewer(key, version, ttl, INVALIDATE_IF_NEWER) }

        override suspend fun evict(key: K) = runSuspending { writes.evictKeepingVersion(storageKey(key)) }

        override suspend fun purge(key: K) = runSuspending { purgeKey(key) }

        override fun async(): AsyncVersionedCache<K, V> = asyncView
    }

    private companion object {
        const val PUT_IF_NEWER = "putIfNewer"
        const val INVALIDATE_IF_NEWER = "invalidateIfNewer"
    }
}

/**
 * 权威的带版本副本 `VersionedStore`（RFC 0005 §4.1、§4.2）：没有数据源，读取规则同 `Store`，只有按版本的写入。
 */
internal class VersionedStoreEngine<K : Any, V : Any>(
    spec: EngineSpec<K, Versioned<V?>>,
    store: L1Store,
    context: EngineContext,
) : VersionedEngine<K, V>(spec, store, context), VersionedStore<K, V> {
    private val asyncView = AsyncView()
    private val suspendingView = SuspendingView()

    override fun get(key: K): V? = valueOf(key)

    override fun getAll(keys: Collection<K>): Map<K, V> = valuesOf(keys)

    override fun lookup(key: K): Lookup<V> = lookupValueOf(key)

    override fun lookupAll(keys: Collection<K>): Map<K, Lookup<V>> = lookupValuesOf(keys)

    override fun getVersioned(key: K): Versioned<V>? = versionedOf(key)

    override fun putIfNewer(key: K, value: V, version: Long): VersionedPut =
        runSync { writeIfNewer(key, value, version, null, PUT_IF_NEWER) }

    override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): VersionedPut =
        runSync { writeIfNewer(key, value, version, ttl, PUT_IF_NEWER) }

    override fun deleteIfNewer(key: K, version: Long): VersionedPut =
        runSync { deleteIfNewer(key, version, null, DELETE_IF_NEWER) }

    override fun deleteIfNewer(key: K, version: Long, ttl: Duration): VersionedPut =
        runSync { deleteIfNewer(key, version, ttl, DELETE_IF_NEWER) }

    override fun purge(key: K) {
        runSync { purgeKey(key) }
    }

    /**
     * 运维的按 key 删除：同 [purge]（没有版本可给出），失败时抛出。
     */
    override fun invalidateEncoded(encodedKey: String) {
        val storageKey = storageKeyOf(encodedKey)
        runSync { deleteStorageKeys(listOf(storageKey), PURGE) }
    }

    override val keyDeletion: CacheAdmin.KeyDeletion
        get() = CacheAdmin.KeyDeletion.PURGE

    override fun purgeEncoded(encodedKey: String) = invalidateEncoded(encodedKey)

    override fun async(): AsyncVersionedStore<K, V> = asyncView

    override fun suspending(): SuspendingVersionedStore<K, V> = suspendingView

    override fun toString(): String = "VersionedStore(${super.toString()})"

    private inner class AsyncView : AsyncVersionedStore<K, V> {
        override val name: String
            get() = this@VersionedStoreEngine.name

        override fun get(key: K): CompletableFuture<V?> = entryAsync(key, answer)

        override fun getAll(keys: Collection<K>): CompletableFuture<Map<K, V>> = resolveAllAsync(keys, values)

        override fun lookup(key: K): CompletableFuture<Lookup<V>> = entryAsync(key, lookupAnswer)

        override fun lookupAll(keys: Collection<K>): CompletableFuture<Map<K, Lookup<V>>> =
            resolveAllAsync(keys, lookups)

        override fun getVersioned(key: K): CompletableFuture<Versioned<V>?> = entryAsync(key, versionedAnswer)

        override fun putIfNewer(key: K, value: V, version: Long): CompletableFuture<VersionedPut> =
            runAsync { writeIfNewer(key, value, version, null, PUT_IF_NEWER) }

        override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): CompletableFuture<VersionedPut> =
            runAsync { writeIfNewer(key, value, version, ttl, PUT_IF_NEWER) }

        override fun deleteIfNewer(key: K, version: Long): CompletableFuture<VersionedPut> =
            runAsync { deleteIfNewer(key, version, null, DELETE_IF_NEWER) }

        override fun deleteIfNewer(key: K, version: Long, ttl: Duration): CompletableFuture<VersionedPut> =
            runAsync { deleteIfNewer(key, version, ttl, DELETE_IF_NEWER) }

        override fun purge(key: K): CompletableFuture<Void?> = runAsyncCommand { purgeKey(key) }

        override fun sync(): VersionedStore<K, V> = this@VersionedStoreEngine

        override fun toString(): String = "AsyncView(${this@VersionedStoreEngine})"
    }

    /**
     * 挂起视图：直接调用协程核心（[SuspendingReads]，RFC 0005 §4.4）。
     */
    private inner class SuspendingView :
        SuspendingReads<K, V, Versioned<V?>>(this@VersionedStoreEngine, answer, lookupAnswer, values, lookups),
        SuspendingVersionedStore<K, V> {
        override suspend fun getVersioned(key: K): Versioned<V>? {
            LoadingKeys.check(coroutineContext, this@VersionedStoreEngine, name, key)
            return entrySuspending(key, versionedAnswer)
        }

        override suspend fun putIfNewer(key: K, value: V, version: Long): VersionedPut =
            runSuspending { writeIfNewer(key, value, version, null, PUT_IF_NEWER) }

        override suspend fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): VersionedPut =
            runSuspending { writeIfNewer(key, value, version, ttl, PUT_IF_NEWER) }

        override suspend fun deleteIfNewer(key: K, version: Long): VersionedPut =
            runSuspending { deleteIfNewer(key, version, null, DELETE_IF_NEWER) }

        override suspend fun deleteIfNewer(key: K, version: Long, ttl: Duration): VersionedPut =
            runSuspending { deleteIfNewer(key, version, ttl, DELETE_IF_NEWER) }

        override suspend fun purge(key: K) = runSuspending { purgeKey(key) }

        override fun async(): AsyncVersionedStore<K, V> = asyncView
    }

    private companion object {
        const val PUT_IF_NEWER = "putIfNewer"
        const val DELETE_IF_NEWER = "deleteIfNewer"
    }
}
