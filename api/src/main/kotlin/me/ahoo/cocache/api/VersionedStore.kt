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
 * 权威的带版本副本（RFC 0005 §4.1、§4.2）：L1 是唯一副本，写入方按版本有序地写入，迟到执行的写入不会越过之后的写入。
 * 读取的规则同 [Store]（不申请 lease，L1 未命中是 [Lookup.Absent]，L1 读失败是 [Lookup.Unknown]；本地 L2 中的“不存在”在
 * `absentTtl` 与 `localMaxAge` 之内可能比 L1 旧，要 L1 此刻的答案用 [lookupRemote]）。
 *
 * 没有丢失版本的写入：只有 [putIfNewer]、[deleteIfNewer] 与运维的 [purge]。每次写入都带写入标识。
 * 值默认永不过期；墓碑另有有限的寿命（策略 `tombstoneTtl`），它要覆盖重复投递的时间窗口。
 *
 * 不供用户实现，委托除外（见 [ReadableCache]）。
 */
interface VersionedStore<K : Any, V : Any> : ReadableCache<K, V> {
    /**
     * 读取并返回值的版本。不存在（含墓碑）返回 `null`。
     *
     * @throws CacheReadException 同 [get]
     */
    fun getVersioned(key: K): Versioned<V>?

    /**
     * 远端读取：直接读 L1（唯一副本）一次，返回它此刻的答案，规则同 [Store.lookupRemote]：不读写 L2、不申请 lease、不合并，
     * L1 读取失败与读不懂的内容总是抛出。墓碑与保留版本的淘汰（`E`）都是 [Lookup.Absent]；版本不返回，要版本用 [getVersioned]。
     *
     * @throws CacheReadException L1 读取失败，或读到读不懂的内容
     */
    fun lookupRemote(key: K): Lookup.Known<V>

    /**
     * 按版本有序的写入，比较规则与结果同 [VersionedCache.putIfNewer]；按策略决定到期时间（`ttlOf` 优先，否则策略寿命）。
     *
     * @param version 这份值的版本，不能是 [Versioned.NO_VERSION]
     * @throws CacheWriteException L1 写入失败（结果可能未知；重放同一版本是安全的），或 L1 中是读不懂的内容
     */
    fun putIfNewer(key: K, value: V, version: Long): VersionedPut

    /**
     * 以指定时长按版本写入，语义同 [putIfNewer]。
     *
     * @param ttl 这份值的寿命；`null` 为策略的寿命（与不带 `ttl` 的重载相同：`ttlOf` 优先，否则策略寿命）
     */
    fun putIfNewer(key: K, value: V, version: Long, ttl: Duration?): VersionedPut

    /**
     * 按版本有序的删除：仅当 [version] 大于 L1 中的版本时写入带版本的墓碑，比较规则与结果同 [putIfNewer]。
     * 墓碑的寿命为策略 `tombstoneTtl`；到期之后迟到的旧版本可以再次写入。
     *
     * @param version 删除时的版本，不能是 [Versioned.NO_VERSION]
     * @throws CacheWriteException L1 写入失败（结果可能未知，重放同一版本是安全的）
     */
    fun deleteIfNewer(key: K, version: Long): VersionedPut

    /**
     * 以指定时长写入墓碑，语义同 [deleteIfNewer]。
     *
     * @param ttl 墓碑的寿命；`null` 为策略的墓碑寿命（与不带 `ttl` 的重载相同）
     */
    fun deleteIfNewer(key: K, version: Long, ttl: Duration?): VersionedPut

    /**
     * 运维清除：删除 key，丢掉版本栅栏。不重试。
     *
     * @throws CacheWriteException L1 写入失败
     */
    fun purge(key: K)

    override fun async(): AsyncVersionedStore<K, V>

    override fun suspending(): SuspendingVersionedStore<K, V>
}

/**
 * [VersionedStore] 的异步视图，约定见 [AsyncReadableCache]。不供用户实现，委托除外。
 */
interface AsyncVersionedStore<K : Any, V : Any> : AsyncReadableCache<K, V> {
    /**
     * 语义同 [VersionedStore.getVersioned]。
     */
    fun getVersioned(key: K): CompletableFuture<Versioned<V>?>

    /**
     * 语义同 [VersionedStore.lookupRemote]：失败以 [CacheReadException] 完成。
     */
    fun lookupRemote(key: K): CompletableFuture<Lookup.Known<V>>

    /**
     * 语义同 [VersionedStore.putIfNewer]。
     */
    fun putIfNewer(key: K, value: V, version: Long): CompletableFuture<VersionedPut>

    /**
     * 语义同 [VersionedStore.putIfNewer] (key, value, version, ttl)。
     */
    fun putIfNewer(key: K, value: V, version: Long, ttl: Duration?): CompletableFuture<VersionedPut>

    /**
     * 语义同 [VersionedStore.deleteIfNewer]。
     */
    fun deleteIfNewer(key: K, version: Long): CompletableFuture<VersionedPut>

    /**
     * 语义同 [VersionedStore.deleteIfNewer] (key, version, ttl)。
     */
    fun deleteIfNewer(key: K, version: Long, ttl: Duration?): CompletableFuture<VersionedPut>

    /**
     * 语义同 [VersionedStore.purge]。
     */
    fun purge(key: K): CompletableFuture<Void?>

    override fun sync(): VersionedStore<K, V>

    /**
     * 同一个缓存的挂起视图（同 `sync().suspending()`）。
     */
    override fun suspending(): SuspendingVersionedStore<K, V> = sync().suspending()
}

/**
 * [VersionedStore] 的挂起视图，约定见 [SuspendingReadableCache]。不供用户实现，委托除外。
 */
interface SuspendingVersionedStore<K : Any, V : Any> : SuspendingReadableCache<K, V> {
    /**
     * 语义同 [VersionedStore.getVersioned]。
     */
    suspend fun getVersioned(key: K): Versioned<V>?

    /**
     * 语义同 [VersionedStore.lookupRemote]。
     */
    suspend fun lookupRemote(key: K): Lookup.Known<V>

    /**
     * 语义同 [VersionedStore.putIfNewer]。
     */
    suspend fun putIfNewer(key: K, value: V, version: Long): VersionedPut

    /**
     * 语义同 [VersionedStore.putIfNewer] (key, value, version, ttl)。
     */
    suspend fun putIfNewer(key: K, value: V, version: Long, ttl: Duration?): VersionedPut

    /**
     * 语义同 [VersionedStore.deleteIfNewer]。
     */
    suspend fun deleteIfNewer(key: K, version: Long): VersionedPut

    /**
     * 语义同 [VersionedStore.deleteIfNewer] (key, version, ttl)。
     */
    suspend fun deleteIfNewer(key: K, version: Long, ttl: Duration?): VersionedPut

    /**
     * 语义同 [VersionedStore.purge]。
     */
    suspend fun purge(key: K)

    override fun async(): AsyncVersionedStore<K, V>

    /**
     * 回到同步 API（同一个引擎，同 `async().sync()`）。
     */
    override fun sync(): VersionedStore<K, V> = async().sync()
}
