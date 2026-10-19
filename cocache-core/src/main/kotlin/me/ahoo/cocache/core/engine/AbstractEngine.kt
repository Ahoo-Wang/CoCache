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
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.KeyEncoder
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

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            log.info { "Cache[$name] - close." }
        }
    }

    override fun toString(): String = "${javaClass.simpleName}(name='$name', namespace='$namespace')"

    protected companion object {
        val log = KotlinLogging.logger {}

        fun <V> buildLocal(policy: CachePolicy): Cache<String, LocalEntry<V>> {
            val local = policy.local
            val builder = Caffeine.newBuilder().maximumSize(local.maximumSize)
            local.initialCapacity?.let { builder.initialCapacity(it) }
            local.expireAfterAccess?.let { builder.expireAfterAccess(it) }
            return builder.build()
        }
    }
}
