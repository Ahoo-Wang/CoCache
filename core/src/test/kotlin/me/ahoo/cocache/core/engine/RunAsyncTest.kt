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

import kotlinx.coroutines.future.await
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * 协程核心的异步变体（[AsyncContext.runAsync]）交给用户的结果与异常。
 */
class RunAsyncTest {
    private fun failureOf(future: CompletableFuture<*>): Throwable? = try {
        future.get(5, TimeUnit.SECONDS)
        null
    } catch (e: ExecutionException) {
        e.cause
    }

    @Test
    fun runAsyncCompletesWithTheResult() {
        val context = AsyncContext(ForkJoinPool.commonPool(), ForkJoinPool.commonPool())
        try {
            context.runAsync {
                yield()
                "v"
            }.get(5, TimeUnit.SECONDS).assert().isEqualTo("v")
        } finally {
            context.close()
        }
    }

    @Test
    fun runAsyncFailsWithTheOriginalException() {
        val context = AsyncContext(ForkJoinPool.commonPool(), ForkJoinPool.commonPool())
        val failure = IllegalStateException("boom")
        try {
            failureOf(
                context.runAsync {
                    yield()
                    throw failure
                }
            ).assert().isSameAs(failure)
        } finally {
            context.close()
        }
    }

    /**
     * 执行器拒绝派发：kotlinx 取消协程，用户拿到的仍是那个 [RejectedExecutionException]（与 6.x 的续接被拒绝一致）。
     */
    @Test
    fun rejectedDispatchFailsWithTheRejection() {
        val rejection = RejectedExecutionException("full")
        val rejecting = Executor { throw rejection }
        val context = AsyncContext(rejecting, rejecting)
        try {
            failureOf(
                context.runAsync {
                    yield()
                    "v"
                }
            ).assert().isSameAs(rejection)
        } finally {
            context.close()
        }
    }

    /**
     * 运行时关闭取消作用域：之后的调用在第一次挂起时以 [IllegalStateException] 失败，不以取消结束。
     */
    @Test
    fun runAsyncAfterCloseFailsAsClosed() {
        val context = AsyncContext(ForkJoinPool.commonPool(), ForkJoinPool.commonPool())
        context.close()
        val failure = failureOf(
            context.runAsync {
                yield()
                "v"
            }
        )
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().isEqualTo("CacheRuntime closed.")
    }

    @Test
    fun cancellingTheResultDoesNotCancelTheCoroutine() {
        val context = AsyncContext(ForkJoinPool.commonPool(), ForkJoinPool.commonPool())
        val gate = CompletableFuture<String>()
        val finished = CompletableFuture<String>()
        try {
            val result = context.runAsync {
                val value = gate.await()
                finished.complete(value)
                value
            }
            result.cancel(true)
            gate.complete("v")
            finished.get(5, TimeUnit.SECONDS).assert().isEqualTo("v")
        } finally {
            context.close()
        }
    }

    @Test
    fun dispatcherNamesItsExecutor() {
        val executor = ForkJoinPool.commonPool()
        EngineDispatcher(executor).toString().assert().isEqualTo("EngineDispatcher($executor)")
    }
}
