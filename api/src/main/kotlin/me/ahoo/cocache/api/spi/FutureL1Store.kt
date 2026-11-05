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

package me.ahoo.cocache.api.spi

import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * 以 [CompletableFuture] 表达的 L1 协议，供 Java 实现者使用（RFC 0005 §4.3）：方法与 [L1Store] 同名，语义逐一相同
 * （原子性、到期时间、广播、令牌、限时与失败的分类），只是返回 future。[CoherenceBackend.store] 返回 [adapt] 的结果。
 *
 * 实现约定：
 * - future 可以在 I/O 线程上完成；只有阻塞客户端的实现在自己的执行器上完成 future，不在调用方线程上阻塞。
 * - 失败时同步抛出或返回异常完成的 future 都可以：调用方都得到原始异常。
 * - 每个操作都必须自己限时，不得返回永不完成的 future。调用方从不取消返回的 future。
 * - 实现自己取消的 future（如用 `cancel` 实现超时）与同步抛出的 `CancellationException` 按 L1 故障（结果未知）处理，
 *   不是调用方的取消：调用方得到以原取消异常为 cause 的 [IllegalStateException]。
 * - 不得返回 `null`；结果类型非空的 future 也不得以 `null` 完成（`Void` 与集合写入的 `Integer` 除外）。
 * - 能力访问器同 [L1Store]：不支持时返回 `null`，同一个存储每次返回同样的结果。
 *
 * 它是接口而不是 [L1Store] 的基类：Kotlin 不允许同名的挂起与非挂起重载，基类只能把方法改名（如 `readAsync`），
 * Java 子类还会看到一组 final 的挂起方法。
 */
@Suppress("TooManyFunctions")
interface FutureL1Store {
    /**
     * 同 [L1Store.read]：读取，按请求原子地取得 lease 或刷新令牌；[timeout] 为这次调用的时限（`null` 为实现的命令超时，给出时以它为上限）。
     */
    fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): CompletableFuture<L1Read>

    /**
     * 同 [L1Store.readAll]：批量读取，只为 [leases] 中的 key 申请 lease；[timeout] 同 [read]。
     */
    fun readAll(
        keys: Collection<String>,
        leases: Map<String, LeaseRequest>,
        timeout: Duration?
    ): CompletableFuture<Map<String, L1Read>>

    /**
     * 同 [L1Store.fill]：令牌匹配时写入，以 [L1Fill] 完成。
     */
    fun fill(fill: Fill): CompletableFuture<L1Fill>

    /**
     * 同 [L1Store.fillAll]：批量填充，结果与 [fills] 一一对应。
     */
    fun fillAll(fills: List<Fill>): CompletableFuture<List<L1Fill>>

    /**
     * 同 [L1Store.release]：令牌匹配时释放。
     */
    fun release(key: String, token: Token): CompletableFuture<Void?>

    /**
     * 同 [L1Store.reclaim]：内容指纹匹配时条件回收为 lease，以是否回收完成。
     */
    fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): CompletableFuture<Boolean>

    /**
     * 同 [L1Store.put]：整体覆盖写入并广播。
     */
    fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?>

    /**
     * 同 [L1Store.putAll]：批量整体覆盖写入并广播，每个 key 恰好随其写入广播一次。
     */
    fun putAll(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?>

    /**
     * 同 [L1Store.invalidate]：删除并广播。
     */
    fun invalidate(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?>

    /**
     * 同 [L1Store.conditional]：条件读写的能力面；不支持时为 `null`。
     */
    fun conditional(): FutureConditionalL1Store?

    /**
     * 同 [L1Store.versioned]：按版本有序写入的能力面；不支持时为 `null`。
     */
    fun versioned(): FutureVersionedL1Store?

    /**
     * 同 [L1Store.sets]：集合成员增删的能力面；不支持时为 `null`。
     */
    fun sets(): FutureSetL1Store?

    companion object {
        /**
         * 把 [store] 适配为 [L1Store]：
         * - 已完成的 future 直接取结果，不挂起；未完成时挂起，经 `whenComplete` 恢复。
         * - 只用标准库的 `suspendCoroutine`，不经 kotlinx：等待不响应协程取消（调用一直等到 future 完成，取消在之后的第一个挂起点生效），
         *   从不取消 future；失败时抛出原始异常（解包 [CompletionException] 与 [ExecutionException]）。续接总是以成功的结果恢复、
         *   在适配器的栈帧中抛出异常，异常因此不会在 kotlinx 的调试模式下被复制。
         * - 被取消的 future 按 L1 故障（结果未知）处理，不是调用方的取消：在 JVM 上 [CancellationException] 就是协程的取消，
         *   原样抛出会被引擎当作调用方被取消，因此换成以原取消异常为 cause 的 [IllegalStateException]。方法同步抛出的
         *   [CancellationException] 同样转换。
         * - 方法同步抛出的其它异常原样传播。返回 `null`，或结果类型非空的 future 以 `null` 完成时，以指明操作的 [IllegalStateException] 失败。
         * - 能力面在适配时各取一次、各适配一次：访问器每次返回同一个对象，`null` 原样保留。
         */
        @JvmStatic
        fun adapt(store: FutureL1Store): L1Store = FutureL1StoreAdapter(store)
    }
}

/**
 * 同 [FutureL1Store.adapt]。
 */
fun FutureL1Store.asL1Store(): L1Store = FutureL1Store.adapt(this)

/**
 * 以 [CompletableFuture] 表达的 [ConditionalL1Store]，约定同 [FutureL1Store]。
 */
interface FutureConditionalL1Store {
    /**
     * 同 [ConditionalL1Store.readIfChanged]：摘要相同时以 [L1ConditionalRead.Unchanged] 完成，不返回正文；[timeout] 同 [FutureL1Store.read]。
     */
    fun readIfChanged(key: String, known: Digest?, timeout: Duration?): CompletableFuture<L1ConditionalRead>

    /**
     * 同 [ConditionalL1Store.putIfMatch]：当前内容符合期望时写入并广播，以 [L1ConditionalWrite.Written] 完成；否则不修改、不广播。
     */
    fun putIfMatch(
        key: String,
        expected: Digest?,
        envelope: Envelope.Scalar,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<L1ConditionalWrite>

    /**
     * 同 [ConditionalL1Store.deleteIfMatch]：当前值的摘要段等于 [expected] 时删除并广播，以 [L1ConditionalWrite.Written] 完成；
     * 否则不修改、不广播。
     */
    fun deleteIfMatch(key: String, expected: Digest, frame: ByteArray): CompletableFuture<L1ConditionalWrite>
}

/**
 * 以 [CompletableFuture] 表达的 [VersionedL1Store]，约定同 [FutureL1Store]。
 */
interface FutureVersionedL1Store {
    /**
     * 同 [VersionedL1Store.putIfNewer]：版本更新时写入并广播，以 [L1VersionedWrite.Written] 完成；否则不修改、不广播。
     */
    fun putIfNewer(
        key: String,
        envelope: Envelope,
        version: Long,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<L1VersionedWrite>

    /**
     * 同 [VersionedL1Store.evictKeepingVersion]：带版本的内容转为 `E`、没有版本的内容删除并广播，以 [L1Eviction.Evicted] 完成；
     * 否则不修改、不广播。
     */
    fun evictKeepingVersion(key: String, ttl: Duration?, frame: ByteArray): CompletableFuture<L1Eviction>
}

/**
 * 以 [CompletableFuture] 表达的 [SetL1Store]，约定同 [FutureL1Store]。
 */
interface FutureSetL1Store {
    /**
     * 同 [SetL1Store.addMembers]：写入时以 `null` 完成；读不懂的内容不修改、不广播，以其格式版本完成。
     */
    fun addMembers(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Int?>

    /**
     * 同 [SetL1Store.removeMembers]：执行时以 `null` 完成；读不懂的内容不修改、不广播，以其格式版本完成。
     */
    fun removeMembers(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Int?>
}

/**
 * [FutureL1Store.adapt] 的实现。能力面在构造时各适配一次。
 */
@Suppress("TooManyFunctions")
private class FutureL1StoreAdapter(private val store: FutureL1Store) : L1Store {
    private val conditional = store.conditional()?.let(::FutureConditionalAdapter)
    private val versioned = store.versioned()?.let(::FutureVersionedAdapter)
    private val sets = store.sets()?.let(::FutureSetAdapter)

    override suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read =
        awaitValue(store, "read") { store.read(key, lease, refresh, timeout) }

    override suspend fun readAll(
        keys: Collection<String>,
        leases: Map<String, LeaseRequest>,
        timeout: Duration?
    ): Map<String, L1Read> = awaitValue(store, "readAll") { store.readAll(keys, leases, timeout) }

    override suspend fun fill(fill: Fill): L1Fill = awaitValue(store, "fill") { store.fill(fill) }

    override suspend fun fillAll(fills: List<Fill>): List<L1Fill> =
        awaitValue(store, "fillAll") { store.fillAll(fills) }

    override suspend fun release(key: String, token: Token) {
        awaitNullable(store, "release") { store.release(key, token) }
    }

    override suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean =
        awaitValue(store, "reclaim") { store.reclaim(key, observed, lease) }

    override suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
        awaitNullable(store, "put") { store.put(key, envelope, ttl, frame) }
    }

    override suspend fun putAll(puts: List<Put>, frame: ByteArray) {
        awaitNullable(store, "putAll") { store.putAll(puts, frame) }
    }

    override suspend fun invalidate(keys: Collection<String>, frame: ByteArray) {
        awaitNullable(store, "invalidate") { store.invalidate(keys, frame) }
    }

    override fun conditional(): ConditionalL1Store? = conditional

    override fun versioned(): VersionedL1Store? = versioned

    override fun sets(): SetL1Store? = sets
}

private class FutureConditionalAdapter(private val store: FutureConditionalL1Store) : ConditionalL1Store {
    override suspend fun readIfChanged(key: String, known: Digest?, timeout: Duration?): L1ConditionalRead =
        awaitValue(store, "readIfChanged") { store.readIfChanged(key, known, timeout) }

    override suspend fun putIfMatch(
        key: String,
        expected: Digest?,
        envelope: Envelope.Scalar,
        ttl: Duration?,
        frame: ByteArray
    ): L1ConditionalWrite = awaitValue(store, "putIfMatch") {
        store.putIfMatch(key, expected, envelope, ttl, frame)
    }

    override suspend fun deleteIfMatch(key: String, expected: Digest, frame: ByteArray): L1ConditionalWrite =
        awaitValue(store, "deleteIfMatch") { store.deleteIfMatch(key, expected, frame) }
}

private class FutureVersionedAdapter(private val store: FutureVersionedL1Store) : VersionedL1Store {
    override suspend fun putIfNewer(
        key: String,
        envelope: Envelope,
        version: Long,
        ttl: Duration?,
        frame: ByteArray
    ): L1VersionedWrite = awaitValue(store, "putIfNewer") {
        store.putIfNewer(key, envelope, version, ttl, frame)
    }

    override suspend fun evictKeepingVersion(key: String, ttl: Duration?, frame: ByteArray): L1Eviction =
        awaitValue(store, "evictKeepingVersion") { store.evictKeepingVersion(key, ttl, frame) }
}

private class FutureSetAdapter(private val store: FutureSetL1Store) : SetL1Store {
    override suspend fun addMembers(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): Int? = awaitNullable(store, "addMembers") { store.addMembers(key, members, ttl, frame) }

    override suspend fun removeMembers(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): Int? = awaitNullable(store, "removeMembers") { store.removeMembers(key, members, ttl, frame) }
}
