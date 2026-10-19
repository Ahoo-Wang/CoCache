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

package me.ahoo.cocache.core.internal

import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * 同步与异步共用一张表的 SingleFlight（RFC 0004 §5.4）。
 */
class AsyncSingleFlightTest {
    private val flights = SingleFlight<String, String>()
    private val direct = Executor { it.run() }

    @Test
    fun asyncLeaderSharesWithAsyncAndSyncJoiners() {
        val pending = CompletableFuture<String>()
        val calls = AtomicInteger()
        val leader = flights.executeAsync("k", direct) {
            calls.incrementAndGet()
            pending
        }
        val asyncJoiner = flights.executeAsync("k", direct) { error("not the leader") }
        val syncJoiner = fork { flights.execute("k") { "other" } }
        awaitCondition { syncJoiner.thread.state == Thread.State.WAITING }
        flights.inFlight.assert().isEqualTo(1)
        pending.complete("v")
        leader.join().assert().isEqualTo("v")
        asyncJoiner.join().assert().isEqualTo("v")
        syncJoiner.join().assert().isEqualTo("v")
        calls.get().assert().isEqualTo(1)
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun deregistersBeforePublishing() {
        val pending = CompletableFuture<String>()
        val leader = flights.executeAsync("k", direct) { pending }
        val retried = CompletableFuture<String>()
        leader.whenComplete { _, _ ->
            // 结果发布时调用已注销：立即重试必然开始新的执行
            flights.executeAsync("k", direct) { CompletableFuture.completedFuture("new") }.whenComplete { value, _ ->
                retried.complete(value)
            }
        }
        pending.complete("old")
        retried.join().assert().isEqualTo("new")
    }

    @Test
    fun eachCallerGetsItsOwnFuture() {
        val pending = CompletableFuture<String>()
        val leader = flights.executeAsync("k", direct) { pending }
        val joiner = flights.executeAsync("k", direct) { error("not the leader") }
        joiner.cancel(true)
        joiner.obtrudeValue("tampered")
        pending.complete("v")
        leader.join().assert().isEqualTo("v")
        joiner.join().assert().isEqualTo("tampered")
        runCatching { CompletableFuture<String>().apply { cancel(true) }.join() }
            .exceptionOrNull().assert().isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun failuresAreUnwrappedAndSynchronousThrowsBecomeFailures() {
        val failure = IllegalStateException("boom")
        val wrapped = flights.executeAsync("k", direct) { CompletableFuture.failedFuture(CompletionException(failure)) }
        wrapped.handle { _, e -> e }.join().assert().isSameAs(failure)
        val thrown = flights.executeAsync("k", direct) { throw failure }
        thrown.handle { _, e -> e }.join().assert().isSameAs(failure)
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun asyncJoinerOfASyncLeaderCompletesThroughTheExecutor() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val leader = fork("sync-leader") {
            flights.execute("k") {
                started.countDown()
                release.awaitOrFail()
                "v"
            }
        }
        started.awaitOrFail()
        val dispatched = AtomicInteger()
        val joined = flights.executeAsync("k", { task ->
            dispatched.incrementAndGet()
            Thread(task, "engine").start()
        }) { error("not the leader") }
        val completedOn = CompletableFuture<String>()
        joined.whenComplete { _, _ -> completedOn.complete(Thread.currentThread().name) }
        release.countDown()
        leader.join().assert().isEqualTo("v")
        joined.join().assert().isEqualTo("v")
        completedOn.join().assert().isEqualTo("engine")
        dispatched.get().assert().isEqualTo(1)
    }

    @Test
    fun reentryWhileLoadingFailsFastForSyncAndAsyncCalls() {
        val inner = CompletableFuture<Throwable>()
        flights.executeAsync("k", direct) {
            flights.loading(listOf("k")) {
                inner.complete(
                    flights.executeAsync(
                        "k",
                        direct
                    ) { CompletableFuture.completedFuture("x") }.handle { _, e -> e }.join()
                )
                runCatching {
                    flights.execute(
                        "k"
                    ) { "y" }
                }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
            }
            CompletableFuture.completedFuture("v")
        }.join().assert().isEqualTo("v")
        inner.join().assert().isInstanceOf(IllegalStateException::class.java)
        // 守卫在离开后清除：同一线程可以再次调用
        flights.execute("k") { "again" }.assert().isEqualTo("again")
    }

    @Test
    fun nestedLoadingKeepsOuterKeysUntilItEnds() {
        flights.executeAsync("a", direct) {
            flights.loading(listOf("a")) {
                flights.loading(listOf("a", "b")) { Unit }
                flights.executeAsync("a", direct) { CompletableFuture.completedFuture("x") }
                    .handle { _, e -> e }.join().assert().isInstanceOf(IllegalStateException::class.java)
            }
            CompletableFuture.completedFuture("v")
        }.join()
        flights.executeAsync("b", direct) { CompletableFuture.completedFuture("b") }.join().assert().isEqualTo("b")
    }

    @Test
    fun asyncBatchLeadsOwnKeysAndJoinsOthers() {
        val single = CompletableFuture<String>()
        val joinedAsync = flights.executeAsync("a", direct) { single }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val syncLeader = fork {
            flights.execute("b") {
                started.countDown()
                release.awaitOrFail()
                "vb"
            }
        }
        started.awaitOrFail()
        val led = CompletableFuture<Map<String, String>>()
        val batch = flights.executeAllAsync(listOf("a", "b", "c", "c"), direct) { keys ->
            keys.assert().containsExactly("c")
            led
        }
        led.complete(mapOf("c" to "vc"))
        single.complete("va")
        release.countDown()
        syncLeader.join()
        batch.join().assert().isEqualTo(mapOf("c" to "vc", "a" to "va", "b" to "vb"))
        joinedAsync.join().assert().isEqualTo("va")
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun asyncBatchFailuresAbandonOwnKeys() {
        val failure = IllegalStateException("boom")
        flights.executeAllAsync(listOf("a"), direct) { CompletableFuture.failedFuture(failure) }
            .handle { _, e -> e }.join().assert().isSameAs(failure)
        flights.executeAllAsync(
            listOf("a"),
            direct
        ) { throw failure }.handle { _, e -> e }.join().assert().isSameAs(failure)
        val missing = flights.executeAllAsync(
            listOf("a", "b"),
            direct
        ) { CompletableFuture.completedFuture(mapOf("a" to "v")) }
        // 与同步版本相同：缺少结果的 key 不在返回值中（引擎改走单 key 路径），合并到它的调用者以异常结束
        missing.join().assert().isEqualTo(mapOf("a" to "v"))
        flights.executeAllAsync(emptyList(), direct) { error("unused") }.join().assert().isEmpty()
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun asyncBatchReentryFailsAndAbandonsRegisteredKeys() {
        val error = flights.executeAsync("b", direct) {
            flights.loading(listOf("b")) {
                flights.executeAllAsync(listOf("a", "b"), direct) { error("unused") }.handle { _, e -> e }.join()
            }.let { CompletableFuture.completedFuture(it.javaClass.name) }
        }.join()
        error.assert().isEqualTo(IllegalStateException::class.java.name)
        flights.inFlight.assert().isEqualTo(0)
    }
}
