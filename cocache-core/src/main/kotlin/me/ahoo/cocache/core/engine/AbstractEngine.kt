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

package me.ahoo.cocache.core.engine

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.AsyncInvalidationChannel
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.internal.Futures
import me.ahoo.cocache.core.internal.InvalidationStamps
import me.ahoo.cocache.core.internal.SingleFlight
import me.ahoo.cocache.core.policy.CachePolicy
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * L2 副本：[value] 为 `null` 表示负缓存。到期与刷新时间均为纪元毫秒，[FOREVER] 表示没有。
 *
 * @param unresolved 没有数据源的缓存未命中时的“不存在”：只是没有答案，带 loader 的读取不接受它
 */
internal class LocalEntry<V>(
    val value: V?,
    val expiresAt: Long,
    val refreshAt: Long = FOREVER,
    val unresolved: Boolean = false
)

/**
 * 一次加载的结果及其开始时的失效戳。
 */
internal class Loaded<V>(val entry: LocalEntry<V>, val stamp: Long)

internal const val FOREVER = Long.MAX_VALUE

/**
 * 两种引擎共享的部分：L2、L2 栅栏、存储 key、写入顺序、失效订阅。
 */
internal abstract class AbstractEngine<K : Any, V : Any>(
    override val name: String,
    protected val namespace: String,
    keyPrefix: String,
    private val keyEncoder: KeyEncoder<K>,
    protected val policy: CachePolicy,
    protected val store: RemoteStore,
    protected val context: EngineContext,
) : InvalidationListener, CacheAdmin, AutoCloseable {
    override val metrics = CacheMetrics(context.recordLocalHits)

    /**
     * 与 [metrics] 分开保存：命中路径只读引擎自身的一个 final 字段，关闭时不触碰计数器。
     */
    private val recordLocalHits = context.recordLocalHits

    protected val clock = context.clock
    protected val stamps = InvalidationStamps()
    protected val flights = SingleFlight<String, Loaded<V>>()

    /**
     * L2 命中计数（开启 `recordLocalHits` 时）。
     */
    protected fun countLocalHit() {
        if (recordLocalHits) {
            metrics.localHit()
        }
    }
    protected val local: Cache<String, LocalEntry<V>> = buildLocal(policy)
    protected val closed = AtomicBoolean(false)
    private val nodeId: NodeId = context.nodeId
    private val atomicBroadcast = context.backend.atomicBroadcast
    private val storagePrefix = "$keyPrefix$namespace:"

    /**
     * 异步路径（RFC 0004）：原生异步的存储与通道直接使用，否则同步调用只在阻塞执行器上执行。
     */
    protected val asyncStore: AsyncRemoteStore = BlockingAsyncStore.of(store, context.async.blocking)
    private val asyncChannel: AsyncInvalidationChannel = BlockingAsyncChannel.of(
        context.backend.channel,
        context.async.blocking
    )

    /**
     * 异步执行器：异步路径的全部续接都在这里（或已完成时就地）执行，从不在 I/O 线程上执行。
     */
    protected val executor: Executor = context.async.executor

    protected fun storageKey(key: K): String = storagePrefix + keyEncoder.encode(key)

    /**
     * 编码后的 key → 存储 key（运维接口使用）。
     */
    protected fun storageKeyOf(encodedKey: String): String {
        require(encodedKey.isNotEmpty()) { "Cache[$name] - encodedKey must not be empty." }
        return storagePrefix + encodedKey
    }

    protected fun frameOf(keys: Collection<String>): ByteArray = InvalidationFrame.encode(nodeId, keys)

    /**
     * 戳保护的 L2 填充：写入前校验、写入后复核，失败则撤销。
     */
    protected fun fillLocal(storageKey: String, entry: LocalEntry<V>, stamp: Long) {
        if (!stamps.isValid(storageKey, stamp)) {
            metrics.staleFillDiscarded()
            return
        }
        local.put(storageKey, entry)
        if (!stamps.isValid(storageKey, stamp)) {
            local.invalidate(storageKey)
            metrics.staleFillDiscarded()
        }
    }

    /**
     * L2 副本：到期时间不晚于 L1 剩余寿命，也不晚于 [maxAge]。
     */
    protected fun localEntry(value: V?, ttl: Duration?, maxAge: Duration): LocalEntry<V> {
        val now = clock.nowMillis()
        val remoteExpiresAt = ttl?.let { now + it.toMillis() } ?: FOREVER
        val expiresAt = minOf(remoteExpiresAt, now + maxAge.toMillis())
        val window = policy.refreshWindow
        val refreshAt = if (window != null && value != null && remoteExpiresAt != FOREVER) remoteExpiresAt - window.toMillis() else FOREVER
        return LocalEntry(value, expiresAt, refreshAt)
    }

    protected fun lookupOf(entry: LocalEntry<V>): Lookup<V> {
        // L2 副本总是受 localMaxAge 限龄，因此到期时间总是有限的
        val expiresAt = Instant.ofEpochMilli(entry.expiresAt)
        return entry.value?.let { Lookup.Present(it, expiresAt) } ?: Lookup.Absent(expiresAt)
    }

    /**
     * 写入方顺序（RFC 0003 §4.4）：戳 → 淘汰 L2 → 修改 L1（并广播）→ 戳 → 淘汰 L2。写入方从不直接写 L2。
     *
     * 第二次递增使“在第一次递增之后取戳、在修改 L1 之前读到旧值”的并发填充失效；
     * 本节点自己的广播会被过滤，不能依赖它清除本地副本。
     *
     * 非原子广播时 L1 失败也尽力广播：失败的写入可能已部分生效（`putAll` 跨多个 slot 组时有的组已写入，
     * 或写入已在 L1 生效却报错），不广播的话其它节点会一直读旧的 L2 直到 `localMaxAge`（G2）。多余的广播只淘汰副本，无害。
     * 前提是 [mutate] 返回或抛出时它的全部 L1 命令都已结束（否则广播可能先于仍在执行的命令）。
     * 例外是 [ForeignFormatException]：后端拒绝了写入，L1 原样未动，不广播。
     *
     * @param onFailure L1 失败时调用（在第二次递增之前，因此登记的状态对随后的读取可见）
     */
    protected fun write(keys: Collection<String>, mutate: (ByteArray) -> Unit, onFailure: (Exception) -> Unit) {
        write(keys, mutate, ALWAYS_CHANGED, onFailure)
    }

    /**
     * 同 [write]，但 [mutate] 正常返回后由 [changed] 判定 L1 是否被修改（条件写入的冲突不修改 L1）：
     * 未修改时非原子广播的后端不广播（原子广播的后端由存储自己决定）。戳与 L2 淘汰照常执行，只多淘汰一次本地副本。
     */
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    protected fun write(
        keys: Collection<String>,
        mutate: (ByteArray) -> Unit,
        changed: () -> Boolean,
        onFailure: (Exception) -> Unit
    ) {
        keys.forEach {
            stamps.invalidate(it)
            local.invalidate(it)
        }
        try {
            val frame = frameOf(keys)
            try {
                mutate(frame)
            } catch (e: Exception) {
                if (!atomicBroadcast && e !is ForeignFormatException) {
                    publishAfterFailure(frame, e)
                }
                throw e
            }
            if (!atomicBroadcast && changed()) {
                context.backend.channel.publish(namespace, frame)
            }
        } catch (e: Exception) {
            metrics.writeFailure()
            onFailure(e)
        } finally {
            keys.forEach {
                stamps.invalidate(it)
                local.invalidate(it)
            }
        }
    }

    /**
     * [write] 的异步版本（RFC 0004 §5.6）：顺序相同；第二次递增挂在 L1 的 future 上（无论成败），
     * 在返回的 future 完成之前执行，取消返回的 future 不会跳过它。[onFailure] 同样在第二次递增之前调用，可以抛出。
     *
     * 第二次递增与淘汰（以及 [onFailure]：抛出写入异常或登记重试，都不阻塞、不调用用户代码）在 L1 future 的完成线程上就地执行，
     * 不经执行器：执行器拒绝任务时也必须执行，否则在两次递增之间开始的填充会把旧值写进 L2（G2）。只有返回 future 的完成切换到执行器。
     * 非原子广播的派发被拒绝时同样就地广播（[publishAfterWrite]）。
     */
    protected fun writeAsync(
        keys: Collection<String>,
        mutate: (ByteArray) -> CompletableFuture<Void?>,
        onFailure: (Exception) -> Unit
    ): CompletableFuture<Void?> = writeAsync(keys, mutate, ALWAYS_CHANGED, onFailure)

    /**
     * 同 [writeAsync]，[changed] 的含义同 [write]：在 [mutate] 的 future 正常完成之后调用。
     */
    @Suppress("TooGenericExceptionCaught")
    protected fun writeAsync(
        keys: Collection<String>,
        mutate: (ByteArray) -> CompletableFuture<Void?>,
        changed: () -> Boolean,
        onFailure: (Exception) -> Unit
    ): CompletableFuture<Void?> {
        keys.forEach {
            stamps.invalidate(it)
            local.invalidate(it)
        }
        val mutated = Futures.attempt {
            val frame = frameOf(keys)
            val written = Futures.attempt { mutate(frame) }
            if (atomicBroadcast) written else publishAfterWrite(written, frame, changed)
        }
        if (mutated.isDone) {
            // 已完成（没有 I/O 线程参与）：就地收尾并返回
            val failure = finishWrite(keys, Futures.outcome(mutated).second, onFailure)
            return if (failure == null) Futures.done() else CompletableFuture.failedFuture(failure)
        }
        val written = CompletableFuture<Void?>()
        mutated.whenComplete { _, error ->
            val failure = finishWrite(keys, error?.let { Futures.unwrap(it) }, onFailure)
            Futures.dispatch(executor, written) {
                if (failure == null) written.complete(null) else written.completeExceptionally(failure)
            }
        }
        return written
    }

    /**
     * 非原子广播的续接：[written] 完成后（无论成败）调用 [publishAfter]。已完成时就地执行；否则从完成线程派发到执行器，
     * 执行器拒绝时在完成线程上就地执行（`publishAsync` 不阻塞、不调用用户代码）：拒绝不能跳过广播（G2），
     * 也不能把写入的结果换成 `RejectedExecutionException`（成功的写入因此计为失败、`invalidate` 因此登记多余的重试）。
     */
    private fun publishAfterWrite(
        written: CompletableFuture<Void?>,
        frame: ByteArray,
        changed: () -> Boolean
    ): CompletableFuture<Void?> {
        if (written.isDone) {
            return publishAfter(frame, Futures.outcome(written).second, changed)
        }
        val published = CompletableFuture<Void?>()
        written.whenComplete { _, error ->
            val publish = Runnable {
                Futures.relay(
                    Futures.attempt { publishAfter(frame, error?.let { Futures.unwrap(it) }, changed) },
                    published
                )
            }
            try {
                executor.execute(publish)
            } catch (_: RejectedExecutionException) {
                publish.run()
            }
        }
        return published
    }

    /**
     * 非原子广播：写入成功后广播；写入失败时也尽力广播（见 [write]），广播的失败只告警，返回的 future 以写入的原始异常失败。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun publishAfter(frame: ByteArray, error: Throwable?, changed: () -> Boolean): CompletableFuture<Void?> {
        if (error == null) {
            // 条件写入的冲突没有修改 L1，没有需要广播的变化
            return if (changed()) asyncChannel.publishAsync(namespace, frame) else Futures.done()
        }
        if (error is ForeignFormatException) {
            // 后端拒绝了写入（L1 未修改），没有需要广播的变化
            return CompletableFuture.failedFuture(error)
        }
        val failed = CompletableFuture<Void?>()
        Futures.attempt { asyncChannel.publishAsync(namespace, frame) }.whenComplete { _, publishError ->
            if (publishError != null) {
                warnPublishAfterFailure(Futures.unwrap(publishError))
            }
            failed.completeExceptionally(error)
        }
        return failed
    }

    @Suppress("TooGenericExceptionCaught")
    private fun publishAfterFailure(frame: ByteArray, error: Exception) {
        try {
            context.backend.channel.publish(namespace, frame)
        } catch (e: Exception) {
            error.addSuppressed(e)
            warnPublishAfterFailure(e)
        }
    }

    private fun warnPublishAfterFailure(error: Throwable) {
        log.warn(error) { "Cache[$name] - failed to broadcast the invalidation after a failed write." }
    }

    /**
     * 写入的收尾：失败时计数并调用 [onFailure]，然后（无论成败）第二次递增并淘汰 L2。返回写入的结果异常（成功为 `null`）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun finishWrite(keys: Collection<String>, error: Throwable?, onFailure: (Exception) -> Unit): Throwable? {
        try {
            if (error == null) {
                return null
            }
            if (error !is Exception) {
                return error
            }
            metrics.writeFailure()
            onFailure(error)
            return null
        } catch (e: Throwable) {
            return e
        } finally {
            keys.forEach {
                stamps.invalidate(it)
                local.invalidate(it)
            }
        }
    }

    protected fun throwWriteFailure(operation: String): (Exception) -> Unit = {
        throw CacheWriteException(
            name,
            operation,
            it
        )
    }

    override fun onInvalidated(keys: List<String>, origin: NodeId) {
        if (origin == nodeId) {
            return
        }
        metrics.invalidationReceived()
        keys.forEach {
            stamps.invalidate(it)
            local.invalidate(it)
        }
    }

    override fun onReset() {
        log.info { "Cache[$name] - reset: clear local copies." }
        metrics.reset()
        stamps.invalidateAll()
        local.invalidateAll()
    }

    /**
     * 本地副本数（估计值），供运维查看。
     */
    override val localSize: Long
        get() = local.estimatedSize()

    override fun clearLocal() {
        stamps.invalidateAll()
        local.invalidateAll()
    }

    override val pendingInvalidationCount: Int
        get() = 0

    /**
     * 该存储 key 的失效是否仍在重试中。
     */
    protected open fun isInvalidationPending(storageKey: String): Boolean = false

    /**
     * 把 L1 内容解码为展示用的值；形状不符或解码失败时抛出。
     */
    protected abstract fun describe(envelope: Envelope): Any?

    /**
     * 只读快照：L2 用 `getIfPresentQuietly`（不影响淘汰统计），L1 用不带 lease 与刷新令牌的 `read`（纯读取）。
     * 不经过 SingleFlight，不取戳，不写任何一层，不计入指标：与命中路径完全无关。
     */
    override fun inspect(encodedKey: String): KeyInspection {
        val storageKey = storageKeyOf(encodedKey)
        val localCopy = local.policy().getIfPresentQuietly(storageKey)?.let { localCopyOf(it) }
        return KeyInspection(name, storageKey, localCopy, remoteCopyOf(storageKey), isInvalidationPending(storageKey))
    }

    private fun localCopyOf(entry: LocalEntry<V>): KeyInspection.LocalCopy {
        val state = when {
            entry.value != null -> KeyInspection.LocalState.VALUE
            entry.unresolved -> KeyInspection.LocalState.UNRESOLVED
            else -> KeyInspection.LocalState.ABSENT
        }
        return KeyInspection.LocalCopy(
            state = state,
            value = entry.value?.let { KeyInspection.display(it) },
            expiresAt = Instant.ofEpochMilli(entry.expiresAt),
            refreshAt = entry.refreshAt.takeIf { it != FOREVER }?.let { Instant.ofEpochMilli(it) },
            expired = entry.expiresAt <= clock.nowMillis(),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun remoteCopyOf(storageKey: String): KeyInspection.RemoteCopy {
        val read = try {
            store.read(storageKey, null, null)
        } catch (e: Exception) {
            return KeyInspection.RemoteCopy(KeyInspection.RemoteState.ERROR, error = e.toString())
        }
        return when (read) {
            is RemoteRead.Miss -> KeyInspection.RemoteCopy(KeyInspection.RemoteState.MISS)
            is RemoteRead.Leased -> KeyInspection.RemoteCopy(KeyInspection.RemoteState.LEASED, ttl = read.remaining)
            is RemoteRead.Hit -> remoteHitOf(read)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun remoteHitOf(read: RemoteRead.Hit): KeyInspection.RemoteCopy {
        val unreadable = { reason: String ->
            KeyInspection.RemoteCopy(
                KeyInspection.RemoteState.UNREADABLE,
                ttl = read.ttl,
                format = read.format,
                error = reason
            )
        }
        if (read.format < MIN_FORMAT) {
            return unreadable("Unrecognized remote content (not written by this protocol).")
        }
        if (read.format > context.backend.format) {
            return unreadable("Written by a newer format[${read.format}] than this node[${context.backend.format}].")
        }
        val value = try {
            describe(read.envelope)
        } catch (e: Exception) {
            return unreadable(e.toString())
        }
        val state = when (read.envelope) {
            is Envelope.Scalar -> KeyInspection.RemoteState.VALUE
            is Envelope.Members -> KeyInspection.RemoteState.SET
            Envelope.Absent -> KeyInspection.RemoteState.ABSENT
        }
        return KeyInspection.RemoteCopy(
            state = state,
            value = value?.let { KeyInspection.display(it) },
            ttl = read.ttl,
            format = read.format,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            log.info { "Cache[$name] - close." }
        }
    }

    override fun toString(): String = "${javaClass.simpleName}(name='$name', namespace='$namespace')"

    protected companion object {
        val log = KotlinLogging.logger {}

        /**
         * 格式版本 `0` 表示无法识别的内容（如旧版本写入的值），按损坏处理。
         */
        const val MIN_FORMAT = 1

        /**
         * 无条件写入：总是修改 L1。
         */
        val ALWAYS_CHANGED: () -> Boolean = { true }

        fun <V> buildLocal(policy: CachePolicy): Cache<String, LocalEntry<V>> {
            val local = policy.local
            val builder = Caffeine.newBuilder().maximumSize(local.maximumSize)
            local.initialCapacity?.let { builder.initialCapacity(it) }
            local.expireAfterAccess?.let { builder.expireAfterAccess(it) }
            return builder.build()
        }
    }
}
