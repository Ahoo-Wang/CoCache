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
 * [Cache] 的异步视图（RFC 0004）：与同步 API 共享同一个引擎的 L2、失效戳、在途加载与 lease，
 * 读写语义（栅栏、失败模式、负缓存）与同名的同步方法完全相同。
 *
 * - 所有方法都不同步抛出：失败以原始异常（不包装为 `CompletionException`）异常完成返回的 future。
 *   注意 JDK 的 `join()` / `get()` 与依赖阶段（`thenApply` 等）仍会按 JDK 的规则包装。
 * - L2 命中时返回已完成的 future；其余情况 future 在调用方线程（结果已同步可得时）或异步执行器上完成，
 *   从不在 L1 客户端的 I/O 线程上完成。
 * - 每次调用返回一个新的 future：取消它只影响本次调用，不取消共享的加载。
 *
 * @param K 业务 key
 * @param V 缓存值
 */
interface AsyncCache<K : Any, V : Any> {
    /**
     * 缓存名。
     */
    val name: String

    /**
     * 读穿透，语义同 [Cache.get]。不存在（含负缓存）时以 `null` 完成。
     */
    fun get(key: K): CompletableFuture<V?>

    /**
     * 读穿透，未命中时用 [loader] 回源，语义同 [Cache.get] (key, loader)。
     */
    fun get(key: K, loader: AsyncCacheLoader<K, V>): CompletableFuture<V?>

    /**
     * 批量读穿透，语义同 [Cache.getAll]。
     */
    fun getAll(keys: Collection<K>): CompletableFuture<Map<K, V>>

    /**
     * 语义同 [Cache.lookup]。
     */
    fun lookup(key: K): CompletableFuture<Lookup<V>>

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

    /**
     * 回到同步 API（同一个引擎）。
     */
    fun sync(): Cache<K, V>
}
