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

package me.ahoo.cocache.api.spi

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiConsumer
import kotlin.concurrent.thread

/**
 * [FutureL1Store.adapt]：
 * - 转发每个操作（含能力面上的操作），返回 future 的结果；
 * - 已完成的 future 不挂起、不登记回调；未完成时挂起，在完成 future 的线程上恢复；从不取消 future；
 * - 失败时抛出原始异常（解包 CompletionException / ExecutionException），方法同步抛出的异常原样传播；
 * - 被取消的 future 与同步抛出的 CancellationException 按 L1 故障处理：非取消的 [IllegalStateException]，cause 是原取消异常；
 * - 返回 `null`，或结果类型非空的 future 以 `null` 完成时，以指明操作的 [IllegalStateException] 失败；
 * - 能力面在适配时各适配一次：访问器每次返回同一个对象，`null` 原样保留。
 */
class FutureL1StoreTest {
    private val node = NodeId.random()
    private val token = Token(node, 1)
    private val lease = LeaseRequest(token, Duration.ofSeconds(1))
    private val refresh = RefreshRequest(Duration.ofSeconds(1), Token(node, 2), Duration.ofSeconds(1))
    private val envelope = Envelope.Scalar(byteArrayOf(1))
    private val digest = Digest.of(envelope)
    private val ttl = Duration.ofMinutes(1)
    private val frame = InvalidationFrame.encode(node, listOf("k"))
    private val fill = Fill("k", token, envelope, ttl)
    private val hit = RemoteRead.Hit(envelope, 1, ttl)

    private fun storeWithoutCapabilities(): FutureL1Store = mockk {
        every { conditional() } returns null
        every { versioned() } returns null
        every { sets() } returns null
    }

    @Test
    fun `adapt forwards every operation and returns its result`() {
        val keys = listOf("k")
        val leases = mapOf("k" to lease)
        val puts = listOf(Put("k", envelope, ttl))
        val store = storeWithoutCapabilities()
        every { store.read("k", lease, refresh) } returns CompletableFuture.completedFuture(hit)
        every { store.readAll(keys, leases) } returns CompletableFuture.completedFuture(mapOf("k" to hit))
        every { store.fill(fill) } returns CompletableFuture.completedFuture(true)
        every { store.fillAll(listOf(fill)) } returns CompletableFuture.completedFuture(listOf(false))
        every { store.release("k", token) } returns CompletableFuture.completedFuture(null)
        every { store.reclaim("k", digest, lease) } returns CompletableFuture.completedFuture(true)
        every { store.put("k", envelope, ttl, frame) } returns CompletableFuture.completedFuture(null)
        every { store.putAll(puts, frame) } returns CompletableFuture.completedFuture(null)
        every { store.invalidate(keys, frame) } returns CompletableFuture.completedFuture(null)
        val l1 = FutureL1Store.adapt(store)

        Suspending.await { l1.read("k", lease, refresh) }.assert().isSameAs(hit)
        Suspending.await { l1.readAll(keys, leases) }.assert().isEqualTo(mapOf("k" to hit))
        Suspending.await { l1.fill(fill) }.assert().isTrue()
        Suspending.await { l1.fillAll(listOf(fill)) }.assert().containsExactly(false)
        Suspending.await { l1.release("k", token) }
        Suspending.await { l1.reclaim("k", digest, lease) }.assert().isTrue()
        Suspending.await { l1.put("k", envelope, ttl, frame) }
        Suspending.await { l1.putAll(puts, frame) }
        Suspending.await { l1.invalidate(keys, frame) }

        verify(exactly = 1) {
            store.release("k", token)
            store.put("k", envelope, ttl, frame)
            store.putAll(puts, frame)
            store.invalidate(keys, frame)
        }
    }

    @Test
    fun `adapt forwards the operations of every capability surface`() {
        val members = listOf(byteArrayOf(2))
        val conditionalSurface = mockk<FutureConditionalL1Store> {
            every { readIfChanged("k", digest) } returns CompletableFuture.completedFuture(RemoteConditionalRead.Unchanged(1))
            every {
                putIfMatch("k", null, envelope, ttl, frame)
            } returns CompletableFuture.completedFuture(RemoteConditionalWrite.Written)
        }
        val versionedSurface = mockk<FutureVersionedL1Store> {
            every {
                putIfNewer("k", envelope, 7, ttl, frame)
            } returns CompletableFuture.completedFuture(RemoteVersionedWrite.Stale(9))
        }
        val setSurface = mockk<FutureSetL1Store> {
            every { addMembers("k", members, ttl, frame) } returns CompletableFuture.completedFuture(null)
            every { removeMembers("k", members, ttl, frame) } returns CompletableFuture.completedFuture(0)
        }
        val store = mockk<FutureL1Store> {
            every { conditional() } returns conditionalSurface
            every { versioned() } returns versionedSurface
            every { sets() } returns setSurface
        }
        val l1 = store.asL1Store()

        val conditional = requireNotNull(l1.conditional())
        val unchanged = Suspending.await { conditional.readIfChanged("k", digest) }
        unchanged.assert().isEqualTo(RemoteConditionalRead.Unchanged(1))
        val written = Suspending.await { conditional.putIfMatch("k", null, envelope, ttl, frame) }
        written.assert().isSameAs(RemoteConditionalWrite.Written)
        val versioned = requireNotNull(l1.versioned())
        val stale = Suspending.await { versioned.putIfNewer("k", envelope, 7, ttl, frame) }
        stale.assert().isEqualTo(RemoteVersionedWrite.Stale(9))
        val sets = requireNotNull(l1.sets())
        Suspending.await { sets.addMembers("k", members, ttl, frame) }.assert().isNull()
        Suspending.await { sets.removeMembers("k", members, ttl, frame) }.assert().isEqualTo(0)
    }

    @Test
    fun `completed future is read without suspending or registering a callback`() {
        val future = WatchedFuture<RemoteRead>().apply { complete(hit) }
        val store = storeWithoutCapabilities()
        every { store.read("k", null, null) } returns future
        val l1 = FutureL1Store.adapt(store)

        val started = Started { l1.read("k", null, null) }

        started.completedOnStart.assert().isTrue()
        started.outcome().getOrThrow().assert().isSameAs(hit)
        future.callbacks.get().assert().isZero()
        future.cancels.get().assert().isZero()
    }

    @Test
    fun `pending future suspends, resumes on the completing thread and is never cancelled`() {
        val future = WatchedFuture<Boolean>()
        val store = storeWithoutCapabilities()
        every { store.fill(fill) } returns future
        val l1 = FutureL1Store.adapt(store)

        val started = Started { l1.fill(fill) to Thread.currentThread() }
        started.completedOnStart.assert().isFalse()
        val completer = thread(name = "completer") { future.complete(true) }
        completer.join()

        val (filled, resumedOn) = started.outcome().getOrThrow()
        filled.assert().isTrue()
        resumedOn.assert().isSameAs(completer)
        future.callbacks.get().assert().isEqualTo(1)
        future.cancels.get().assert().isZero()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `failed future throws the original exception unwrapped`(completedBeforeTheCall: Boolean) {
        val original = IllegalStateException("L1 down")
        val wrappings: List<(Throwable) -> Throwable> = listOf(
            { it },
            { CompletionException(it) },
            { ExecutionException(it) },
            { CompletionException(ExecutionException(it)) },
        )
        wrappings.forEach { wrap ->
            val future = CompletableFuture<Boolean>()
            if (completedBeforeTheCall) {
                future.completeExceptionally(wrap(original))
            }
            val store = storeWithoutCapabilities()
            every { store.fill(fill) } returns future
            val l1 = FutureL1Store.adapt(store)

            val started = Started { l1.fill(fill) }
            if (!completedBeforeTheCall) {
                future.completeExceptionally(wrap(original))
            }

            started.outcome().exceptionOrNull().assert().isSameAs(original)
        }
    }

    @Test
    fun `failed dependent stage throws the original exception`() {
        val original = IllegalStateException("decode failed")
        val source = CompletableFuture<Boolean>()
        val store = storeWithoutCapabilities()
        every { store.fill(fill) } returns source.thenApply<Boolean> { throw original }
        val l1 = FutureL1Store.adapt(store)

        val started = Started { l1.fill(fill) }
        source.complete(true)

        started.outcome().exceptionOrNull().assert().isSameAs(original)
    }

    @Test
    fun `wrapper without a cause is thrown as is`() {
        listOf(CompletionException(null as Throwable?), ExecutionException(null as Throwable?)).forEach { wrapper ->
            val store = storeWithoutCapabilities()
            every { store.fill(fill) } returns CompletableFuture.failedFuture(wrapper)
            val l1 = FutureL1Store.adapt(store)

            Started { l1.fill(fill) }.outcome().exceptionOrNull().assert().isSameAs(wrapper)
        }
    }

    @Test
    fun `synchronous throw propagates as is`() {
        val original = IllegalArgumentException("bad frame")
        val store = storeWithoutCapabilities()
        every { store.put("k", envelope, ttl, frame) } throws original
        val l1 = FutureL1Store.adapt(store)

        Started { l1.put("k", envelope, ttl, frame) }.outcome().exceptionOrNull().assert().isSameAs(original)
    }

    @ParameterizedTest
    @CsvSource("true, false", "true, true", "false, false", "false, true")
    fun `cancelled future fails as an L1 fault, not as a cancellation`(
        cancelledBeforeTheCall: Boolean,
        throughDependentStage: Boolean
    ) {
        val source = CompletableFuture<Boolean>()
        val future = if (throughDependentStage) source.thenApply<Boolean> { it } else source
        if (cancelledBeforeTheCall) {
            source.cancel(false)
        }
        val store = storeWithoutCapabilities()
        every { store.fill(fill) } returns future
        val l1 = FutureL1Store.adapt(store)

        val started = Started { l1.fill(fill) }
        if (!cancelledBeforeTheCall) {
            source.cancel(false)
        }
        // future 中保存的取消异常本身：较新的 JDK 上 join 每次抛出新建的 CancellationException
        val original = source.handle<Throwable> { _, error -> error }.join()
        original.assert().isInstanceOf(CancellationException::class.java)

        val failure = started.outcome().exceptionOrNull()
        failure.assert().isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("${store.javaClass.name}.fill() future was cancelled.")
        requireNotNull(failure).cause.assert().isSameAs(original)
    }

    @Test
    fun `synchronous CancellationException fails as an L1 fault, not as a cancellation`() {
        val original = CancellationException("stopped by the store")
        val store = storeWithoutCapabilities()
        every { store.put("k", envelope, ttl, frame) } throws original
        val l1 = FutureL1Store.adapt(store)

        val failure = Started { l1.put("k", envelope, ttl, frame) }.outcome().exceptionOrNull()

        failure.assert().isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("${store.javaClass.name}.put() threw a CancellationException.")
        requireNotNull(failure).cause.assert().isSameAs(original)
    }

    @Test
    fun `null where a value is required fails with an IllegalStateException naming the operation`() {
        val pending = CompletableFuture<List<Boolean>>()
        val store = storeWithoutCapabilities()
        every { store.read("k", null, null) } returns CompletableFuture.completedFuture<RemoteRead>(null)
        every { store.fillAll(listOf(fill)) } returns pending
        every { store.fill(fill) } returns uncheckedNull()
        every { store.invalidate(listOf("k"), frame) } returns uncheckedNull()
        val l1 = FutureL1Store.adapt(store)
        val name = store.javaClass.name

        assertThrows<IllegalStateException> { Suspending.await { l1.read("k", null, null) } }
            .assert().hasMessage("$name.read() completed with null.")
        val started = Started { l1.fillAll(listOf(fill)) }
        pending.complete(null)
        started.outcome().exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessage("$name.fillAll() completed with null.")
        assertThrows<IllegalStateException> { Suspending.await { l1.fill(fill) } }
            .assert().hasMessage("$name.fill() returned null instead of a future.")
        assertThrows<IllegalStateException> { Suspending.await { l1.invalidate(listOf("k"), frame) } }
            .assert().hasMessage("$name.invalidate() returned null instead of a future.")
    }

    @Test
    fun `null result of a capability operation names the surface`() {
        val surface = mockk<FutureConditionalL1Store> {
            every {
                putIfMatch("k", null, envelope, ttl, frame)
            } returns CompletableFuture.completedFuture<RemoteConditionalWrite>(null)
        }
        val store = mockk<FutureL1Store> {
            every { conditional() } returns surface
            every { versioned() } returns null
            every { sets() } returns null
        }
        val conditional = requireNotNull(FutureL1Store.adapt(store).conditional())

        val failure = Started { conditional.putIfMatch("k", null, envelope, ttl, frame) }.outcome().exceptionOrNull()
        failure.assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessage("${surface.javaClass.name}.putIfMatch() completed with null.")
    }

    @Test
    fun `capability surfaces are adapted once and null stays null`() {
        val store = mockk<FutureL1Store> {
            every { conditional() } returns mockk()
            every { versioned() } returns null
            every { sets() } returns mockk()
        }
        val l1 = FutureL1Store.adapt(store)

        requireNotNull(l1.conditional()).assert().isSameAs(l1.conditional())
        l1.versioned().assert().isNull()
        requireNotNull(l1.sets()).assert().isSameAs(l1.sets())
        verify(exactly = 1) {
            store.conditional()
            store.versioned()
            store.sets()
        }
    }

    /**
     * 违反非空声明的 Java 实现：返回 `null`（类型参数不做运行时检查）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> uncheckedNull(): T = null as T

    /**
     * 记录登记的回调与取消的 future。
     */
    private class WatchedFuture<T> : CompletableFuture<T>() {
        val callbacks = AtomicInteger()
        val cancels = AtomicInteger()

        override fun whenComplete(action: BiConsumer<in T, in Throwable?>): CompletableFuture<T> {
            callbacks.incrementAndGet()
            return super.whenComplete(action)
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancels.incrementAndGet()
            return super.cancel(mayInterruptIfRunning)
        }
    }
}
