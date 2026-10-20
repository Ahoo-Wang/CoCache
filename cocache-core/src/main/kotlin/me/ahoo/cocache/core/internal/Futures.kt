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

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 异步路径的 future 工具（RFC 0004 §5.2）。
 *
 * - 续接：上一步已完成时就地执行（没有 I/O 线程参与，省去一次切换）；上一步在同一执行器派发的续接中完成时也就地执行
 *   （[continueOn]，嵌套不超过 [MAX_INLINE_DEPTH] 层）；否则把续接提交给执行器。续接本身从不在 I/O 线程上执行。
 * - 异常：续接收到的异常总是解包后的原始异常；续接抛出的异常变为失败的 future。
 * - 返回给用户的 future 一律经 [relay] 新建并显式完成，不直接返回依赖阶段（依赖阶段会包装为 `CompletionException`）。
 */
internal object Futures {
    private val DONE: CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    /**
     * 就地续接的最大嵌套层数：超过时改为派发（新任务从第 0 层开始），避免长链在一个线程上递归过深。
     * 执行器在调用方线程上执行任务时（就地执行的执行器、`CallerRunsPolicy`），派发也只是在同一个栈上继续，这个上限限制不住栈深
     * （与 6.4.0 相同）。
     */
    const val MAX_INLINE_DEPTH = 16

    /**
     * 当前线程正在执行哪个执行器经 [dispatch] 派发的任务、其中就地续接嵌套了几层。
     */
    private class Inline {
        var executor: Executor? = null
        var depth: Int = 0
    }

    /**
     * 只在执行派发的任务期间有值（[Marked] 设置，结束时 `remove`）：没有值即没有标记。读取从不创建对象，
     * 所以 I/O 线程、`commonPool` 等只是完成过 future 的线程上不会留下本类的对象（需要重新部署的容器里会钉住 classloader）。
     */
    private val inline = ThreadLocal<Inline?>()

    /**
     * 已完成的 `Void` future（内部使用，从不交给用户）。
     */
    fun done(): CompletableFuture<Void?> = DONE

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
        relay(source, target)
        return target
    }

    fun <T> relay(source: CompletableFuture<out T>, target: CompletableFuture<T>) {
        source.whenComplete { value, error ->
            if (error != null) target.completeExceptionally(unwrap(error)) else target.complete(value)
        }
    }

    /**
     * 与 [relay] 相同，但总在 [executor] 上完成（[source] 可能在不属于引擎的线程上完成时使用）。
     */
    fun <T> relayOn(executor: Executor, source: CompletableFuture<out T>): CompletableFuture<T> {
        val target = CompletableFuture<T>()
        source.whenComplete { value, error ->
            dispatch(executor, target) {
                if (error != null) target.completeExceptionally(unwrap(error)) else target.complete(value)
            }
        }
        return target
    }

    /**
     * 在 [executor] 上执行 [task]；执行器拒绝时以该异常完成 [target]。任务执行期间当前线程标记为 [executor] 的续接（见 [continueOn]）。
     */
    fun dispatch(executor: Executor, target: CompletableFuture<*>, task: Runnable) {
        try {
            executor.execute(Marked(executor, task))
        } catch (e: RejectedExecutionException) {
            target.completeExceptionally(e)
        }
    }

    /**
     * 上一步刚完成时执行续接 [task]：当前线程正在执行 [executor] 派发的任务（上一步在那里完成）且嵌套未超过 [MAX_INLINE_DEPTH]
     * 时就地执行，否则经 [dispatch] 派发。I/O 线程、定时器线程、阻塞执行器与用户的线程上没有标记，总是派发（A9）。
     */
    fun continueOn(executor: Executor, target: CompletableFuture<*>, task: Runnable) {
        val state = inline.get()
        if (state == null || state.executor !== executor || state.depth >= MAX_INLINE_DEPTH) {
            dispatch(executor, target, task)
            return
        }
        state.depth++
        try {
            task.run()
        } finally {
            state.depth--
        }
    }

    /**
     * 在 [block] 执行期间清除当前线程的续接标记，结束（含异常）时恢复：期间完成的 future 的续接改为派发，不在 [block] 的作用域内就地执行。
     * 本线程对某些 key 有重入守卫的作用域都用它：数据源调用（`SingleFlight.loading`）与同步 leader（`SingleFlight.execute` /
     * `executeAll` 的 block）。否则其间完成的另一个 key 的续接（数据源同步完成的 future、归还回源许可唤醒的异步等待者）
     * 会在本 key 的作用域内执行，回调里再读本 key 会被误判为同一线程的重入。没有标记时直接执行，不创建对象。
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
     * 执行期间把当前线程标记为 [executor] 的续接，结束（含异常）时恢复原来的标记（执行器可能在调用方线程上就地执行任务）。
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
            val outerDepth = outer.depth
            outer.executor = executor
            outer.depth = 0
            try {
                task.run()
            } finally {
                outer.executor = outerExecutor
                outer.depth = outerDepth
            }
        }
    }

    /**
     * 已完成 future 的结果：成功时 `(value, null)`，失败时 `(null, 原始异常)`。
     */
    fun <T> outcome(future: CompletableFuture<T>): Pair<T?, Throwable?> {
        return try {
            future.getNow(null) to null
        } catch (e: CancellationException) {
            null to e
        } catch (e: CompletionException) {
            null to unwrap(e)
        }
    }
}

/**
 * 上一步完成（成功或失败）之后继续，见 [Futures]。
 */
internal fun <T, R> CompletableFuture<T>.handleNext(
    executor: Executor,
    fn: (T?, Throwable?) -> CompletableFuture<R>
): CompletableFuture<R> {
    if (isDone) {
        return invokeHandler(this, fn)
    }
    val result = CompletableFuture<R>()
    whenComplete { _, _ ->
        Futures.continueOn(executor, result) { Futures.relay(invokeHandler(this, fn), result) }
    }
    return result
}

/**
 * 上一步成功之后继续；失败原样（解包后）传递。
 */
internal fun <T, R> CompletableFuture<T>.thenNext(
    executor: Executor,
    fn: (T) -> CompletableFuture<R>
): CompletableFuture<R> {
    return handleNext(executor) { value, error ->
        if (error != null) {
            CompletableFuture.failedFuture(error)
        } else {
            @Suppress("UNCHECKED_CAST")
            fn(value as T)
        }
    }
}

private fun <T, R> invokeHandler(future: CompletableFuture<T>, fn: (T?, Throwable?) -> CompletableFuture<R>): CompletableFuture<R> {
    val (value, error) = Futures.outcome(future)
    return Futures.attempt { fn(value, error) }
}
