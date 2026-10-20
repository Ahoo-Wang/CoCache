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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.AsyncSetCache
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.coroutines.LoadCancellation.cancellationAsFailure
import me.ahoo.cocache.coroutines.LoadCancellation.cancelledAsFailure
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * [SuspendingCache] 的实现：每个方法调用异步视图的同名方法并等待结果，不复制任何引擎逻辑。
 *
 * 读方法的唯一挂起点在尾部（`awaitResult` 内联后为 `return future.await()`），编译器不为它们生成状态机对象：
 * L2 命中（已完成的 future）除异步视图本身的分配外不再分配（RFC 0004 §7）。
 */
internal open class SuspendingCacheView<K : Any, V : Any>(private val async: AsyncCache<K, V>) : SuspendingCache<K, V> {
    override val name: String
        get() = async.name

    override suspend fun get(key: K): V? {
        LoadingKeys.check(coroutineContext, async, name, key)
        return async.get(key).awaitResult()
    }

    override suspend fun get(key: K, loader: suspend (K) -> V?): V? {
        val context = coroutineContext
        LoadingKeys.check(context, async, name, key)
        // 上下文与作用域只在真正加载时才构造（loadAsync 内），命中不付这些分配
        return async.get(key, CallerLoader(async, name, context, loader)).awaitResult()
    }

    override suspend fun getAll(keys: Collection<K>): Map<K, V> {
        LoadingKeys.checkAll(coroutineContext, async, name, keys)
        return async.getAll(keys).awaitResult()
    }

    override suspend fun lookup(key: K): Lookup<V> {
        LoadingKeys.check(coroutineContext, async, name, key)
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

    override fun toString(): String = "SuspendingCache($async)"

    /**
     * 已完成且成功时直接取结果（L2 命中不挂起）；否则挂起等待。取消等待只取消这个 future（异步视图为每次调用新建 future，
     * 取消它不影响共享的加载）。失败时抛出 future 中保存的原始异常。内联：调用方的挂起点因此在尾部。
     */
    @Suppress("UNCHECKED_CAST")
    protected suspend inline fun <T> CompletableFuture<T>.awaitResult(): T {
        if (isDone && !isCompletedExceptionally) {
            return getNow(null) as T
        }
        return await()
    }
}

internal class SuspendingSetCacheView<K : Any, E : Any>(private val async: AsyncSetCache<K, E>) :
    SuspendingCacheView<K, Set<E>>(async),
    SuspendingSetCache<K, E> {
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

    override fun async(): AsyncSetCache<K, E> = async
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

/**
 * 正在由当前协程（及其子协程）加载的 key：经挂起视图对同一缓存同一 key 的重入读取立即失败，而不是等待自己的加载直到
 * `load-async-timeout`。引擎的同 key 重入检查基于线程，只覆盖数据源的同步部分；挂起之后在别的线程上继续时由这个元素覆盖。
 *
 * 每项的 owner 是调用级 loader 所在视图背后的异步视图实例（按实例匹配），或 spec 级 loader 的缓存名（声明时只知道名字）。
 */
internal class LoadingKeys private constructor(private val entries: List<Pair<Any, Any>>) :
    AbstractCoroutineContextElement(LoadingKeys) {
    private fun contains(async: Any, name: String, key: Any): Boolean =
        entries.any { (owner, loading) -> loading == key && (owner === async || owner == name) }

    companion object Key : CoroutineContext.Key<LoadingKeys> {
        fun adding(current: LoadingKeys?, owner: Any, keys: Collection<Any>): LoadingKeys =
            LoadingKeys(current?.entries.orEmpty() + keys.map { owner to it })

        fun check(context: CoroutineContext, async: Any, name: String, key: Any) {
            val loading = context[LoadingKeys] ?: return
            check(!loading.contains(async, name, key)) { "Recursive call on the same key[$key] is not supported." }
        }

        fun checkAll(context: CoroutineContext, async: Any, name: String, keys: Collection<Any>) {
            val loading = context[LoadingKeys] ?: return
            keys.forEach { key ->
                check(!loading.contains(async, name, key)) { "Recursive call on the same key[$key] is not supported." }
            }
        }
    }

    override fun toString(): String = "LoadingKeys($entries)"
}
