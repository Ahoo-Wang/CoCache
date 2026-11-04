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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

/**
 * [EngineDispatcher] 总是派发；派发的任务执行期间线程带本调度器的标记（只供 [AsyncContext.close] 使用）。
 */
class EngineDispatcherTest {
    @Test
    fun alwaysDispatches() {
        EngineDispatcher { it.run() }.isDispatchNeeded(EmptyCoroutineContext).assert().isTrue()
    }

    /**
     * 标记只在本调度器派发的任务执行期间存在：别的调度器、任务之外都没有；执行器在已带标记的线程上就地执行任务时保留外层的标记，
     * 结束后线程上什么也不留。
     */
    @Test
    fun marksOnlyItsOwnDispatchedTasks() {
        val direct = EngineDispatcher { it.run() }
        val other = EngineDispatcher { it.run() }
        direct.onDispatchedThread.assert().isFalse()
        val seen = ArrayList<Boolean>()
        direct.dispatch(EmptyCoroutineContext) {
            seen += direct.onDispatchedThread
            seen += other.onDispatchedThread
            direct.dispatch(EmptyCoroutineContext) { seen += direct.onDispatchedThread }
            seen += direct.onDispatchedThread
        }
        seen.assert().isEqualTo(listOf(true, false, true, true))
        direct.onDispatchedThread.assert().isFalse()
    }

    @Test
    fun markIsClearedWhenATaskThrows() {
        val direct = EngineDispatcher { it.run() }
        val failure = IllegalStateException("boom")
        val error = runCatching { direct.dispatch(EmptyCoroutineContext) { throw failure } }.exceptionOrNull()
        error.assert().isSameAs(failure)
        direct.onDispatchedThread.assert().isFalse()
    }

    @Test
    fun runsTasksOnTheExecutor() {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "dispatched") }
        try {
            val dispatcher = EngineDispatcher(pool)
            val ranOn = CompletableFuture<Pair<String, Boolean>>()
            dispatcher.dispatch(EmptyCoroutineContext) {
                ranOn.complete(Thread.currentThread().name to dispatcher.onDispatchedThread)
            }
            ranOn.get(5, TimeUnit.SECONDS).assert().isEqualTo("dispatched" to true)
            dispatcher.toString().assert().contains("EngineDispatcher")
        } finally {
            pool.shutdownNow()
        }
    }
}
