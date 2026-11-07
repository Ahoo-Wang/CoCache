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
import me.ahoo.cocache.core.internal.L1Call
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.CoroutineContext

/**
 * 协程核心的调度器（运行时作用域，RFC 0005 §4.4）：每次恢复都派发给运行时的执行器（默认每个任务一个虚拟线程），
 * 从不在完成上一步的线程（L1 客户端的 I/O 线程、数据源的线程）上就地继续。
 *
 * - 执行器拒绝时与 kotlinx 的执行器调度器相同：以携带 [RejectedExecutionException] 的取消结束协程，
 *   再交给 [Dispatchers.IO] 运行到结束（释放令牌等收尾照常执行）。
 * - 例外是正在执行的 L1 调用（上下文带有 [L1Call]）：不取消，只交给 [Dispatchers.IO]（核心从不取消 L1 调用，RFC 0005 §4.3）。
 * - 派发的任务执行期间（包括执行器拒绝、改由 [Dispatchers.IO] 执行的任务），线程带有本调度器的标记（[onDispatchedThread]）。
 *   它只供 [AsyncContext.close] 判断调用方是不是运行时作用域中的协程（如在异步结果的回调里关闭运行时）：那样的调用方不等待作用域排空，
 *   否则只会等满上限。
 */
internal class EngineDispatcher(private val executor: Executor) : CoroutineDispatcher() {
    private val dispatched = ThreadLocal<Boolean?>()

    /**
     * 当前线程是否正在执行本调度器派发的任务。
     */
    val onDispatchedThread: Boolean
        get() = dispatched.get() == true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val marked = Marked(block)
        try {
            executor.execute(marked)
        } catch (e: RejectedExecutionException) {
            if (context[L1Call] == null) {
                context.cancel(CancellationException("The task was rejected.").apply { initCause(e) })
            }
            // 交给 Dispatchers.IO 的任务同样带标记（#872）：它仍是运行时作用域中的协程，在其中（如异步结果的回调里）关闭运行时不等待自己
            Dispatchers.IO.dispatch(context, marked)
        }
    }

    override fun toString(): String = "EngineDispatcher($executor)"

    /**
     * 执行期间带本调度器的标记；执行器在已带标记的线程上就地执行任务时沿用外层的标记。
     */
    private inner class Marked(private val block: Runnable) : Runnable {
        override fun run() {
            if (dispatched.get() == true) {
                block.run()
                return
            }
            dispatched.set(true)
            try {
                block.run()
            } finally {
                dispatched.remove()
            }
        }
    }
}
