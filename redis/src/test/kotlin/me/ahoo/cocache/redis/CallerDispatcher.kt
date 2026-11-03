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

package me.ahoo.cocache.redis

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * 调用方的调度器（只用标准库的协程原语：cocache-redis 不直接依赖 kotlinx）：协程在名为 [threadName] 的单线程上启动，
 * 每次恢复都派发回这个线程。存储经 `intercepted()` 恢复调用方时，挂起点之后的代码都在这个线程上执行，不在 Lettuce 的 I/O 线程上。
 */
internal class CallerDispatcher(val threadName: String = "l1-caller") : ContinuationInterceptor, AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(
            it,
            threadName
        ).apply { isDaemon = true }
    }

    override val key: CoroutineContext.Key<*> = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = object : Continuation<T> {
        override val context: CoroutineContext = continuation.context

        override fun resumeWith(result: Result<T>) = executor.execute { continuation.resumeWith(result) }
    }

    /**
     * 在本调度器上启动 [block]，不等待。
     */
    fun <T> start(block: suspend () -> T): Pending<T> {
        val pending = Pending<T>()
        block.startCoroutine(Continuation(this) { pending.outcome.complete(it) })
        return pending
    }

    /**
     * 在本调度器上运行 [block] 并等它结束：返回它的结果，或抛出它抛出的异常本身。
     */
    fun <T> run(block: suspend () -> T): T = start(block).await()

    override fun close() {
        executor.shutdownNow()
    }

    class Pending<T> {
        internal val outcome = CompletableFuture<Result<T>>()

        val isDone: Boolean
            get() = outcome.isDone

        /**
         * 等协程结束（至多 10 秒）：返回它的结果，或抛出它抛出的异常本身。
         */
        fun await(): T = outcome.get(10, TimeUnit.SECONDS).getOrThrow()

        /**
         * 等协程结束（至多 10 秒），返回它抛出的异常；正常结束时为 `null`。
         */
        fun failure(): Throwable? = outcome.get(10, TimeUnit.SECONDS).exceptionOrNull()
    }
}
