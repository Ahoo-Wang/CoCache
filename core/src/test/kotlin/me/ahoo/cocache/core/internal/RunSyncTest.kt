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

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * 同步变体的入口 [runSync] 被中断时的规则（RFC 0005 §4.3）。协程停在不可取消的等待上（[awaitUncancellable]，即在途的 L1 命令），
 * 测试线程中断调用方，等 `runBlocking` 取消协程之后再完成那个等待。
 */
class RunSyncTest {
    /**
     * 固定 kotlinx 的行为（1.10.2 与 1.11.0 相同，1.8.0 不同）：线程被中断时 `runBlocking` 取消协程、清除中断标志，
     * 继续在调用方线程上运行协程直到结束，然后才抛出 [InterruptedException]。
     *
     * 同步变体依赖它：在途的 L1 命令先完成，持有令牌的步骤释放令牌，调用方返回之后不会有命令再写出。
     * kotlinx 1.8.0 被中断时立即抛出：协程停在等待上、再也不会在这个线程上恢复，这个用例因此失败（调用方线程不再停下）。
     */
    @Test
    fun runBlockingCancelsAndWaitsForTheCoroutineWhenInterrupted() {
        val command = CompletableFuture<String>()
        val job = CompletableFuture<Job>()
        val resumed = AtomicBoolean()
        val caller = fork("caller") {
            val result = runCatching {
                runBlocking {
                    job.complete(requireNotNull(coroutineContext[Job]))
                    command.awaitUncancellable().also { resumed.set(true) }
                }
            }
            Triple(result, resumed.get(), Thread.currentThread().isInterrupted)
        }
        val coroutine = job.get(5, TimeUnit.SECONDS)
        awaitCondition("caller parked") { caller.parked }

        caller.thread.interrupt()

        awaitCondition("runBlocking cancelled the coroutine and parked again") {
            coroutine.isCancelled && !caller.thread.isInterrupted && caller.parked
        }
        command.complete("v")
        val (result, resumedBeforeReturning, interrupted) = caller.join()
        resumedBeforeReturning.assert().withFailMessage { "runBlocking returned before the coroutine finished" }.isTrue()
        result.exceptionOrNull().assert().isInstanceOf(InterruptedException::class.java)
        interrupted.assert().withFailMessage { "runBlocking clears the interrupt flag" }.isFalse()
    }

    /**
     * 取消之后协程没有再经过检查取消的挂起点、自己结束了：照常返回结果，恢复中断标志。
     */
    @Test
    fun aCoroutineThatFinishesAfterTheInterruptReturnsItsResult() {
        val outcome = interruptWhileWaiting { command -> command.awaitUncancellable() }
        outcome.result.getOrThrow().assert().isEqualTo("v")
        outcome.interrupted.assert().isTrue()
    }

    /**
     * 同上，协程自己抛出的异常照常交给调用方（不是 [InterruptedException]），恢复中断标志。
     */
    @Test
    fun aCoroutineThatFailsAfterTheInterruptThrowsItsOwnFailure() {
        val failure = IllegalStateException("own")
        val outcome = interruptWhileWaiting { command ->
            command.awaitUncancellable()
            throw failure
        }
        outcome.result.exceptionOrNull().assert().isSameAs(failure)
        outcome.interrupted.assert().isTrue()
    }

    /**
     * 取消在之后的第一个检查取消的挂起点生效：抛出 [InterruptedException]，恢复中断标志。
     */
    @Test
    fun theCancellationTakesEffectAtTheNextSuspensionPoint() {
        val reached = AtomicBoolean()
        val outcome = interruptWhileWaiting { command ->
            val value = command.awaitUncancellable()
            reached.set(true)
            yield()
            value
        }
        reached.get().assert().withFailMessage { "the wait in flight must finish first" }.isTrue()
        outcome.result.exceptionOrNull().assert().isInstanceOf(InterruptedException::class.java)
        outcome.interrupted.assert().isTrue()
    }

    /**
     * 协程没有被取消、[runSync] 的 block 自己抛出的 [InterruptedException]（loader 被中断）原样抛出，不置中断标志。
     */
    @Test
    fun anInterruptedExceptionOfTheBlockLeavesTheFlagAlone() {
        val own = InterruptedException("loader interrupted")
        runCatching { runSync { throw own } }.exceptionOrNull().assert().isSameAs(own)
        Thread.currentThread().isInterrupted.assert().isFalse()
    }

    private class Outcome(val result: Result<String>, val interrupted: Boolean)

    /**
     * 在后台线程上以 [runSync] 运行 [block]，它停在 `command` 上时中断调用方，等 `runBlocking` 取消协程之后再完成 `command`。
     */
    private fun interruptWhileWaiting(block: suspend (CompletableFuture<String>) -> String): Outcome {
        val command = CompletableFuture<String>()
        val caller = fork("caller") {
            val result = runCatching { runSync { block(command) } }
            Outcome(result, Thread.currentThread().isInterrupted)
        }
        awaitCondition("caller parked") { caller.parked }
        caller.thread.interrupt()
        awaitCondition("runBlocking cancelled the coroutine and parked again") {
            !caller.thread.isInterrupted && caller.parked
        }
        caller.thread.isAlive.assert().isTrue()
        command.complete("v")
        return caller.join()
    }
}
