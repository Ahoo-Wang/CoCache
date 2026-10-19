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

import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Token
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
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
     * 写入类操作（put、putAll、invalidate、成员增删）抛出 [RemoteUnavailableException]。
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
     * 单 key fill 在 L1 执行之后、返回之前调用，参数为 key 与是否写入成功。
     */
    @Volatile
    var afterFill: ((String, Boolean) -> Unit)? = null

    /**
     * 异步方法的结果改在这个执行器上送达（模拟 L1 客户端的 I/O 线程，RFC 0004 §10.1）；为 `null` 时原样返回。
     */
    @Volatile
    var ioExecutor: Executor? = null

    val reads = AtomicInteger()
    val readAlls = AtomicInteger()
    val fills = AtomicInteger()
    val putAlls = AtomicInteger()

    /**
     * 被包装的存储原生异步时，钩子同样作用于异步方法；否则只包装同步方法（引擎经阻塞执行器调用它们）。
     */
    override fun store(namespace: String): RemoteStore = when (val store = delegate.store(namespace)) {
        is AsyncRemoteStore -> HookedAsyncStore(store)
        else -> HookedStore(store)
    }

    private open inner class HookedStore(private val store: RemoteStore) : RemoteStore {
        protected fun guard(operation: String, fail: Boolean = false) {
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
            val filled = store.fill(fill)
            afterFill?.invoke(fill.key, filled)
            return filled
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

        override fun putAll(puts: List<Put>, frame: ByteArray) {
            guard("putAll", fail = failWrites)
            putAlls.incrementAndGet()
            store.putAll(puts, frame)
            afterPut?.let { hook -> puts.forEach { hook(it.key) } }
        }

        override fun invalidate(keys: Collection<String>, frame: ByteArray) {
            beforeInvalidate?.invoke(keys)
            guard("invalidate", fail = failWrites)
            store.invalidate(keys, frame)
            afterInvalidate?.invoke(keys)
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

    private inner class HookedAsyncStore(private val store: AsyncRemoteStore) : HookedStore(store), AsyncRemoteStore {
        /**
         * 与同步方法相同的钩子与故障；故障以失败的 future 返回，从不同步抛出。
         */
        @Suppress("TooGenericExceptionCaught")
        private fun <T> hooked(
            operation: String,
            fail: Boolean = false,
            block: () -> CompletableFuture<T>
        ): CompletableFuture<T> {
            val future = try {
                guard(operation, fail)
                block()
            } catch (e: Throwable) {
                CompletableFuture.failedFuture(e)
            }
            return deliver(future)
        }

        /**
         * 总是新建 future 并以原始异常完成（钩子在 `thenApply` 中抛出时 JDK 会包装为 CompletionException），遵守 SPI 的异常约定。
         */
        private fun <T> deliver(future: CompletableFuture<T>): CompletableFuture<T> {
            val io = ioExecutor
            val result = CompletableFuture<T>()
            future.whenComplete { value, error ->
                val complete = Runnable {
                    if (error != null) result.completeExceptionally(unwrap(error)) else result.complete(value)
                }
                if (io == null) complete.run() else io.execute(complete)
            }
            return result
        }

        private fun unwrap(error: Throwable): Throwable = if (error is CompletionException) error.cause ?: error else error

        override fun readAsync(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead> =
            hooked("read") {
                reads.incrementAndGet()
                store.readAsync(key, lease, refresh).thenApply { afterRead?.invoke(key, it) ?: it }
            }

        override fun readAllAsync(keys: Collection<String>, leases: Map<String, LeaseRequest>): CompletableFuture<Map<String, RemoteRead>> =
            hooked("readAll") {
                readAlls.incrementAndGet()
                store.readAllAsync(keys, leases).thenApply { afterReadAll?.invoke(it) ?: it }
            }

        override fun fillAsync(fill: Fill): CompletableFuture<Boolean> = hooked("fill", fail = failFill) {
            fills.incrementAndGet()
            store.fillAsync(fill).thenApply { filled ->
                afterFill?.invoke(fill.key, filled)
                filled
            }
        }

        override fun fillAllAsync(fills: List<Fill>): CompletableFuture<List<Boolean>> = hooked(
            "fillAll",
            fail = failFill
        ) {
            this@HookedBackend.fills.addAndGet(fills.size)
            store.fillAllAsync(fills)
        }

        override fun releaseAsync(key: String, token: Token): CompletableFuture<Void?> =
            hooked("release", fail = failRelease) { store.releaseAsync(key, token) }

        override fun putAsync(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
            hooked("put", fail = failWrites) {
                store.putAsync(key, envelope, ttl, frame).thenApply {
                    afterPut?.invoke(key)
                    null
                }
            }

        override fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = hooked(
            "putAll",
            fail = failWrites
        ) {
            putAlls.incrementAndGet()
            store.putAllAsync(puts, frame).thenApply {
                afterPut?.let { hook -> puts.forEach { hook(it.key) } }
                null
            }
        }

        @Suppress("TooGenericExceptionCaught")
        override fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> {
            try {
                beforeInvalidate?.invoke(keys)
            } catch (e: Throwable) {
                return deliver(CompletableFuture.failedFuture(e))
            }
            return hooked("invalidate", fail = failWrites) {
                store.invalidateAsync(keys, frame).thenApply {
                    afterInvalidate?.invoke(keys)
                    null
                }
            }
        }

        override fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
            hooked("reclaim") { store.reclaimAsync(key, observed, lease) }

        override fun addMembersAsync(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
            hooked("addMembers", fail = failWrites) { store.addMembersAsync(key, members, ttl, frame) }

        override fun removeMembersAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Void?> = hooked("removeMembers", fail = failWrites) {
            store.removeMembersAsync(key, members, ttl, frame)
        }
    }
}
