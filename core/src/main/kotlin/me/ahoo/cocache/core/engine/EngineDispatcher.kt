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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import me.ahoo.cocache.core.internal.Futures
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.CoroutineContext

/**
 * 协程核心的调度器（运行时作用域，RFC 0005 §4.4），与异步路径的 future 链遵守同一套派发规则（docs/investigations/async-8t-gap.md）：
 *
 * - 任务经 [Futures.execute] 交给异步执行器，执行期间带续接标记：其中完成的 future 链的续接可以就地执行。
 * - 当前线程已在执行该执行器派发的任务时不再派发，就地恢复：上一步在执行器上完成时省去一次派发
 *   （kotlinx 以线程局部的事件循环展开就地恢复，栈深有界）。只在上一步于执行器之外（I/O 线程、阻塞执行器、数据源的线程）完成时派发。
 * - 执行器拒绝时与 kotlinx 的执行器调度器相同：以携带 [RejectedExecutionException] 的取消结束协程，
 *   再交给 [Dispatchers.IO] 运行到结束（释放令牌等收尾照常执行）。
 */
internal class EngineDispatcher(private val executor: Executor) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Futures.onExecutor(executor)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        try {
            Futures.execute(executor, block)
        } catch (e: RejectedExecutionException) {
            context.cancel(CancellationException("The task was rejected.").apply { initCause(e) })
            Dispatchers.IO.dispatch(context, block)
        }
    }

    override fun toString(): String = "EngineDispatcher($executor)"
}
