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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 测试用并发工具：所有等待都有超时，后台线程的异常会在 [join] 时抛出。
 */
object Concurrency {
    val TIMEOUT: Duration = Duration.ofSeconds(10)

    /**
     * kotlinx 调试模式（`-ea` 或 `kotlinx.coroutines.debug`）在协程运行期间给线程名加上的后缀（` @名字#编号`）。
     */
    private val COROUTINE_SUFFIX = Regex(" @[^@]*#\\d+$")

    /**
     * 当前线程的名字，去掉 kotlinx 调试模式加上的协程后缀。引擎的读路径运行在协程核心上（同步变体在调用方线程上以 `runBlocking` 运行，
     * 异步变体在执行器线程上运行协程），按线程名编排竞态或断言完成线程时用它，结果与是否开启调试模式无关。
     */
    fun currentThreadName(): String = Thread.currentThread().name.replace(COROUTINE_SUFFIX, "")

    fun CountDownLatch.awaitOrFail(what: String = "latch") {
        check(await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) { "Timed out waiting for $what." }
    }

    /**
     * 轮询直到条件成立，超时失败。用于断言跨实例的最终效果。
     */
    fun awaitCondition(what: String = "condition", condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what." }
            Thread.sleep(5)
        }
    }

    /**
     * 在后台线程运行并返回句柄。
     */
    fun <T> fork(name: String = "tck-worker", block: () -> T): Forked<T> = Forked(name, block)

    class Forked<T> internal constructor(name: String, block: () -> T) {
        private val result = AtomicReference<Result<T>>()
        private val finished = CountDownLatch(1)

        val thread: Thread = Thread({
            result.set(runCatching(block))
            finished.countDown()
        }, name).apply {
            isDaemon = true
            start()
        }

        /**
         * 线程停在等待中：`WAITING`，或 `TIMED_WAITING`（同步路径的跟随者在 `runBlocking` 的事件循环里以限时停顿等待）。
         */
        val parked: Boolean
            get() = thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING

        /**
         * 等待结束并返回结果；线程抛出的异常原样抛出。
         */
        fun join(): T {
            finished.awaitOrFail(thread.name)
            return result.get().getOrThrow()
        }

        /**
         * 等待结束并返回异常（没有异常时失败）。
         */
        fun joinFailure(): Throwable {
            finished.awaitOrFail(thread.name)
            return checkNotNull(result.get().exceptionOrNull()) { "${thread.name} did not fail." }
        }
    }
}
