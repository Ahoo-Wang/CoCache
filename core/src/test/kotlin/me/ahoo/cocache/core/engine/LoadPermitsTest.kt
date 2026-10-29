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

package me.ahoo.cocache.core.engine

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LoadPermitsTest {
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val async = AsyncContext(Executor { it.run() }, Executor { it.run() }, timer)

    @AfterEach
    fun destroy() {
        async.close()
        timer.shutdownNow()
    }

    private fun rejected() = IllegalStateException("rejected")

    @Test
    fun waitersAreWokenInOrderOnRelease() {
        val permits = LoadPermits(1, async)
        permits.acquireAsync(TimeUnit.SECONDS.toNanos(10), ::rejected).isDone.assert().isTrue()
        val first = permits.acquireAsync(TimeUnit.SECONDS.toNanos(10), ::rejected)
        val second = permits.acquireAsync(TimeUnit.SECONDS.toNanos(10), ::rejected)
        first.isDone.assert().isFalse()
        permits.release()
        first.isDone.assert().isTrue()
        second.isDone.assert().isFalse()
        permits.release()
        second.isDone.assert().isTrue()
        permits.release()
        permits.tryAcquire(0).assert().isTrue()
    }

    @Test
    fun waitersTimeOutAndTheirPermitsAreNotLost() {
        val permits = LoadPermits(1, async)
        permits.tryAcquire(0).assert().isTrue()
        val waiter = permits.acquireAsync(TimeUnit.MILLISECONDS.toNanos(20), ::rejected)
        waiter.handle { _, e -> e }.get(10, TimeUnit.SECONDS).assert().isInstanceOf(IllegalStateException::class.java)
        permits.release()
        permits.tryAcquire(0).assert().isTrue()
    }

    @Test
    fun closingTheRuntimeFailsWaiters() {
        val permits = LoadPermits(1, async)
        permits.tryAcquire(0).assert().isTrue()
        val waiter = permits.acquireAsync(TimeUnit.SECONDS.toNanos(10), ::rejected)
        async.close()
        waiter.handle { _, e -> e }.get(10, TimeUnit.SECONDS).assert().isInstanceOf(IllegalStateException::class.java)
        val noTimer = LoadPermits(1, AsyncContext.direct())
        noTimer.tryAcquire(0).assert().isTrue()
        noTimer.acquireAsync(
            0,
            ::rejected
        ).handle { _, e -> e }.join().assert().isInstanceOf(IllegalStateException::class.java)
    }
}

class AsyncContextTest {
    @Test
    fun afterRunsOnTheExecutorAndFailsOnceClosed() {
        val timer = Executors.newSingleThreadScheduledExecutor()
        val executed = CompletableFuture<String>()
        val async = AsyncContext({ task -> Thread(task, "engine").start() }, Executor { it.run() }, timer)
        try {
            async.after(0) { CompletableFuture.completedFuture(Thread.currentThread().name) }.get(10, TimeUnit.SECONDS)
                .assert().isEqualTo("engine")
            async.after(TimeUnit.SECONDS.toNanos(10)) { executed }.let { pending ->
                async.close()
                async.closed.assert().isTrue()
                pending.handle { _, e -> e }.join().assert().isInstanceOf(IllegalStateException::class.java)
            }
            async.after(
                0
            ) { executed }.handle { _, e -> e }.join().assert().isInstanceOf(IllegalStateException::class.java)
        } finally {
            timer.shutdownNow()
        }
        val stopped = Executors.newSingleThreadScheduledExecutor().apply { shutdown() }
        AsyncContext(Executor { it.run() }, Executor { it.run() }, stopped).after(0) { executed }
            .handle { _, e -> e }.join().assert().isInstanceOf(IllegalStateException::class.java)
    }
}
