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

package me.ahoo.cocache.core.memory

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.AsyncInvalidationChannel
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteConditionalRead
import me.ahoo.cocache.api.spi.RemoteConditionalWrite
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.RemoteVersionedWrite
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.api.spi.Token
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 单进程内存后端：完整实现 L1 协议（RFC 0003 §4.4）与原子广播，供测试与 TCK 使用。
 *
 * 多个运行时共享同一个实例即可模拟多节点。到期时间按注入的 [clock] 判断。
 */
class InMemoryBackend @JvmOverloads constructor(
    private val clock: CacheClock = CacheClock { System.currentTimeMillis() },
) : CoherenceBackend {
    override val atomicBroadcast: Boolean = true

    override val format: Int = FORMAT

    override val versionedWrites: Boolean = true

    private val stores = ConcurrentHashMap<String, Store>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<InvalidationListener>>()

    override val channel: InvalidationChannel = Channel()

    override fun store(namespace: String): RemoteStore = stores.computeIfAbsent(namespace) { Store(it) }

    /**
     * 模拟订阅连接断开并重新建立：通知所有订阅者重置。与 Redis 后端相同，某个订阅者的重置抛出异常（包括 `Error`）记录后继续，
     * 不影响其余订阅者。
     */
    @Suppress("TooGenericExceptionCaught")
    fun reconnect() {
        listeners.forEach { (namespace, namespaceListeners) ->
            namespaceListeners.forEach { listener ->
                try {
                    listener.onReset()
                } catch (e: Throwable) {
                    log.error(e) { "Invalidation listener reset failed on namespace[$namespace]." }
                }
            }
        }
    }

    /**
     * 与 Redis 后端相同：某个订阅者抛出异常（包括 `Error`）记录后继续分发，不影响其余订阅者，也不传给写入方；
     * `VirtualMachineError` 照常抛出。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun publish(namespace: String, frame: ByteArray) {
        val decoded = InvalidationFrame.decode(frame)
        listeners[namespace]?.forEach { listener ->
            try {
                listener.onInvalidated(decoded.keys, decoded.origin)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                log.error(e) { "Invalidation listener failed on namespace[$namespace]." }
            }
        }
    }

    private inner class Channel : AsyncInvalidationChannel {
        override fun subscribe(namespace: String, listener: InvalidationListener): Subscription {
            val namespaceListeners = listeners.computeIfAbsent(namespace) { CopyOnWriteArrayList() }
            namespaceListeners.add(listener)
            // 订阅建立即重置（与真实通道的“订阅确认后重置”一致）
            listener.onReset()
            return Subscription { namespaceListeners.remove(listener) }
        }

        override fun publish(namespace: String, frame: ByteArray) {
            this@InMemoryBackend.publish(namespace, frame)
        }

        override fun publishAsync(namespace: String, frame: ByteArray): CompletableFuture<Void?> = completing {
            publish(
                namespace,
                frame
            )
        }
    }

    private enum class State { VALUE, MISSING, SET, LEASED }

    /**
     * 不可变槽位：每次转换都生成新槽位并重设到期时间。
     *
     * @param version 值或负缓存的版本（6.8）；[Versioned.NO_VERSION] 表示没有版本。与 Redis 后端相同：带版本的槽位从不授予刷新令牌、
     *   从不被回收（版本只保存在值上，转为 lease 就丢了），只有整体写入能替换它
     */
    private class Slot(
        val state: State,
        val envelope: Envelope,
        val token: Token? = null,
        val tokenIssuedAt: Long = 0,
        val expireAt: Long = FOREVER,
        val version: Long = Versioned.NO_VERSION,
    )

    private fun versionOf(envelope: Envelope): Long = (envelope as? Envelope.Scalar)?.version ?: Versioned.NO_VERSION

    /**
     * 原生异步（RFC 0004 §4.1）：内存操作不阻塞，异步方法就地执行并返回已完成的 future，从不同步抛出。
     */
    private inner class Store(private val namespace: String) : AsyncRemoteStore {
        private val slots = ConcurrentHashMap<String, Slot>()

        private fun now() = clock.nowMillis()

        private fun expireAt(ttl: Duration?): Long = ttl?.let { now() + it.toMillis().coerceAtLeast(1) } ?: FOREVER

        private fun Slot?.live(): Slot? = this?.takeIf { it.expireAt == FOREVER || it.expireAt > now() }

        private fun remaining(slot: Slot): Duration? {
            if (slot.expireAt == FOREVER) {
                return null
            }
            return Duration.ofMillis((slot.expireAt - now()).coerceAtLeast(1))
        }

        private fun stateOf(envelope: Envelope): State = when (envelope) {
            is Envelope.Scalar -> State.VALUE
            is Envelope.Members -> State.SET
            Envelope.Absent -> State.MISSING
        }

        override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead {
            var result: RemoteRead = RemoteRead.Miss(false)
            slots.compute(key) { _, current ->
                val slot = current.live()
                when {
                    slot == null -> {
                        result = RemoteRead.Miss(lease != null)
                        lease?.let { leaseSlot(it) }
                    }

                    slot.state == State.LEASED -> {
                        result = RemoteRead.Leased(remaining(slot) ?: Duration.ZERO)
                        slot
                    }

                    refresh != null && grantsRefresh(slot, refresh) -> {
                        result = RemoteRead.Hit(slot.envelope, FORMAT, remaining(slot), refreshGranted = true)
                        Slot(slot.state, slot.envelope, refresh.token, now(), slot.expireAt, slot.version)
                    }

                    else -> {
                        result = RemoteRead.Hit(slot.envelope, FORMAT, remaining(slot))
                        slot
                    }
                }
            }
            return result
        }

        private fun leaseSlot(lease: LeaseRequest) = Slot(
            State.LEASED,
            Envelope.Absent,
            lease.token,
            now(),
            expireAt(lease.ttl)
        )

        private fun grantsRefresh(slot: Slot, refresh: RefreshRequest): Boolean {
            if (slot.state != State.VALUE || slot.expireAt == FOREVER || slot.version != Versioned.NO_VERSION) {
                return false
            }
            if (slot.expireAt - now() > refresh.window.toMillis()) {
                return false
            }
            return slot.token == null || now() - slot.tokenIssuedAt > refresh.tokenTtl.toMillis()
        }

        override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> {
            val result = LinkedHashMap<String, RemoteRead>()
            for (key in keys) {
                result[key] = read(key, leases[key], null)
            }
            return result
        }

        override fun fill(fill: Fill): Boolean {
            var filled = false
            slots.compute(fill.key) { _, current ->
                val slot = current.live()
                if (slot?.token == fill.token) {
                    filled = true
                    val envelope = withVersion(fill.envelope, fill.version)
                    Slot(stateOf(envelope), envelope, expireAt = expireAt(fill.ttl), version = fill.version)
                } else {
                    slot
                }
            }
            return filled
        }

        /**
         * 填充的版本（墓碑的版本只能经 [Fill.version] 传递）：标量的信封与槽位带同一个版本。
         */
        private fun withVersion(envelope: Envelope, version: Long): Envelope {
            if (envelope !is Envelope.Scalar) {
                return envelope
            }
            return Envelope.Scalar(envelope.bytes, version)
        }

        override fun fillAll(fills: List<Fill>): List<Boolean> = fills.map { fill(it) }

        override fun release(key: String, token: Token) {
            slots.compute(key) { _, current ->
                val slot = current.live()
                when {
                    slot == null || slot.token != token -> slot
                    slot.state == State.LEASED -> null
                    else -> Slot(slot.state, slot.envelope, expireAt = slot.expireAt, version = slot.version)
                }
            }
        }

        override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
            slots[key] = Slot(stateOf(envelope), envelope, expireAt = expireAt(ttl), version = versionOf(envelope))
            publish(namespace, frame)
        }

        /**
         * 每个 key 的转换同 [put]，整批只广播一次 [frame]。
         */
        override fun putAll(puts: List<Put>, frame: ByteArray) {
            puts.forEach {
                slots[it.key] = Slot(stateOf(it.envelope), it.envelope, expireAt = expireAt(it.ttl), version = versionOf(it.envelope))
            }
            publish(namespace, frame)
        }

        override fun invalidate(keys: Collection<String>, frame: ByteArray) {
            keys.forEach { slots.remove(it) }
            publish(namespace, frame)
        }

        override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean {
            var reclaimed = false
            slots.compute(key) { _, current ->
                val slot = current.live()
                if (slot != null && reclaimable(slot) && Digest.of(slot.envelope) == observed) {
                    reclaimed = true
                    leaseSlot(lease)
                } else {
                    slot
                }
            }
            return reclaimed
        }

        /**
         * lease 占位没有可回收的内容；带版本的槽位不回收（转为 lease 会丢掉版本，与 Redis 后端相同）。
         */
        private fun reclaimable(
            slot: Slot
        ): Boolean = slot.state != State.LEASED && slot.version == Versioned.NO_VERSION

        override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
            addMembers(key, members, ttl, frame, checked = false)
        }

        override fun addMembersIfReadable(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int? =
            addMembers(key, members, ttl, frame, checked = true)

        /**
         * 本后端只存本协议的内容：不能当作集合的只有标量值（[checked] 时拒绝，返回本后端的格式版本）。
         */
        private fun addMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray,
            checked: Boolean
        ): Int? {
            var refused: Int? = null
            slots.compute(key) { _, current ->
                val slot = current.live()
                if (checked && slot?.state == State.VALUE) {
                    refused = FORMAT
                    return@compute slot
                }
                val existing = if (slot?.state == State.SET) (slot.envelope as Envelope.Members).members else emptyList()
                Slot(State.SET, Envelope.Members(union(existing, members)), expireAt = expireAt(ttl))
            }
            if (refused == null) {
                publish(namespace, frame)
            }
            return refused
        }

        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
            removeMembers(key, members, ttl, frame, checked = false)
        }

        override fun removeMembersIfReadable(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int? =
            removeMembers(key, members, ttl, frame, checked = true)

        private fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray, checked: Boolean): Int? {
            var refused: Int? = null
            slots.compute(key) { _, current ->
                val slot = current.live()
                if (checked && slot?.state == State.VALUE) {
                    refused = FORMAT
                    slot
                } else if (slot?.state != State.SET) {
                    slot
                } else {
                    val remaining = (slot.envelope as Envelope.Members).members.filter { member ->
                        members.none {
                            it.contentEquals(member)
                        }
                    }
                    if (remaining.isEmpty()) {
                        null
                    } else {
                        Slot(
                            State.SET,
                            Envelope.Members(remaining),
                            expireAt = expireAt(ttl)
                        )
                    }
                }
            }
            if (refused == null) {
                publish(namespace, frame)
            }
            return refused
        }

        /**
         * 本后端只存本协议的内容：读不懂的只有集合（返回本后端的格式版本）。
         */
        override fun readIfChanged(key: String, known: Digest?): RemoteConditionalRead {
            val slot = slots[key].live()
            return when (slot?.state) {
                null, State.LEASED, State.MISSING -> RemoteConditionalRead.Absent
                State.SET -> RemoteConditionalRead.Unreadable(FORMAT)
                State.VALUE -> {
                    val envelope = slot.envelope as Envelope.Scalar
                    if (known != null && Digest.of(envelope) == known) {
                        RemoteConditionalRead.Unchanged(FORMAT)
                    } else {
                        RemoteConditionalRead.Changed(envelope, FORMAT)
                    }
                }
            }
        }

        /**
         * 比较与写入在同一次 `compute` 中完成（对单个 key 原子）；只在写入时广播。本后端只存本协议的内容，没有格式 0 的内容，
         * 因此 [foreignAsAbsent] 不起作用：集合总是拒绝。
         */
        override fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray,
            foreignAsAbsent: Boolean
        ): RemoteConditionalWrite {
            var outcome: RemoteConditionalWrite = RemoteConditionalWrite.Written
            slots.compute(key) { _, current ->
                val slot = current.live()
                val digest = when (slot?.state) {
                    null, State.LEASED, State.MISSING -> null
                    State.VALUE -> Digest.of(slot.envelope)
                    State.SET -> {
                        outcome = RemoteConditionalWrite.Unreadable(FORMAT)
                        return@compute slot
                    }
                }
                if (digest != expected) {
                    outcome = RemoteConditionalWrite.Conflict(digest)
                    return@compute slot
                }
                // 与 Redis 的 PUT_IF_MATCH 相同：条件写入写入没有版本的值
                Slot(State.VALUE, Envelope.Scalar(envelope.bytes), expireAt = expireAt(ttl))
            }
            if (outcome == RemoteConditionalWrite.Written) {
                publish(namespace, frame)
            }
            return outcome
        }

        /**
         * 比较版本与写入在同一次 `compute` 中完成（对单个 key 原子）；只在写入时广播。没有版本的值与负缓存、lease、不存在都总是写入；
         * 集合总是拒绝（本后端没有格式 0 的内容，[foreignAsAbsent] 不起作用）。
         */
        override fun putIfNewer(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray,
            foreignAsAbsent: Boolean
        ): RemoteVersionedWrite {
            require(envelope !is Envelope.Members) { "putIfNewer takes a scalar value or Envelope.Absent, not a set." }
            var outcome: RemoteVersionedWrite = RemoteVersionedWrite.Written
            slots.compute(key) { _, current ->
                val slot = current.live()
                if (slot?.state == State.SET) {
                    outcome = RemoteVersionedWrite.Unreadable(FORMAT)
                    return@compute slot
                }
                if (slot != null && slot.version != Versioned.NO_VERSION && slot.version >= version) {
                    outcome = RemoteVersionedWrite.Stale(slot.version)
                    return@compute slot
                }
                val stored = if (envelope is Envelope.Scalar) Envelope.Scalar(envelope.bytes, version) else envelope
                Slot(stateOf(envelope), stored, expireAt = expireAt(ttl), version = version)
            }
            if (outcome == RemoteVersionedWrite.Written) {
                publish(namespace, frame)
            }
            return outcome
        }

        override fun putIfNewerAsync(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray,
            foreignAsAbsent: Boolean
        ): CompletableFuture<RemoteVersionedWrite> = supply {
            putIfNewer(key, envelope, version, ttl, frame, foreignAsAbsent)
        }

        override fun readIfChangedAsync(key: String, known: Digest?): CompletableFuture<RemoteConditionalRead> =
            supply { readIfChanged(key, known) }

        override fun putIfMatchAsync(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray,
            foreignAsAbsent: Boolean
        ): CompletableFuture<RemoteConditionalWrite> = supply {
            putIfMatch(key, expected, envelope, ttl, frame, foreignAsAbsent)
        }

        override fun readAsync(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead> =
            supply { read(key, lease, refresh) }

        override fun readAllAsync(keys: Collection<String>, leases: Map<String, LeaseRequest>): CompletableFuture<Map<String, RemoteRead>> =
            supply { readAll(keys, leases) }

        override fun fillAsync(fill: Fill): CompletableFuture<Boolean> = supply { fill(fill) }

        override fun fillAllAsync(fills: List<Fill>): CompletableFuture<List<Boolean>> = supply { fillAll(fills) }

        override fun releaseAsync(key: String, token: Token): CompletableFuture<Void?> = completing {
            release(
                key,
                token
            )
        }

        override fun putAsync(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
            completing { put(key, envelope, ttl, frame) }

        override fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = completing {
            putAll(
                puts,
                frame
            )
        }

        override fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> = completing {
            invalidate(
                keys,
                frame
            )
        }

        override fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
            supply { reclaim(key, observed, lease) }

        override fun addMembersAsync(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
            completing { addMembers(key, members, ttl, frame) }

        override fun removeMembersAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Void?> = completing { removeMembers(key, members, ttl, frame) }

        override fun addMembersIfReadableAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Int?> = supply { addMembersIfReadable(key, members, ttl, frame) }

        override fun removeMembersIfReadableAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Int?> = supply { removeMembersIfReadable(key, members, ttl, frame) }

        private fun union(existing: List<ByteArray>, added: Collection<ByteArray>): List<ByteArray> {
            val result = ArrayList(existing)
            for (member in added) {
                if (result.none { it.contentEquals(member) }) {
                    result.add(member)
                }
            }
            return result
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> supply(block: () -> T): CompletableFuture<T> {
        return try {
            CompletableFuture.completedFuture(block())
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
    }

    private inline fun completing(block: () -> Unit): CompletableFuture<Void?> = supply {
        block()
        null
    }

    companion object {
        const val FORMAT = 1
        private const val FOREVER = Long.MAX_VALUE
        private val log = KotlinLogging.logger {}
    }
}
