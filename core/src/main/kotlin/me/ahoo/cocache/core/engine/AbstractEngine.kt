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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.AsyncInvalidationChannel
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.internal.InvalidationStamps
import me.ahoo.cocache.core.internal.SingleFlight
import me.ahoo.cocache.core.internal.awaitUncancellable
import me.ahoo.cocache.core.internal.fillGuarded
import me.ahoo.cocache.core.internal.runsInline
import me.ahoo.cocache.core.policy.CachePolicy
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * L2 副本：[value] 为 `null` 表示负缓存。到期与刷新时间均为纪元毫秒，[FOREVER] 表示没有。
 *
 * 按身份比较：不得改为 data class，也不得重写 `equals`。L2 填充的写后复核按身份撤销自己写入的那一份（[fillGuarded]），
 * 按值比较会删掉其间别的填充写入的、内容相同的副本。
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
 *
 * @param failure 这次加载没有答案时要抛出的异常（没有数据源的读取遇到 L1 失败）。此时 [entry] 是没有写入 L2 的 `unresolved` 占位：
 *   合并到这次加载的带 loader 的读取不接受它，改用自己的数据源
 */
internal class Loaded<V>(val entry: LocalEntry<V>, val stamp: Long, val failure: Exception? = null) {
    /**
     * 这次加载的答案；没有答案时抛出 [failure]。
     */
    fun answer(): LocalEntry<V> {
        failure?.let { throw it }
        return entry
    }
}

internal const val FOREVER = Long.MAX_VALUE

/**
 * 两种引擎共享的部分：L2、L2 栅栏、存储 key、写入顺序、失效订阅。
 */
internal abstract class AbstractEngine<K : Any, V : Any>(
    override val name: String,
    protected val namespace: String,
    keyPrefix: String,
    private val keyEncoder: KeyEncoder<K>,
    internal val policy: CachePolicy,
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

    /**
     * 合并回源：跟随者的等待与加载的登记都以 `load.timeout` 为上限（RFC 0005 §4.4）。
     */
    protected val flights = SingleFlight<String, Loaded<V>>(policy.load.timeout.toNanos(), context.ticker) {
        metrics.loadTimeout()
        SourceLoadTimeoutException(name, policy.load.timeout)
    }

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

    /**
     * 协程核心的运行时作用域（RFC 0005 §4.4）：异步变体的共享加载在这里执行。
     */
    protected val scope: CoroutineScope = context.async.scope

    /**
     * 协程核心读取 L1：同步变体就地调用阻塞的 SPI，保留它在超时与中断时的处理（#701、#704）；异步变体等待异步 SPI，不占线程，
     * 续接回到运行时作用域的调度器上，从不在 L1 客户端的 I/O 线程上执行。
     */
    protected suspend fun readRemote(storageKey: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead =
        if (runsInline()) {
            store.read(storageKey, lease, refresh)
        } else {
            checkRead(asyncStore.readAsync(storageKey, lease, refresh).awaitUncancellable())
        }

    /**
     * 异步 SPI 的实现可能以 `null` 完成读取（Java 实现）：按实现缺陷失败，而不是在之后的分支上抛出更难懂的异常。
     */
    protected fun checkRead(read: RemoteRead?): RemoteRead {
        checkNotNull(read) { "Cache[$name] - remote read returned null." }
        return read
    }

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
     * 戳保护的 L2 填充：在 key 的锁内校验并写入，写后按身份复核（[fillGuarded]，RFC 0005 §4.5）。
     */
    protected fun fillLocal(storageKey: String, entry: LocalEntry<V>, stamp: Long) {
        if (!local.asMap().fillGuarded(storageKey, entry) { stamps.isValid(storageKey, stamp) }) {
            metrics.staleFillDiscarded()
        }
    }

    /**
     * L2 副本：到期时间不晚于 L1 剩余寿命，也不晚于 [maxAge]。
     *
     * 刷新点在 L1 到期前 `refreshWindow`（只对有限寿命的值）。L1 剩余寿命不长于窗口时，这样算出的刷新点不晚于现在，
     * 副本一出生就在窗口内，每次命中都会触发刷新，因此（§5.5）：
     * - [backoff] 为 0（默认）：不设刷新点。否则持有刷新令牌的节点每次写回之后（`fill` 清零令牌）都能再取得令牌，
     *   一次接一次地回源。寿命不长于窗口的值（`ttlOf`、`put(k, v, ttl)` 给出的短寿命）因此不参与提前刷新，
     *   在窗口内才读到 L1 的副本也不发起刷新。
     * - [backoff] 为正（没取得刷新令牌的重新填充：令牌在别的节点手里）：刷新点退避到 `now + min(backoff, 剩余寿命 / 2)`，
     *   到时再读一次 L1，取持有者写回的值（`fill` 不广播）。刷新点总是早于 L1 到期；不足 1 毫秒时不设刷新点。
     *
     * @param backoff 毫秒
     */
    protected fun localEntry(value: V?, ttl: Duration?, maxAge: Duration, backoff: Long = 0): LocalEntry<V> {
        val now = clock.nowMillis()
        val remoteExpiresAt = ttl?.let { now + it.toMillis() } ?: FOREVER
        val expiresAt = minOf(remoteExpiresAt, now + maxAge.toMillis())
        val refreshAt = if (value != null) refreshAtOf(now, remoteExpiresAt, backoff) else FOREVER
        return LocalEntry(value, expiresAt, refreshAt)
    }

    private fun refreshAtOf(now: Long, remoteExpiresAt: Long, backoff: Long): Long {
        val window = policy.refreshWindow
        if (window == null || remoteExpiresAt == FOREVER) {
            return FOREVER
        }
        val refreshAt = remoteExpiresAt - window.toMillis()
        if (refreshAt > now) {
            return refreshAt
        }
        val delay = minOf(backoff, (remoteExpiresAt - now) / 2)
        return if (delay > 0) now + delay else FOREVER
    }

    protected fun lookupOf(entry: LocalEntry<V>): Lookup<V> {
        // L2 副本总是受 localMaxAge 限龄，因此到期时间总是有限的
        val expiresAt = Instant.ofEpochMilli(entry.expiresAt)
        return entry.value?.let { Lookup.Present(it, expiresAt) } ?: Lookup.Absent(expiresAt)
    }

    /**
     * 写入方顺序（RFC 0003 §4.4；协程核心，同步与异步共用，RFC 0005 V5）：戳 → 淘汰 L2 → 修改 L1（并广播）→ 戳 → 淘汰 L2。
     * 写入方从不直接写 L2。
     *
     * 第二次递增使“在第一次递增之后取戳、在修改 L1 之前读到旧值”的并发填充失效；
     * 本节点自己的广播会被过滤，不能依赖它清除本地副本。
     *
     * 非原子广播时 L1 失败也尽力广播：失败的写入可能已部分生效（`putAll` 跨多个 slot 组时有的组已写入，
     * 或写入已在 L1 生效却报错），不广播的话其它节点会一直读旧的 L2 直到 `localMaxAge`（G2）。多余的广播只淘汰副本，无害。
     * 前提是 [mutate] 返回或抛出时它的全部 L1 命令都已结束（否则广播可能先于仍在执行的命令）。
     * 例外是 [ForeignFormatException] 与 [UnsupportedOperationException]：后端拒绝了写入（或不支持这个操作），L1 原样未动，不广播。
     *
     * 同步变体在调用方线程上调用阻塞的 SPI。异步变体等待异步 SPI，从修改 L1 到第二次递增（含广播与 [onFailure]）
     * 不可取消、不经执行器，在 L1 命令的完成线程上就地继续（与 6.x 的异步路径相同，这些步骤都不阻塞、不调用用户代码）：
     * - 协程被取消（运行时关闭）也要等 L1 命令结束再第二次递增，否则在两次递增之间开始的填充会把旧值写进 L2（G2）；
     * - 执行器拒绝派发不会跳过这些步骤，也不会把写入的结果换成取消（成功的写入因此计为失败、`invalidate` 因此登记多余的重试）。
     *   只有回到调用方的那一次派发可能被拒绝，调用方看到 `RejectedExecutionException`。
     *
     * @param mutate 修改 L1（经 [l1] / [l1Command] 选择阻塞或异步的 SPI）
     * @param changed [mutate] 正常返回后是否需要广播：L1 被这次写入修改，或可能已被之前同样的写入修改（条件写入的重放：
     * 上一次已在 L1 生效、单独的广播却失败了）。不需要时非原子广播的后端不广播（原子广播的后端由存储自己决定）。
     * 戳与 L2 淘汰照常执行，只多淘汰一次本地副本
     * @param onFailure L1 失败时调用（在第二次递增之前，因此登记的状态对随后的读取可见），可以抛出
     */
    protected suspend fun write(
        keys: Collection<String>,
        mutate: suspend (ByteArray) -> Unit,
        changed: () -> Boolean = ALWAYS_CHANGED,
        onFailure: (Exception) -> Unit
    ) {
        keys.forEach {
            stamps.invalidate(it)
            local.invalidate(it)
        }
        if (runsInline()) {
            mutateAndBump(keys, mutate, changed, onFailure)
        } else {
            withContext(NonCancellable + Dispatchers.Unconfined) { mutateAndBump(keys, mutate, changed, onFailure) }
        }
    }

    private suspend fun mutateAndBump(
        keys: Collection<String>,
        mutate: suspend (ByteArray) -> Unit,
        changed: () -> Boolean,
        onFailure: (Exception) -> Unit
    ) {
        try {
            mutateAndPublish(keys, mutate, changed, onFailure)
        } finally {
            keys.forEach {
                stamps.invalidate(it)
                local.invalidate(it)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun mutateAndPublish(
        keys: Collection<String>,
        mutate: suspend (ByteArray) -> Unit,
        changed: () -> Boolean,
        onFailure: (Exception) -> Unit
    ) {
        try {
            val frame = frameOf(keys)
            try {
                mutate(frame)
            } catch (e: Exception) {
                if (!atomicBroadcast && !isRefusal(e)) {
                    publishAfterFailure(frame, e)
                }
                throw e
            }
            if (!atomicBroadcast && changed()) {
                publish(frame)
            }
        } catch (e: Exception) {
            metrics.writeFailure()
            onFailure(e)
        }
    }

    /**
     * 非原子广播的后端上发布失效帧：同步变体经阻塞的通道，异步变体等待异步通道。
     */
    private suspend fun publish(frame: ByteArray) {
        l1Command(
            { context.backend.channel.publish(namespace, frame) },
            { asyncChannel.publishAsync(namespace, frame) }
        )
    }

    /**
     * 写入失败之后尽力广播（见 [write]）：广播的失败只告警并附在写入的异常上，调用方看到的仍是写入的原始异常。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun publishAfterFailure(frame: ByteArray, error: Exception) {
        try {
            publish(frame)
        } catch (e: Exception) {
            error.addSuppressed(e)
            log.warn(e) { "Cache[$name] - failed to broadcast the invalidation after a failed write." }
        }
    }

    /**
     * 协程核心调用 L1：同步变体调用阻塞的 SPI（[blocking]），异步变体等待异步 SPI（[async]），失败时抛出原始异常。
     * 两种变体的 L1 命令都不可取消（[awaitUncancellable]）。
     */
    protected suspend inline fun <T> l1(blocking: () -> T, async: () -> CompletableFuture<T>): T =
        if (runsInline()) blocking() else async().awaitUncancellable()

    /**
     * 同 [l1]，用于没有结果的 L1 命令。
     */
    protected suspend inline fun l1Command(blocking: () -> Unit, async: () -> CompletableFuture<Void?>) {
        if (runsInline()) blocking() else async().awaitUncancellable()
    }

    /**
     * 异步变体的写入入口：在运行时作用域中运行 [block]，返回结果的副本（`AsyncContext.runAsync`）。
     */
    protected fun runAsyncCommand(block: suspend () -> Unit): CompletableFuture<Void?> = context.async.runAsync<Void?> {
        block()
        null
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

        /**
         * 后端没有修改 L1 就拒绝了写入：读不懂的内容，或后端不支持这个操作（SPI 默认实现）。
         */
        fun isRefusal(
            error: Throwable
        ): Boolean = error is ForeignFormatException || error is UnsupportedOperationException

        fun <V> buildLocal(policy: CachePolicy): Cache<String, LocalEntry<V>> {
            val local = policy.local
            val builder = Caffeine.newBuilder().maximumSize(local.maximumSize)
            local.initialCapacity?.let { builder.initialCapacity(it) }
            local.expireAfterAccess?.let { builder.expireAfterAccess(it) }
            return builder.build()
        }
    }
}
