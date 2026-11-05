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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.BiConsumer
import kotlin.concurrent.thread

/**
 * [FutureInvalidationChannel.adapt]：规则同 [FutureL1Store.adapt]（RFC 0005 §4.3）。
 * - 订阅原样转发；发布转发并等待 future：已完成的不挂起、不登记回调，未完成时挂起，在完成 future 的线程上恢复，从不取消 future；
 * - 失败时抛出原始异常，方法同步抛出的异常原样传播；
 * - 被取消的 future 与同步抛出的 CancellationException 按故障处理：非取消的 [IllegalStateException]，cause 是原取消异常；
 * - 返回 `null` 时以指明操作的 [IllegalStateException] 失败。
 */
class FutureInvalidationChannelTest {
    private val frame = InvalidationFrame.encode(NodeId.random(), listOf("k"))
    private val keyspace = Keyspace("cocache:", "ns")

    @Test
    fun `adapt and asInvalidationChannel forward subscribe and publish`() {
        val listener = mockk<InvalidationListener>()
        val subscription = Subscription { }
        val channel = mockk<FutureInvalidationChannel> {
            every { subscribe(keyspace, listener) } returns subscription
            every { publish(keyspace, frame) } returns CompletableFuture.completedFuture(null)
        }
        val adapted = FutureInvalidationChannel.adapt(channel)

        adapted.subscribe(keyspace, listener).assert().isSameAs(subscription)
        Suspending.await { adapted.publish(keyspace, frame) }
        // 扩展函数与 adapt 相同
        val extended = channel.asInvalidationChannel()
        Suspending.await { extended.publish(keyspace, frame) }
        extended.toString().assert().contains("FutureInvalidationChannel.adapt")

        verify(exactly = 1) { channel.subscribe(keyspace, listener) }
        verify(exactly = 2) { channel.publish(keyspace, frame) }
    }

    @Test
    fun `completed publish is awaited without suspending or registering a callback`() {
        val future = WatchedFuture().apply { complete(null) }
        val adapted = FutureInvalidationChannel.adapt(channelPublishing(future))

        val started = Started { adapted.publish(keyspace, frame) }

        started.completedOnStart.assert().isTrue()
        started.outcome().isSuccess.assert().isTrue()
        future.callbacks.assert().isZero()
    }

    @Test
    fun `pending publish suspends, resumes on the completing thread and is never cancelled`() {
        val future = WatchedFuture()
        val adapted = FutureInvalidationChannel.adapt(channelPublishing(future))

        val started = Started {
            adapted.publish(keyspace, frame)
            Thread.currentThread()
        }
        started.completedOnStart.assert().isFalse()
        val completer = thread(name = "completer") { future.complete(null) }
        completer.join()

        started.outcome().getOrThrow().assert().isSameAs(completer)
        future.callbacks.assert().isEqualTo(1)
        future.cancels.assert().isZero()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `failed publish throws the original exception unwrapped`(completedBeforeTheCall: Boolean) {
        val original = IllegalStateException("channel down")
        val future = CompletableFuture<Void?>()
        if (completedBeforeTheCall) {
            future.completeExceptionally(CompletionException(original))
        }
        val adapted = FutureInvalidationChannel.adapt(channelPublishing(future))

        val started = Started { adapted.publish(keyspace, frame) }
        if (!completedBeforeTheCall) {
            future.completeExceptionally(original)
        }

        started.outcome().exceptionOrNull().assert().isSameAs(original)
    }

    @Test
    fun `synchronous throw propagates as is`() {
        val original = IllegalArgumentException("bad frame")
        val channel = mockk<FutureInvalidationChannel> { every { publish(keyspace, frame) } throws original }

        Started { FutureInvalidationChannel.adapt(channel).publish(keyspace, frame) }.outcome().exceptionOrNull()
            .assert().isSameAs(original)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `cancelled publish fails as a fault, not as a cancellation`(cancelledBeforeTheCall: Boolean) {
        val future = CompletableFuture<Void?>()
        if (cancelledBeforeTheCall) {
            future.cancel(false)
        }
        val channel = channelPublishing(future)
        val adapted = FutureInvalidationChannel.adapt(channel)

        val started = Started { adapted.publish(keyspace, frame) }
        if (!cancelledBeforeTheCall) {
            future.cancel(false)
        }
        val original = future.handle<Throwable> { _, error -> error }.join()

        val failure = started.outcome().exceptionOrNull()
        failure.assert().isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("${channel.javaClass.name}.publish() future was cancelled.")
        requireNotNull(failure).cause.assert().isSameAs(original)
    }

    @Test
    fun `synchronous CancellationException fails as a fault, not as a cancellation`() {
        val original = CancellationException("stopped by the channel")
        val channel = mockk<FutureInvalidationChannel> { every { publish(keyspace, frame) } throws original }

        val failure = Started {
            FutureInvalidationChannel.adapt(
                channel
            ).publish(keyspace, frame)
        }.outcome().exceptionOrNull()

        failure.assert().isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("${channel.javaClass.name}.publish() threw a CancellationException.")
        requireNotNull(failure).cause.assert().isSameAs(original)
    }

    @Test
    fun `null instead of a future fails with an IllegalStateException naming the operation`() {
        val channel = mockk<FutureInvalidationChannel> { every { publish(keyspace, frame) } returns uncheckedNull() }

        val failure = Started {
            FutureInvalidationChannel.adapt(
                channel
            ).publish(keyspace, frame)
        }.outcome().exceptionOrNull()

        failure.assert().isExactlyInstanceOf(IllegalStateException::class.java)
            .hasMessage("${channel.javaClass.name}.publish() returned null instead of a future.")
    }

    private fun channelPublishing(future: CompletableFuture<Void?>): FutureInvalidationChannel = mockk {
        every { publish(keyspace, frame) } returns future
    }

    /**
     * 违反非空声明的 Java 实现：返回 `null`（类型参数不做运行时检查）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> uncheckedNull(): T = null as T

    /**
     * 记录登记的回调与取消。
     */
    private class WatchedFuture : CompletableFuture<Void?>() {
        @Volatile
        var callbacks = 0

        @Volatile
        var cancels = 0

        override fun whenComplete(action: BiConsumer<in Void?, in Throwable?>): CompletableFuture<Void?> {
            callbacks++
            return super.whenComplete(action)
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancels++
            return super.cancel(mayInterruptIfRunning)
        }
    }
}
