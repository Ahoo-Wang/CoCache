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
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Token
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 模拟的 L1 故障。
 */
class RemoteUnavailableException(operation: String) : RuntimeException("Remote store unavailable: $operation.")

/**
 * 为任意后端注入钩子与故障的装饰器，供 TCK 编排竞态（用 latch，不用 sleep）。
 */
class HookedBackend(val delegate: CoherenceBackend) : CoherenceBackend {
    /**
     * 覆盖后端的原子广播能力，用于验证引擎在非原子后端上的行为（为 `false` 时写入不再在存储内广播）。
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
     * 写入类操作（put、invalidate、成员增删）抛出 [RemoteUnavailableException]。
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
    var afterRead: ((String, RemoteRead) -> RemoteRead)? = null

    /**
     * 批量读取返回之前调用，可以改写结果。
     */
    @Volatile
    var afterReadAll: ((Map<String, RemoteRead>) -> Map<String, RemoteRead>)? = null

    /**
     * put 在 L1 生效之后、返回之前调用。
     */
    @Volatile
    var afterPut: ((String) -> Unit)? = null

    /**
     * invalidate 修改 L1 之前调用。
     */
    @Volatile
    var beforeInvalidate: ((Collection<String>) -> Unit)? = null

    val reads = AtomicInteger()
    val readAlls = AtomicInteger()
    val fills = AtomicInteger()

    override fun store(namespace: String): RemoteStore = HookedStore(delegate.store(namespace))

    private inner class HookedStore(private val store: RemoteStore) : RemoteStore {
        private fun guard(operation: String, fail: Boolean = false) {
            if (failAll || fail) {
                throw RemoteUnavailableException(operation)
            }
        }

        override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead {
            guard("read")
            reads.incrementAndGet()
            val read = store.read(key, lease, refresh)
            return afterRead?.invoke(key, read) ?: read
        }

        override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> {
            guard("readAll")
            readAlls.incrementAndGet()
            val reads = store.readAll(keys, leases)
            return afterReadAll?.invoke(reads) ?: reads
        }

        override fun fill(fill: Fill): Boolean {
            guard("fill", fail = failFill)
            fills.incrementAndGet()
            return store.fill(fill)
        }

        override fun fillAll(fills: List<Fill>): List<Boolean> {
            guard("fillAll", fail = failFill)
            this@HookedBackend.fills.addAndGet(fills.size)
            return store.fillAll(fills)
        }

        override fun release(key: String, token: Token) {
            guard("release", fail = failRelease)
            store.release(key, token)
        }

        override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
            guard("put", fail = failWrites)
            store.put(key, envelope, ttl, frame)
            afterPut?.invoke(key)
        }

        override fun invalidate(keys: Collection<String>, frame: ByteArray) {
            beforeInvalidate?.invoke(keys)
            guard("invalidate", fail = failWrites)
            store.invalidate(keys, frame)
        }

        override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean {
            guard("reclaim")
            return store.reclaim(key, observed, lease)
        }

        override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
            guard("addMembers", fail = failWrites)
            store.addMembers(key, members, ttl, frame)
        }

        override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
            guard("removeMembers", fail = failWrites)
            store.removeMembers(key, members, ttl, frame)
        }

        override fun close() = store.close()
    }
}
