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

package me.ahoo.cocache.core.internal

import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * future 工具与执行器的续接标记（协程核心，RFC 0005 V5）。
 *
 * - 异常：交给用户的异常总是解包后的原始异常（[unwrap]、[awaitOriginal]）。
 * - 返回给用户的 future 一律新建并显式完成（[relay]），不直接返回依赖阶段（依赖阶段会包装为 `CompletionException`）。
 * - 续接标记：执行器派发的任务（[execute]）执行期间，线程带有该执行器的标记。协程核心的调度器（`EngineDispatcher`）
 *   在带标记的线程上就地恢复协程，只在上一步于执行器之外完成时派发（[onExecutor]）；有重入守卫的作用域清除标记（[withoutInline]）。
 */
internal object Futures {
    /**
     * 当前线程正在执行哪个执行器经 [execute] 派发的任务。
     */
    private class Inline {
        var executor: Executor? = null
    }

    /**
     * 只在执行派发的任务期间有值（[Marked] 设置，结束时 `remove`）：没有值即没有标记。读取从不创建对象，
     * 所以 I/O 线程、`commonPool` 等只是完成过 future 的线程上不会留下本类的对象（需要重新部署的容器里会钉住 classloader）。
     */
    private val inline = ThreadLocal<Inline?>()

    /**
     * 剥掉 JDK 加上的 [CompletionException] / [ExecutionException]。
     */
    fun unwrap(error: Throwable): Throwable {
        var current = error
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    /**
     * 调用 [block]，把同步抛出转为失败的 future。
     */
    @Suppress("TooGenericExceptionCaught")
    inline fun <R> attempt(block: () -> CompletableFuture<R>): CompletableFuture<R> {
        return try {
            block()
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
    }

    /**
     * 新建一个 future，以 [source] 的结果或解包后的原始异常完成。取消它不影响 [source]。
     */
    fun <T> relay(source: CompletableFuture<out T>): CompletableFuture<T> {
        val target = CompletableFuture<T>()
        source.whenComplete { value, error ->
            if (error != null) target.completeExceptionally(unwrap(error)) else target.complete(value)
        }
        return target
    }

    /**
     * 把 [task] 作为 [executor] 派发的任务交给它执行（执行期间带续接标记）；拒绝时抛出 [RejectedExecutionException]。
     */
    fun execute(executor: Executor, task: Runnable) {
        executor.execute(Marked(executor, task))
    }

    /**
     * 当前线程是否正在执行 [executor] 派发的任务（且不在 [withoutInline] 的作用域内）：此时完成的上一步可以就地继续。
     * I/O 线程、阻塞执行器与用户的线程上没有标记，在那里完成的结果总是派发（A9）。
     */
    fun onExecutor(executor: Executor): Boolean = inline.get()?.executor === executor

    /**
     * 在 [block] 执行期间清除当前线程的续接标记，结束（含异常）时恢复：期间被唤醒的协程改为派发，不在 [block] 的作用域内就地恢复。
     * 本线程对某些 key 有重入守卫的作用域都用它：数据源调用（`SingleFlight.loading`）与同步 leader（[withoutInlineSuspending]）。
     * 否则其间被唤醒的另一个 key 的协程（数据源同步完成的 future、归还回源许可唤醒的等待者）会在本 key 的作用域内执行，
     * 它再读本 key 会被误判为同一线程的重入。没有标记时直接执行，不创建对象。
     */
    fun <T> withoutInline(block: () -> T): T {
        val state = inline.get()
        val outerExecutor = state?.executor
        if (state == null || outerExecutor == null) {
            return block()
        }
        state.executor = null
        try {
            return block()
        } finally {
            state.executor = outerExecutor
        }
    }

    /**
     * [withoutInline] 的挂起版本，供协程核心同步变体的内联 leader 使用（`SingleFlight.executeSuspending`）。
     * 标记是线程局部的，只在协程始终留在当前线程上时正确：同步变体在 `runBlocking` 的事件循环里执行，挂起后仍在调用方线程上恢复。
     */
    suspend fun <T> withoutInlineSuspending(block: suspend () -> T): T {
        val state = inline.get()
        val outerExecutor = state?.executor
        if (state == null || outerExecutor == null) {
            return block()
        }
        state.executor = null
        try {
            return block()
        } finally {
            state.executor = outerExecutor
        }
    }

    /**
     * 执行期间把当前线程标记为 [executor] 的任务，结束（含异常）时恢复原来的标记（执行器可能在调用方线程上就地执行任务）。
     */
    private class Marked(private val executor: Executor, private val task: Runnable) : Runnable {
        override fun run() {
            val outer = inline.get()
            if (outer == null) {
                val state = Inline()
                state.executor = executor
                inline.set(state)
                try {
                    task.run()
                } finally {
                    inline.remove()
                }
                return
            }
            // 执行器在调用方线程上就地执行任务（已带标记）：沿用同一个对象，结束时恢复原来的标记
            val outerExecutor = outer.executor
            outer.executor = executor
            try {
                task.run()
            } finally {
                outer.executor = outerExecutor
            }
        }
    }
}

/**
 * 挂起等待，失败时抛出解包后的原始异常（协程核心等待 future 一律用它）。
 *
 * kotlinx 的 `await()` 在调试模式（`-ea` 或 `kotlinx.coroutines.debug`）下恢复挂起时复制异常（stack trace recovery），
 * 调用方拿到副本、原始异常成了它的 cause，违反“不包装异常”。这里等待一个总是正常完成的派生 future，在自己的栈帧里抛出原始异常。
 * 取消等待只取消派生的 future，不影响本 future 与它的其它等待者。
 */
internal suspend fun <T> CompletableFuture<T>.awaitOriginal(): T {
    val outcome = handle { value, error ->
        if (error == null) Result.success(value) else Result.failure(Futures.unwrap(error))
    }.await()
    return outcome.getOrThrow()
}

/**
 * 不可取消地等待，失败时抛出解包后的原始异常（RFC 0005 §4.4、I-H：L1 命令与令牌步骤不可取消）。协程核心等待 L1 命令一律用它。
 *
 * 协程被取消（运行时关闭）时仍等到命令完成：取得令牌的读取不会丢掉结果（否则令牌要到 `lease.ttl` 才回收），
 * 释放也不会抢在仍在途的读取之前到达 L1。等待由存储的命令超时限定；取消在之后的第一个挂起点生效，持有令牌的步骤据此释放它。
 * 与 [awaitOriginal] 一样以总是成功的结果恢复、在自己的栈帧里抛出原始异常，不受调试模式复制异常的影响。
 */
internal suspend fun <T> CompletableFuture<T>.awaitUncancellable(): T {
    val outcome = if (isDone) {
        outcomeNow()
    } else {
        suspendCoroutine { continuation ->
            whenComplete { value, error ->
                continuation.resume(if (error == null) Result.success(value) else Result.failure(Futures.unwrap(error)))
            }
        }
    }
    return outcome.getOrThrow()
}

@Suppress("TooGenericExceptionCaught")
private fun <T> CompletableFuture<T>.outcomeNow(): Result<T> = try {
    Result.success(join())
} catch (e: Throwable) {
    Result.failure(Futures.unwrap(e))
}
