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

@file:JvmName("SuspendingCacheLoaders")

package me.ahoo.cocache.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.future
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.core.CacheSpec
import java.util.concurrent.CompletionStage
import kotlin.coroutines.CoroutineContext

/**
 * 挂起的数据源（RFC 0004 §7）。返回 `null` 表示不存在（按负缓存处理）。
 */
fun interface SuspendingCacheLoader<K : Any, V : Any> {
    suspend fun load(key: K): V?

    /**
     * 批量加载：结果中缺少的 key 视为不存在。默认并发调用 [load]；数据源应覆盖为一次批量查询。
     */
    suspend fun loadAll(keys: Collection<K>): Map<K, V> = coroutineScope {
        keys.map { key -> async { key to load(key) } }
            .awaitAll()
            .mapNotNull { (key, value) -> value?.let { key to it } }
            .toMap()
    }
}

/**
 * 转为 [AsyncCacheLoader]，在 `Dispatchers.Default` 中执行，见另一个重载。
 */
fun <K : Any, V : Any> SuspendingCacheLoader<K, V>.asAsyncCacheLoader(): AsyncCacheLoader<K, V> =
    asAsyncCacheLoader(Dispatchers.Default)

/**
 * 转为 [AsyncCacheLoader]：每次加载在 [context] 中启动一个协程（[context] 没有 dispatcher 时为 `Dispatchers.Default`），
 * 以它的结果或原始异常完成。
 *
 * - 加载协程都挂在一个 `SupervisorJob` 下：一次加载失败不影响其它加载；[context] 带 `Job` 时它是这个 `SupervisorJob` 的父 Job，
 *   取消它会取消进行中与之后的加载，这些加载以 [SourceLoadCancelledException] 失败（同步、异步与挂起的调用方都是）。
 * - 这个 `SupervisorJob` 永远不会完成：[context] 中的 Job 请用一个长生命周期的 Job（如应用级的），不要用结构化作用域的 Job，
 *   否则那个作用域永远结束不了。
 * - 每次调用都新建一个 `SupervisorJob`：请创建一次、反复使用返回的 loader，不要每次加载都调用本函数，否则子 Job 会越积越多。
 * - 数据源自己抛出的 `CancellationException`（如数据源内部的 `withTimeout`）转为 [SourceLoadCancelledException]：
 *   否则合并到这次加载的所有调用方都会把它当作自己被取消。
 * - 引擎的 `load-async-timeout` 到期时调用方以 `SourceLoadTimeoutException` 失败，协程不会被取消（与 [AsyncCacheLoader] 相同）。
 * - 单独使用（不经 [suspendingLoader]）时没有同 key 重入检查：转换时不知道它属于哪个缓存。
 */
fun <K : Any, V : Any> SuspendingCacheLoader<K, V>.asAsyncCacheLoader(
    context: CoroutineContext
): AsyncCacheLoader<K, V> =
    SuspendingAsyncLoader(this, context, null)

/**
 * 把 [loader] 设为声明的异步数据源（`asyncLoader`），在 `Dispatchers.Default` 中执行，见另一个重载。
 */
fun <K : Any, V : Any> CacheSpec.Builder<K, V>.suspendingLoader(loader: SuspendingCacheLoader<K, V>) {
    suspendingLoader(Dispatchers.Default, loader)
}

/**
 * 把 [loader] 设为声明的异步数据源（`asyncLoader`），在 [context] 中执行；语义见 [asAsyncCacheLoader]。
 *
 * 另外，经挂起视图在 [loader] 中读取本缓存正在加载的 key 立即失败（`IllegalStateException`）。这项检查按 key（`K`）匹配，
 * 不按编码后的存储 key：两个不相等的 key 编码成同一个存储 key 时检查不到，要等到 `load-async-timeout` 才失败。它按缓存名匹配：
 * 声明时还不知道缓存属于哪个运行时，同一进程中另一个运行时的同名缓存也会被视为本缓存。
 * spec 级 loader 没有调用方，不继承调用方的重入记录：跨缓存的循环（A 的 loader 读 B、B 的 loader 又读 A 的同一个 key）
 * 只能等到 `load-async-timeout` 才失败。
 */
fun <K : Any, V : Any> CacheSpec.Builder<K, V>.suspendingLoader(
    context: CoroutineContext,
    loader: SuspendingCacheLoader<K, V>
) {
    asyncLoader = SuspendingAsyncLoader(loader, context, name)
}

internal class SuspendingAsyncLoader<K : Any, V : Any>(
    private val loader: SuspendingCacheLoader<K, V>,
    context: CoroutineContext,
    private val cacheName: String?,
) : AsyncCacheLoader<K, V> {
    // 一个 SupervisorJob（而不是每次加载各建一个）：每次新建的话，它们都是 context 中 Job 的子 Job 且永不完成，会一直累积
    private val scope = CoroutineScope(context + SupervisorJob(context[Job]))

    override fun loadAsync(key: K): CompletionStage<out V?> =
        scope.future(loadingKeys(listOf(key))) {
            cancellationAsFailure(cacheName) { loader.load(key) }
        }.cancelledAsFailure(cacheName)

    override fun loadAllAsync(keys: Collection<K>): CompletionStage<out Map<K, V>> =
        scope.future(loadingKeys(keys)) {
            cancellationAsFailure(cacheName) { loader.loadAll(keys) }
        }.cancelledAsFailure(cacheName)

    private fun loadingKeys(keys: Collection<K>): CoroutineContext =
        if (cacheName == null) kotlin.coroutines.EmptyCoroutineContext else LoadingKeys.adding(null, cacheName, keys)

    override fun toString(): String = "SuspendingAsyncLoader($loader)"
}
