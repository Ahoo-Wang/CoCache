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
        awaitValue(store, "read") { store.read(key, lease, refresh) }

    override suspend fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> =
        awaitValue(store, "readAll") { store.readAll(keys, leases) }

    override suspend fun fill(fill: Fill): Boolean = awaitValue(store, "fill") { store.fill(fill) }

    override suspend fun fillAll(fills: List<Fill>): List<Boolean> =
        awaitValue(store, "fillAll") { store.fillAll(fills) }

    override suspend fun release(key: String, token: Token) {
        awaitNullable(store, "release") { store.release(key, token) }
    }

    override suspend fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean =
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
    override suspend fun readIfChanged(key: String, known: Digest?): RemoteConditionalRead =
        awaitValue(store, "readIfChanged") { store.readIfChanged(key, known) }

    override suspend fun putIfMatch(
        key: String,
        expected: Digest?,
        envelope: Envelope.Scalar,
        ttl: Duration?,
        frame: ByteArray
    ): RemoteConditionalWrite = awaitValue(store, "putIfMatch") {
        store.putIfMatch(key, expected, envelope, ttl, frame)
    }
}

private class FutureVersionedAdapter(private val store: FutureVersionedL1Store) : VersionedL1Store {
    override suspend fun putIfNewer(
        key: String,
        envelope: Envelope,
        version: Long,
        ttl: Duration?,
        frame: ByteArray
    ): RemoteVersionedWrite = awaitValue(store, "putIfNewer") {
        store.putIfNewer(key, envelope, version, ttl, frame)
    }
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

/**
 * 同 [awaitNullable]，结果类型非空：以 `null` 完成时以指明 [owner] 与 [operation] 的 [IllegalStateException] 失败。
 */
private suspend fun <T : Any> awaitValue(owner: Any, operation: String, call: () -> CompletableFuture<T>?): T =
    awaitNullable(owner, operation, call)
        ?: throw IllegalStateException("${owner.javaClass.name}.$operation() completed with null.")

/**
 * 调用 [call] 并等待它返回的 future，结果可以是 `null`（Java 实现者可能违反非空声明，因此 future 与结果都按可空处理）。
 * - 已完成的 future 直接取结果，不挂起；成功时也不登记回调。
 * - 未完成时挂起，经 `whenComplete` 恢复。标准库的 [suspendCoroutine] 不可取消：调用一直等到 future 完成，从不取消它。
 * - 失败时抛出 [failureOf] 的结果。不经 kotlinx 的 `await()`，异常不会在调试模式下被复制。
 */
private suspend fun <T> awaitNullable(owner: Any, operation: String, call: () -> CompletableFuture<T>?): T? {
    val future = start(owner, operation, call)
    if (future.isDone) {
        return completedResult(future, owner, operation)
    }
    return suspendCoroutine { continuation ->
        future.whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(failureOf(error, owner, operation))
            }
        }
    }
}

/**
 * 调用 [call] 取得 future。同步抛出的异常原样传播，只有 [CancellationException] 换成以它为 cause 的 [IllegalStateException]
 * （理由见 [failureOf]）；返回 `null` 时以 [IllegalStateException] 失败。
 */
private fun <T> start(owner: Any, operation: String, call: () -> CompletableFuture<T>?): CompletableFuture<T> {
    val future = try {
        call()
    } catch (e: CancellationException) {
        throw IllegalStateException("${owner.javaClass.name}.$operation() threw a CancellationException.", e)
    }
    return future
        ?: throw IllegalStateException("${owner.javaClass.name}.$operation() returned null instead of a future.")
}

/**
 * 已完成的 future 的结果。失败时抛出 [failureOf] 的结果，异常取自 future 本身而不是 `join`：较新的 JDK（如 25）上
 * `join` 对被取消的 future 每次抛出新建的 [CancellationException]，不是 future 中保存的那一个。
 */
private fun <T> completedResult(future: CompletableFuture<T>, owner: Any, operation: String): T? {
    if (future.isCompletedExceptionally) {
        throw failureOf(future.handle<Throwable> { _, error -> error }.join(), owner, operation)
    }
    return future.join()
}

/**
 * future 失败时抛给调用方的异常：原始异常（[unwrap]）。被取消的 future 是 L1 故障（结果未知），不是调用方的取消：
 * 在 JVM 上 [CancellationException] 就是协程的取消，原样抛出会被引擎当作调用方被取消（跟随者自行运行、不降级），
 * 因此换成以原取消异常为 cause 的 [IllegalStateException]。
 */
private fun failureOf(error: Throwable, owner: Any, operation: String): Throwable {
    val original = error.unwrap()
    if (original is CancellationException) {
        return IllegalStateException("${owner.javaClass.name}.$operation() future was cancelled.", original)
    }
    return original
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
