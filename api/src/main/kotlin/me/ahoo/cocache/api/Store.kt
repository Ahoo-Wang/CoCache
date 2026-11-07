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

import me.ahoo.cocache.api.spi.Digest
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 权威副本（RFC 0005 §4.1）：L1 是唯一副本，本进程无法按需重建它。没有数据源，读取不申请 lease。
 *
 * - L1 中不存在（含 lease 占位）就是 [Lookup.Absent]，受戳保护进入 L2（限龄 `absentTtl` 与 `localMaxAge`）：[lookup] 与 [get]
 *   可能由这份本地的“不存在”回答，失效丢失时陈旧至多这么久。要 L1（唯一副本）此刻的答案用 [lookupRemote]。
 * - L1 读失败为 [Lookup.Unknown]（声明 `unknownRead = ABSENT` 时为 `Absent`，不写任何一层）；读不懂的内容总是抛出 [CacheReadException]。
 * - 每次写入都带写入标识与载荷的摘要段（SHA-256）；读取方重算摘要，与头部不一致或缺少摘要段按损坏处理。
 * - [delete] 与 [deleteIfMatch] 同步完成，失败抛出 [CacheWriteException]，从不排队重试：延迟的删除会越过之后的写入。
 * - 默认永不过期；L2 的默认寿命更短（`localMaxAge` 30 s、`absentTtl` 10 s）：失效丢失时没有回源可以纠正，陈旧的上界只剩 L2。
 * - 数据持久性等于 L1 的持久性：L1 不得使用会驱逐 key 的内存策略（如 Redis `allkeys-*`）。
 *
 * 不供用户实现，委托除外（见 [ReadableCache]）。
 */
interface Store<K : Any, V : Any> : ReadableCache<K, V> {
    /**
     * 写入 L1 并广播失效，按策略决定到期时间（`ttlOf` 优先，否则策略寿命，默认永不过期）；原子地使在途填充失效。
     * 写入不填充本地 L2：下一次读取从 L1 填充。
     *
     * @throws CacheWriteException L1 写入失败（[CacheWriteException.outcomeUnknown] 区分“确定未执行”与“结果未知”）
     */
    fun put(key: K, value: V)

    /**
     * 以指定时长写入，语义同 [put]。
     */
    fun put(key: K, value: V, ttl: Duration)

    /**
     * 批量写入，每个 key 的语义同 [put]，写入顺序同 [Cache.putAll]。[entries] 为空时不做任何事。
     *
     * @throws CacheWriteException L1 写入失败（部分 key 可能已生效）
     */
    fun putAll(entries: Map<K, V>)

    /**
     * 以同一时长批量写入，语义同 [putAll]。
     */
    fun putAll(entries: Map<K, V>, ttl: Duration)

    /**
     * 删除并广播，同步完成。失败时抛出，从不排队重试：唯一副本上延迟的删除会删掉失败之后写入的值。
     * 需要可靠删除的写入方用 [VersionedStore.deleteIfNewer]：版本严格递增，迟到执行不会越过之后的写入。
     *
     * @throws CacheWriteException L1 写入失败
     */
    fun delete(key: K)

    /**
     * 条件删除：仅当 L1 中的值的摘要等于 [expected] 时删除并广播；否则返回 [ConditionalDelete.Conflict]（附当前摘要），不修改、不广播。
     * 比较与删除在 L1 中原子完成。
     *
     * 结果未知（超时）的 `deleteIfMatch` 迟到执行时，可能删除之后重新写入的相同内容（ABA，RFC 0005 §4.6）：需要顺序的写入方用 [VersionedStore]。
     *
     * @throws CacheWriteException L1 写入失败，或 L1 中是读不懂的内容
     */
    fun deleteIfMatch(key: K, expected: Digest): ConditionalDelete

    /**
     * 远端读取：直接读 L1（唯一副本）一次，返回它此刻的答案，[Lookup.Present] 或 [Lookup.Absent]（不存在、lease 占位、负缓存）。
     *
     * - 不读也不写本地 L2，不申请 lease，不与并发的读取合并（每次调用一次 L1 读取），时限同其它读取（`readTimeout`）。
     * - 不受 `unknownRead` 影响：L1 读取失败时总是抛出，从不返回 [Lookup.Unknown]。读不懂的内容（包括摘要校验失败）同样抛出。
     * - 到期时间是 L1 中这份值的剩余寿命（`null` 为永不过期）；不存在时为读取的时刻。
     *
     * 与 [lookup] 的区别：`lookup` 先读 L2，L2 中的副本（含不存在的副本）在 `localMaxAge`、`absentTtl` 之内可能比 L1 旧；
     * 需要唯一副本此刻答案的读取（撤销检查、读之后马上要按它决策）用本方法。与 `getIfChanged(key, null)` 的区别：不返回摘要，
     * 只回答存在与否和值。值同样要带有效的摘要段：缺少摘要段或与载荷不一致按读不懂的内容抛出（同 [get]）。
     *
     * @throws CacheReadException L1 读取失败，或读到读不懂的内容
     */
    fun lookupRemote(key: K): Lookup.Known<V>

    /**
     * 条件读取：直接读 L1（唯一副本），确认调用方已有的值是否仍是 L1 中的那一份。
     *
     * - L1 中的值的摘要段等于 [known] 时返回 [ConditionalGet.Unchanged]：后端只读头部比较，不传输正文，成本与值的大小无关。
     * - 否则返回 [ConditionalGet.Present]（值与新摘要，摘要由读取方重算校验）或 [ConditionalGet.Absent]（不存在、lease 占位、负缓存）。
     *   [known] 为 `null` 时就是一次绕过 L2、带摘要的读取，供 [putIfMatch] 的“读—改—写”使用。
     *
     * 不读也不写本地 L2，不申请 lease，不修改 L1。不受 `unknownRead` 影响：L1 读取失败时总是抛出。
     * 读不懂的内容（不是本格式写入的内容、更高格式写入的内容、集合、没有摘要段的值、带版本的内容、解码失败）总是抛出。
     *
     * @param known 调用方已有的值的摘要（上一次 [getIfChanged] 或 [putIfMatch] 返回的）；`null` 表示没有
     * @throws CacheReadException L1 读取失败，或读到读不懂的内容
     */
    fun getIfChanged(key: K, known: Digest?): ConditionalGet<V>

    /**
     * 条件写入：仅当 L1 中的当前内容符合 [expected] 时写入 [value]，按策略决定到期时间（同 [put]）。比较与写入在 L1 中原子完成。
     *
     * - [expected] 为 `null`：仅当 L1 中没有值（不存在、lease 占位、负缓存）时写入。
     * - 否则：仅当 L1 中的值的摘要等于 [expected] 时写入。
     * - 不符合时返回 [ConditionalPut.Conflict]（附当前摘要），不写入、不广播、不改变到期时间：调用方用 [getIfChanged] 重读后重试。
     *
     * 摘要相等只能证明已写入，不能证明未写入：结果未知的写入迟到执行时可能匹配上之后重新写入的同一内容（ABA）。
     *
     * @param expected L1 中应有的值的摘要（[getIfChanged] 返回的）；`null` 表示应当没有值
     * @return 写入时为 [ConditionalPut.Written]（附写入的值的摘要），否则为 [ConditionalPut.Conflict]
     * @throws CacheWriteException L1 写入失败（结果可能未知：重读后再决定是否重试），或 L1 中是读不懂的内容
     */
    fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut

    /**
     * 以指定时长条件写入，语义同 [putIfMatch]。
     */
    fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): ConditionalPut

    override fun async(): AsyncStore<K, V>

    override fun suspending(): SuspendingStore<K, V>
}

/**
 * [Store] 的异步视图，约定见 [AsyncReadableCache]。不供用户实现，委托除外。
 */
interface AsyncStore<K : Any, V : Any> : AsyncReadableCache<K, V> {
    /**
     * 语义同 [Store.put]。完成时本地 L2 已淘汰（读己之写）。
     */
    fun put(key: K, value: V): CompletableFuture<Void?>

    /**
     * 语义同 [Store.put] (key, value, ttl)。
     */
    fun put(key: K, value: V, ttl: Duration): CompletableFuture<Void?>

    /**
     * 语义同 [Store.putAll]。
     */
    fun putAll(entries: Map<K, V>): CompletableFuture<Void?>

    /**
     * 语义同 [Store.putAll] (entries, ttl)。
     */
    fun putAll(entries: Map<K, V>, ttl: Duration): CompletableFuture<Void?>

    /**
     * 语义同 [Store.delete]。
     */
    fun delete(key: K): CompletableFuture<Void?>

    /**
     * 语义同 [Store.deleteIfMatch]。
     */
    fun deleteIfMatch(key: K, expected: Digest): CompletableFuture<ConditionalDelete>

    /**
     * 语义同 [Store.lookupRemote]：失败以 [CacheReadException] 完成。
     */
    fun lookupRemote(key: K): CompletableFuture<Lookup.Known<V>>

    /**
     * 语义同 [Store.getIfChanged]。
     */
    fun getIfChanged(key: K, known: Digest?): CompletableFuture<ConditionalGet<V>>

    /**
     * 语义同 [Store.putIfMatch]。
     */
    fun putIfMatch(key: K, expected: Digest?, value: V): CompletableFuture<ConditionalPut>

    /**
     * 语义同 [Store.putIfMatch] (key, expected, value, ttl)。
     */
    fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): CompletableFuture<ConditionalPut>

    override fun sync(): Store<K, V>

    /**
     * 同一个缓存的挂起视图（同 `sync().suspending()`）。
     */
    override fun suspending(): SuspendingStore<K, V> = sync().suspending()
}

/**
 * [Store] 的挂起视图，约定见 [SuspendingReadableCache]。不供用户实现，委托除外。
 */
interface SuspendingStore<K : Any, V : Any> : SuspendingReadableCache<K, V> {
    /**
     * 语义同 [Store.put]。
     */
    suspend fun put(key: K, value: V)

    /**
     * 语义同 [Store.put] (key, value, ttl)。
     */
    suspend fun put(key: K, value: V, ttl: Duration)

    /**
     * 语义同 [Store.putAll]。
     */
    suspend fun putAll(entries: Map<K, V>)

    /**
     * 语义同 [Store.putAll] (entries, ttl)。
     */
    suspend fun putAll(entries: Map<K, V>, ttl: Duration)

    /**
     * 语义同 [Store.delete]。
     */
    suspend fun delete(key: K)

    /**
     * 语义同 [Store.deleteIfMatch]。
     */
    suspend fun deleteIfMatch(key: K, expected: Digest): ConditionalDelete

    /**
     * 语义同 [Store.lookupRemote]。
     */
    suspend fun lookupRemote(key: K): Lookup.Known<V>

    /**
     * 语义同 [Store.getIfChanged]。
     */
    suspend fun getIfChanged(key: K, known: Digest?): ConditionalGet<V>

    /**
     * 语义同 [Store.putIfMatch]。
     */
    suspend fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut

    /**
     * 语义同 [Store.putIfMatch] (key, expected, value, ttl)。
     */
    suspend fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration): ConditionalPut

    override fun async(): AsyncStore<K, V>

    /**
     * 回到同步 API（同一个引擎，同 `async().sync()`）。
     */
    override fun sync(): Store<K, V> = async().sync()
}
