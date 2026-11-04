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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.future
import kotlinx.coroutines.job
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.internal.ExecutionMode
import me.ahoo.cocache.core.internal.Futures
import me.ahoo.cocache.core.internal.awaitOriginal
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 异步与挂起变体的执行资源（RFC 0005 §4.4、§5）。
 *
 * @param executor 运行时的执行器（阻塞调度器，默认每个任务一个虚拟线程）：运行作用域中协程的每一步（L1 命令完成之后的续接、
 *   共享加载），以及异步路径上的阻塞 loader
 * @param dispatcher 运行时作用域的调度器；`null` 时为 [EngineDispatcher]（生产）。确定性仿真传入测试调度器（RFC 0005 §4.10）
 * @param drainTimeout 关闭时等待作用域中在途步骤结束的上限（[close]，`CacheRuntime.Builder.closeTimeout`）；`0` 表示不等待
 *
 * 协程核心（RFC 0005 §4.4）的异步变体运行在 [scope] 中：调度器把任务交给 [executor]（[EngineDispatcher]），`SupervisorJob` 让一次加载的失败不影响其它加载；
 * 共享加载在这里执行，调用方被取消只结束它自己的等待。运行时关闭时取消它。
 */
internal class AsyncContext(
    val executor: Executor,
    dispatcher: CoroutineDispatcher? = null,
    private val drainTimeout: Duration = CacheRuntime.DEFAULT_CLOSE_TIMEOUT,
) : AutoCloseable {
    private val engineDispatcher: EngineDispatcher? = if (dispatcher == null) EngineDispatcher(executor) else null

    /**
     * 运行时作用域：协程核心的异步变体与共享加载在这里运行。
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + (dispatcher ?: requireNotNull(engineDispatcher)) + CoroutineName("cocache")
    )

    /**
     * 异步变体：在 [scope] 中运行 [block]；第一次挂起之前在调用方线程上执行，之后在 [executor] 上继续。
     * 返回的是结果的副本，以原始异常完成（[userError]）：取消它只影响调用方自己，协程照常运行
     * （它可能是别的调用方正在等待的 SingleFlight leader），只在运行时关闭时被取消。
     *
     * @param elements 加到协程上下文中的元素（[runSuspending] 传入调用方的元素）；不得含 `Job` 与调度器
     */
    fun <T> runAsync(
        elements: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> T
    ): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        scope.future(elements + ExecutionMode.ASYNC, CoroutineStart.UNDISPATCHED, block).whenComplete { value, error ->
            if (error == null) result.complete(value) else result.completeExceptionally(userError(error))
        }
        return result
    }

    /**
     * 挂起变体进入核心（RFC 0005 §4.4，K2、K6）：同 [runAsync] 在 [scope] 中运行 [block]，协程上下文的元素取自调用方，
     * 去掉 `Job` 与调度器（`ThreadContextElement`、`CoroutineName`、重入记录原样带入），再等待结果。
     *
     * - 调度器取自运行时：[block] 第一次挂起之前在调用方线程上执行（与异步变体相同，同线程的重入守卫因此照常生效），
     *   之后经运行时的调度器继续，从不在 L1 客户端的完成线程上继续。结果在运行时的执行器线程上交出，调用方随后在它自己的调度器上
     *   恢复；拦截器是 `Unconfined` 的调用方（Spring WebFlux 的挂起处理器默认如此）因此在运行时的执行器线程上恢复，
     *   不会在 Lettuce 的事件循环上继续调用方的代码。
     * - 去掉 `Job`：调用方被取消只结束它自己的等待，[block]（可能是别的调用方正在等待的 SingleFlight leader，或一次写入）照常完成，
     *   只在运行时关闭时被取消。
     * - 失败的约定同 [runAsync]：原始异常（不受调试模式复制异常的影响）；[executor] 拒绝派发时抛出那个 [RejectedExecutionException]，
     *   运行时已关闭时抛出 [IllegalStateException]。
     * - [block] 没有挂起就完成时（内存后端）不挂起。
     */
    suspend fun <T> runSuspending(block: suspend CoroutineScope.() -> T): T =
        runAsync(currentCoroutineContext().minusKey(Job).minusKey(ContinuationInterceptor), block).awaitOriginal()

    /**
     * 交给用户的异常。kotlinx 用取消表达的两种情形还原为异步视图的约定：[executor] 拒绝派发时协程以 `CancellationException`
     * （cause 为 [RejectedExecutionException]）结束，交出那个拒绝；运行时关闭取消 [scope]，交出 [IllegalStateException]。
     */
    private fun userError(error: Throwable): Throwable {
        val cause = Futures.unwrap(error)
        if (cause !is CancellationException) {
            return cause
        }
        (cause.cause as? RejectedExecutionException)?.let { return it }
        return if (closed) closedError().initCause(cause) else cause
    }

    @Volatile
    var closed: Boolean = false
        private set

    /**
     * 取消运行时作用域，再至多等待 [drainTimeout]，让作用域中的协程结束（由运行时在关闭执行器之前调用）。
     *
     * - 进行中的异步调用（含等待 lease、等待回源许可）在下一个挂起点以 [IllegalStateException] 失败。
     * - 在途的不可取消步骤照常完成：L1 命令、令牌的释放、写入之后的补发广播与第二次递增、取消之后的收尾。
     *   等待它们结束再返回（RFC 0005 第三轮 K17）：否则之后关闭的执行器与后端会让它们失败，其它节点陈旧到 `localMaxAge`，
     *   lease 留到 `lease.ttl`。这些步骤的等待由存储的命令超时限定，上限只防止卡住的命令让关闭无限期等待。
     * - 在调度器派发的任务中调用时不等待（[EngineDispatcher.onDispatchedThread]）：调用方可能正是作用域中的协程
     *   （如异步结果的回调），等待只会等满上限。
     * - 同步变体的调用在调用方线程上运行，不在作用域中，不等待。
     */
    override fun close() {
        closed = true
        scope.cancel("CacheRuntime closed.")
        if (drainTimeout > Duration.ZERO && engineDispatcher?.onDispatchedThread != true) {
            awaitDrained(scope.coroutineContext.job)
        }
    }

    private fun awaitDrained(job: Job) {
        val drained = CountDownLatch(1)
        job.invokeOnCompletion { drained.countDown() }
        try {
            if (!drained.await(drainTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                log.warn {
                    "CacheRuntime closed with ${job.children.count()} coroutines still running after $drainTimeout; " +
                        "their L1 steps may fail once the backend is closed."
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun closedError(): IllegalStateException = IllegalStateException("CacheRuntime closed.")

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
