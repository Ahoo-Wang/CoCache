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
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.internal.InvalidationStamps
import me.ahoo.cocache.core.internal.SingleFlight
import me.ahoo.cocache.core.policy.CachePolicy
import java.time.Duration
import java.time.Instant
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
    override val metrics = CacheMetrics()

    protected val clock = context.clock
    protected val stamps = InvalidationStamps()
    protected val flights = SingleFlight<String, Loaded<V>>()
    protected val local: Cache<String, LocalEntry<V>> = buildLocal(policy)
    protected val closed = AtomicBoolean(false)
    private val nodeId: NodeId = context.nodeId
    private val atomicBroadcast = context.backend.atomicBroadcast
    private val storagePrefix = "$keyPrefix$namespace:"

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
     * @param onFailure L1 失败时调用（在第二次递增之前，因此登记的状态对随后的读取可见）
     */
    @Suppress("TooGenericExceptionCaught")
    protected fun write(keys: Collection<String>, mutate: (ByteArray) -> Unit, onFailure: (Exception) -> Unit) {
        keys.forEach {
            stamps.invalidate(it)
            local.invalidate(it)
        }
        try {
            val frame = frameOf(keys)
            mutate(frame)
            if (!atomicBroadcast) {
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

        fun <V> buildLocal(policy: CachePolicy): Cache<String, LocalEntry<V>> {
            val local = policy.local
            val builder = Caffeine.newBuilder().maximumSize(local.maximumSize)
            local.initialCapacity?.let { builder.initialCapacity(it) }
            local.expireAfterAccess?.let { builder.expireAfterAccess(it) }
            return builder.build()
        }
    }
}
