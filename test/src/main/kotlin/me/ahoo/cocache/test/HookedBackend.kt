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

package me.ahoo.cocache.test

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.ForwardingConditionalL1Store
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.ForwardingSetL1Store
import me.ahoo.cocache.api.spi.ForwardingVersionedL1Store
import me.ahoo.cocache.api.spi.InvalidationChannel
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
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.api.spi.VersionedL1Store
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 模拟的 L1 故障。
 */
class RemoteUnavailableException(operation: String) : RuntimeException("Remote store unavailable: $operation.")

/**
 * 为任意后端注入钩子与故障的装饰器，供 TCK 编排竞态（用 latch，不用 sleep）。存储继承 [ForwardingL1Store]，
 * 能力面继承对应的 `Forwarding*` 基类：被包装的存储没有某个能力面时，这里也没有（RFC 0005 §4.3）。
 *
 * 钩子在被包装的存储返回之后执行：调用仍在发起它的线程上时就地执行（同步变体即调用方线程）；调用在别的线程上恢复时
 * （L1 客户端的 I/O 线程、运行时的执行器线程）改在 [HookedBackend] 自己的线程上执行，钩子可能阻塞（latch），
 * 不能停住共享连接上的全部命令。执行期间 [issuer] 返回发起调用的线程名。
 */
class HookedBackend(val delegate: CoherenceBackend) : CoherenceBackend {
    /**
     * 覆盖后端的原子广播能力，用于验证引擎在非原子后端上的行为（为 `false` 时引擎在写入之后另经通道发布）。
     */
    @Volatile
    var atomicBroadcastOverride: Boolean? = null

    override val atomicBroadcast: Boolean
        get() = atomicBroadcastOverride ?: delegate.atomicBroadcast
    override val format: Int
        get() = delegate.format
    override val channel: InvalidationChannel
        get() = delegate.channel

    /**
     * 所有存储操作抛出 [RemoteUnavailableException]。
     */
    @Volatile
    var failAll: Boolean = false

    /**
     * 写入类操作（put、putAll、invalidate、成员增删、条件与按版本的写入）抛出 [RemoteUnavailableException]。
     */
    @Volatile
    var failWrites: Boolean = false

    /**
     * 释放令牌时抛出 [RemoteUnavailableException]。
     */
    @Volatile
    var failRelease: Boolean = false

    /**
     * 填充（含批量）时抛出 [RemoteUnavailableException]。
     */
    @Volatile
    var failFill: Boolean = false

    /**
     * 单 key 读取返回之前调用，可以改写结果。
     */
    @Volatile
    var afterRead: ((String, L1Read) -> L1Read)? = null

    /**
     * 批量读取返回之前调用，可以改写结果。
     */
    @Volatile
    var afterReadAll: ((Map<String, L1Read>) -> Map<String, L1Read>)? = null

    /**
     * put 在 L1 生效之后、返回之前调用；putAll 在整批生效之后对每个 key 各调用一次。
     */
    @Volatile
    var afterPut: ((String) -> Unit)? = null

    /**
     * invalidate 修改 L1 之前调用。
     */
    @Volatile
    var beforeInvalidate: ((Collection<String>) -> Unit)? = null

    /**
     * invalidate 在 L1 生效之后、返回之前调用；抛出即模拟“已生效但向调用方报错”的写入（如应答超时）。
     */
    @Volatile
    var afterInvalidate: ((Collection<String>) -> Unit)? = null

    /**
     * 单 key fill 在 L1 执行之后、返回之前调用，参数为 key 与结果（[L1Fill.Filled] 即写入）。
     */
    @Volatile
    var afterFill: ((String, L1Fill) -> Unit)? = null

    /**
     * 按版本写入（`putIfNewer`，6.8）在 L1 执行之后、返回之前调用，参数为 key 与结果；抛出即模拟“已生效但向调用方报错”的写入。
     */
    @Volatile
    var afterVersionedWrite: ((String, L1VersionedWrite) -> Unit)? = null

    /**
     * 每个存储调用（含钩子）结束之后，改在这个执行器上恢复调用方的协程（模拟 L1 客户端的 I/O 线程，RFC 0004 §10.1）；
     * 为 `null` 时就地返回。成功与失败都经它送达。
     */
    @Volatile
    var ioExecutor: Executor? = null

    /**
     * 发起当前存储调用的线程名：钩子用它代替 `Concurrency.currentThreadName()` 编排竞态。
     * 钩子在被包装的存储返回之后执行，可能已不在发起调用的线程上（异步变体在执行器线程上恢复），执行期间这里返回发起调用的线程名。
     */
    fun issuer(): String = issuerName.get() ?: Concurrency.currentThreadName()

    private val issuerName = ThreadLocal<String?>()

    /**
     * 在别的线程上恢复的调用的钩子在这里执行（见类注释）。
     */
    private val hookExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "tck-hook").apply { isDaemon = true }
    }

    /**
     * [close] 时是否同时关闭被包装的后端。默认 `false`（与此前的行为相同）：被包装的后端可能是共享的，或由创建方自己关闭。
     * 规范的 `closesBackend` 为 `true` 时开启（每个用例新建后端的实现，如 Redis，应当开启，否则客户端与线程在用例之间泄漏）。
     */
    @Volatile
    var closesDelegate: Boolean = false

    /**
     * 停止钩子线程；[closesDelegate] 为 `true` 时同时关闭被包装的后端。
     */
    override fun close() {
        hookExecutor.shutdownNow()
        if (closesDelegate) {
            delegate.close()
        }
    }

    private fun <T> asIssuer(issuer: String, block: () -> T): T {
        val previous = issuerName.get()
        issuerName.set(issuer)
        try {
            return block()
        } finally {
            if (previous == null) issuerName.remove() else issuerName.set(previous)
        }
    }

    /**
     * 各类存储调用的次数（单 key 读取、批量读取、填充、单 key 覆盖写入、批量覆盖写入）：在调用被包装的存储之前计数，
     * 故障开关拒绝的调用不计。用例用它等到某个调用已经到达被包装的存储。
     */
    val reads = AtomicInteger()
    val readAlls = AtomicInteger()
    val fills = AtomicInteger()
    val puts = AtomicInteger()
    val putAlls = AtomicInteger()

    override fun store(keyspace: Keyspace): L1Store = HookedStore(delegate.store(keyspace))

    private fun guard(operation: String, fail: Boolean = false) {
        if (failAll || fail) {
            throw RemoteUnavailableException(operation)
        }
    }

    /**
     * 运行一次存储调用：[call] 在其中先检查故障开关（[guard]）再调用被包装的存储；之后在发起者的身份下执行钩子 [after]
     * （没有设置钩子时为 `null`），最后经 [ioExecutor] 送达结果（成功或失败）。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> hooked(after: ((T) -> T)?, call: suspend () -> T): T {
        val thread = Thread.currentThread()
        val issuer = Concurrency.currentThreadName()
        val outcome = try {
            val value = call()
            Result.success(if (after == null) value else runHook(thread, issuer) { after(value) })
        } catch (e: Throwable) {
            Result.failure(e)
        }
        return deliver(outcome)
    }

    /**
     * 在发起者 [issuer] 的身份下执行钩子：仍在发起调用的线程 [thread] 上时就地执行，否则在 [hookExecutor] 上执行（见类注释）。
     */
    private suspend fun <T> runHook(thread: Thread, issuer: String, hook: () -> T): T {
        if (Thread.currentThread() === thread) {
            return asIssuer(issuer, hook)
        }
        val outcome = suspendCoroutine { continuation ->
            hookExecutor.execute { continuation.resume(runCatching { asIssuer(issuer, hook) }) }
        }
        return outcome.getOrThrow()
    }

    /**
     * 经 [ioExecutor] 恢复调用方的协程。总是以成功的 [Result] 恢复、在这里的栈帧中抛出：以异常恢复时 kotlinx 的调度器在调试模式下会复制异常。
     */
    private suspend fun <T> deliver(outcome: Result<T>): T {
        val io = ioExecutor ?: return outcome.getOrThrow()
        val delivered = suspendCoroutine { continuation -> io.execute { continuation.resume(outcome) } }
        return delivered.getOrThrow()
    }

    private inner class HookedStore(delegate: L1Store) : ForwardingL1Store(delegate) {
        private val conditional = delegate.conditional()?.let { HookedConditional(it) }
        private val versioned = delegate.versioned()?.let { HookedVersioned(it) }
        private val sets = delegate.sets()?.let { HookedSets(it) }

        override fun conditional(): ConditionalL1Store? = conditional

        override fun versioned(): VersionedL1Store? = versioned

        override fun sets(): SetL1Store? = sets

        override suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read {
            val hook = afterRead?.let { { read: L1Read -> it(key, read) } }
            return hooked(hook) {
                guard("read")
                reads.incrementAndGet()
                delegate.read(key, lease, refresh, timeout)
            }
        }

        override suspend fun readAll(
            keys: Collection<String>,
            leases: Map<String, LeaseRequest>,
            timeout: Duration?
        ): Map<String, L1Read> = hooked(afterReadAll) {
            guard("readAll")
            readAlls.incrementAndGet()
            delegate.readAll(keys, leases, timeout)
        }

        override suspend fun fill(fill: Fill): L1Fill {
            val hook = afterFill?.let { { filled: L1Fill -> it(fill.key, filled).let { filled } } }
            return hooked(hook) {
                guard("fill", fail = failFill)
                fills.incrementAndGet()
                delegate.fill(fill)
            }
        }

        override suspend fun fillAll(fills: List<Fill>): List<L1Fill> = hooked(null) {
            guard("fillAll", fail = failFill)
            this@HookedBackend.fills.addAndGet(fills.size)
            delegate.fillAll(fills)
        }

        override suspend fun release(key: String, token: Token) = hooked(null) {
            guard("release", fail = failRelease)
            delegate.release(key, token)
        }

        override suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
            val hook = afterPut?.let { { _: Unit -> it(key) } }
            hooked(hook) {
                guard("put", fail = failWrites)
                puts.incrementAndGet()
                delegate.put(key, envelope, ttl, frame)
            }
        }

        override suspend fun putAll(puts: List<Put>, frame: ByteArray) {
            val hook = afterPut?.let { { _: Unit -> puts.forEach { put -> it(put.key) } } }
            hooked(hook) {
                guard("putAll", fail = failWrites)
                putAlls.incrementAndGet()
                delegate.putAll(puts, frame)
            }
        }

        override suspend fun invalidate(keys: Collection<String>, frame: ByteArray) {
            val before = beforeInvalidate
            val hook = afterInvalidate?.let { { _: Unit -> it(keys) } }
            hooked(hook) {
                before?.invoke(keys)
                guard("invalidate", fail = failWrites)
                delegate.invalidate(keys, frame)
            }
        }

        override suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean = hooked(
            null
        ) {
            guard("reclaim")
            delegate.reclaim(key, observed, lease)
        }
    }

    private inner class HookedConditional(delegate: ConditionalL1Store) : ForwardingConditionalL1Store(delegate) {
        override suspend fun readIfChanged(key: String, known: Digest?, timeout: Duration?): L1ConditionalRead = hooked(
            null
        ) {
            guard("read")
            delegate.readIfChanged(key, known, timeout)
        }

        override suspend fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): L1ConditionalWrite = hooked(null) {
            guard("put", fail = failWrites)
            delegate.putIfMatch(key, expected, envelope, ttl, frame)
        }
    }

    private inner class HookedVersioned(delegate: VersionedL1Store) : ForwardingVersionedL1Store(delegate) {
        override suspend fun putIfNewer(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray
        ): L1VersionedWrite {
            val hook = afterVersionedWrite?.let { { result: L1VersionedWrite -> it(key, result).let { result } } }
            return hooked(hook) {
                guard("put", fail = failWrites)
                delegate.putIfNewer(key, envelope, version, ttl, frame)
            }
        }
    }

    private inner class HookedSets(delegate: SetL1Store) : ForwardingSetL1Store(delegate) {
        override suspend fun addMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): Int? =
            hooked(null) {
                guard("addMembers", fail = failWrites)
                delegate.addMembers(key, members, ttl, frame)
            }

        override suspend fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int? =
            hooked(null) {
                guard("removeMembers", fail = failWrites)
                delegate.removeMembers(key, members, ttl, frame)
            }
    }
}
