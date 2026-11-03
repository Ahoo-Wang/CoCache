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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cocache.core.engine.AsyncContext
import me.ahoo.cocache.test.Concurrency
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 协程核心的 SingleFlight（[SingleFlight.executeSuspending]、[SingleFlight.executeAllSuspending]）与同步变体的入口（[runSync]）。
 */
class SingleFlightTest {
    private val flights = SingleFlight<String, String>()
    private val context = AsyncContext(ForkJoinPool.commonPool(), ForkJoinPool.commonPool())
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
     * leader 自己的协程仍处于活动状态时，block 自己抛出的 `CancellationException`（其中的 `withTimeout`）是普通的失败，
     * 与其它失败一样交给全部跟随者：跟随者不重新领头、不绕过 SingleFlight 自行运行（RFC 0005 第三轮 K5，实验 E4：8 个调用方运行 8 次）。
     * 直接从协程调用与异步变体两种 leader。
     */
    @Test
    fun aSuspendingLeadersOwnCancellationIsSharedWithItsFollowers() {
        assertOwnCancellationIsShared(::suspending)
    }

    @Test
    fun anAsyncLeadersOwnCancellationIsSharedWithItsFollowers() {
        assertOwnCancellationIsShared(::async)
    }

    private fun assertOwnCancellationIsShared(start: (String, suspend () -> String) -> CompletableFuture<String>) {
        val gate = CompletableFuture<String>()
        val runs = AtomicInteger()
        // 都在测试线程上注册（执行到第一次挂起）：第一个领头、停在 gate 上，其余合并到它
        val callers = List(CALLERS) {
            start("k") {
                runs.incrementAndGet()
                gate.awaitOriginal()
                withTimeout(1) { awaitCancellation() }
            }
        }
        flights.inFlight.assert().isEqualTo(1)
        gate.complete("go")

        callers.forEach { causeOf(it).assert().isInstanceOf(TimeoutCancellationException::class.java) }
        runs.get().assert().isEqualTo(1)
        flights.inFlight.assert().isEqualTo(0)
    }

    /**
     * 异步变体的 leader 没有被中断时，[InterruptedException]（阻塞执行器线程上的 loader 被中断）也是普通的失败：
     * 只有同步变体的 loader 在调用方线程上执行，那里的中断才是 leader 被中断（K5）。
     */
    @Test
    fun anInterruptOffTheCallerThreadIsSharedWithTheFollowers() {
        val gate = CompletableFuture<String>()
        val runs = AtomicInteger()
        val failure = InterruptedException("blocking thread interrupted")
        val callers = List(CALLERS) {
            async {
                runs.incrementAndGet()
                gate.awaitOriginal()
                throw failure
            }
        }
        gate.complete("go")

        callers.forEach { causeOf(it).assert().isSameAs(failure) }
        runs.get().assert().isEqualTo(1)
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
     * 合并规则（RFC 0005 §4.4）：同步 leader 被中断时加载随之结束，leader 收到 loader 抛出的 [InterruptedException]；
     * 跟随者不接受这个失败，自行运行（重新注册，由它自己领头），它自己的中断标志不被置上。
     */
    @Test
    fun followersOfAnInterruptedSyncLeaderRunTheirOwnLoad() {
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
        val follower = fork { sync { "own" } to Thread.currentThread().isInterrupted }
        awaitCondition { follower.parked }
        leader.thread.interrupt()
        val (leaderCause, _) = leader.joinFailure() as FollowerFailure
        leaderCause.assert().isInstanceOf(InterruptedException::class.java)
        follower.join().assert().isEqualTo("own" to false)
        flights.inFlight.assert().isEqualTo(0)
    }

    /**
     * 同步 leader 被中断时，失败不一定是 [InterruptedException]（loader 包装中断、恢复中断标志）：以中断标志为准。
     */
    @Test
    fun aSyncLeaderFailingWithItsInterruptFlagSetCountsAsInterrupted() {
        val followerParked = CountDownLatch(1)
        val failure = IllegalStateException("Loader interrupted.")
        val leader = fork {
            try {
                sync {
                    followerParked.awaitOrFail("follower parked")
                    Thread.currentThread().interrupt()
                    throw failure
                }
            } finally {
                Thread.interrupted()
            }
        }
        awaitCondition { flights.inFlight == 1 }
        val follower = fork { sync { "own" } }
        awaitCondition { follower.parked }
        followerParked.countDown()
        leader.joinFailure().assert().isSameAs(failure)
        follower.join().assert().isEqualTo("own")
    }

    /**
     * 重试有上界：跟随者重新注册之后仍合并到被中断的 leader，就绕过 SingleFlight 直接执行自己的加载。
     * 跟随者的恢复由手动执行的执行器派发，以便在它重新注册之前让第二个 leader 先注册。
     */
    @Test
    fun aFollowerRunsUnregisteredAfterJoiningTwoInterruptedLeaders() {
        val held = LinkedBlockingQueue<Runnable>()
        val gated = AsyncContext({ held.add(it) }, ForkJoinPool.commonPool())
        try {
            fun interruptibleLeader(value: String): Concurrency.Forked<String> {
                val started = CountDownLatch(1)
                val leader = fork {
                    sync("k") {
                        started.countDown()
                        CountDownLatch(1).await()
                        value
                    }
                }
                started.awaitOrFail("$value started")
                return leader
            }
            val first = interruptibleLeader("first")
            val ownRuns = AtomicInteger()
            val follower = gated.runAsync {
                flights.executeSuspending("k", gated.scope) {
                    ownRuns.incrementAndGet()
                    "own"
                }
            }
            first.thread.interrupt()
            first.joinFailure().assert().isInstanceOf(InterruptedException::class.java)
            val second = interruptibleLeader("second")
            // 跟随者恢复：重新注册时合并到第二个 leader
            requireNotNull(held.poll(5, TimeUnit.SECONDS)).run()
            follower.isDone.assert().isFalse()
            second.thread.interrupt()
            second.joinFailure().assert().isInstanceOf(InterruptedException::class.java)
            while (!follower.isDone) {
                requireNotNull(held.poll(5, TimeUnit.SECONDS)).run()
            }
            follower.get(5, TimeUnit.SECONDS).assert().isEqualTo("own")
            ownRuns.get().assert().isEqualTo(1)
            flights.inFlight.assert().isEqualTo(0)
        } finally {
            gated.close()
        }
    }

    /**
     * 批量合并到的 leader 被中断：结果中不含那个 key（调用方改走单 key 路径），自己负责的 key 照常返回。
     */
    @Test
    fun batchOmitsKeysWhoseLeaderWasInterrupted() {
        val started = CountDownLatch(1)
        val leader = fork {
            sync("a") {
                started.countDown()
                CountDownLatch(1).await()
                "a1"
            }
        }
        started.awaitOrFail()
        val batch = fork { syncAll(listOf("a", "b")) { keys -> keys.associateWith { "${it}2" } } }
        awaitCondition { batch.parked }
        leader.thread.interrupt()
        leader.joinFailure().assert().isInstanceOf(InterruptedException::class.java)
        batch.join().assert().isEqualTo(mapOf("b" to "b2"))
        flights.inFlight.assert().isEqualTo(0)
    }

    /**
     * 上下文中没有执行变体的调用方是它在运行时作用域中启动的 leader 的拥有者：leader 被取消时交出原始的取消，
     * 而不是跟随者看到的内部异常。
     */
    @Test
    fun anUnmodedCallerSeesTheOriginalCancellationOfItsLeader() {
        val gate = CompletableFuture<String>()
        val caller = fork {
            runCatching {
                runBlocking { flights.executeSuspending("k", scope) { gate.awaitOriginal() } }
            }.exceptionOrNull()
        }
        awaitCondition { flights.inFlight == 1 }
        context.close()
        caller.join().assert().isInstanceOf(CancellationException::class.java)
    }

    /**
     * leader 先注销、再发布：被唤醒的跟随者立即再调用同一个 key，必然开始新的执行（读己之写依赖这一顺序）。
     */
    @Test
    fun deregistersBeforePublishing() {
        val gate = CompletableFuture<String>()
        val leader = async { gate.awaitOriginal() }
        val follower = context.runAsync {
            val joined = flights.executeSuspending("k", scope) { "other" }
            val retried = flights.executeSuspending("k", scope) { "fresh" }
            joined to retried
        }
        gate.complete("v")
        leader.get(5, TimeUnit.SECONDS).assert().isEqualTo("v")
        follower.get(5, TimeUnit.SECONDS).assert().isEqualTo("v" to "fresh")
    }

    /**
     * 正在 [SingleFlight.loading] 内为某个 key 调用数据源的线程，再调用这个 key 快速失败（同步与异步变体），离开作用域后清除。
     */
    @Test
    fun reentryWhileLoadingFailsFast() {
        async("k") {
            flights.loading(listOf("k")) {
                val syncError = runCatching { sync("k") { "x" } }.exceptionOrNull()
                syncError.assert().isInstanceOf(IllegalStateException::class.java)
                causeOf(async("k") { "y" }).assert().isInstanceOf(IllegalStateException::class.java)
            }
            "v"
        }.get(5, TimeUnit.SECONDS).assert().isEqualTo("v")
        sync("k") { "again" }.assert().isEqualTo("again")
    }

    @Test
    fun nestedLoadingKeepsOuterKeysUntilItEnds() {
        async("a") {
            flights.loading(listOf("a")) {
                flights.loading(listOf("a", "b")) { Unit }
                causeOf(async("a") { "x" }).assert().isInstanceOf(IllegalStateException::class.java)
            }
            "v"
        }.get(5, TimeUnit.SECONDS).assert().isEqualTo("v")
        async("b") { "b" }.get(5, TimeUnit.SECONDS).assert().isEqualTo("b")
    }

    @Test
    fun batchWithoutKeysDoesNotCallBlock() {
        syncAll(emptyList()) { throw AssertionError("not called") }.assert().isEmpty()
    }

    private class LoadTimedOut : RuntimeException("timed out")

    /**
     * 卡住的同步 leader：一直阻塞到 [release]。
     */
    private fun stuckLeader(
        flights: SingleFlight<String, String>,
        release: CountDownLatch,
        key: String = "k"
    ): Concurrency.Forked<String> {
        val started = CountDownLatch(1)
        val leader = fork("stuck") {
            runSync {
                flights.executeSuspending(key, scope) {
                    started.countDown()
                    release.awaitOrFail("release stuck leader")
                    "stuck"
                }
            }
        }
        started.awaitOrFail("stuck leader started")
        return leader
    }

    /**
     * `load.timeout`（RFC 0005 §4.4）：跟随者（同步与异步）等到这次加载的截止时刻仍没有结果时，以 onTimeout 的异常失败；
     * 截止时刻之后到来的调用方注销卡住的加载（不完成它），自己领头；卡住的 leader 迟到的结果只交给它自己。
     */
    @Test
    fun theLoadTimeoutBoundsFollowersAndDeregistersAStuckLoad() {
        val bounded = SingleFlight<String, String>(TimeUnit.MILLISECONDS.toNanos(200)) { LoadTimedOut() }
        val release = CountDownLatch(1)
        val stuck = stuckLeader(bounded, release)
        val syncFollower = fork("follower") { runSync { bounded.executeSuspending("k", scope) { "other" } } }
        val asyncFollower = context.runAsync { bounded.executeSuspending("k", scope) { "other" } }
        syncFollower.joinFailure().assert().isInstanceOf(LoadTimedOut::class.java)
        causeOf(asyncFollower).assert().isInstanceOf(LoadTimedOut::class.java)
        // 截止时刻已过：新的调用方注销卡住的加载，自己领头
        runSync { bounded.executeSuspending("k", scope) { "fresh" } }.assert().isEqualTo("fresh")
        bounded.inFlight.assert().isEqualTo(0)
        release.countDown()
        stuck.join().assert().isEqualTo("stuck")
        bounded.inFlight.assert().isEqualTo(0)
    }

    /**
     * 批量：截止时刻之后，登记时注销卡住的加载、由本批领头；截止时刻之前合并到它的批量在截止时刻以超时失败。
     */
    @Test
    fun batchesAreBoundedByTheLoadTimeoutToo() {
        val bounded = SingleFlight<String, String>(TimeUnit.MILLISECONDS.toNanos(200)) { LoadTimedOut() }
        val release = CountDownLatch(1)
        val stuck = stuckLeader(bounded, release, key = "a")
        val joined = fork("joined batch") {
            runSync {
                bounded.executeAllSuspending(listOf("a", "b"), scope) { keys -> keys.associateWith { "${it}2" } }
            }
        }
        joined.joinFailure().assert().isInstanceOf(LoadTimedOut::class.java)
        val leading = runSync {
            bounded.executeAllSuspending(listOf("a", "b"), scope) { keys -> keys.associateWith { "${it}3" } }
        }
        leading.assert().isEqualTo(mapOf("a" to "a3", "b" to "b3"))
        release.countDown()
        stuck.join().assert().isEqualTo("stuck")
        bounded.inFlight.assert().isEqualTo(0)
    }

    /**
     * 批量领头自己的 key 期间越过了合并到的 key 的截止时刻：那次加载已经完成时照常交出结果，仍未完成时立即以超时失败
     * （默认的 onTimeout 抛出 [IllegalStateException]）。
     */
    @Test
    fun aJoinedKeyPastItsDeadlineYieldsItsReadyResultOrTimesOut() {
        val timeout = TimeUnit.MILLISECONDS.toNanos(200)
        val bounded = SingleFlight<String, String>(timeout)
        val finishing = CountDownLatch(1)
        val finished = stuckLeader(bounded, finishing, key = "a")
        val ready = runSync {
            bounded.executeAllSuspending(listOf("a", "b"), scope) { keys ->
                finishing.countDown()
                finished.join()
                TimeUnit.NANOSECONDS.sleep(timeout)
                keys.associateWith { "${it}2" }
            }
        }
        ready.assert().isEqualTo(mapOf("a" to "stuck", "b" to "b2"))

        val release = CountDownLatch(1)
        val stuck = stuckLeader(bounded, release, key = "a")
        val timedOut = runCatching {
            runSync {
                bounded.executeAllSuspending(listOf("a", "b"), scope) { keys ->
                    TimeUnit.NANOSECONDS.sleep(timeout)
                    keys.associateWith { "${it}3" }
                }
            }
        }.exceptionOrNull()
        timedOut.assert().isInstanceOf(IllegalStateException::class.java).hasMessage("Load timed out.")
        release.countDown()
        stuck.join().assert().isEqualTo("stuck")
        bounded.inFlight.assert().isEqualTo(0)
    }

    /**
     * 上下文中没有执行变体的调用方：作用域中的 leader 同步执行、越过截止时刻才完成时，发起的调用方照常拿到结果。
     */
    @Test
    fun anUnmodedCallerGetsTheResultOfALeaderThatFinishedPastTheDeadline() {
        val timeout = TimeUnit.MILLISECONDS.toNanos(200)
        val bounded = SingleFlight<String, String>(timeout) { LoadTimedOut() }
        val result = runBlocking {
            bounded.executeSuspending("k", scope) {
                TimeUnit.NANOSECONDS.sleep(timeout)
                "slow"
            }
        }
        result.assert().isEqualTo("slow")
        bounded.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun reentrantSyncCallFailsFast() {
        val error = runCatching { sync { sync { "inner" } } }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
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

    private fun syncAll(keys: List<String>, block: suspend (List<String>) -> Map<String, String>): Map<String, String> =
        runSync { flights.executeAllSuspending(keys, scope, block) }

    private fun asyncAll(keys: List<String>, block: suspend (List<String>) -> Map<String, String>): CompletableFuture<Map<String, String>> =
        context.runAsync { flights.executeAllSuspending(keys, scope, block) }

    private fun suspendingAll(
        keys: List<String>,
        block: suspend (List<String>) -> Map<String, String>
    ): CompletableFuture<Map<String, String>> =
        scope.future(start = CoroutineStart.UNDISPATCHED) { flights.executeAllSuspending(keys, scope, block) }

    /**
     * 批量：本调用负责空闲的 key（去重），合并在途的 key，先完成自己负责的整批再等待其它调用者。
     */
    @Test
    fun batchLeadsFreeKeysAndJoinsInFlightOnes() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val single = fork {
            sync("a") {
                started.countDown()
                release.awaitOrFail()
                "a1"
            }
        }
        started.awaitOrFail()
        val led = mutableListOf<String>()
        val batch = fork {
            syncAll(listOf("a", "b", "b", "c")) { keys ->
                led.addAll(keys)
                keys.associateWith { "${it}2" }
            }
        }
        awaitCondition { batch.parked }
        led.assert().containsExactly("b", "c")
        release.countDown()
        single.join().assert().isEqualTo("a1")
        batch.join().assert().isEqualTo(mapOf("b" to "b2", "c" to "c2", "a" to "a1"))
        flights.inFlight.assert().isEqualTo(0)
    }

    /**
     * 整批缺少某个 key 的结果：发起的调用方的结果中没有它，合并到它的等待者以 [IllegalStateException] 失败。
     * 三种调用方（同步、异步、直接从协程调用）的语义相同。
     */
    @Test
    fun missingResultIsOmittedForTheLeaderAndFailsJoiners() {
        listOf<(List<String>, suspend (List<String>) -> Map<String, String>) -> Map<String, String>>(
            { keys, block -> syncAll(keys, block) },
            { keys, block -> asyncAll(keys, block).get(5, TimeUnit.SECONDS) },
            { keys, block -> suspendingAll(keys, block).get(5, TimeUnit.SECONDS) },
        ).forEach { run ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val leader = fork {
                run(listOf("a", "b")) {
                    started.countDown()
                    release.awaitOrFail()
                    mapOf("a" to "v")
                }
            }
            started.awaitOrFail()
            val joiner = async("b") { "other" }
            release.countDown()
            leader.join().assert().isEqualTo(mapOf("a" to "v"))
            causeOf(joiner).assert().isInstanceOf(IllegalStateException::class.java)
            awaitCondition { flights.inFlight == 0 }
        }
    }

    /**
     * 整批失败：发起的调用方与合并到它的等待者都收到原始异常，全部 key 注销。
     */
    @Test
    fun batchFailureReachesItsJoiners() {
        listOf<(List<String>, suspend (List<String>) -> Map<String, String>) -> CompletableFuture<Map<String, String>>>(
            { keys, block -> asyncAll(keys, block) },
            { keys, block -> suspendingAll(keys, block) },
        ).forEach { start ->
            val gate = CompletableFuture<Map<String, String>>()
            val failure = IllegalStateException("batch failed")
            val batch = start(listOf("a", "b")) { gate.awaitOriginal() }
            val joiner = async("a") { "other" }
            gate.completeExceptionally(failure)
            causeOf(batch).assert().isSameAs(failure)
            causeOf(joiner).assert().isSameAs(failure)
            awaitCondition { flights.inFlight == 0 }
        }
        val syncFailure = IllegalStateException("sync batch failed")
        runCatching { syncAll(listOf("a")) { throw syncFailure } }.exceptionOrNull().assert().isSameAs(syncFailure)
        flights.inFlight.assert().isEqualTo(0)
    }

    @Test
    fun reentrantBatchFailsFast() {
        val error = runCatching {
            sync("k") {
                syncAll(listOf("j", "k")) { keys -> keys.associateWith { "v" } }.getValue("k")
            }
        }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
        flights.inFlight.assert().isEqualTo(0)
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

    private companion object {
        /**
         * 合并到同一次加载的调用方个数（实验 E4）。
         */
        const val CALLERS = 8
    }
}
