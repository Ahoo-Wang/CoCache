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
 * 权威集合（RFC 0005 §4.1）：L1 是唯一副本，元素增删在 L1 中原子完成，并由 L1 串行化。
 *
 * - 没有数据源：未命中即空集合，[get] 返回空集合，[lookup] 返回 [Lookup.Absent]；空集合与不存在等价。
 * - L1 读失败为 [Lookup.Unknown]（[get] / [getAll] 抛出 [CacheReadException]）；不允许 `unknownRead`。
 * - 写入全部同步完成或抛出 [CacheWriteException]，从不排队重试；没有 `invalidate`，删除数据用 [clear]。
 * - 元素写入在结果未知时按“每个元素后写者胜”收敛，没有有序的重放方式。
 * - 数据持久性等于 L1 的持久性：L1 不得使用会驱逐 key 的内存策略（如 Redis `allkeys-*`）。
 *
 * 数据权威在数据源中的集合，应声明为普通的 `Cache<K, Set<E>>` 并用 `invalidate` 维护。
 *
 * 不供用户实现，委托除外（见 [ReadableCache]）。
 */
interface SetStore<K : Any, E : Any> : ReadableCache<K, Set<E>> {
    /**
     * 读取集合；不存在时返回空集合。
     *
     * @throws CacheReadException L1 读失败，或读到读不懂的内容
     */
    override fun get(key: K): Set<E>

    /**
     * 原子地加入元素；key 不存在时创建。[elements] 为空时不做任何事。
     *
     * @throws CacheWriteException L1 写入失败，或 key 中是读不懂的内容
     */
    fun add(key: K, elements: Collection<E>)

    /**
     * 原子地移除元素；移空时删除 key。key 不存在时不做任何事。
     *
     * @throws CacheWriteException L1 写入失败，或 key 中是读不懂的内容
     */
    fun remove(key: K, elements: Collection<E>)

    /**
     * 删除整个集合并广播。
     *
     * @throws CacheWriteException L1 写入失败
     */
    fun clear(key: K)

    /**
     * 整体覆盖写入集合，按策略决定到期时间（默认永不过期）；空集合等价于 [clear]。
     *
     * @throws CacheWriteException L1 写入失败
     */
    fun put(key: K, value: Set<E>)

    /**
     * 以指定时长整体覆盖写入，语义同 [put]。
     */
    fun put(key: K, value: Set<E>, ttl: Duration)

    /**
     * 批量整体覆盖写入，每个 key 的语义同 [put]。
     */
    fun putAll(entries: Map<K, Set<E>>)

    /**
     * 以同一时长批量整体覆盖写入，语义同 [putAll]。
     */
    fun putAll(entries: Map<K, Set<E>>, ttl: Duration)

    override fun async(): AsyncSetStore<K, E>

    override fun suspending(): SuspendingSetStore<K, E>
}

/**
 * [SetStore] 的异步视图，约定见 [AsyncReadableCache]。[get] 总以非 `null` 的集合完成（不存在为空集合）。
 * 不供用户实现，委托除外。
 */
interface AsyncSetStore<K : Any, E : Any> : AsyncReadableCache<K, Set<E>> {
    /**
     * 语义同 [SetStore.add]。
     */
    fun add(key: K, elements: Collection<E>): CompletableFuture<Void?>

    /**
     * 语义同 [SetStore.remove]。
     */
    fun remove(key: K, elements: Collection<E>): CompletableFuture<Void?>

    /**
     * 语义同 [SetStore.clear]。
     */
    fun clear(key: K): CompletableFuture<Void?>

    /**
     * 语义同 [SetStore.put]。
     */
    fun put(key: K, value: Set<E>): CompletableFuture<Void?>

    /**
     * 语义同 [SetStore.put] (key, value, ttl)。
     */
    fun put(key: K, value: Set<E>, ttl: Duration): CompletableFuture<Void?>

    /**
     * 语义同 [SetStore.putAll]。
     */
    fun putAll(entries: Map<K, Set<E>>): CompletableFuture<Void?>

    /**
     * 语义同 [SetStore.putAll] (entries, ttl)。
     */
    fun putAll(entries: Map<K, Set<E>>, ttl: Duration): CompletableFuture<Void?>

    override fun sync(): SetStore<K, E>

    /**
     * 同一个缓存的挂起视图（同 `sync().suspending()`）。
     */
    override fun suspending(): SuspendingSetStore<K, E> = sync().suspending()
}

/**
 * [SetStore] 的挂起视图，约定见 [SuspendingReadableCache]。不供用户实现，委托除外。
 */
interface SuspendingSetStore<K : Any, E : Any> : SuspendingReadableCache<K, Set<E>> {
    /**
     * 集合的全部成员；不存在时为空集合（与 [SetStore.get] 相同，从不返回 `null`）。
     */
    override suspend fun get(key: K): Set<E>

    /**
     * 语义同 [SetStore.add]。
     */
    suspend fun add(key: K, elements: Collection<E>)

    /**
     * 语义同 [SetStore.remove]。
     */
    suspend fun remove(key: K, elements: Collection<E>)

    /**
     * 语义同 [SetStore.clear]。
     */
    suspend fun clear(key: K)

    /**
     * 语义同 [SetStore.put]。
     */
    suspend fun put(key: K, value: Set<E>)

    /**
     * 语义同 [SetStore.put] (key, value, ttl)。
     */
    suspend fun put(key: K, value: Set<E>, ttl: Duration)

    /**
     * 语义同 [SetStore.putAll]。
     */
    suspend fun putAll(entries: Map<K, Set<E>>)

    /**
     * 语义同 [SetStore.putAll] (entries, ttl)。
     */
    suspend fun putAll(entries: Map<K, Set<E>>, ttl: Duration)

    override fun async(): AsyncSetStore<K, E>

    /**
     * 回到同步 API（同一个引擎，同 `async().sync()`）。
     */
    override fun sync(): SetStore<K, E> = async().sync()
}
