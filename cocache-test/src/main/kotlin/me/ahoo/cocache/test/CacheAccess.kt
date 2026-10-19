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
import me.ahoo.cocache.api.AsyncSetCache
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.core.CacheSpec
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/**
 * TCK 访问缓存的方式（RFC 0004 §10.1）：同一组用例既可以经同步 API 运行，也可以经异步视图运行。
 */
interface CacheAccess {
    /**
     * 用例拿到的缓存。
     */
    fun <K : Any, V : Any> cache(cache: Cache<K, V>): Cache<K, V>

    /**
     * 用例拿到的集合缓存。
     */
    fun <K : Any, E : Any> setCache(cache: SetCache<K, E>): SetCache<K, E>

    /**
     * 调整声明的数据源（在用例的声明块之后调用）。
     */
    fun <K : Any, V : Any> source(builder: CacheSpec.Builder<K, V>)

    companion object {
        /**
         * 同步 API，声明不变。
         */
        @JvmField
        val SYNC: CacheAccess = object : CacheAccess {
            override fun <K : Any, V : Any> cache(cache: Cache<K, V>): Cache<K, V> = cache

            override fun <K : Any, E : Any> setCache(cache: SetCache<K, E>): SetCache<K, E> = cache

            override fun <K : Any, V : Any> source(builder: CacheSpec.Builder<K, V>) = Unit

            override fun toString(): String = "CacheAccess.SYNC"
        }

        /**
         * 经 `async()` 视图读写并等待结果（异常解包为原始异常）；同步数据源换成在调用线程上执行它的异步数据源，
         * 用例中按线程名编排的钩子因此仍然作用于发起调用的线程。
         */
        @JvmField
        val ASYNC: CacheAccess = object : CacheAccess {
            override fun <K : Any, V : Any> cache(cache: Cache<K, V>): Cache<K, V> = AsyncBackedCache(cache)

            override fun <K : Any, E : Any> setCache(cache: SetCache<K, E>): SetCache<K, E> = AsyncBackedSetCache(cache)

            override fun <K : Any, V : Any> source(builder: CacheSpec.Builder<K, V>) {
                val loader = builder.loader ?: return
                builder.loader = null
                builder.asyncLoader = InlineAsyncLoader(loader)
            }

            override fun toString(): String = "CacheAccess.ASYNC"
        }

        /**
         * 等待异步调用：异步方法不得同步抛出；失败时抛出原始异常（解开 JDK 的 [CompletionException]）。
         */
        @Suppress("TooGenericExceptionCaught")
        fun <T> await(call: () -> CompletableFuture<T>): T {
            val future = try {
                call()
            } catch (e: Throwable) {
                throw AssertionError("Async API must not throw synchronously.", e)
            }
            try {
                return future.join()
            } catch (e: CompletionException) {
                val cause = e.cause ?: e
                check(cause !is CompletionException) { "Async API completed with a wrapped exception: $cause" }
                throw cause
            }
        }
    }
}

/**
 * 在调用线程上执行同步数据源的异步数据源（TCK 用）。
 */
class InlineAsyncLoader<K : Any, V : Any>(private val delegate: CacheLoader<K, V>) : AsyncCacheLoader<K, V> {
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
 * 经 `async()` 视图实现的同步 [Cache]（TCK 用）。
 */
open class AsyncBackedCache<K : Any, V : Any>(val target: Cache<K, V>) : Cache<K, V> {
    private val async = target.async()

    override val name: String
        get() = async.name

    override fun get(key: K): V? = CacheAccess.await { async.get(key) }

    override fun get(key: K, loader: CacheLoader<K, V>): V? = CacheAccess.await {
        async.get(
            key,
            InlineAsyncLoader(loader)
        )
    }

    override fun getAll(keys: Collection<K>): Map<K, V> = CacheAccess.await { async.getAll(keys) }

    override fun lookup(key: K): Lookup<V> = CacheAccess.await { async.lookup(key) }

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

    override fun toString(): String = "AsyncBackedCache($target)"
}

/**
 * 经 `async()` 视图实现的同步 [SetCache]（TCK 用）。
 */
class AsyncBackedSetCache<K : Any, E : Any>(target: SetCache<K, E>) :
    AsyncBackedCache<K, Set<E>>(
        target
    ),
    SetCache<K, E> {
    private val async: AsyncSetCache<K, E> = target.async()

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

    override fun async(): AsyncSetCache<K, E> = async
}
