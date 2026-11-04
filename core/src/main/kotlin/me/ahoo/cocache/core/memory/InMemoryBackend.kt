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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.api.spi.VersionedL1Store
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException

/**
 * 单进程内存后端：完整实现 L1 协议（RFC 0003 §4.4，挂起形状见 RFC 0005 §4.3）与原子广播，提供全部可选能力，供测试与 TCK 使用。
 *
 * 多个运行时共享同一个实例即可模拟多节点。到期时间按注入的 [clock] 判断。存储与频道都按 [Keyspace] 划分：前缀不同、命名空间相同的
 * 两个缓存既不共用 key，也收不到彼此的帧。没有访问控制，创建存储时不探测频道。
 *
 * @param commandTimeout 每个存储操作的时限：只在 [stall] 期间起作用（内存操作本身不等待）；读取的 `timeout` 以它为上限
 */
class InMemoryBackend @JvmOverloads constructor(
    private val clock: CacheClock = CacheClock { System.currentTimeMillis() },
    private val commandTimeout: Duration = DEFAULT_COMMAND_TIMEOUT,
) : CoherenceBackend {
    override val atomicBroadcast: Boolean = true

    override val format: Int = FORMAT

    private val stores = ConcurrentHashMap<Keyspace, Store>()
    private val listeners = ConcurrentHashMap<Keyspace, CopyOnWriteArrayList<InvalidationListener>>()

    override val channel: InvalidationChannel = Channel()

    override fun store(keyspace: Keyspace): L1Store = stores.computeIfAbsent(keyspace) { Store(it) }

    /**
     * 卡顿中时为恢复前一直未完成的门（[stall]），否则为 `null`。
     */
    @Volatile
    private var stalled: CompletableDeferred<Unit>? = null

    /**
     * 模拟 L1 卡顿（供 TCK 检验读取的时限，RFC 0005 round 3 D6）：之后开始的存储操作等到恢复再执行，最多等它的时限
     * （读取为 `timeout` 与 [commandTimeout] 中较短的一个，其它操作为 [commandTimeout]）。超时的操作以 L1 故障
     * （[IllegalStateException]，cause 为 [TimeoutException]）失败，并且永远不执行：相当于尚未写出的命令不再写出，
     * 不会取得令牌。关闭返回值即恢复，等待中的操作随即执行。
     */
    fun stall(): AutoCloseable {
        val gate = CompletableDeferred<Unit>()
        stalled = gate
        return AutoCloseable {
            if (stalled === gate) {
                stalled = null
            }
            gate.complete(Unit)
        }
    }

    /**
     * 卡顿中时等到恢复，最多等 [timeout]（`null` 为 [commandTimeout]，给出时以它为上限）；超时时抛出，调用方不再执行操作。
     */
    private suspend fun awaitRunnable(timeout: Duration?) {
        if (timeout != null) {
            require(!timeout.isNegative && !timeout.isZero) { "timeout[$timeout] must be positive." }
        }
        val gate = stalled ?: return
        val bound = timeout?.coerceAtMost(commandTimeout) ?: commandTimeout
        withTimeoutOrNull(bound.toMillis()) { gate.await() }
            ?: throw IllegalStateException("In-memory L1 command timed out after $bound.", TimeoutException())
    }

    /**
     * 模拟订阅连接断开并重新建立：通知所有订阅者重置。与 Redis 后端相同，某个订阅者的重置抛出异常（包括 `Error`）记录后继续，
     * 不影响其余订阅者。
     */
    fun reconnect() {
        listeners.forEach { (keyspace, keyspaceListeners) -> reset(keyspace, keyspaceListeners) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun reset(keyspace: Keyspace, keyspaceListeners: List<InvalidationListener>) {
        keyspaceListeners.forEach { listener ->
            try {
                listener.onReset()
            } catch (e: Throwable) {
                log.error(e) { "Invalidation listener reset failed on $keyspace." }
            }
        }
    }

    /**
     * 与 Redis 后端相同：某个订阅者抛出异常（包括 `Error`）记录后继续分发，不影响其余订阅者，也不传给写入方；
     * `VirtualMachineError` 照常抛出。读不懂的帧（未知版本、解码失败）按该 keyspace 的重置处理，不丢弃（RFC 0005 §4.7，D3）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun publish(keyspace: Keyspace, frame: ByteArray) {
        val keyspaceListeners = listeners[keyspace] ?: return
        val decoded = try {
            InvalidationFrame.decode(frame)
        } catch (e: IllegalArgumentException) {
            log.warn(e) { "Unreadable invalidation frame on $keyspace - reset subscribers." }
            reset(keyspace, keyspaceListeners)
            return
        }
        keyspaceListeners.forEach { listener ->
            try {
                listener.onInvalidated(decoded.keys, decoded.origin)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                log.error(e) { "Invalidation listener failed on $keyspace." }
            }
        }
    }

    private inner class Channel : InvalidationChannel {
        override fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription {
            val keyspaceListeners = listeners.computeIfAbsent(keyspace) { CopyOnWriteArrayList() }
            keyspaceListeners.add(listener)
            // 订阅建立即重置（与真实通道的“订阅确认后重置”一致）
            listener.onReset()
            return Subscription { keyspaceListeners.remove(listener) }
        }

        override suspend fun publish(keyspace: Keyspace, frame: ByteArray) {
            this@InMemoryBackend.publish(keyspace, frame)
        }
    }

    private enum class State { VALUE, MISSING, SET, LEASED }

    /**
     * 不可变槽位：每次转换都生成新槽位并重设到期时间。
     *
     * @param version 值或负缓存的版本；[Versioned.NO_VERSION] 表示没有版本。与 Redis 后端相同：带版本的槽位从不授予刷新令牌、
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
     * 原生的 [L1Store]（RFC 0005 §4.3）：内存操作不阻塞，挂起方法就地执行、不挂起（[stall] 期间除外）。三个能力面都由存储自己实现，
     * 访问器返回它本身。
     */
    @Suppress("TooManyFunctions")
    private inner class Store(
        private val keyspace: Keyspace
    ) : L1Store, ConditionalL1Store, VersionedL1Store, SetL1Store {
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

        override fun conditional(): ConditionalL1Store = this

        override fun versioned(): VersionedL1Store = this

        override fun sets(): SetL1Store = this

        override suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read {
            awaitRunnable(timeout)
            return readNow(key, lease, refresh)
        }

        private fun readNow(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): L1Read {
            var result: L1Read = L1Read.Miss(false)
            slots.compute(key) { _, current ->
                val slot = current.live()
                when {
                    slot == null -> {
                        result = L1Read.Miss(lease != null)
                        lease?.let { leaseSlot(it) }
                    }

                    slot.state == State.LEASED -> {
                        result = L1Read.Leased(remaining(slot) ?: Duration.ZERO)
                        slot
                    }

                    refresh != null && grantsRefresh(slot, refresh) -> {
                        result = L1Read.Hit(slot.envelope, FORMAT, remaining(slot), refreshGranted = true)
                        Slot(slot.state, slot.envelope, refresh.token, now(), slot.expireAt, slot.version)
                    }

                    else -> {
                        result = L1Read.Hit(slot.envelope, FORMAT, remaining(slot))
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

        override suspend fun readAll(
            keys: Collection<String>,
            leases: Map<String, LeaseRequest>,
            timeout: Duration?
        ): Map<String, L1Read> {
            awaitRunnable(timeout)
            val result = LinkedHashMap<String, L1Read>()
            for (key in keys) {
                result[key] = readNow(key, leases[key], null)
            }
            return result
        }

        override suspend fun fill(fill: Fill): L1Fill {
            awaitRunnable(null)
            return fillNow(fill)
        }

        /**
         * 令牌匹配即写入：本后端的 lease 不带版本，填充从不让版本前进（[L1Fill.Filled.advanced] 为 `false`），也没有版本拒绝。
         */
        private fun fillNow(fill: Fill): L1Fill {
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
            return if (filled) FILLED else TOKEN_MISMATCH
        }

        /**
         * 填充的版本（墓碑的版本只能经 [Fill.version] 传递）：标量的信封与槽位带同一个版本，写入标识原样保存。
         */
        private fun withVersion(envelope: Envelope, version: Long): Envelope {
            if (envelope !is Envelope.Scalar) {
                return envelope
            }
            return Envelope.Scalar(envelope.bytes, version, envelope.writer)
        }

        override suspend fun fillAll(fills: List<Fill>): List<L1Fill> {
            awaitRunnable(null)
            return fills.map { fillNow(it) }
        }

        override suspend fun release(key: String, token: Token) {
            awaitRunnable(null)
            slots.compute(key) { _, current ->
                val slot = current.live()
                when {
                    slot == null || slot.token != token -> slot
                    slot.state == State.LEASED -> null
                    else -> Slot(slot.state, slot.envelope, expireAt = slot.expireAt, version = slot.version)
                }
            }
        }

        override suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
            awaitRunnable(null)
            slots[key] = Slot(stateOf(envelope), envelope, expireAt = expireAt(ttl), version = versionOf(envelope))
            publish(keyspace, frame)
        }

        /**
         * 每个 key 的转换同 [put]，整批只广播一次 [frame]。
         */
        override suspend fun putAll(puts: List<Put>, frame: ByteArray) {
            awaitRunnable(null)
            puts.forEach {
                slots[it.key] = Slot(stateOf(it.envelope), it.envelope, expireAt = expireAt(it.ttl), version = versionOf(it.envelope))
            }
            publish(keyspace, frame)
        }

        override suspend fun invalidate(keys: Collection<String>, frame: ByteArray) {
            awaitRunnable(null)
            keys.forEach { slots.remove(it) }
            publish(keyspace, frame)
        }

        override suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean {
            awaitRunnable(null)
            var reclaimed = false
            slots.compute(key) { _, current ->
                val slot = current.live()
                if (slot != null && reclaimable(slot) && ContentFingerprint.of(slot.envelope) == observed) {
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

        /**
         * 本后端只存本协议的内容：不能当作集合的只有标量值（拒绝，返回本后端的格式版本）。
         */
        override suspend fun addMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): Int? {
            awaitRunnable(null)
            var refused: Int? = null
            slots.compute(key) { _, current ->
                val slot = current.live()
                if (slot?.state == State.VALUE) {
                    refused = FORMAT
                    return@compute slot
                }
                val existing = if (slot?.state == State.SET) (slot.envelope as Envelope.Members).members else emptyList()
                Slot(State.SET, Envelope.Members(union(existing, members)), expireAt = expireAt(ttl))
            }
            if (refused == null) {
                publish(keyspace, frame)
            }
            return refused
        }

        /**
         * 同 [addMembers]：标量值拒绝；不存在、负缓存、lease 占位不修改。
         */
        override suspend fun removeMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): Int? {
            awaitRunnable(null)
            var refused: Int? = null
            slots.compute(key) { _, current ->
                val slot = current.live()
                if (slot?.state == State.VALUE) {
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
                publish(keyspace, frame)
            }
            return refused
        }

        /**
         * 本后端只存本协议的内容：读不懂的只有集合（返回本后端的格式版本）。
         */
        override suspend fun readIfChanged(key: String, known: Digest?, timeout: Duration?): L1ConditionalRead {
            awaitRunnable(timeout)
            val slot = slots[key].live()
            return when (slot?.state) {
                null, State.LEASED, State.MISSING -> L1ConditionalRead.Absent
                State.SET -> L1ConditionalRead.Unreadable(FORMAT)
                State.VALUE -> {
                    val envelope = slot.envelope as Envelope.Scalar
                    if (known != null && Digest.of(envelope) == known) {
                        L1ConditionalRead.Unchanged(FORMAT)
                    } else {
                        L1ConditionalRead.Changed(envelope, FORMAT)
                    }
                }
            }
        }

        /**
         * 比较与写入在同一次 `compute` 中完成（对单个 key 原子）；只在写入时广播。本后端只存本协议的内容：读不懂的只有集合，总是拒绝。
         */
        override suspend fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): L1ConditionalWrite {
            awaitRunnable(null)
            var outcome: L1ConditionalWrite = L1ConditionalWrite.Written
            slots.compute(key) { _, current ->
                val slot = current.live()
                val digest = when (slot?.state) {
                    null, State.LEASED, State.MISSING -> null
                    State.VALUE -> Digest.of(slot.envelope)
                    State.SET -> {
                        outcome = L1ConditionalWrite.Unreadable(FORMAT)
                        return@compute slot
                    }
                }
                if (digest != expected) {
                    outcome = L1ConditionalWrite.Conflict(digest)
                    return@compute slot
                }
                // 与 Redis 的 PUT_IF_MATCH 相同：条件写入写入没有版本的值，写入标识原样保存
                Slot(
                    State.VALUE,
                    Envelope.Scalar(envelope.bytes, Versioned.NO_VERSION, envelope.writer),
                    expireAt = expireAt(ttl)
                )
            }
            if (outcome == L1ConditionalWrite.Written) {
                publish(keyspace, frame)
            }
            return outcome
        }

        /**
         * 比较版本与写入在同一次 `compute` 中完成（对单个 key 原子）；只在写入时广播。没有版本的值与负缓存、lease、不存在都总是写入；
         * 集合总是拒绝（本后端只存本协议的内容，读不懂的只有集合）。
         */
        override suspend fun putIfNewer(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray
        ): L1VersionedWrite {
            // 与 Redis 后端相同：版本段中不得是 NO_VERSION（RFC 0005 §4.7，K15）
            require(
                version != Versioned.NO_VERSION
            ) { "putIfNewer takes a version, not Versioned.NO_VERSION (Long.MIN_VALUE)." }
            require(envelope !is Envelope.Members) { "putIfNewer takes a scalar value or Envelope.Absent, not a set." }
            awaitRunnable(null)
            var outcome: L1VersionedWrite = L1VersionedWrite.Written
            slots.compute(key) { _, current ->
                val slot = current.live()
                if (slot?.state == State.SET) {
                    outcome = L1VersionedWrite.Unreadable(FORMAT)
                    return@compute slot
                }
                if (slot != null && slot.version != Versioned.NO_VERSION && slot.version >= version) {
                    outcome = L1VersionedWrite.Stale(slot.version)
                    return@compute slot
                }
                val stored = if (envelope is Envelope.Scalar) {
                    Envelope.Scalar(
                        envelope.bytes,
                        version,
                        envelope.writer
                    )
                } else {
                    envelope
                }
                Slot(stateOf(envelope), stored, expireAt = expireAt(ttl), version = version)
            }
            if (outcome == L1VersionedWrite.Written) {
                publish(keyspace, frame)
            }
            return outcome
        }

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

    companion object {
        const val FORMAT = 2
        private const val FOREVER = Long.MAX_VALUE

        /**
         * 默认的命令超时（与 Redis 后端相同）。
         */
        @JvmField
        val DEFAULT_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val FILLED: L1Fill = L1Fill.Filled(advanced = false)
        private val TOKEN_MISMATCH: L1Fill = L1Fill.Rejected(currentVersion = null)
        private val log = KotlinLogging.logger {}
    }
}
