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

package me.ahoo.cocache.api

import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 派生副本（RFC 0005 §4.1）：L1 丢了一个 key，本进程能按需从别处重建它。
 *
 * 数据来源必须在声明中写明：spec 的数据源（`loader` / `asyncLoader`），或 `externallySourced()`，即由调用方经 [put]、
 * Spring Cache 或调用级 loader（[get] (key, loader)）提供。
 *
 * - 有数据源：未命中时回源（受 lease 与失效戳保护），L1 读失败默认降级回源；显式 `readFailure = THROW` 时为 [Lookup.Unknown]。
 * - 外部来源：L1 未命中是“未填充”，带标志、受戳保护进入 L2；不带 loader 的读取看到 [Lookup.Absent]，带 loader 的调用方不接受它，
 *   用自己的 loader 回源。不带 loader 的读取遇到 L1 读失败为 [Lookup.Unknown]（声明 `unknownRead = ABSENT` 时为 `Absent`）。
 * - [invalidate] 失败默认进入重试队列（删除副本在任何时刻都安全）。
 *
 * 不供用户实现，委托除外（见 [ReadableCache]）。
 */
interface Cache<K : Any, V : Any> : ReadableCache<K, V> {
    /**
     * 读取，未命中时用 [loader] 回源（代替声明中的数据源）。
     *
     * 与 [get] 走同一条受 lease 与失效戳保护的路径：回源结果只能凭 lease 写回 L1，期间发生的失效不会被吞没。
     * 供 Spring `@Cacheable(sync = true)` 等“调用方提供加载逻辑”的场景使用。L1 读失败时由 `readFailure` 决定（默认降级回源）。
     *
     * @throws CacheReadException `readFailure = THROW` 时 L1 读失败
     */
    fun get(key: K, loader: CacheLoader<K, V>): V?

    /**
     * 写穿透到 L1 并广播失效，按策略决定到期时间；原子地使所有在途填充失效。后写者胜，不推荐：首选先提交数据源再 [invalidate]。
     *
     * 写入不填充本地 L2：下一次读取从 L1 填充，保证 L2 与 L1 的写入顺序一致。
     *
     * 结果未知的失败（超时、应答途中断线），在失效失败模式为 RETRY（默认）时不抛出：引擎登记这个 key 的补偿失效（与失败的
     * [invalidate] 共用重试），key 收敛到“从数据源重新加载”（RFC 0005 §4.6）。
     *
     * @throws CacheWriteException L1 写入确定未执行（[CacheWriteException.outcomeUnknown] 为 `false`），或失效失败模式为 THROW 时的任何失败
     */
    fun put(key: K, value: V)

    /**
     * 以指定时长写入，语义同 [put]。
     */
    fun put(key: K, value: V, ttl: Duration)

    /**
     * 批量写入，每个 key 的语义同 [put]：寿命逐个按 [put] 计算（各自独立抖动），不填充本地 L2。
     *
     * 内置引擎把全部 key 放进一次写入：先对全部 key 递增失效戳并淘汰 L2，再批量写入 L1 并广播一次，最后再递增并淘汰，
     * 因此任何在途填充都不会把旧值写回。L1 只保证每个 key 原子，不保证跨 key 原子（Cluster 按 slot 分组执行）。
     * [entries] 为空时不做任何事。
     *
     * @throws CacheWriteException 同 [put]：确定未执行，或失效失败模式为 THROW 时的任何失败（部分 key 可能已生效）
     */
    fun putAll(entries: Map<K, V>)

    /**
     * 以同一时长批量写入，语义同 [putAll]。
     */
    fun putAll(entries: Map<K, V>, ttl: Duration)

    /**
     * 失效两级缓存并广播。必须在数据源提交之后调用（事务内应登记到提交之后）。
     *
     * L1 写入失败时默认进入重试队列而不抛出：删除副本在任何时刻都安全。显式 `invalidateFailure = THROW` 时抛出 [CacheWriteException]。
     */
    fun invalidate(key: K)

    /**
     * 批量失效，语义同 [invalidate]。
     */
    fun invalidateAll(keys: Collection<K>)

    override fun async(): AsyncCache<K, V>

    override fun suspending(): SuspendingCache<K, V>
}

/**
 * [Cache] 的异步视图，约定见 [AsyncReadableCache]。不供用户实现，委托除外。
 */
interface AsyncCache<K : Any, V : Any> : AsyncReadableCache<K, V> {
    /**
     * 读取，未命中时用 [loader] 回源，语义同 [Cache.get] (key, loader)。
     */
    fun get(key: K, loader: AsyncCacheLoader<K, V>): CompletableFuture<V?>

    /**
     * 写入，语义同 [Cache.put]。失败时以 [CacheWriteException] 完成；完成时本地 L2 已淘汰（读己之写）。
     */
    fun put(key: K, value: V): CompletableFuture<Void?>

    /**
     * 以指定时长写入，语义同 [Cache.put] (key, value, ttl)。
     */
    fun put(key: K, value: V, ttl: Duration): CompletableFuture<Void?>

    /**
     * 批量写入，语义同 [Cache.putAll]。
     */
    fun putAll(entries: Map<K, V>): CompletableFuture<Void?>

    /**
     * 以同一时长批量写入，语义同 [Cache.putAll] (entries, ttl)。
     */
    fun putAll(entries: Map<K, V>, ttl: Duration): CompletableFuture<Void?>

    /**
     * 失效，语义同 [Cache.invalidate]：L1 失败时默认登记重试并正常完成，配置为抛出时以 [CacheWriteException] 完成。
     */
    fun invalidate(key: K): CompletableFuture<Void?>

    /**
     * 批量失效，语义同 [Cache.invalidateAll]。
     */
    fun invalidateAll(keys: Collection<K>): CompletableFuture<Void?>

    override fun sync(): Cache<K, V>

    /**
     * 同一个缓存的挂起视图（同 `sync().suspending()`）。
     */
    override fun suspending(): SuspendingCache<K, V> = sync().suspending()
}

/**
 * [Cache] 的挂起视图，约定见 [SuspendingReadableCache]。不供用户实现，委托除外。
 */
interface SuspendingCache<K : Any, V : Any> : SuspendingReadableCache<K, V> {
    /**
     * 读取，未命中时用 [loader] 回源，受与声明的数据源相同的保护（lease、失效戳、按 key 合并），语义同 [Cache.get] (key, loader)。
     *
     * [loader] 在这次加载的协程中直接调用，带着调用方协程上下文中的元素，但不带调用方的 `Job` 与调度器：
     * - 它运行在运行时的执行器（`CacheRuntime.executor`）上：加载之前的 L1 读取通常已经挂起过，所以不要指望它在调用方线程上；
     *   设置了 `LoadObserver`（如链路追踪）时，它在观测线程（`cocache-observe-N`）上开始。阻塞调用请包在 `withContext(Dispatchers.IO)` 中。
     * - `ThreadContextElement`（如 MDC、追踪上下文）随之传递。
     * - 它脱离结构化并发：调用方被取消不会取消这次加载（它可能被同一 key 的其它调用方共享）。
     * - [loader] 自己抛出的 `CancellationException`（如其中的 `withTimeout`）按回源失败处理。
     * - 在 [loader] 中经挂起视图读取同一缓存实例的同一 key 立即失败（`IllegalStateException`），而不是等待自己。
     */
    suspend fun get(key: K, loader: suspend (K) -> V?): V?

    /**
     * 语义同 [Cache.put]。
     */
    suspend fun put(key: K, value: V)

    /**
     * 语义同 [Cache.put] (key, value, ttl)。
     */
    suspend fun put(key: K, value: V, ttl: Duration)

    /**
     * 语义同 [Cache.putAll]。
     */
    suspend fun putAll(entries: Map<K, V>)

    /**
     * 语义同 [Cache.putAll] (entries, ttl)。
     */
    suspend fun putAll(entries: Map<K, V>, ttl: Duration)

    /**
     * 语义同 [Cache.invalidate]。
     */
    suspend fun invalidate(key: K)

    /**
     * 语义同 [Cache.invalidateAll]。
     */
    suspend fun invalidateAll(keys: Collection<K>)

    override fun async(): AsyncCache<K, V>

    /**
     * 回到同步 API（同一个引擎，同 `async().sync()`）。
     */
    override fun sync(): Cache<K, V> = async().sync()
}
