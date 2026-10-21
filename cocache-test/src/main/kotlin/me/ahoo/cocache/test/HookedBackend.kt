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
import me.ahoo.cocache.api.spi.RemoteConditionalRead
import me.ahoo.cocache.api.spi.RemoteConditionalWrite
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Token
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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

    /**
     * 发起当前存储调用的线程名：钩子用它代替 `Thread.currentThread().name` 编排竞态。
     * 异步方法的钩子在结果的完成线程（如 Lettuce 的 I/O 线程）上执行，执行期间这里返回发起调用的线程名。
     */
    fun issuer(): String = issuerName.get() ?: Thread.currentThread().name

    private val issuerName = ThreadLocal<String?>()

    /**
     * 异步方法的钩子在这里执行（结果尚未完成时）：钩子可能阻塞（latch），不能在 L1 客户端的 I/O 线程上执行，否则共享连接上的全部命令都会停下。
     */
    private val hookExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "tck-hook").apply { isDaemon = true }
    }

    /**
     * [future] 完成后在发起者的身份下执行 [hook]：已完成时就地执行，否则在 [hookExecutor] 上执行。
     */
    private fun <T, R> CompletableFuture<T>.hooked(
        issuer: String,
        active: Boolean,
        hook: (T) -> R
    ): CompletableFuture<R> {
        val apply = java.util.function.Function<T, R> { value -> asIssuer(issuer) { hook(value) } }
        // 没有设置钩子时不切换线程：不为 TCK 的线程数断言引入额外线程
        return if (isDone || !active) thenApply(apply) else thenApplyAsync(apply, hookExecutor)
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

        override fun addMembersIfReadable(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int? {
            guard("addMembers", fail = failWrites)
            return store.addMembersIfReadable(key, members, ttl, frame)
        }

        override fun removeMembersIfReadable(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int? {
            guard("removeMembers", fail = failWrites)
            return store.removeMembersIfReadable(key, members, ttl, frame)
        }

        override fun readIfChanged(key: String, known: Digest?): RemoteConditionalRead {
            guard("read")
            return store.readIfChanged(key, known)
        }

        override fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray,
            foreignAsAbsent: Boolean
        ): RemoteConditionalWrite {
            guard("put", fail = failWrites)
            return store.putIfMatch(key, expected, envelope, ttl, frame, foreignAsAbsent)
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
                val issuer = Thread.currentThread().name
                store.readAsync(
                    key,
                    lease,
                    refresh
                ).hooked(issuer, afterRead != null) { read -> afterRead?.invoke(key, read) ?: read }
            }

        override fun readAllAsync(keys: Collection<String>, leases: Map<String, LeaseRequest>): CompletableFuture<Map<String, RemoteRead>> =
            hooked("readAll") {
                readAlls.incrementAndGet()
                val issuer = Thread.currentThread().name
                store.readAllAsync(
                    keys,
                    leases
                ).hooked(issuer, afterReadAll != null) { reads -> afterReadAll?.invoke(reads) ?: reads }
            }

        override fun fillAsync(fill: Fill): CompletableFuture<Boolean> = hooked("fill", fail = failFill) {
            fills.incrementAndGet()
            val issuer = Thread.currentThread().name
            store.fillAsync(fill).hooked(issuer, afterFill != null) { filled ->
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
                val issuer = Thread.currentThread().name
                store.putAsync(key, envelope, ttl, frame).hooked(issuer, afterPut != null) {
                    afterPut?.invoke(key)
                    null
                }
            }

        override fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = hooked(
            "putAll",
            fail = failWrites
        ) {
            putAlls.incrementAndGet()
            val issuer = Thread.currentThread().name
            store.putAllAsync(puts, frame).hooked(issuer, afterPut != null) {
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
                val issuer = Thread.currentThread().name
                store.invalidateAsync(keys, frame).hooked(issuer, afterInvalidate != null) {
                    afterInvalidate?.invoke(keys)
                    null
                }
            }
        }

        override fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
            hooked("reclaim") { store.reclaimAsync(key, observed, lease) }

        override fun readIfChangedAsync(key: String, known: Digest?): CompletableFuture<RemoteConditionalRead> =
            hooked("read") { store.readIfChangedAsync(key, known) }

        override fun putIfMatchAsync(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray,
            foreignAsAbsent: Boolean
        ): CompletableFuture<RemoteConditionalWrite> = hooked("put", fail = failWrites) {
            store.putIfMatchAsync(key, expected, envelope, ttl, frame, foreignAsAbsent)
        }

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

        override fun addMembersIfReadableAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Int?> = hooked("addMembers", fail = failWrites) {
            store.addMembersIfReadableAsync(key, members, ttl, frame)
        }

        override fun removeMembersIfReadableAsync(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): CompletableFuture<Int?> = hooked("removeMembers", fail = failWrites) {
            store.removeMembersIfReadableAsync(key, members, ttl, frame)
        }
    }
}
