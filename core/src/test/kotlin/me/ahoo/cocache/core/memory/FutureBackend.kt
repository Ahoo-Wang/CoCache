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

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.FutureConditionalL1Store
import me.ahoo.cocache.api.spi.FutureInvalidationChannel
import me.ahoo.cocache.api.spi.FutureL1Store
import me.ahoo.cocache.api.spi.FutureSetL1Store
import me.ahoo.cocache.api.spi.FutureVersionedL1Store
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.api.spi.L1Eviction
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
import me.ahoo.cocache.test.CommandCancellation
import me.ahoo.cocache.test.Suspending
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 以 Java 实现者的形状暴露被包装的后端（RFC 0005 §4.3）：存储是 [FutureL1Store]、通道是 [FutureInvalidationChannel]，
 * 分别经 [FutureL1Store.adapt] 与 [FutureInvalidationChannel.adapt] 交给引擎。每个操作在调用线程上执行被包装的挂起方法
 * （内存后端从不挂起），以已完成或已失败的 future 返回；[cancellation] 期间返回尚未完成、随后被取消的 future。
 */
class FutureBackend(private val backend: CoherenceBackend) : CoherenceBackend by backend {
    override val channel: InvalidationChannel = FutureInvalidationChannel.adapt(FutureChannel(backend.channel))

    override fun store(keyspace: Keyspace): L1Store = FutureL1Store.adapt(FutureStore(backend.store(keyspace)))

    private val lock = Any()
    private var mode = Mode.NORMAL
    private val held = ArrayList<CompletableFuture<*>>()

    private enum class Mode { NORMAL, HOLDING, CANCELLING }

    /**
     * 底层命令被取消的故障（`FaultSpec.commandCancellation`）：[CommandCancellation.hold] 之后存储的每个操作返回尚未完成的 future，
     * 操作不执行（相当于尚未写出的命令）；[CommandCancellation.cancel] 取消这些 future，此后到 [CommandCancellation.close]
     * 之前的操作返回已取消的 future。没有执行的操作不取得令牌，没有要释放的。通道不受影响。
     */
    fun cancellation(): CommandCancellation = object : CommandCancellation {
        override fun hold() = synchronized(lock) { mode = Mode.HOLDING }

        override fun cancel() {
            val pending = synchronized(lock) {
                mode = Mode.CANCELLING
                held.toList().also { held.clear() }
            }
            pending.forEach { it.cancel(false) }
        }

        override fun close() = synchronized(lock) { mode = Mode.NORMAL }
    }

    /**
     * 按 [mode] 返回 future：正常时就地执行 [block]，否则返回停住的或已取消的 future（[block] 不执行）。
     */
    private fun <T> supplying(block: suspend () -> T): CompletableFuture<T> {
        synchronized(lock) {
            when (mode) {
                Mode.NORMAL -> Unit
                Mode.HOLDING -> return CompletableFuture<T>().also { held.add(it) }
                Mode.CANCELLING -> return CompletableFuture<T>().apply { cancel(false) }
            }
        }
        return completed(block)
    }

    private fun completing(block: suspend () -> Unit): CompletableFuture<Void?> = supplying {
        block()
        null
    }

    private class FutureChannel(private val channel: InvalidationChannel) : FutureInvalidationChannel {
        override fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription =
            channel.subscribe(keyspace, listener)

        override fun publish(keyspace: Keyspace, frame: ByteArray): CompletableFuture<Void?> = completed {
            channel.publish(keyspace, frame)
            null
        }
    }

    @Suppress("TooManyFunctions")
    private inner class FutureStore(private val store: L1Store) : FutureL1Store {
        private val conditional = store.conditional()?.let { FutureConditional(it) }
        private val versioned = store.versioned()?.let { FutureVersioned(it) }
        private val sets = store.sets()?.let { FutureSets(it) }

        override fun read(
            key: String,
            lease: LeaseRequest?,
            refresh: RefreshRequest?,
            timeout: Duration?
        ): CompletableFuture<L1Read> = supplying { store.read(key, lease, refresh, timeout) }

        override fun readAll(
            keys: Collection<String>,
            leases: Map<String, LeaseRequest>,
            timeout: Duration?
        ): CompletableFuture<Map<String, L1Read>> = supplying { store.readAll(keys, leases, timeout) }

        override fun fill(fill: Fill): CompletableFuture<L1Fill> = supplying { store.fill(fill) }

        override fun fillAll(fills: List<Fill>): CompletableFuture<List<L1Fill>> = supplying { store.fillAll(fills) }

        override fun release(key: String, token: Token): CompletableFuture<Void?> = completing {
            store.release(
                key,
                token
            )
        }

        override fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): CompletableFuture<Boolean> =
            supplying { store.reclaim(key, observed, lease) }

        override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
            completing { store.put(key, envelope, ttl, frame) }

        override fun putAll(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = completing {
            store.putAll(puts, frame)
        }

        override fun invalidate(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
            completing { store.invalidate(keys, frame) }

        override fun conditional(): FutureConditionalL1Store? = conditional

        override fun versioned(): FutureVersionedL1Store? = versioned

        override fun sets(): FutureSetL1Store? = sets
    }

    private inner class FutureConditional(private val surface: ConditionalL1Store) : FutureConditionalL1Store {
        override fun readIfChanged(
            key: String,
            known: Digest?,
            timeout: Duration?
        ): CompletableFuture<L1ConditionalRead> =
            supplying { surface.readIfChanged(key, known, timeout) }

        override fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<L1ConditionalWrite> = supplying {
            surface.putIfMatch(
                key,
                expected,
                envelope,
                ttl,
                frame
            )
        }
        override fun deleteIfMatch(
            key: String,
            expected: Digest,
            frame: ByteArray
        ): CompletableFuture<L1ConditionalWrite> =
            supplying { surface.deleteIfMatch(key, expected, frame) }
    }

    private inner class FutureVersioned(private val surface: VersionedL1Store) : FutureVersionedL1Store {
        override fun putIfNewer(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<L1VersionedWrite> = supplying {
            surface.putIfNewer(
                key,
                envelope,
                version,
                ttl,
                frame
            )
        }

        override fun evictKeepingVersion(key: String, ttl: Duration?, frame: ByteArray): CompletableFuture<L1Eviction> =
            supplying { surface.evictKeepingVersion(key, ttl, frame) }
    }

    private inner class FutureSets(private val surface: SetL1Store) : FutureSetL1Store {
        override fun addMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Int?> =
            supplying { surface.addMembers(key, members, ttl, frame) }

        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Int?> =
            supplying { surface.removeMembers(key, members, ttl, frame) }
    }

    private companion object {
        /**
         * 在调用线程上执行 [block]，以已完成或已失败的 future 返回。
         */
        @Suppress("TooGenericExceptionCaught")
        fun <T> completed(block: suspend () -> T): CompletableFuture<T> = try {
            CompletableFuture.completedFuture(Suspending.await(block))
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
    }
}
