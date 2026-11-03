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

package me.ahoo.cocache.api.spi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * 不经 kotlinx 启动的协程（cocache-api 的测试只用标准库的协程原语）：在调用方线程上启动，没有调度器，
 * 挂起后在恢复它的线程上继续。
 */
class Started<T>(block: suspend () -> T) {
    private val finished = CountDownLatch(1)

    @Volatile
    private var outcome: Result<T>? = null

    /**
     * 启动返回时协程是否已经结束：没有挂起（或挂起之前已被恢复）。
     */
    val completedOnStart: Boolean

    init {
        block.startCoroutine(
            Continuation(EmptyCoroutineContext) {
                outcome = it
                finished.countDown()
            }
        )
        completedOnStart = outcome != null
    }

    /**
     * 等协程结束（至多 5 秒），返回它的结果；失败时是它抛出的异常本身。
     */
    fun outcome(): Result<T> {
        check(finished.await(5, TimeUnit.SECONDS)) { "The coroutine did not finish in 5 seconds." }
        return requireNotNull(outcome)
    }
}

/**
 * 供测试（含 Java 测试）调用挂起函数。
 */
object Suspending {
    /**
     * 运行 [block] 并等它结束：返回它的结果，或抛出它抛出的异常本身。
     */
    @JvmStatic
    fun <T> await(block: suspend () -> T): T = Started(block).outcome().getOrThrow()
}
