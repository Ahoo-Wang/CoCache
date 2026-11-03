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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 以 [CompletableFuture] 表达的 L1 协议，供 Java 实现者使用（RFC 0005 §4.3）：方法与 [L1Store] 同名，语义逐一相同
 * （原子性、到期时间、广播、令牌、限时与失败的分类），只是返回 future。[CoherenceBackend.store] 返回 [adapt] 的结果。
 *
 * 实现约定：
 * - future 可以在 I/O 线程上完成；只有阻塞客户端的实现在自己的执行器上完成 future，不在调用方线程上阻塞。
 * - 失败时同步抛出或返回异常完成的 future 都可以：调用方都得到原始异常。
 * - 每个操作都必须自己限时，不得返回永不完成的 future。调用方从不取消返回的 future。
 * - 不得返回 `null`；结果类型非空的 future 也不得以 `null` 完成（`Void` 与集合写入的 `Integer` 除外）。
 * - 能力访问器同 [L1Store]：不支持时返回 `null`，同一个存储每次返回同样的结果。
 *
 * 它是接口而不是 [L1Store] 的基类：Kotlin 不允许同名的挂起与非挂起重载，基类只能把方法改名（如 `readAsync`），
 * Java 子类还会看到一组 final 的挂起方法。
 */
@Suppress("TooManyFunctions")
interface FutureL1Store {
    /**
     * 同 [L1Store.read]：读取，按请求原子地取得 lease 或刷新令牌。
     */
    fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead>

    /**
     * 同 [L1Store.readAll]：批量读取，只为 [leases] 中的 key 申请 lease。
     */
    fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): CompletableFuture<Map<String, RemoteRead>>

    /**
     * 同 [L1Store.fill]：令牌匹配时写入，以是否写入完成。
     */
    fun fill(fill: Fill): CompletableFuture<Boolean>

    /**
     * 同 [L1Store.fillAll]：批量填充，结果与 [fills] 一一对应。
     */
    fun fillAll(fills: List<Fill>): CompletableFuture<List<Boolean>>

    /**
     * 同 [L1Store.release]：令牌匹配时释放。
     */
    fun release(key: String, token: Token): CompletableFuture<Void?>

    /**
     * 同 [L1Store.reclaim]：摘要匹配时条件回收为 lease，以是否回收完成。
     */
    fun reclaim(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean>

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
         *   从不取消 future；失败时抛出原始异常（解包 [CompletionException] 与 [ExecutionException]），不会在调试模式下被复制。
         * - 方法同步抛出的异常原样传播。返回 `null`，或结果类型非空的 future 以 `null` 完成时，以指明操作的 [IllegalStateException] 失败。
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
     * 同 [ConditionalL1Store.readIfChanged]：摘要相同时以 [RemoteConditionalRead.Unchanged] 完成，不返回正文。
     */
    fun readIfChanged(key: String, known: Digest?): CompletableFuture<RemoteConditionalRead>

    /**
     * 同 [ConditionalL1Store.putIfMatch]：当前内容符合期望时写入并广播，以 [RemoteConditionalWrite.Written] 完成；否则不修改、不广播。
     */
    fun putIfMatch(
        key: String,
        expected: Digest?,
        envelope: Envelope.Scalar,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<RemoteConditionalWrite>
}

/**
 * 以 [CompletableFuture] 表达的 [VersionedL1Store]，约定同 [FutureL1Store]。
 */
interface FutureVersionedL1Store {
    /**
     * 同 [VersionedL1Store.putIfNewer]：版本更新时写入并广播，以 [RemoteVersionedWrite.Written] 完成；否则不修改、不广播。
     */
    fun putIfNewer(
        key: String,
        envelope: Envelope,
        version: Long,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<RemoteVersionedWrite>
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

    override suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead =
        store.read(key, lease, refresh).awaitValue(store, "read")

    override suspend fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> =
        store.readAll(keys, leases).awaitValue(store, "readAll")

    override suspend fun fill(fill: Fill): Boolean = store.fill(fill).awaitValue(store, "fill")

    override suspend fun fillAll(fills: List<Fill>): List<Boolean> = store.fillAll(fills).awaitValue(store, "fillAll")

    override suspend fun release(key: String, token: Token) {
        store.release(key, token).awaitNullable(store, "release")
    }

    override suspend fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean =
        store.reclaim(key, observed, lease).awaitValue(store, "reclaim")

    override suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
        store.put(key, envelope, ttl, frame).awaitNullable(store, "put")
    }

    override suspend fun putAll(puts: List<Put>, frame: ByteArray) {
        store.putAll(puts, frame).awaitNullable(store, "putAll")
    }

    override suspend fun invalidate(keys: Collection<String>, frame: ByteArray) {
        store.invalidate(keys, frame).awaitNullable(store, "invalidate")
    }

    override fun conditional(): ConditionalL1Store? = conditional

    override fun versioned(): VersionedL1Store? = versioned

    override fun sets(): SetL1Store? = sets
}

private class FutureConditionalAdapter(private val store: FutureConditionalL1Store) : ConditionalL1Store {
    override suspend fun readIfChanged(key: String, known: Digest?): RemoteConditionalRead =
        store.readIfChanged(key, known).awaitValue(store, "readIfChanged")

    override suspend fun putIfMatch(
        key: String,
        expected: Digest?,
        envelope: Envelope.Scalar,
        ttl: Duration?,
        frame: ByteArray
    ): RemoteConditionalWrite = store.putIfMatch(key, expected, envelope, ttl, frame).awaitValue(store, "putIfMatch")
}

private class FutureVersionedAdapter(private val store: FutureVersionedL1Store) : VersionedL1Store {
    override suspend fun putIfNewer(
        key: String,
        envelope: Envelope,
        version: Long,
        ttl: Duration?,
        frame: ByteArray
    ): RemoteVersionedWrite = store.putIfNewer(key, envelope, version, ttl, frame).awaitValue(store, "putIfNewer")
}

private class FutureSetAdapter(private val store: FutureSetL1Store) : SetL1Store {
    override suspend fun addMembers(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): Int? = store.addMembers(key, members, ttl, frame).awaitNullable(store, "addMembers")

    override suspend fun removeMembers(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): Int? = store.removeMembers(key, members, ttl, frame).awaitNullable(store, "removeMembers")
}

/**
 * 等待结果类型非空的 future：以 `null` 完成时以指明 [owner] 与 [operation] 的 [IllegalStateException] 失败。
 */
private suspend fun <T : Any> CompletableFuture<T>?.awaitValue(owner: Any, operation: String): T =
    awaitNullable(owner, operation) ?: throw IllegalStateException("${owner.javaClass.name}.$operation() completed with null.")

/**
 * 等待 future，结果可以是 `null`（Java 实现者可能违反非空声明，因此接收者与结果都按可空处理）。
 * - 已完成的 future 直接取结果，不挂起，也不登记回调。
 * - 未完成时挂起，经 `whenComplete` 恢复。标准库的 [suspendCoroutine] 不可取消：调用一直等到 future 完成，从不取消它。
 * - 失败时抛出原始异常（[unwrap]）。不经 kotlinx 的 `await()`，异常不会在调试模式下被复制。
 */
private suspend fun <T> CompletableFuture<T>?.awaitNullable(owner: Any, operation: String): T? {
    val future = this ?: throw IllegalStateException("${owner.javaClass.name}.$operation() returned null instead of a future.")
    if (future.isDone) {
        return try {
            future.join()
        } catch (e: CompletionException) {
            throw e.unwrap()
        }
    }
    return suspendCoroutine { continuation ->
        future.whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(error.unwrap())
            }
        }
    }
}

/**
 * 原始异常：剥掉 [CompletionException] 与 [ExecutionException] 的包装（依赖阶段的失败、`join` 与 `get` 都会加上它们）；
 * 没有原因的包装原样返回。
 */
private fun Throwable.unwrap(): Throwable {
    var error = this
    while (error is CompletionException || error is ExecutionException) {
        error = error.cause ?: break
    }
    return error
}
