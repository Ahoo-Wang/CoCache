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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.future.future
import me.ahoo.cocache.core.engine.AsyncContext
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 协程核心的 SingleFlight（[SingleFlight.executeSuspending]）与同步变体的入口（[runSync]）。
 */
class SuspendingSingleFlightTest {
    private val flights = SingleFlight<String, String>()
    private val context = AsyncContext(ForkJoinPool.commonPool(), ForkJoinPool.commonPool(), null)
    private val scope = context.scope

    @AfterEach
    fun tearDown() {
        context.close()
    }

    /** 同步变体。 */
    private fun sync(key: String = "k", block: suspend () -> String): String =
        runSync { flights.executeSuspending(key, scope, block) }

    /** 异步变体：用户拿到结果的副本。 */
    private fun async(key: String = "k", block: suspend () -> String): CompletableFuture<String> =
        context.runAsync { flights.executeSuspending(key, scope, block) }

    /** 直接从协程调用（上下文中没有 [ExecutionMode]，调用方可能被单独取消）：返回的 future 就是调用方的协程。 */
    private fun suspending(key: String = "k", block: suspend () -> String): CompletableFuture<String> =
        scope.future(start = CoroutineStart.UNDISPATCHED) { flights.executeSuspending(key, scope, block) }

    @Test
    fun syncLeaderRunsInlineOnTheCallerThread() {
        val caller = Thread.currentThread()
        var ranOn: Thread? = null
        sync {
            ranOn = Thread.currentThread()
            "v"
        }.assert().isEqualTo("v")
        ranOn.assert().isSameAs(caller)
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun syncFollowersShareTheLeaderResult() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val leader = fork {
            sync {
                calls.incrementAndGet()
                started.countDown()
                release.awaitOrFail()
                "v"
            }
        }
        started.awaitOrFail()
        val follower = fork {
            sync {
                calls.incrementAndGet()
                "other"
            }
        }
        awaitCondition { follower.parked }
        flights.inFlight.assert().isEqualTo(1)
        release.countDown()
        leader.join().assert().isEqualTo("v")
        follower.join().assert().isEqualTo("v")
        calls.get().assert().isEqualTo(1)
        flights.inFlight.assert().isEqualTo(0)
    }

    /**
     * 跟随者收到的是 leader 抛出的原始异常（不变量“不包装异常”）：调试模式下 kotlinx 恢复挂起时会复制异常，这里不能拿到副本。
     */
    @Test
    fun followersReceiveTheOriginalException() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IllegalStateException("boom")
        val leader = fork {
            sync {
                started.countDown()
                release.awaitOrFail()
                throw failure
            }
        }
        started.awaitOrFail()
        val syncFollower = fork { sync { "other" } }
        val asyncFollower = async { "other" }
        awaitCondition { syncFollower.parked }
        release.countDown()
        leader.joinFailure().assert().isSameAs(failure)
        syncFollower.joinFailure().assert().isSameAs(failure)
        causeOf(asyncFollower).assert().isSameAs(failure)
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun asyncLeaderFailurePropagatesTheOriginalException() {
        val gate = CompletableFuture<String>()
        val failure = IllegalStateException("boom")
        val leader = async { gate.awaitOriginal() }
        val syncFollower = fork { sync { "other" } }
        awaitCondition { syncFollower.parked }
        gate.completeExceptionally(failure)
        causeOf(leader).assert().isSameAs(failure)
        syncFollower.joinFailure().assert().isSameAs(failure)
        flights.inFlight.assert().isEqualTo(0)
    }

    /**
     * 异步变体的 leader 在调用方的协程中执行，第一次挂起之前就在调用方线程上（省去一次派发）。
     */
    @Test
    fun asyncLeaderRunsInTheCallerCoroutine() {
        val caller = Thread.currentThread()
        var ranOn: Thread? = null
        async {
            ranOn = Thread.currentThread()
            "v"
        }.get(5, TimeUnit.SECONDS).assert().isEqualTo("v")
        ranOn.assert().isSameAs(caller)
        flights.inFlight.assert().isEqualTo(0)
    }

    /**
     * 取消发起加载的调用方只结束调用方自己的等待，共享加载照常完成并交给其它等待者：
     * 异步变体取消的是结果的副本；直接从协程调用时，共享加载在运行时作用域中执行。
     */
    @Test
    fun cancellingTheAsyncCallerDoesNotCancelTheSharedLoad() {
        assertCallerCancellationKeepsTheSharedLoad(::async)
    }

    @Test
    fun cancellingTheSuspendingCallerDoesNotCancelTheSharedLoad() {
        assertCallerCancellationKeepsTheSharedLoad(::suspending)
    }

    private fun assertCallerCancellationKeepsTheSharedLoad(
        start: (String, suspend () -> String) -> CompletableFuture<String>
    ) {
        val gate = CompletableFuture<String>()
        val loads = AtomicInteger()
        val caller = start("k") {
            loads.incrementAndGet()
            gate.awaitOriginal()
        }
        val follower = async { "other" }
        caller.cancel(true)
        caller.isCancelled.assert().isEqualTo(true)
        flights.inFlight.assert().isEqualTo(1)
        gate.complete("v")
        follower.get(5, TimeUnit.SECONDS).assert().isEqualTo("v")
        awaitCondition { flights.inFlight == 0 }
        loads.get().assert().isEqualTo(1)
    }

    /**
     * 跟随者的等待被取消只结束它自己的等待，不取消共享的结果。
     */
    @Test
    fun cancellingAFollowerDoesNotCancelTheSharedResult() {
        val gate = CompletableFuture<String>()
        val leader = async { gate.awaitOriginal() }
        val cancelled = suspending { "other" }
        val waiting = async { "other" }
        cancelled.cancel(true)
        gate.complete("v")
        leader.get(5, TimeUnit.SECONDS).assert().isEqualTo("v")
        waiting.get(5, TimeUnit.SECONDS).assert().isEqualTo("v")
        cancelled.isCancelled.assert().isEqualTo(true)
    }

    @Test
    fun syncFollowerJoinsABlockingLeader() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val leader = fork {
            flights.execute("k") {
                started.countDown()
                release.awaitOrFail()
                "v"
            }
        }
        started.awaitOrFail()
        val follower = fork { sync { "other" } }
        awaitCondition { follower.parked }
        release.countDown()
        leader.join().assert().isEqualTo("v")
        follower.join().assert().isEqualTo("v")
    }

    /**
     * 同步跟随者被中断：以 [InterruptedException] 失败并保留中断标志，只影响它自己（RFC 0005 §4.4）。
     */
    @Test
    fun interruptedSyncFollowerFailsAloneAndKeepsItsInterruptFlag() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val leader = fork {
            sync {
                started.countDown()
                release.awaitOrFail()
                "v"
            }
        }
        started.awaitOrFail()
        val interrupted = fork { interruptFlagAfter { sync { "other" } } }
        val waiting = fork { sync { "other" } }
        awaitCondition { interrupted.parked && waiting.parked }
        interrupted.thread.interrupt()
        val failure = interrupted.joinFailure()
        failure.assert().isInstanceOf(FollowerFailure::class.java)
        val (cause, flag) = failure as FollowerFailure
        cause.assert().isInstanceOf(InterruptedException::class.java)
        flag.assert().withFailMessage { "the interrupt flag must be restored" }.isEqualTo(true)
        flights.inFlight.assert().isEqualTo(1)
        release.countDown()
        leader.join().assert().isEqualTo("v")
        waiting.join().assert().isEqualTo("v")
        flights.inFlight.assert().isEqualTo(0)
    }

    /**
     * 同步 leader 被中断时加载随之结束：leader 收到 loader 抛出的 [InterruptedException]，跟随者收到同一个失败，
     * 但跟随者自己没有被中断，中断标志不能被置上（6.x 的合并规则；按合并规则自行运行属于 M2）。
     */
    @Test
    fun interruptedSyncLeaderEndsTheLoadWithoutInterruptingFollowers() {
        val started = CountDownLatch(1)
        val leader = fork {
            interruptFlagAfter {
                sync {
                    started.countDown()
                    CountDownLatch(1).await()
                    "v"
                }
            }
        }
        started.awaitOrFail()
        val follower = fork { interruptFlagAfter { sync { "other" } } }
        awaitCondition { follower.parked }
        leader.thread.interrupt()
        val (leaderCause, _) = leader.joinFailure() as FollowerFailure
        leaderCause.assert().isInstanceOf(InterruptedException::class.java)
        val (followerCause, followerFlag) = follower.joinFailure() as FollowerFailure
        followerCause.assert().isSameAs(leaderCause)
        followerFlag.assert().withFailMessage { "the follower was not interrupted" }.isEqualTo(false)
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun reentrantSyncCallFailsFast() {
        val error = runCatching { sync { sync { "inner" } } }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
        val blockingError = runCatching { flights.execute("k") { sync { "inner" } } }.exceptionOrNull()
        blockingError.assert().isInstanceOf(IllegalStateException::class.java)
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun runSyncOnAnInterruptedThreadFailsAndKeepsTheFlag() {
        Thread.currentThread().interrupt()
        try {
            val error = runCatching {
                runSync {
                    kotlinx.coroutines.yield()
                    "v"
                }
            }.exceptionOrNull()
            error.assert().isInstanceOf(InterruptedException::class.java)
            Thread.currentThread().isInterrupted.assert().isEqualTo(true)
        } finally {
            Thread.interrupted()
        }
    }

    private fun causeOf(future: CompletableFuture<*>): Throwable? = try {
        future.get(5, TimeUnit.SECONDS)
        null
    } catch (e: ExecutionException) {
        e.cause
    } catch (e: CancellationException) {
        e
    }

    /**
     * 记下失败时线程的中断标志（`Forked` 线程结束后标志不可再读）。
     */
    private data class FollowerFailure(val error: Throwable, val interrupted: Boolean) : RuntimeException(error)

    @Suppress("TooGenericExceptionCaught")
    private fun <T> interruptFlagAfter(block: () -> T): T {
        try {
            return block()
        } catch (e: Throwable) {
            throw FollowerFailure(e, Thread.currentThread().isInterrupted)
        }
    }
}
