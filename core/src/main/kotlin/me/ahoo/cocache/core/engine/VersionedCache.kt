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
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.internal.Futures
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * 带版本的缓存（6.8，按版本有序的写入）：引擎的值是 `Versioned<V?>`，读写路径（栅栏、合并回源、批量、异步）全部沿用 [CoherentEngine]，
 * 版本随值进入 L1（[VersionedFormat]）与 L2 副本。本类只在用户的 `V` 与引擎的 `Versioned<V?>` 之间转换，不包含协议逻辑：
 * 普通写入（`put`、`putIfMatch`）写入没有版本的值，按版本的写入经 [CoherentEngine.writeIfNewer]。
 *
 * `value` 为 `null` 的 `Versioned` 只来自带版本的数据源（“在这个版本时不存在”，回源写入墓碑）：对用户就是不存在。
 *
 * 不是代理：异常原样传递。命中路径比普通缓存多一次委托与一次字段读取，普通缓存不受影响。
 */
internal class VersionedCache<K : Any, V : Any>(
    private val engine: CoherentEngine<K, Versioned<V?>>,
) : Cache<K, V> {
    private val view = AsyncView()

    override val name: String
        get() = engine.name

    override fun get(key: K): V? = engine.get(key)?.value

    override fun get(key: K, loader: CacheLoader<K, V>): V? = engine.get(key, UnversionedLoader(loader))?.value

    override fun getAll(keys: Collection<K>): Map<K, V> = valuesOf(engine.getAll(keys))

    override fun lookup(key: K): Lookup<V> = lookupOf(engine.lookup(key))

    override fun put(key: K, value: V) = engine.put(key, unversioned(value))

    override fun put(key: K, value: V, ttl: Duration) = engine.put(key, unversioned(value), ttl)

    override fun putAll(entries: Map<K, V>) = engine.putAll(unversioned(entries))

    override fun putAll(entries: Map<K, V>, ttl: Duration) = engine.putAll(unversioned(entries), ttl)

    override fun invalidate(key: K) = engine.invalidate(key)

    override fun invalidateAll(keys: Collection<K>) = engine.invalidateAll(keys)

    override fun getIfChanged(key: K, known: Digest?): ConditionalGet<V> = conditionalGetOf(
        engine.getIfChanged(key, known)
    )

    override fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut =
        engine.putIfMatch(key, expected, unversioned(value))

    override fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): ConditionalPut =
        engine.putIfMatch(key, expected, unversioned(value), ttl)

    override fun putIfNewer(key: K, value: V, version: Long): VersionedPut {
        val versioned = Versioned<V?>(value, version)
        return engine.writeIfNewer(key, versioned, version, engine.versionedTtl(key, versioned))
    }

    override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): VersionedPut =
        engine.writeIfNewer(key, Versioned(value, version), version, ttl)

    override fun invalidateIfNewer(key: K, version: Long): VersionedPut =
        engine.writeIfNewer(key, null, version, engine.versionedTtl(key, null))

    override fun invalidateIfNewer(key: K, version: Long, ttl: Duration): VersionedPut =
        engine.writeIfNewer(key, null, version, ttl)

    override fun getVersioned(key: K): Versioned<V>? = presentOf(engine.get(key))

    override fun async(): AsyncCache<K, V> = view

    override fun toString(): String = "VersionedCache($engine)"

    private inner class AsyncView : AsyncCache<K, V> {
        private val async: AsyncCache<K, Versioned<V?>> = engine.async()

        override val name: String
            get() = engine.name

        override fun get(key: K): CompletableFuture<V?> = mapped(async.get(key)) { it?.value }

        override fun get(key: K, loader: AsyncCacheLoader<K, V>): CompletableFuture<V?> =
            mapped(async.get(key, UnversionedAsyncLoader(loader))) { it?.value }

        override fun getAll(keys: Collection<K>): CompletableFuture<Map<K, V>> = mapped(
            async.getAll(keys)
        ) { valuesOf(it) }

        override fun lookup(key: K): CompletableFuture<Lookup<V>> = mapped(async.lookup(key)) { lookupOf(it) }

        override fun put(key: K, value: V): CompletableFuture<Void?> = async.put(key, unversioned(value))

        override fun put(key: K, value: V, ttl: Duration): CompletableFuture<Void?> = async.put(
            key,
            unversioned(value),
            ttl
        )

        override fun putAll(entries: Map<K, V>): CompletableFuture<Void?> = async.putAll(unversioned(entries))

        override fun putAll(entries: Map<K, V>, ttl: Duration): CompletableFuture<Void?> = async.putAll(
            unversioned(entries),
            ttl
        )

        override fun invalidate(key: K): CompletableFuture<Void?> = async.invalidate(key)

        override fun invalidateAll(keys: Collection<K>): CompletableFuture<Void?> = async.invalidateAll(keys)

        override fun getIfChanged(key: K, known: Digest?): CompletableFuture<ConditionalGet<V>> =
            mapped(async.getIfChanged(key, known)) { conditionalGetOf(it) }

        override fun putIfMatch(key: K, expected: Digest?, value: V): CompletableFuture<ConditionalPut> =
            async.putIfMatch(key, expected, unversioned(value))

        override fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): CompletableFuture<ConditionalPut> =
            async.putIfMatch(key, expected, unversioned(value), ttl)

        override fun putIfNewer(key: K, value: V, version: Long): CompletableFuture<VersionedPut> = versionedWrite {
            val versioned = Versioned<V?>(value, version)
            engine.writeIfNewerAsync(key, versioned, version, engine.versionedTtl(key, versioned))
        }

        override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): CompletableFuture<VersionedPut> =
            versionedWrite { engine.writeIfNewerAsync(key, Versioned(value, version), version, ttl) }

        override fun invalidateIfNewer(key: K, version: Long): CompletableFuture<VersionedPut> =
            versionedWrite { engine.writeIfNewerAsync(key, null, version, engine.versionedTtl(key, null)) }

        override fun invalidateIfNewer(key: K, version: Long, ttl: Duration): CompletableFuture<VersionedPut> =
            versionedWrite { engine.writeIfNewerAsync(key, null, version, ttl) }

        override fun getVersioned(key: K): CompletableFuture<Versioned<V>?> = mapped(async.get(key)) { presentOf(it) }

        override fun sync(): Cache<K, V> = this@VersionedCache

        override fun toString(): String = "AsyncView(${this@VersionedCache})"

        /**
         * 与引擎异步视图的写入相同：同步抛出（如非法版本）转为失败的 future，返回的 future 以原始异常完成。
         */
        private fun versionedWrite(block: () -> CompletableFuture<VersionedPut>): CompletableFuture<VersionedPut> =
            Futures.relay(Futures.attempt(block))

        /**
         * 在 [source] 完成的线程上转换（引擎的 future 只在调用方线程或异步执行器上完成；已完成时就地转换，L2 命中仍返回已完成的 future），
         * 以原始异常失败。每次调用新建 future，取消它不影响引擎的 future。
         */
        @Suppress("TooGenericExceptionCaught")
        private fun <T, R> mapped(source: CompletableFuture<T>, map: (T) -> R): CompletableFuture<R> {
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
        fun <V : Any> unversioned(value: V): Versioned<V?> = Versioned(value, Versioned.NO_VERSION)

        fun <K : Any, V : Any> unversioned(entries: Map<K, V>): Map<K, Versioned<V?>> = entries.mapValues {
            unversioned(it.value)
        }

        /**
         * 有值时就是同一个对象（`value` 不为 `null` 的 `Versioned<V?>` 即 `Versioned<V>`），不分配；带版本的不存在为 `null`。
         */
        @Suppress("UNCHECKED_CAST")
        fun <V : Any> presentOf(versioned: Versioned<V?>?): Versioned<V>? =
            versioned?.takeIf { it.value != null } as Versioned<V>?

        fun <K : Any, V : Any> valuesOf(entries: Map<K, Versioned<V?>>): Map<K, V> {
            val values = LinkedHashMap<K, V>(entries.size)
            entries.forEach { (key, versioned) -> versioned.value?.let { values[key] = it } }
            return values
        }

        fun <V : Any> lookupOf(lookup: Lookup<Versioned<V?>>): Lookup<V> = when (lookup) {
            is Lookup.Present -> lookup.value.value?.let { Lookup.Present(it, lookup.expiresAt) } ?: Lookup.Absent(lookup.expiresAt)
            is Lookup.Absent -> lookup
        }

        /**
         * 条件读取只读到标量值（墓碑与负缓存是 `Absent`），它们总有值。
         */
        fun <V : Any> conditionalGetOf(read: ConditionalGet<Versioned<V?>>): ConditionalGet<V> = when (read) {
            is ConditionalGet.Present -> ConditionalGet.Present(requireNotNull(read.value.value), read.digest)
            ConditionalGet.Unchanged -> ConditionalGet.Unchanged
            ConditionalGet.Absent -> ConditionalGet.Absent
        }
    }
}

/**
 * 不带版本的数据源（普通 `loader`、调用方给出的 loader）在带版本的缓存上：值的版本为 [Versioned.NO_VERSION]。
 */
internal class UnversionedLoader<K : Any, V : Any>(
    private val delegate: CacheLoader<K, V>
) : CacheLoader<K, Versioned<V?>> {
    override fun load(key: K): Versioned<V?>? = delegate.load(key)?.let { Versioned(it, Versioned.NO_VERSION) }

    override fun loadAll(keys: Collection<K>): Map<K, Versioned<V?>> =
        delegate.loadAll(keys).mapValues { Versioned(it.value, Versioned.NO_VERSION) }
}

/**
 * [UnversionedLoader] 的异步版本：只在数据源的 future 完成的线程上做一次包装，不调用其它用户代码。
 */
internal class UnversionedAsyncLoader<K : Any, V : Any>(
    private val delegate: AsyncCacheLoader<K, V>
) : AsyncCacheLoader<K, Versioned<V?>> {
    override fun loadAsync(key: K): CompletionStage<out Versioned<V?>?> =
        delegate.loadAsync(key).thenApply { value -> value?.let { Versioned(it, Versioned.NO_VERSION) } }

    override fun loadAllAsync(keys: Collection<K>): CompletionStage<out Map<K, Versioned<V?>>> =
        delegate.loadAllAsync(
            keys
        ).thenApply { values -> values.mapValues { Versioned(it.value, Versioned.NO_VERSION) } }
}

/**
 * 带版本的缓存的引擎声明：值类型为 `Versioned<V?>`，数据源与 `ttlOf` 按此转换（不带版本的数据源的值没有版本；
 * 带版本的不存在没有值可交给 `ttlOf`，按策略寿命）。引擎经 [VersionedFormat] 编解码，[PayloadCodec] 只为满足必填项。
 */
@Suppress("UNCHECKED_CAST")
internal fun <K : Any, V : Any> CacheSpec<K, V>.versionedEngineSpec(): CacheSpec<K, Versioned<V?>> {
    val userTtlOf = ttlOf
    return CacheSpec.builder<K, Versioned<V?>>(name).also {
        it.namespace = namespace
        it.keyPrefix = keyPrefix
        it.keyEncoder = keyEncoder
        it.codec = PayloadCodec(codec)
        // 数据源只产出值（out 投影），按 Versioned<V?> 使用是安全的
        it.loader = versionedLoader as CacheLoader<K, Versioned<V?>>? ?: loader?.let { source -> UnversionedLoader(source) }
        it.asyncLoader = versionedAsyncLoader as AsyncCacheLoader<K, Versioned<V?>>?
            ?: asyncLoader?.let { source -> UnversionedAsyncLoader(source) }
        it.ttlOf = userTtlOf?.let { function -> TtlFunction { key, value -> value.value?.let { present -> function.ttlOf(key, present) } } }
        it.keyFilter = keyFilter
        it.policy = policy
    }.build()
}
