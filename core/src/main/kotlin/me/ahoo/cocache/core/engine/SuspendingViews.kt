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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.AsyncReadableCache
import me.ahoo.cocache.api.AsyncSetStore
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.AsyncVersionedCache
import me.ahoo.cocache.api.AsyncVersionedStore
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SuspendingCache
import me.ahoo.cocache.api.SuspendingReadableCache
import me.ahoo.cocache.api.SuspendingSetStore
import me.ahoo.cocache.api.SuspendingStore
import me.ahoo.cocache.api.SuspendingVersionedCache
import me.ahoo.cocache.api.SuspendingVersionedStore
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.internal.LoadCancellation.cancellationAsFailure
import me.ahoo.cocache.core.internal.LoadCancellation.cancelledAsFailure
import me.ahoo.cocache.core.internal.LoadingKeys
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 挂起视图（`suspending()`）的实现：每个方法调用异步视图的同名方法并等待结果，不复制任何引擎逻辑。
 * 五种用户 API 类型的挂起视图与它们的异步视图同构（RFC 0005 §3.1）；改为直接调用协程核心（K2、K6、K7）是 M5c。
 *
 * 读方法的唯一挂起点在尾部（[awaitResult] 内联后为 `return future.await()`），编译器不为它们生成状态机对象：
 * L2 命中（已完成的 future）除异步视图本身的分配外不再分配（RFC 0004 §7）。
 *
 * 读取先检查同 key 重入（[LoadingKeys]）：在这个缓存的调用级 loader 中经挂起视图读取同一 key 立即失败，而不是等待自己的加载。
 */
internal open class SuspendingReadableView<K : Any, V : Any>(
    private val async: AsyncReadableCache<K, V>
) : SuspendingReadableCache<K, V> {
    override val name: String
        get() = async.name

    override suspend fun get(key: K): V? {
        LoadingKeys.check(coroutineContext, async, name, key)
        return async.get(key).awaitResult()
    }

    override suspend fun getAll(keys: Collection<K>): Map<K, V> {
        LoadingKeys.checkAll(coroutineContext, async, name, keys)
        return async.getAll(keys).awaitResult()
    }

    override suspend fun lookup(key: K): Lookup<V> {
        LoadingKeys.check(coroutineContext, async, name, key)
        return async.lookup(key).awaitResult()
    }

    override suspend fun lookupAll(keys: Collection<K>): Map<K, Lookup<V>> {
        LoadingKeys.checkAll(coroutineContext, async, name, keys)
        return async.lookupAll(keys).awaitResult()
    }

    override fun async(): AsyncReadableCache<K, V> = async

    override fun toString(): String = "Suspending($async)"
}

internal class SuspendingCacheView<K : Any, V : Any>(
    private val async: AsyncCache<K, V>
) : SuspendingReadableView<K, V>(async), SuspendingCache<K, V> {
    override suspend fun get(key: K, loader: suspend (K) -> V?): V? {
        val context = coroutineContext
        LoadingKeys.check(context, async, name, key)
        // 上下文与作用域只在真正加载时才构造（loadAsync 内），命中不付这些分配
        return async.get(key, CallerLoader(async, name, context, loader)).awaitResult()
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
}

internal class SuspendingVersionedCacheView<K : Any, V : Any>(
    private val async: AsyncVersionedCache<K, V>
) : SuspendingReadableView<K, V>(async), SuspendingVersionedCache<K, V> {
    override suspend fun getVersioned(key: K): Versioned<V>? {
        LoadingKeys.check(coroutineContext, async, name, key)
        return async.getVersioned(key).awaitResult()
    }

    override suspend fun putIfNewer(key: K, value: V, version: Long): VersionedPut =
        async.putIfNewer(key, value, version).awaitResult()

    override suspend fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): VersionedPut =
        async.putIfNewer(key, value, version, ttl).awaitResult()

    override suspend fun invalidateIfNewer(key: K, version: Long): VersionedPut =
        async.invalidateIfNewer(key, version).awaitResult()

    override suspend fun invalidateIfNewer(key: K, version: Long, ttl: Duration): VersionedPut =
        async.invalidateIfNewer(key, version, ttl).awaitResult()

    override suspend fun evict(key: K) {
        async.evict(key).awaitResult()
    }

    override suspend fun purge(key: K) {
        async.purge(key).awaitResult()
    }

    override fun async(): AsyncVersionedCache<K, V> = async
}

internal class SuspendingStoreView<K : Any, V : Any>(
    private val async: AsyncStore<K, V>
) : SuspendingReadableView<K, V>(async), SuspendingStore<K, V> {
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

    override suspend fun delete(key: K) {
        async.delete(key).awaitResult()
    }

    override suspend fun deleteIfMatch(key: K, expected: Digest): ConditionalDelete =
        async.deleteIfMatch(key, expected).awaitResult()

    override suspend fun getIfChanged(key: K, known: Digest?): ConditionalGet<V> =
        async.getIfChanged(key, known).awaitResult()

    override suspend fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut =
        async.putIfMatch(key, expected, value).awaitResult()

    override suspend fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): ConditionalPut =
        async.putIfMatch(key, expected, value, ttl).awaitResult()

    override fun async(): AsyncStore<K, V> = async
}

internal class SuspendingVersionedStoreView<K : Any, V : Any>(
    private val async: AsyncVersionedStore<K, V>
) : SuspendingReadableView<K, V>(async), SuspendingVersionedStore<K, V> {
    override suspend fun getVersioned(key: K): Versioned<V>? {
        LoadingKeys.check(coroutineContext, async, name, key)
        return async.getVersioned(key).awaitResult()
    }

    override suspend fun putIfNewer(key: K, value: V, version: Long): VersionedPut =
        async.putIfNewer(key, value, version).awaitResult()

    override suspend fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): VersionedPut =
        async.putIfNewer(key, value, version, ttl).awaitResult()

    override suspend fun deleteIfNewer(key: K, version: Long): VersionedPut =
        async.deleteIfNewer(key, version).awaitResult()

    override suspend fun deleteIfNewer(key: K, version: Long, ttl: Duration): VersionedPut =
        async.deleteIfNewer(key, version, ttl).awaitResult()

    override suspend fun purge(key: K) {
        async.purge(key).awaitResult()
    }

    override fun async(): AsyncVersionedStore<K, V> = async
}

internal class SuspendingSetStoreView<K : Any, E : Any>(
    private val async: AsyncSetStore<K, E>
) : SuspendingReadableView<K, Set<E>>(async), SuspendingSetStore<K, E> {
    override suspend fun get(key: K): Set<E> {
        LoadingKeys.check(coroutineContext, async, name, key)
        val future = async.get(key)
        if (future.isDone && !future.isCompletedExceptionally) {
            return future.getNow(null).orEmpty()
        }
        // 尾调用：命中路径不生成状态机
        return awaitMembers(future)
    }

    private suspend fun awaitMembers(future: CompletableFuture<Set<E>?>): Set<E> = future.await().orEmpty()

    override suspend fun add(key: K, elements: Collection<E>) {
        async.add(key, elements).awaitResult()
    }

    override suspend fun remove(key: K, elements: Collection<E>) {
        async.remove(key, elements).awaitResult()
    }

    override suspend fun clear(key: K) {
        async.clear(key).awaitResult()
    }

    override suspend fun put(key: K, value: Set<E>) {
        async.put(key, value).awaitResult()
    }

    override suspend fun put(key: K, value: Set<E>, ttl: Duration) {
        async.put(key, value, ttl).awaitResult()
    }

    override suspend fun putAll(entries: Map<K, Set<E>>) {
        async.putAll(entries).awaitResult()
    }

    override suspend fun putAll(entries: Map<K, Set<E>>, ttl: Duration) {
        async.putAll(entries, ttl).awaitResult()
    }

    override fun async(): AsyncSetStore<K, E> = async
}

/**
 * 已完成且成功时直接取结果（L2 命中不挂起）；否则挂起等待。取消等待只取消这个 future（异步视图为每次调用新建 future，
 * 取消它不影响共享的加载）。失败时抛出 future 中保存的原始异常。内联：调用方的挂起点因此在尾部。
 */
@Suppress("UNCHECKED_CAST")
private suspend inline fun <T> CompletableFuture<T>.awaitResult(): T {
    if (isDone && !isCompletedExceptionally) {
        return getNow(null) as T
    }
    return await()
}

/**
 * 调用级 loader（`get(key) { … }`）：加载时才以调用方的上下文去掉 `Job` 启动协程。
 * 调用方的 dispatcher 是事件循环（`runBlocking`）时改用 `Dispatchers.Default`：加载可能比调用方活得更久，
 * 事件循环结束后提交给它的任务会被转到内部的 DefaultExecutor。
 */
private class CallerLoader<K : Any, V : Any>(
    private val owner: Any,
    private val cacheName: String,
    private val context: CoroutineContext,
    private val loader: suspend (K) -> V?,
) : AsyncCacheLoader<K, V> {
    override fun loadAsync(key: K): CompletionStage<out V?> {
        val loading = LoadingKeys.adding(context[LoadingKeys], owner, listOf(key))
        return CoroutineScope(dispatcherOf(context.minusKey(Job)) + loading).future {
            cancellationAsFailure(cacheName) { loader(key) }
        }.cancelledAsFailure(cacheName)
    }

    private companion object {
        /**
         * `runBlocking` 的事件循环的基类。kotlinx-coroutines 没有公开的判断方法，这里依赖它的内部类名：
         * 升级 kotlinx-coroutines 时请复查（类名变化时识别失效，调用级 loader 会留在事件循环上，`SuspendingCacheTest` 中的用例会失败）。
         */
        const val EVENT_LOOP = "kotlinx.coroutines.EventLoop"

        fun dispatcherOf(context: CoroutineContext): CoroutineContext {
            var type: Class<*>? = context[ContinuationInterceptor]?.javaClass
            while (type != null) {
                if (type.name == EVENT_LOOP) {
                    return context + Dispatchers.Default
                }
                type = type.superclass
            }
            return context
        }
    }
}
