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

@file:JvmName("SuspendingCaches")

package me.ahoo.cocache.coroutines

import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncSetCache
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.Digest
import java.time.Duration

/**
 * 缓存的挂起视图（RFC 0004 §7）：异步视图（[Cache.async]）的薄封装，与同步 API、异步视图共享同一个引擎
 * （同一个 L2、失效戳、在途加载与 lease）。方法与同步 API 同名、语义相同。
 *
 * - L2 命中不挂起：异步视图返回已完成的 future，直接取结果。
 * - 失败抛出原始异常（与同步 API 相同，不包装为 `CompletionException`）。
 * - 取消调用方协程只结束本次等待；共享的加载照常完成并填充缓存（与异步视图相同）。
 * - 不阻塞任何线程：等待都是挂起。
 *
 * 由 [suspending] 创建，不打算由第三方实现：今后新增方法不视为破坏性变化。
 */
interface SuspendingCache<K : Any, V : Any> {
    val name: String

    /**
     * 读取；未命中时经声明的数据源加载。
     */
    suspend fun get(key: K): V?

    /**
     * 读取；未命中时用 [loader] 加载，受与声明的数据源相同的保护（lease、失效戳、按 key 合并）。
     *
     * [loader] 在调用方的协程上下文去掉 `Job` 之后的上下文中启动：
     * - 在调用方的 dispatcher 上执行；没有 dispatcher，或调用方的 dispatcher 是 `runBlocking` 的事件循环时，在 `Dispatchers.Default` 上执行。
     * - `ThreadContextElement`（如 kotlinx-coroutines-slf4j 的 `MDCContext`、追踪上下文）随之传递。
     * - 它脱离结构化并发：调用方被取消、调用方的作用域结束、甚至运行时关闭，都不会取消这次加载（它可能被同一 key 的其它调用方共享），
     *   加载协程可能比它们活得更久，直到数据源返回。
     * - [loader] 自己抛出的 `CancellationException`（如其中的 `withTimeout`）转为 [SourceLoadCancelledException]。
     * - 在 [loader] 中经挂起视图读取同一缓存实例的同一 key 立即失败（`IllegalStateException`），而不是等待自己。
     *   按 key（`K`）的相等性匹配，不按编码后的存储 key：两个不相等的 key 编码成同一个存储 key 时，要等到 `load-async-timeout` 才失败。
     */
    suspend fun get(key: K, loader: suspend (K) -> V?): V?

    suspend fun getAll(keys: Collection<K>): Map<K, V>

    suspend fun lookup(key: K): Lookup<V>

    suspend fun put(key: K, value: V)

    suspend fun put(key: K, value: V, ttl: Duration)

    suspend fun putAll(entries: Map<K, V>)

    suspend fun putAll(entries: Map<K, V>, ttl: Duration)

    suspend fun invalidate(key: K)

    suspend fun invalidateAll(keys: Collection<K>)

    /**
     * 条件读取，语义同 [Cache.getIfChanged]。6.7 新增：默认抛出 [UnsupportedOperationException]，已有的实现无需改动。
     */
    suspend fun getIfChanged(key: K, known: Digest?): ConditionalGet<V> =
        throw UnsupportedOperationException("${javaClass.name} does not support getIfChanged().")

    /**
     * 条件写入，语义同 [Cache.putIfMatch]。6.7 新增：默认抛出 [UnsupportedOperationException]。
     */
    suspend fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut =
        throw UnsupportedOperationException("${javaClass.name} does not support putIfMatch().")

    /**
     * 以指定时长条件写入，语义同 [Cache.putIfMatch] (key, expected, value, ttl)。6.7 新增：默认抛出 [UnsupportedOperationException]。
     */
    suspend fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): ConditionalPut =
        throw UnsupportedOperationException("${javaClass.name} does not support putIfMatch().")

    /**
     * 按版本有序的写入，语义同 [Cache.putIfNewer]。6.8 新增：默认抛出 [UnsupportedOperationException]。
     */
    suspend fun putIfNewer(key: K, value: V, version: Long): VersionedPut =
        throw UnsupportedOperationException("${javaClass.name} does not support putIfNewer().")

    /**
     * 以指定时长按版本写入，语义同 [Cache.putIfNewer] (key, value, version, ttl)。6.8 新增：默认抛出 [UnsupportedOperationException]。
     */
    suspend fun putIfNewer(key: K, value: V, version: Long, ttl: Duration): VersionedPut =
        throw UnsupportedOperationException("${javaClass.name} does not support putIfNewer().")

    /**
     * 按版本有序的删除（墓碑），语义同 [Cache.invalidateIfNewer]。6.8 新增：默认抛出 [UnsupportedOperationException]。
     */
    suspend fun invalidateIfNewer(key: K, version: Long): VersionedPut =
        throw UnsupportedOperationException("${javaClass.name} does not support invalidateIfNewer().")

    /**
     * 以指定时长写入墓碑，语义同 [Cache.invalidateIfNewer] (key, version, ttl)。6.8 新增：默认抛出 [UnsupportedOperationException]。
     */
    suspend fun invalidateIfNewer(key: K, version: Long, ttl: Duration): VersionedPut =
        throw UnsupportedOperationException("${javaClass.name} does not support invalidateIfNewer().")

    /**
     * 读取并返回版本，语义同 [Cache.getVersioned]。6.8 新增：默认抛出 [UnsupportedOperationException]。
     */
    suspend fun getVersioned(key: K): Versioned<V>? =
        throw UnsupportedOperationException("${javaClass.name} does not support getVersioned().")

    /**
     * 同一缓存的异步视图。
     */
    fun async(): AsyncCache<K, V>
}

/**
 * 集合缓存的挂起视图，见 [SuspendingCache]。不打算由第三方实现。
 */
interface SuspendingSetCache<K : Any, E : Any> : SuspendingCache<K, Set<E>> {
    /**
     * 集合的全部成员；不存在时为空集合（与同步的 `SetCache.get` 相同，从不返回 `null`）。
     */
    override suspend fun get(key: K): Set<E>

    suspend fun add(key: K, elements: Collection<E>)

    suspend fun remove(key: K, elements: Collection<E>)

    suspend fun clear(key: K)

    override fun async(): AsyncSetCache<K, E>
}

/**
 * 缓存的挂起视图（每次调用新建一个很小的包装对象，可以缓存在字段中复用）。
 */
fun <K : Any, V : Any> Cache<K, V>.suspending(): SuspendingCache<K, V> = SuspendingCacheView(async())

/**
 * 集合缓存的挂起视图。
 */
fun <K : Any, E : Any> SetCache<K, E>.suspending(): SuspendingSetCache<K, E> = SuspendingSetCacheView(async())

/**
 * 异步视图的挂起视图。
 */
fun <K : Any, V : Any> AsyncCache<K, V>.suspending(): SuspendingCache<K, V> = SuspendingCacheView(this)

/**
 * 集合缓存异步视图的挂起视图。
 */
fun <K : Any, E : Any> AsyncSetCache<K, E>.suspending(): SuspendingSetCache<K, E> = SuspendingSetCacheView(this)
