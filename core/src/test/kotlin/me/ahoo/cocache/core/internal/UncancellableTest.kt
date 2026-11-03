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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import me.ahoo.cocache.core.engine.EngineDispatcher
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * [uncancellable]：核心从不取消 L1 调用（RFC 0005 §4.3）。
 * - 调用方被取消时调用照常执行到结束（存储用可取消的等待也一样），结果交回调用方，取消在之后的第一个挂起点生效；
 * - 执行器拒绝派发调用的续接时结果不丢，取消转给调用方；
 * - 失败时抛出原始异常（调试模式下也不是副本）。
 */
class UncancellableTest {
    /**
     * 存储以可取消的等待实现（kotlinx 的 `await()`）：调用方在等待期间被取消，调用不受影响，取得的结果交回调用方；
     * 之后的挂起点以取消结束，调用方据此释放令牌。
     */
    @Test
    fun aCancelledCallerStillWaitsForTheCallAndGetsItsResult() = runBlocking {
        val command = CompletableFuture<String>()
        val started = CompletableFuture<Unit>()
        val returned = AtomicBoolean()
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            val value = uncancellable {
                started.complete(Unit)
                command.await()
            }
            returned.set(true)
            yield()
            value
        }
        started.get(5, TimeUnit.SECONDS)
        caller.cancel()
        command.isCancelled.assert().isFalse()
        command.complete("v")

        runCatching { caller.await() }.exceptionOrNull().assert().isInstanceOf(CancellationException::class.java)
        returned.get().assert().withFailMessage { "the call's result must reach the caller before the cancellation" }.isTrue()
        command.isCancelled.assert().isFalse()
    }

    /**
     * 执行器拒绝派发调用的续接（运行时关闭之后）：`EngineDispatcher` 不取消正在执行的 L1 调用，续接在 `Dispatchers.IO` 上执行，
     * 结果照常交回（存储用可取消的等待也一样）。调用方的协程在之后的派发被拒绝时才被取消（cause 为那次拒绝）。
     */
    @Test
    fun aRejectedResumptionDoesNotCancelTheCall() {
        val rejecting = AtomicBoolean()
        val executor = Executor { task ->
            if (rejecting.get()) throw RejectedExecutionException("closed")
            thread(name = "engine") { task.run() }
        }
        val scope = CoroutineScope(Job() + EngineDispatcher(executor))
        val atomic = CompletableFuture<String>()
        val cancellable = CompletableFuture<String>()
        val observed = CompletableFuture<List<Any>>()
        val caller = scope.async(start = CoroutineStart.UNDISPATCHED) {
            val first = uncancellable { suspended(atomic) }
            val second = uncancellable { cancellable.await() }
            observed.complete(listOf(first, second, requireNotNull(coroutineContext[Job]).isCancelled))
            yield()
            first + second
        }
        rejecting.set(true)
        atomic.complete("a")
        observed.isDone.assert().isFalse()
        cancellable.complete("b")

        observed.get(5, TimeUnit.SECONDS).assert().isEqualTo(listOf("a", "b", false))
        val failure = runCatching { runBlocking { caller.await() } }.exceptionOrNull()
        failure.assert().isInstanceOf(CancellationException::class.java)
        // 调试模式下 await 交出的是取消异常的副本，原来的那个是它的 cause
        generateSequence(failure) { it.cause }.any { it is RejectedExecutionException }.assert().isTrue()
        cancellable.isCancelled.assert().isFalse()
    }

    /**
     * 调用以异常结束（挂起之后，或不挂起）：调用方拿到原始异常本身，不是 `withContext` 在调试模式下恢复的副本
     * （测试以 `-ea` 运行，kotlinx 的调试模式开启）。
     */
    @Test
    fun failuresAreTheOriginalException() = runBlocking {
        val original = IllegalStateException("L1 down")
        val command = CompletableFuture<String>()
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                uncancellable<String> {
                    suspended(command)
                    throw original
                }
            }
        }
        thread { command.complete("reply") }

        caller.await().exceptionOrNull().assert().isSameAs(original)
        runCatching { uncancellable<String> { throw original } }.exceptionOrNull().assert().isSameAs(original)
    }

    /**
     * 挂起直到 [future] 完成，在完成它的线程上恢复（经调用方的调度器），失败时以异常恢复。
     */
    private suspend fun <T> suspended(future: CompletableFuture<T>): T = suspendCancellableCoroutine { continuation ->
        future.whenComplete { value, error ->
            if (error == null) continuation.resume(value) else continuation.resumeWith(Result.failure(error))
        }
    }
}
