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

import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * [Suspending.await]：在调用方线程上运行挂起的用例体，恢复从不在完成它的线程上继续；抛出原始异常；被中断时保留中断标志。
 */
class SuspendingTest {
    /**
     * 挂起，由另一个线程（模拟 L1 客户端的 I/O 线程）在注册之后以 [outcome] 恢复。
     */
    private suspend fun <T> resumedElsewhere(outcome: Result<T>): Pair<T, Thread> {
        val value = suspendCoroutine { continuation ->
            thread(name = "completer") {
                outcome.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        }
        return value to Thread.currentThread()
    }

    @Test
    fun theBlockResumesOnTheCallerThread() {
        val caller = Thread.currentThread()
        val (value, resumedOn) = Suspending.await { resumedElsewhere(Result.success("v")) }

        value.assert().isEqualTo("v")
        resumedOn.assert().isSameAs(caller)
    }

    @Test
    fun failuresAreTheOriginalException() {
        val original = IllegalStateException("L1 down")

        val failure = runCatching {
            Suspending.await {
                resumedElsewhere(
                    Result.failure<String>(original)
                )
            }
        }.exceptionOrNull()

        failure.assert().isSameAs(original)
        runCatching { Suspending.await<String> { throw original } }.exceptionOrNull().assert().isSameAs(original)
    }

    /**
     * 用例体可以在其中阻塞，包括嵌套的等待：恢复排进调用方线程的队列，不占用完成它的线程。
     */
    @Test
    fun nestedAwaitsBlockOnlyTheCallerThread() {
        val caller = Thread.currentThread()
        val threads = Suspending.await {
            val (_, outer) = resumedElsewhere(Result.success(1))
            val (_, inner) = Suspending.await { resumedElsewhere(Result.success(2)) } to Thread.currentThread()
            listOf(outer, inner, Thread.currentThread())
        }

        threads.forEach { it.assert().isSameAs(caller) }
    }

    @Test
    fun anInterruptedWaiterGetsInterruptedExceptionAndKeepsTheFlag() {
        val never = CompletableFuture<String>()
        val waiter = fork("waiter") {
            val failure = runCatching {
                Suspending.await { suspendCoroutine<String> { never.thenAccept(it::resume) } }
            }.exceptionOrNull()
            failure to Thread.currentThread().isInterrupted
        }
        awaitCondition("waiter parked") { waiter.parked }

        waiter.thread.interrupt()

        val (failure, interrupted) = waiter.join()
        failure.assert().isInstanceOf(InterruptedException::class.java)
        interrupted.assert().withFailMessage { "the interrupt flag must be kept" }.isTrue()
    }
}
