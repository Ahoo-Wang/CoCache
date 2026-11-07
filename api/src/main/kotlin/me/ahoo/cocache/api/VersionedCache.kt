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
 * 派生的带版本副本（RFC 0005 §4.1、§4.2）：数据源带版本（`versionedLoader`），写入方按版本有序地推送（[putIfNewer]）。
 * L1 中的副本本身就是写入顺序的仲裁者，所以这个类型上没有丢失版本的写入：没有 `put`、普通的 `invalidate` 与条件写入，
 * 删除副本用保留版本栅栏的 [evict]，运维清除用 [purge]。
 *
 * 数据源以 `Versioned(null, w)` 表达“在版本 w 时已删除”（写成带版本的墓碑）；返回 `null` 表示数据源中从来没有这个 key
 * （写成没有版本的负缓存）。不带版本的 `null` 落在带版本的内容上（如 [evict] 之后）一律被拒绝：已删除的实体在 `E` 到期之前
 * 每次未命中都回源，没有负缓存；这种情形计入 `cocache.fill.unversioned.absent` 并在第一次时告警（K16）。
 *
 * 不供用户实现，委托除外（见 [ReadableCache]）。
 */
interface VersionedCache<K : Any, V : Any> : ReadableCache<K, V> {
    /**
     * 读取（同 [get]：L2 → L1 → 数据源），同时返回值的版本。不存在（含负缓存与墓碑）返回 `null`；
     * 数据源给出的没有版本的值的版本是 [Versioned.NO_VERSION]。
     *
     * @throws CacheReadException 同 [get]
     */
    fun getVersioned(key: K): Versioned<V>?

    /**
     * 按版本有序的写入：仅当 [version] 大于 L1 中的版本时写入 [value]，按策略决定到期时间（`ttlOf` 优先，否则策略寿命加抖动）。
     * 比较与写入在 L1 中原子完成（Redis 后端是一个 Lua 脚本，一次往返）。
     *
     * - 小于 L1 中的版本：迟到的旧版本被拒绝，返回 [VersionedPut.Stale]，不写入、不广播。
     * - 等于：重复投递，不写入，返回 [VersionedPut.Stale]，但仍广播：这个版本可能是不广播的填充写入的，别的节点还持有更旧的副本。
     * - L1 中没有版本的内容（不带版本的负缓存、lease 占位、不存在）排在任何版本之前：总是写入。
     * - 写入时销毁 lease 与刷新令牌，在途填充因此写不回（哪怕它读到的版本更高）；不填充本地 L2。
     *
     * L1 中是读不懂的内容时抛出 [CacheWriteException]（`cause` 为 `ForeignFormatException`），不修改 L1。
     *
     * @param version 这份值的版本，不能是 [Versioned.NO_VERSION]
     * @throws CacheWriteException L1 写入失败（结果可能未知；重放同一版本是安全的）
     */
    fun putIfNewer(key: K, value: V, version: Long): VersionedPut

    /**
     * 以指定时长按版本写入，语义同 [putIfNewer]。
     *
     * @param ttl 这份值的寿命；`null` 为策略的寿命（与不带 `ttl` 的重载相同：`ttlOf` 优先，否则策略寿命加抖动），
     *   调用方因此不必为“有没有给出寿命”各写一个分支
     */
    fun putIfNewer(key: K, value: V, version: Long, ttl: Duration?): VersionedPut

    /**
     * 按版本有序的删除：仅当 [version] 大于 L1 中的版本时写入一个带版本的墓碑（负缓存 + 版本），比较规则与结果同 [putIfNewer]。
     *
     * 墓碑对读取就是不存在（不回源），并挡住迟到的旧版本。寿命是墓碑寿命（策略 `tombstoneTtl`，未设置时为值的寿命）；
     * 到期之后迟到的旧版本可以再次写入，因此墓碑的寿命要覆盖重复投递的时间窗口。
     *
     * 名称按类型的角色：派生副本的删除叫 `invalidate`（数据源可以重建它），权威副本的叫 `delete`（[VersionedStore.deleteIfNewer]）。
     *
     * @param version 删除时的版本，不能是 [Versioned.NO_VERSION]
     * @throws CacheWriteException L1 写入失败（结果可能未知，重放同一版本是安全的）
     */
    fun invalidateIfNewer(key: K, version: Long): VersionedPut

    /**
     * 以指定时长写入墓碑，语义同 [invalidateIfNewer]。
     *
     * @param ttl 墓碑的寿命；`null` 为策略的墓碑寿命（与不带 `ttl` 的重载相同）
     */
    fun invalidateIfNewer(key: K, version: Long, ttl: Duration?): VersionedPut

    /**
     * 保留版本栅栏的失效：L1 中带版本的内容换成状态 `E(当前版本)`，读作未命中；之后的回源与 [putIfNewer] 都必须不低于这个版本，
     * 迟到的旧版本仍被拒绝。L1 中没有版本的内容照常删除。`E` 的寿命同墓碑。
     *
     * 在 loader 追上之前，`evict` 之后读到的是 loader 的版本（版本被拒绝时只以降级限龄进入 L2）。
     *
     * @throws CacheWriteException L1 写入失败（不重试：重试的删除会丢掉版本），或 L1 中是读不懂的内容、权威标量
     */
    fun evict(key: K)

    /**
     * 运维清除：删除 key，丢掉版本栅栏，之后任何版本都能写入。不重试。
     *
     * @throws CacheWriteException L1 写入失败
     */
    fun purge(key: K)

    override fun async(): AsyncVersionedCache<K, V>

    override fun suspending(): SuspendingVersionedCache<K, V>
}

/**
 * [VersionedCache] 的异步视图，约定见 [AsyncReadableCache]。不供用户实现，委托除外。
 */
interface AsyncVersionedCache<K : Any, V : Any> : AsyncReadableCache<K, V> {
    /**
     * 语义同 [VersionedCache.getVersioned]。不存在时以 `null` 完成。
     */
    fun getVersioned(key: K): CompletableFuture<Versioned<V>?>

    /**
     * 语义同 [VersionedCache.putIfNewer]。写入完成时本地 L2 已淘汰（读己之写）。
     */
    fun putIfNewer(key: K, value: V, version: Long): CompletableFuture<VersionedPut>

    /**
     * 语义同 [VersionedCache.putIfNewer] (key, value, version, ttl)。
     */
    fun putIfNewer(key: K, value: V, version: Long, ttl: Duration?): CompletableFuture<VersionedPut>

    /**
     * 语义同 [VersionedCache.invalidateIfNewer]。
     */
    fun invalidateIfNewer(key: K, version: Long): CompletableFuture<VersionedPut>

    /**
     * 语义同 [VersionedCache.invalidateIfNewer] (key, version, ttl)。
     */
    fun invalidateIfNewer(key: K, version: Long, ttl: Duration?): CompletableFuture<VersionedPut>

    /**
     * 语义同 [VersionedCache.evict]。
     */
    fun evict(key: K): CompletableFuture<Void?>

    /**
     * 语义同 [VersionedCache.purge]。
     */
    fun purge(key: K): CompletableFuture<Void?>

    override fun sync(): VersionedCache<K, V>
}

/**
 * [VersionedCache] 的挂起视图，约定见 [SuspendingReadableCache]。不供用户实现，委托除外。
 */
interface SuspendingVersionedCache<K : Any, V : Any> : SuspendingReadableCache<K, V> {
    /**
     * 语义同 [VersionedCache.getVersioned]。
     */
    suspend fun getVersioned(key: K): Versioned<V>?

    /**
     * 语义同 [VersionedCache.putIfNewer]。
     */
    suspend fun putIfNewer(key: K, value: V, version: Long): VersionedPut

    /**
     * 语义同 [VersionedCache.putIfNewer] (key, value, version, ttl)。
     */
    suspend fun putIfNewer(key: K, value: V, version: Long, ttl: Duration?): VersionedPut

    /**
     * 语义同 [VersionedCache.invalidateIfNewer]。
     */
    suspend fun invalidateIfNewer(key: K, version: Long): VersionedPut

    /**
     * 语义同 [VersionedCache.invalidateIfNewer] (key, version, ttl)。
     */
    suspend fun invalidateIfNewer(key: K, version: Long, ttl: Duration?): VersionedPut

    /**
     * 语义同 [VersionedCache.evict]。
     */
    suspend fun evict(key: K)

    /**
     * 语义同 [VersionedCache.purge]。
     */
    suspend fun purge(key: K)

    override fun async(): AsyncVersionedCache<K, V>
}
