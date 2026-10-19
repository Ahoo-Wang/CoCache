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

import me.ahoo.cocache.core.internal.Futures
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
        try {
            scheduler.schedule({
                Futures.dispatch(executor, result) { Futures.relay(Futures.attempt(block), result) }
            }, delayNanos, TimeUnit.NANOSECONDS)
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
