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

import me.ahoo.cocache.api.CacheClock
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
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
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

    private val stores = ConcurrentHashMap<String, Store>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<InvalidationListener>>()

    override val channel: InvalidationChannel = Channel()

    override fun store(namespace: String): RemoteStore = stores.computeIfAbsent(namespace) { Store(it) }

    /**
     * 模拟订阅连接断开并重新建立：通知所有订阅者重置。
     */
    fun reconnect() {
        listeners.values.forEach { namespaceListeners -> namespaceListeners.forEach { it.onReset() } }
    }

    private fun publish(namespace: String, frame: ByteArray) {
        val decoded = InvalidationFrame.decode(frame)
        listeners[namespace]?.forEach { it.onInvalidated(decoded.keys, decoded.origin) }
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
     */
    private class Slot(
        val state: State,
        val envelope: Envelope,
        val token: Token? = null,
        val tokenIssuedAt: Long = 0,
        val expireAt: Long = FOREVER,
    )

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
                        Slot(slot.state, slot.envelope, refresh.token, now(), slot.expireAt)
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
            if (slot.state != State.VALUE || slot.expireAt == FOREVER) {
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
                    Slot(stateOf(fill.envelope), fill.envelope, expireAt = expireAt(fill.ttl))
                } else {
                    slot
                }
            }
            return filled
        }

        override fun fillAll(fills: List<Fill>): List<Boolean> = fills.map { fill(it) }

        override fun release(key: String, token: Token) {
            slots.compute(key) { _, current ->
                val slot = current.live()
                when {
                    slot == null || slot.token != token -> slot
                    slot.state == State.LEASED -> null
                    else -> Slot(slot.state, slot.envelope, expireAt = slot.expireAt)
                }
            }
        }

        override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
            slots[key] = Slot(stateOf(envelope), envelope, expireAt = expireAt(ttl))
            publish(namespace, frame)
        }

        /**
         * 每个 key 的转换同 [put]，整批只广播一次 [frame]。
         */
        override fun putAll(puts: List<Put>, frame: ByteArray) {
            puts.forEach { slots[it.key] = Slot(stateOf(it.envelope), it.envelope, expireAt = expireAt(it.ttl)) }
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
                if (slot != null && slot.state != State.LEASED && Digest.of(slot.envelope) == observed) {
                    reclaimed = true
                    leaseSlot(lease)
                } else {
                    slot
                }
            }
            return reclaimed
        }

        override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
            slots.compute(key) { _, current ->
                val slot = current.live()
                val existing = if (slot?.state == State.SET) (slot.envelope as Envelope.Members).members else emptyList()
                Slot(State.SET, Envelope.Members(union(existing, members)), expireAt = expireAt(ttl))
            }
            publish(namespace, frame)
        }

        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
            slots.compute(key) { _, current ->
                val slot = current.live()
                if (slot?.state != State.SET) {
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
            publish(namespace, frame)
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
    }
}
