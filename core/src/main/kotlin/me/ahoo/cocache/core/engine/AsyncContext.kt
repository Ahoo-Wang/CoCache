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

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import me.ahoo.cocache.core.internal.ExecutionMode
import me.ahoo.cocache.core.internal.Futures
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 异步路径的执行资源（RFC 0004 §5.1）。
 *
 * @param executor 异步执行器：只运行短小的非阻塞续接（解码、L2 填充、发出下一条 L1 命令、调用 `loadAsync`）
 * @param blocking 阻塞执行器：异步路径上的同步 loader 与非原生异步后端的同步 SPI 调用，只在这里执行
 * @param timer 定时器：等待 lease 的退避重读、等待回源许可的重试；定时任务只把工作提交给 [executor]
 *
 * 协程核心（RFC 0005 §4.4）的异步变体运行在 [scope] 中：调度器把任务交给 [executor]（[EngineDispatcher]），`SupervisorJob` 让一次加载的失败不影响其它加载；
 * 共享加载在这里执行，调用方被取消只结束它自己的等待。运行时关闭时取消它。
 */
internal class AsyncContext(
    val executor: Executor,
    val blocking: Executor,
    private val timer: ScheduledExecutorService?,
) : AutoCloseable {
    /**
     * 尚未触发的定时等待：运行时关闭时以 [IllegalStateException] 完成，否则它们永远不会完成。
     */
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    /**
     * 运行时作用域：协程核心的异步变体与共享加载在这里运行。
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + EngineDispatcher(executor) + CoroutineName("cocache"))

    /**
     * 异步变体：在 [scope] 中运行 [block]；第一次挂起之前在调用方线程上执行，之后在 [executor] 上继续。
     * 返回的是结果的副本，以原始异常完成（[userError]）：取消它只影响调用方自己，协程照常运行
     * （它可能是别的调用方正在等待的 SingleFlight leader），只在运行时关闭时被取消。
     */
    fun <T> runAsync(block: suspend CoroutineScope.() -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        scope.future(ExecutionMode.ASYNC, CoroutineStart.UNDISPATCHED, block).whenComplete { value, error ->
            if (error == null) result.complete(value) else result.completeExceptionally(userError(error))
        }
        return result
    }

    /**
     * 交给用户的异常。kotlinx 用取消表达的两种情形还原为 6.x 的约定：[executor] 拒绝派发时协程以 `CancellationException`
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
     * 延迟 [delayNanos] 后在 [executor] 上执行 [block]。运行时关闭后以 [IllegalStateException] 失败。
     */
    fun <R> after(delayNanos: Long, block: () -> CompletableFuture<R>): CompletableFuture<R> {
        val result = CompletableFuture<R>()
        val scheduler = timer ?: return CompletableFuture.failedFuture(IllegalStateException("No timer."))
        if (closed) {
            return CompletableFuture.failedFuture(closedError())
        }
        pending.add(result)
        result.whenComplete { _, _ -> pending.remove(result) }
        if (closed) {
            // 与 close 并发：close 可能已经遍历过 pending，自己失败，否则这个 future 永远不会完成
            result.completeExceptionally(closedError())
            return result
        }
        try {
            val task = scheduler.schedule({
                Futures.dispatch(executor, result) { Futures.relay(Futures.attempt(block), result) }
            }, delayNanos, TimeUnit.NANOSECONDS)
            // 提前完成（取消、关闭）时撤销定时任务
            result.whenComplete { _, _ -> task.cancel(false) }
        } catch (e: RejectedExecutionException) {
            result.completeExceptionally(closedError().initCause(e))
        }
        return result
    }

    /**
     * 停止接受定时等待，并让尚未触发的等待失败（由运行时在关闭定时器之前调用）。
     */
    override fun close() {
        closed = true
        pending.toList().forEach { it.completeExceptionally(closedError()) }
        scope.cancel("CacheRuntime closed.")
    }

    private fun closedError(): IllegalStateException = IllegalStateException("CacheRuntime closed.")

    companion object {
        private val DIRECT = Executor { it.run() }

        /**
         * 测试用：全部就地执行，没有定时器。
         */
        fun direct(): AsyncContext = AsyncContext(DIRECT, DIRECT, null)
    }
}
