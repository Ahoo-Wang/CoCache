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
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteConditionalRead
import me.ahoo.cocache.api.spi.RemoteConditionalWrite
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteVersionedWrite
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.api.spi.VersionedL1Store
import me.ahoo.cocache.test.Suspending
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 以 Java 实现者的形状暴露被包装的后端（RFC 0005 §4.3）：存储是 [FutureL1Store]、通道是 [FutureInvalidationChannel]，
 * 分别经 [FutureL1Store.adapt] 与 [FutureInvalidationChannel.adapt] 交给引擎。每个操作在调用线程上执行被包装的挂起方法
 * （内存后端从不挂起），以已完成或已失败的 future 返回。
 */
class FutureBackend(private val backend: CoherenceBackend) : CoherenceBackend by backend {
    override val channel: InvalidationChannel = FutureInvalidationChannel.adapt(FutureChannel(backend.channel))

    override fun store(namespace: String): L1Store = FutureL1Store.adapt(FutureStore(backend.store(namespace)))

    private class FutureChannel(private val channel: InvalidationChannel) : FutureInvalidationChannel {
        override fun subscribe(namespace: String, listener: InvalidationListener): Subscription =
            channel.subscribe(namespace, listener)

        override fun publish(namespace: String, frame: ByteArray): CompletableFuture<Void?> = completing {
            channel.publish(namespace, frame)
        }
    }

    @Suppress("TooManyFunctions")
    private class FutureStore(private val store: L1Store) : FutureL1Store {
        private val conditional = store.conditional()?.let { FutureConditional(it) }
        private val versioned = store.versioned()?.let { FutureVersioned(it) }
        private val sets = store.sets()?.let { FutureSets(it) }

        override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead> =
            supplying { store.read(key, lease, refresh) }

        override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): CompletableFuture<Map<String, RemoteRead>> =
            supplying { store.readAll(keys, leases) }

        override fun fill(fill: Fill): CompletableFuture<Boolean> = supplying { store.fill(fill) }

        override fun fillAll(fills: List<Fill>): CompletableFuture<List<Boolean>> = supplying { store.fillAll(fills) }

        override fun release(key: String, token: Token): CompletableFuture<Void?> = completing {
            store.release(
                key,
                token
            )
        }

        override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
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

    private class FutureConditional(private val surface: ConditionalL1Store) : FutureConditionalL1Store {
        override fun readIfChanged(key: String, known: Digest?): CompletableFuture<RemoteConditionalRead> =
            supplying { surface.readIfChanged(key, known) }

        override fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<RemoteConditionalWrite> = supplying {
            surface.putIfMatch(
                key,
                expected,
                envelope,
                ttl,
                frame
            )
        }
    }

    private class FutureVersioned(private val surface: VersionedL1Store) : FutureVersionedL1Store {
        override fun putIfNewer(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<RemoteVersionedWrite> = supplying {
            surface.putIfNewer(
                key,
                envelope,
                version,
                ttl,
                frame
            )
        }
    }

    private class FutureSets(private val surface: SetL1Store) : FutureSetL1Store {
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
        @Suppress("TooGenericExceptionCaught")
        fun <T> supplying(block: suspend () -> T): CompletableFuture<T> = try {
            CompletableFuture.completedFuture(Suspending.await(block))
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }

        fun completing(block: suspend () -> Unit): CompletableFuture<Void?> = supplying {
            block()
            null
        }
    }
}
