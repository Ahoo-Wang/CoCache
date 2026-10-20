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

package me.ahoo.cocache.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.AsyncSetCache
import me.ahoo.cocache.api.Lookup
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * [SuspendingCache] 的实现：每个方法调用异步视图的同名方法并等待结果，不复制任何引擎逻辑。
 */
internal open class SuspendingCacheView<K : Any, V : Any>(private val async: AsyncCache<K, V>) : SuspendingCache<K, V> {
    override val name: String
        get() = async.name

    override suspend fun get(key: K): V? {
        checkNotLoading(listOf(key))
        return async.get(key).awaitResult()
    }

    override suspend fun get(key: K, loader: suspend (K) -> V?): V? {
        val context = currentCoroutineContext()
        LoadingKeys.check(context, name, listOf(key))
        // 去掉调用方的 Job：调用方被取消不取消（可能被共享的）加载；其余元素（dispatcher、MDC、追踪）保留
        val scope = CoroutineScope(context.minusKey(Job) + LoadingKeys.adding(context, name, listOf(key)))
        return async.get(key, AsyncCacheLoader { scope.future { loader(it) } }).awaitResult()
    }

    override suspend fun getAll(keys: Collection<K>): Map<K, V> {
        checkNotLoading(keys)
        return async.getAll(keys).awaitResult()
    }

    override suspend fun lookup(key: K): Lookup<V> {
        checkNotLoading(listOf(key))
        return async.lookup(key).awaitResult()
    }

    override suspend fun put(key: K, value: V) {
        async.put(key, value).awaitResult()
    }

    override suspend fun put(key: K, value: V, ttl: Duration) {
        async.put(key, value, ttl).awaitResult()
    }

    override suspend fun putAll(entries: Map<K, V>) {
        async.putAll(entries).awaitResult()
    }

    override suspend fun putAll(entries: Map<K, V>, ttl: Duration) {
        async.putAll(entries, ttl).awaitResult()
    }

    override suspend fun invalidate(key: K) {
        async.invalidate(key).awaitResult()
    }

    override suspend fun invalidateAll(keys: Collection<K>) {
        async.invalidateAll(keys).awaitResult()
    }

    override fun async(): AsyncCache<K, V> = async

    private suspend fun checkNotLoading(keys: Collection<K>) {
        LoadingKeys.check(currentCoroutineContext(), name, keys)
    }

    override fun toString(): String = "SuspendingCache($async)"
}

internal class SuspendingSetCacheView<K : Any, E : Any>(private val async: AsyncSetCache<K, E>) :
    SuspendingCacheView<K, Set<E>>(async),
    SuspendingSetCache<K, E> {
    override suspend fun add(key: K, elements: Collection<E>) {
        async.add(key, elements).awaitResult()
    }

    override suspend fun remove(key: K, elements: Collection<E>) {
        async.remove(key, elements).awaitResult()
    }

    override suspend fun clear(key: K) {
        async.clear(key).awaitResult()
    }

    override fun async(): AsyncSetCache<K, E> = async
}

/**
 * 已完成且成功时直接取结果（L2 命中不挂起）；否则挂起等待。取消等待只取消这个 future（异步视图为每次调用新建 future，
 * 取消它不影响共享的加载）。失败时抛出 future 中保存的原始异常。
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun <T> CompletableFuture<T>.awaitResult(): T {
    if (isDone && !isCompletedExceptionally) {
        return getNow(null) as T
    }
    return await()
}

/**
 * 正在由当前协程（及其子协程）加载的 key：同一缓存同一 key 的重入读取立即失败，而不是等待自己的加载直到 `load-async-timeout`。
 * 引擎的同 key 重入检查基于线程，只覆盖数据源的同步部分；挂起之后在别的线程上继续时由这个元素覆盖。
 */
internal class LoadingKeys private constructor(private val keys: Set<Pair<String, Any>>) :
    AbstractCoroutineContextElement(LoadingKeys) {
    companion object Key : CoroutineContext.Key<LoadingKeys> {
        fun adding(context: CoroutineContext, cacheName: String, keys: Collection<Any>): LoadingKeys {
            val current = context[LoadingKeys]?.keys.orEmpty()
            return LoadingKeys(current + keys.map { cacheName to it })
        }

        fun check(context: CoroutineContext, cacheName: String, keys: Collection<Any>) {
            val loading = context[LoadingKeys] ?: return
            keys.firstOrNull { (cacheName to it) in loading.keys }?.let {
                throw IllegalStateException("Recursive call on the same key[$it] is not supported.")
            }
        }
    }

    override fun toString(): String = "LoadingKeys($keys)"
}
