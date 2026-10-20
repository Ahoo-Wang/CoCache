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
 * 转为 [AsyncCacheLoader]：每次加载在 [context] 中启动一个协程（默认 `Dispatchers.Default`；[context] 没有 dispatcher 时同样是它），
 * 以它的结果或原始异常完成。[context] 带 `Job` 时取消它会取消进行中的加载。
 *
 * 引擎的 `load-async-timeout` 到期时调用方以 `SourceLoadTimeoutException` 失败，协程不会被取消（与 [AsyncCacheLoader] 相同）。
 */
fun <K : Any, V : Any> SuspendingCacheLoader<K, V>.asAsyncCacheLoader(
    context: CoroutineContext = Dispatchers.Default
): AsyncCacheLoader<K, V> = SuspendingAsyncLoader(this, context, null)

/**
 * 把 [loader] 设为声明的异步数据源（`asyncLoader`），在 [context] 中执行（默认 `Dispatchers.Default`）。
 * 经挂起视图在 [loader] 中读取本缓存正在加载的 key 立即失败（`IllegalStateException`）。
 */
fun <K : Any, V : Any> CacheSpec.Builder<K, V>.suspendingLoader(
    context: CoroutineContext = Dispatchers.Default,
    loader: SuspendingCacheLoader<K, V>
) {
    asyncLoader = SuspendingAsyncLoader(loader, context, name)
}

internal class SuspendingAsyncLoader<K : Any, V : Any>(
    private val loader: SuspendingCacheLoader<K, V>,
    private val context: CoroutineContext,
    private val cacheName: String?,
) : AsyncCacheLoader<K, V> {
    override fun loadAsync(key: K): CompletionStage<out V?> =
        scope(listOf(key)).future { loader.load(key) }

    override fun loadAllAsync(keys: Collection<K>): CompletionStage<out Map<K, V>> =
        scope(keys).future { loader.loadAll(keys) }

    private fun scope(keys: Collection<K>): CoroutineScope =
        CoroutineScope(if (cacheName == null) context else context + LoadingKeys.adding(context, cacheName, keys))

    override fun toString(): String = "SuspendingAsyncLoader($loader)"
}
