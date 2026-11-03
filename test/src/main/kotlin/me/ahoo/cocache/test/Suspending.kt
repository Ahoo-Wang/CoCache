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

import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.startCoroutine

/**
 * 在阻塞的测试代码中调用挂起函数：L1 SPI 是挂起的（`L1Store`、`InvalidationChannel.publish`，RFC 0005 §4.3），继承 TCK 规范的
 * 第三方实现者用它直接调用存储，不必依赖 kotlinx。它是 TCK 的 API，在 8.x 内保持稳定。
 *
 * 只用标准库的协程原语。与 `runBlocking` 一样，协程在调用方线程上运行：挂起之后的恢复（如在 L1 客户端的 I/O 线程上完成的命令）
 * 排进调用方线程的队列，由调用方线程继续执行，从不在完成它的线程上继续。用例体因此可以在其中阻塞（latch、轮询、嵌套的 [await]），
 * 而不会停住 L1 客户端的 I/O 线程。
 */
object Suspending {
    /**
     * 等待的上限：存储的每个调用都必须自己限时（SPI 约定），超过它说明调用永远挂起。
     */
    private val LIMIT: Duration = Duration.ofSeconds(30)

    /**
     * 在调用方线程上运行 [block] 并等它结束：返回它的结果，或抛出它抛出的异常本身（不包装、不复制）。
     *
     * - 等待期间调用方线程被中断时抛出 [InterruptedException]，保留中断标志；协程不再在这个线程上继续。
     * - 超过 30 秒没有结束时以 [AssertionError] 失败（存储的每个调用都必须自己限时）。
     */
    @JvmStatic
    fun <T> await(block: suspend () -> T): T {
        val loop = CallerLoop()
        var outcome: Result<T>? = null
        block.startCoroutine(
            Continuation(loop) {
                outcome = it
                loop.finish()
            }
        )
        loop.run(System.nanoTime() + LIMIT.toNanos())
        return requireNotNull(outcome).getOrThrow()
    }

    /**
     * 调用方线程的事件循环：被拦截的续接恢复时排进队列，[run] 在调用方线程上逐个执行，直到协程结束。
     */
    private class CallerLoop : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
        private val tasks = LinkedBlockingQueue<Runnable>()

        @Volatile
        private var finished = false

        fun finish() {
            finished = true
            tasks.put(Runnable { })
        }

        /**
         * 执行排队的续接直到协程结束。被中断时恢复中断标志再抛出 [InterruptedException]（`poll` 抛出时已清除它）。
         */
        fun run(deadline: Long) {
            while (!finished) {
                val remaining = deadline - System.nanoTime()
                val task = try {
                    tasks.poll(remaining, TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } ?: throw AssertionError("The suspending call did not finish in $LIMIT.")
                task.run()
            }
        }

        override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = object : Continuation<T> {
            override val context = continuation.context

            override fun resumeWith(result: Result<T>) {
                tasks.put(Runnable { continuation.resumeWith(result) })
            }
        }
    }
}
