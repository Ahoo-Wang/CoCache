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

import java.util.concurrent.CompletableFuture

/**
 * 可读的两级一致性缓存：进程内 L2 → 共享 L1（→ 数据源）。五种用户 API 类型的公共父类型，只声明读取（RFC 0005 §3.1）。
 *
 * 选择类型只问一个问题：L1 丢了这个 key，本进程能否按需从别处重建它？
 * - 能：派生副本 [Cache]，或带版本的 [VersionedCache]；
 * - 不能：权威副本 [Store]、带版本的 [VersionedStore]、集合 [SetStore]。
 *
 * 读取的结果是 [Lookup.Present] / [Lookup.Absent] / [Lookup.Unknown]。`Unknown` 只表示故障（L1 读失败、命令超时）：
 * [get] / [getAll] 遇到它抛出 [CacheReadException]，[lookup] / [lookupAll] 返回它；它不进入任何一层，也不广播。
 * 读不懂的 L1 内容不是故障：没有数据源可以纠正时（权威副本、外部来源的读取）总是抛出 [CacheReadException]，`lookup` 也一样。
 *
 * 不供用户实现，委托除外。8.x 新增的操作只会是新的能力类型上的方法，或用已有抽象方法实现的默认方法（RFC 0005 §4.2，U13），
 * 因此手写的实现类在 8.x 内可能编译失败或遇到 `AbstractMethodError`。继承本接口、由代理实现的声明接口（`@CoCache` 接口）不受影响。
 *
 * @param K 业务 key
 * @param V 缓存值
 */
interface ReadableCache<K : Any, V : Any> {
    /**
     * 缓存名。
     */
    val name: String

    /**
     * 读取：L2 → L1（→ 数据源）。不存在（含负缓存）返回 `null`。
     *
     * @throws CacheReadException 结果为 [Lookup.Unknown]，或读到读不懂的内容
     */
    operator fun get(key: K): V?

    /**
     * 批量读取：L1 一次批量读取（有数据源时一次批量回源），并与并发的单 key 读取共享在途加载。结果只包含存在的 key。
     *
     * 部分 key 为 [Lookup.Unknown] 时，先完成其余 key（释放 lease 或写回），再抛出 [CacheReadException]：
     * [CacheReadException.unknownKeys] 是这些 key，[CacheReadException.partialResult] 是其余存在的 key 的值。
     * 需要逐 key 处理的调用方用 [lookupAll]。
     *
     * @throws CacheReadException 部分或全部 key 为 [Lookup.Unknown]，或读到读不懂的内容
     */
    fun getAll(keys: Collection<K>): Map<K, V>

    /**
     * 读取并区分“存在”“确认不存在”与“这一次读取没有答案”（[Lookup.Unknown]，L1 故障）。
     *
     * 跟随者等待超时（`load.timeout`）、回源被拒绝不是 L1 故障，照常抛出相应异常，不返回 `Unknown`。
     */
    fun lookup(key: K): Lookup<V>

    /**
     * 批量的 [lookup]：结果包含每个不重复的 key。L1 故障只让受影响的 key 为 [Lookup.Unknown]，不抛出。
     */
    fun lookupAll(keys: Collection<K>): Map<K, Lookup<V>>

    /**
     * 同一个缓存的异步视图（RFC 0004）：共享 L2、失效戳、在途加载与 lease，每次返回同一个对象。
     */
    fun async(): AsyncReadableCache<K, V>

    /**
     * 同一个缓存的挂起视图，与异步视图共享同一个引擎，每次返回同一个对象。
     */
    fun suspending(): SuspendingReadableCache<K, V>
}

/**
 * [ReadableCache] 的异步视图（RFC 0004）：与同步 API 共享同一个引擎的 L2、失效戳、在途加载与 lease，
 * 读写语义（栅栏、失败模式、负缓存）与同名的同步方法完全相同。
 *
 * - 所有方法都不同步抛出：失败以原始异常（不包装为 `CompletionException`）异常完成返回的 future。
 *   注意 JDK 的 `join()` / `get()` 与依赖阶段（`thenApply` 等）仍会按 JDK 的规则包装。
 * - L2 命中时返回已完成的 future；其余情况 future 在调用方线程（结果已同步可得时）或运行时的调度器上完成，
 *   从不在 L1 客户端的 I/O 线程上完成。
 * - 每次调用返回一个新的 future：取消它只影响本次调用，不取消共享的加载。
 *
 * 不供用户实现，委托除外（见 [ReadableCache]）。
 */
interface AsyncReadableCache<K : Any, V : Any> {
    /**
     * 缓存名。
     */
    val name: String

    /**
     * 语义同 [ReadableCache.get]。不存在（含负缓存）时以 `null` 完成，`Unknown` 时以 [CacheReadException] 完成。
     */
    fun get(key: K): CompletableFuture<V?>

    /**
     * 语义同 [ReadableCache.getAll]。
     */
    fun getAll(keys: Collection<K>): CompletableFuture<Map<K, V>>

    /**
     * 语义同 [ReadableCache.lookup]。
     */
    fun lookup(key: K): CompletableFuture<Lookup<V>>

    /**
     * 语义同 [ReadableCache.lookupAll]。
     */
    fun lookupAll(keys: Collection<K>): CompletableFuture<Map<K, Lookup<V>>>

    /**
     * 回到同步 API（同一个引擎）。
     */
    fun sync(): ReadableCache<K, V>
}

/**
 * [ReadableCache] 的挂起视图：与同步 API、异步视图共享同一个引擎，方法与同步 API 同名、语义相同。
 *
 * - L2 命中不挂起。
 * - 失败抛出原始异常（与同步 API 相同，不包装为 `CompletionException`）。
 * - 取消调用方协程只结束本次等待；共享的加载照常完成并填充缓存。
 * - 不阻塞任何线程：等待都是挂起。
 *
 * 不供用户实现，委托除外（见 [ReadableCache]）。
 */
interface SuspendingReadableCache<K : Any, V : Any> {
    /**
     * 缓存名。
     */
    val name: String

    /**
     * 语义同 [ReadableCache.get]。
     */
    suspend fun get(key: K): V?

    /**
     * 语义同 [ReadableCache.getAll]。
     */
    suspend fun getAll(keys: Collection<K>): Map<K, V>

    /**
     * 语义同 [ReadableCache.lookup]。
     */
    suspend fun lookup(key: K): Lookup<V>

    /**
     * 语义同 [ReadableCache.lookupAll]。
     */
    suspend fun lookupAll(keys: Collection<K>): Map<K, Lookup<V>>

    /**
     * 同一个缓存的异步视图。
     */
    fun async(): AsyncReadableCache<K, V>
}
