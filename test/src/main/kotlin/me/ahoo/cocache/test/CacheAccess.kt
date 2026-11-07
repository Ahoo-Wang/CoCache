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

package me.ahoo.cocache.test

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.AsyncReadableCache
import me.ahoo.cocache.api.AsyncSetStore
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.VersionedCacheSpec
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/**
 * TCK 访问缓存的方式（RFC 0004 §10.1）：同一组用例既可以经同步 API 运行，也可以经异步视图运行。
 *
 * 后端实现者只需要默认的 [SYNC]：变体的语义属于核心，由核心自己的测试覆盖（RFC 0005 §4.10）。仓库内的测试覆盖规范的 `access`，
 * 在异步变体下重跑同一组用例；第三方后端需要异步变体的覆盖时也可以这样做。
 *
 * 五种用户 API 类型各有一个入口；[SYNC] 原样返回，其余变体返回经对应视图实现的同步类型。
 */
interface CacheAccess {
    fun <K : Any, V : Any> cache(cache: Cache<K, V>): Cache<K, V>

    fun <K : Any, V : Any> versionedCache(cache: VersionedCache<K, V>): VersionedCache<K, V>

    fun <K : Any, V : Any> store(store: Store<K, V>): Store<K, V>

    fun <K : Any, V : Any> versionedStore(store: VersionedStore<K, V>): VersionedStore<K, V>

    fun <K : Any, E : Any> setStore(store: SetStore<K, E>): SetStore<K, E>

    /**
     * 调整声明的数据源（在用例的声明块之后调用）。
     */
    fun <K : Any, V : Any> source(builder: CacheSpec.Builder<K, V>)

    /**
     * 调整声明的带版本的数据源（在用例的声明块之后调用）。
     */
    fun <K : Any, V : Any> source(builder: VersionedCacheSpec.Builder<K, V>)

    /**
     * 经视图访问的变体：每种类型都换成经异步视图实现的同步类型（[asyncSource] 决定是否把同步数据源换成在调用线程上执行的异步数据源）。
     */
    private abstract class ViaAsync(private val asyncSource: Boolean, private val label: String) : CacheAccess {
        override fun <K : Any, V : Any> cache(cache: Cache<K, V>): Cache<K, V> = AsyncBackedCache(cache)

        override fun <K : Any, V : Any> versionedCache(cache: VersionedCache<K, V>): VersionedCache<K, V> =
            AsyncBackedVersionedCache(cache)

        override fun <K : Any, V : Any> store(store: Store<K, V>): Store<K, V> = AsyncBackedStore(store)

        override fun <K : Any, V : Any> versionedStore(store: VersionedStore<K, V>): VersionedStore<K, V> =
            AsyncBackedVersionedStore(store)

        override fun <K : Any, E : Any> setStore(store: SetStore<K, E>): SetStore<K, E> = AsyncBackedSetStore(store)

        override fun <K : Any, V : Any> source(builder: CacheSpec.Builder<K, V>) {
            if (!asyncSource) {
                return
            }
            val loader = builder.loader ?: return
            builder.loader = null
            builder.asyncLoader = InlineAsyncLoader(loader)
        }

        override fun <K : Any, V : Any> source(builder: VersionedCacheSpec.Builder<K, V>) {
            if (!asyncSource) {
                return
            }
            val versioned = builder.versionedLoader ?: return
            builder.versionedLoader = null
            // 数据源只产出值（out 投影），按 Versioned<V?> 使用是安全的
            @Suppress("UNCHECKED_CAST")
            builder.versionedAsyncLoader = InlineAsyncLoader(versioned as CacheLoader<K, Versioned<V?>>)
        }

        override fun toString(): String = label
    }

    companion object {
        /**
         * 同步 API，声明不变。
         */
        @JvmField
        val SYNC: CacheAccess = object : CacheAccess {
            override fun <K : Any, V : Any> cache(cache: Cache<K, V>): Cache<K, V> = cache

            override fun <K : Any, V : Any> versionedCache(cache: VersionedCache<K, V>): VersionedCache<K, V> = cache

            override fun <K : Any, V : Any> store(store: Store<K, V>): Store<K, V> = store

            override fun <K : Any, V : Any> versionedStore(store: VersionedStore<K, V>): VersionedStore<K, V> = store

            override fun <K : Any, E : Any> setStore(store: SetStore<K, E>): SetStore<K, E> = store

            override fun <K : Any, V : Any> source(builder: CacheSpec.Builder<K, V>) = Unit

            override fun <K : Any, V : Any> source(builder: VersionedCacheSpec.Builder<K, V>) = Unit

            override fun toString(): String = "CacheAccess.SYNC"
        }

        /**
         * 经 `async()` 视图读写并等待结果（异常解包为原始异常）；同步数据源换成在调用线程上执行它的异步数据源，
         * 用例中按线程名编排的钩子因此仍然作用于发起调用的线程。
         */
        @JvmField
        val ASYNC: CacheAccess = object : ViaAsync(true, "CacheAccess.ASYNC") {}

        /**
         * 经 `async()` 视图读写，但保留声明中的同步数据源（默认的用户路径：同步数据源在阻塞执行器上执行）。
         */
        @JvmField
        val ASYNC_WITH_SYNC_SOURCE: CacheAccess = object : ViaAsync(false, "CacheAccess.ASYNC_WITH_SYNC_SOURCE") {}

        /**
         * 等待异步调用：异步方法不得同步抛出；失败时抛出 future 中保存的原始异常，并断言它不是 [CompletionException]（A8）。
         */
        fun <T> await(call: () -> CompletableFuture<T>): T {
            val future = start(call)
            // handle 收到的是 future 中保存的那个异常；join 会把保存的 CompletionException 原样抛出，看不出是否被包装
            val stored = future.handle { _, error -> error }.join() ?: return future.join()
            throw if (stored is CompletionException) {
                AssertionError(
                    "Async API completed with a wrapped exception.",
                    stored
                )
            } else {
                stored
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun <T> start(call: () -> CompletableFuture<T>): CompletableFuture<T> = try {
            call()
        } catch (e: Throwable) {
            throw AssertionError("Async API must not throw synchronously.", e)
        }
    }
}

/**
 * 在调用线程上执行同步数据源的异步数据源（TCK 用）。
 */
internal class InlineAsyncLoader<K : Any, V : Any>(private val delegate: CacheLoader<K, V>) : AsyncCacheLoader<K, V> {
    @Suppress("TooGenericExceptionCaught")
    override fun loadAsync(key: K): CompletionStage<out V?> = try {
        CompletableFuture.completedFuture(delegate.load(key))
    } catch (e: Throwable) {
        CompletableFuture.failedFuture(e)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun loadAllAsync(keys: Collection<K>): CompletionStage<out Map<K, V>> = try {
        CompletableFuture.completedFuture(delegate.loadAll(keys))
    } catch (e: Throwable) {
        CompletableFuture.failedFuture(e)
    }
}

/**
 * 经异步视图实现的读取（TCK 用）：五种同步类型的读取都经它。
 */
internal abstract class AsyncBackedReads<K : Any, V : Any>(
    private val reads: AsyncReadableCache<K, V>
) : ReadableCache<K, V> {
    override val name: String
        get() = reads.name

    override fun get(key: K): V? = CacheAccess.await { reads.get(key) }

    override fun getAll(keys: Collection<K>): Map<K, V> = CacheAccess.await { reads.getAll(keys) }

    override fun lookup(key: K): Lookup<V> = CacheAccess.await { reads.lookup(key) }

    override fun lookupAll(keys: Collection<K>): Map<K, Lookup<V>> = CacheAccess.await { reads.lookupAll(keys) }
}

/**
 * 经 `async()` 视图实现的同步 [Cache]（TCK 用）。
 */
internal class AsyncBackedCache<K : Any, V : Any>(
    val target: Cache<K, V>
) : AsyncBackedReads<K, V>(target.async()), Cache<K, V> {
    private val async = target.async()

    override fun get(key: K, loader: CacheLoader<K, V>): V? =
        CacheAccess.await { async.get(key, InlineAsyncLoader(loader)) }

    override fun put(key: K, value: V) {
        CacheAccess.await { async.put(key, value) }
    }

    override fun put(key: K, value: V, ttl: Duration) {
        CacheAccess.await { async.put(key, value, ttl) }
    }

    override fun putAll(entries: Map<K, V>) {
        CacheAccess.await { async.putAll(entries) }
    }

    override fun putAll(entries: Map<K, V>, ttl: Duration) {
        CacheAccess.await { async.putAll(entries, ttl) }
    }

    override fun invalidate(key: K) {
        CacheAccess.await { async.invalidate(key) }
    }

    override fun invalidateAll(keys: Collection<K>) {
        CacheAccess.await { async.invalidateAll(keys) }
    }

    override fun async() = async

    override fun suspending() = target.suspending()

    override fun toString(): String = "AsyncBackedCache($target)"
}

/**
 * 经 `async()` 视图实现的同步 [VersionedCache]（TCK 用）。
 */
internal class AsyncBackedVersionedCache<K : Any, V : Any>(val target: VersionedCache<K, V>) :
    AsyncBackedReads<K, V>(target.async()),
    VersionedCache<K, V> {
    private val async = target.async()

    override fun getVersioned(key: K): Versioned<V>? = CacheAccess.await { async.getVersioned(key) }

    override fun putIfNewer(key: K, value: V, version: Long): VersionedPut =
        CacheAccess.await { async.putIfNewer(key, value, version) }

    override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration?): VersionedPut =
        CacheAccess.await { async.putIfNewer(key, value, version, ttl) }

    override fun invalidateIfNewer(key: K, version: Long): VersionedPut =
        CacheAccess.await { async.invalidateIfNewer(key, version) }

    override fun invalidateIfNewer(key: K, version: Long, ttl: Duration?): VersionedPut =
        CacheAccess.await { async.invalidateIfNewer(key, version, ttl) }

    override fun evict(key: K) {
        CacheAccess.await { async.evict(key) }
    }

    override fun purge(key: K) {
        CacheAccess.await { async.purge(key) }
    }

    override fun async() = async

    override fun suspending() = target.suspending()

    override fun toString(): String = "AsyncBackedVersionedCache($target)"
}

/**
 * 经 `async()` 视图实现的同步 [Store]（TCK 用）。
 */
internal class AsyncBackedStore<K : Any, V : Any>(
    val target: Store<K, V>
) : AsyncBackedReads<K, V>(target.async()), Store<K, V> {
    private val async = target.async()

    override fun put(key: K, value: V) {
        CacheAccess.await { async.put(key, value) }
    }

    override fun put(key: K, value: V, ttl: Duration) {
        CacheAccess.await { async.put(key, value, ttl) }
    }

    override fun putAll(entries: Map<K, V>) {
        CacheAccess.await { async.putAll(entries) }
    }

    override fun putAll(entries: Map<K, V>, ttl: Duration) {
        CacheAccess.await { async.putAll(entries, ttl) }
    }

    override fun delete(key: K) {
        CacheAccess.await { async.delete(key) }
    }

    override fun deleteIfMatch(key: K, expected: Digest): ConditionalDelete =
        CacheAccess.await { async.deleteIfMatch(key, expected) }

    override fun getIfChanged(key: K, known: Digest?): ConditionalGet<V> =
        CacheAccess.await { async.getIfChanged(key, known) }

    override fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut =
        CacheAccess.await { async.putIfMatch(key, expected, value) }

    override fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): ConditionalPut =
        CacheAccess.await { async.putIfMatch(key, expected, value, ttl) }

    override fun async() = async

    override fun suspending() = target.suspending()

    override fun toString(): String = "AsyncBackedStore($target)"
}

/**
 * 经 `async()` 视图实现的同步 [VersionedStore]（TCK 用）。
 */
internal class AsyncBackedVersionedStore<K : Any, V : Any>(val target: VersionedStore<K, V>) :
    AsyncBackedReads<K, V>(target.async()),
    VersionedStore<K, V> {
    private val async = target.async()

    override fun getVersioned(key: K): Versioned<V>? = CacheAccess.await { async.getVersioned(key) }

    override fun putIfNewer(key: K, value: V, version: Long): VersionedPut =
        CacheAccess.await { async.putIfNewer(key, value, version) }

    override fun putIfNewer(key: K, value: V, version: Long, ttl: Duration?): VersionedPut =
        CacheAccess.await { async.putIfNewer(key, value, version, ttl) }

    override fun deleteIfNewer(key: K, version: Long): VersionedPut =
        CacheAccess.await { async.deleteIfNewer(key, version) }

    override fun deleteIfNewer(key: K, version: Long, ttl: Duration?): VersionedPut =
        CacheAccess.await { async.deleteIfNewer(key, version, ttl) }

    override fun purge(key: K) {
        CacheAccess.await { async.purge(key) }
    }

    override fun async() = async

    override fun suspending() = target.suspending()

    override fun toString(): String = "AsyncBackedVersionedStore($target)"
}

/**
 * 经 `async()` 视图实现的同步 [SetStore]（TCK 用）。
 */
internal class AsyncBackedSetStore<K : Any, E : Any>(val target: SetStore<K, E>) :
    AsyncBackedReads<K, Set<E>>(target.async()),
    SetStore<K, E> {
    private val async: AsyncSetStore<K, E> = target.async()

    override fun get(key: K): Set<E> = requireNotNull(CacheAccess.await { async.get(key) })

    override fun add(key: K, elements: Collection<E>) {
        CacheAccess.await { async.add(key, elements) }
    }

    override fun remove(key: K, elements: Collection<E>) {
        CacheAccess.await { async.remove(key, elements) }
    }

    override fun clear(key: K) {
        CacheAccess.await { async.clear(key) }
    }

    override fun put(key: K, value: Set<E>) {
        CacheAccess.await { async.put(key, value) }
    }

    override fun put(key: K, value: Set<E>, ttl: Duration) {
        CacheAccess.await { async.put(key, value, ttl) }
    }

    override fun putAll(entries: Map<K, Set<E>>) {
        CacheAccess.await { async.putAll(entries) }
    }

    override fun putAll(entries: Map<K, Set<E>>, ttl: Duration) {
        CacheAccess.await { async.putAll(entries, ttl) }
    }

    override fun async(): AsyncSetStore<K, E> = async

    override fun suspending() = target.suspending()

    override fun toString(): String = "AsyncBackedSetStore($target)"
}
