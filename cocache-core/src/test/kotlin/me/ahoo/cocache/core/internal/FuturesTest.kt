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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FuturesTest {
    private val rejecting = Executor { throw RejectedExecutionException("full") }

    @Test
    fun unwrapStripsJdkWrappers() {
        val cause = IllegalStateException("boom")
        Futures.unwrap(CompletionException(ExecutionException(cause))).assert().isSameAs(cause)
        Futures.unwrap(CompletionException(null as Throwable?)).assert().isInstanceOf(CompletionException::class.java)
        Futures.unwrap(cause).assert().isSameAs(cause)
    }

    @Test
    fun outcomeReportsValueFailureAndCancellation() {
        Futures.outcome(CompletableFuture.completedFuture("v")).assert().isEqualTo("v" to null)
        val failure = IllegalStateException("boom")
        Futures.outcome(CompletableFuture.failedFuture<String>(failure)).second.assert().isSameAs(failure)
        val cancelled = CompletableFuture<String>().apply { cancel(true) }
        Futures.outcome(cancelled).second.assert().isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun relayUnwrapsAndIsIndependentOfTheSource() {
        val source = CompletableFuture<String>()
        val relayed = Futures.relay(source)
        relayed.cancel(true)
        source.isCancelled.assert().isFalse()
        val failure = IllegalStateException("boom")
        val failed = Futures.relay(CompletableFuture.failedFuture<String>(CompletionException(failure)))
        failed.handle { _, e -> e }.join().assert().isSameAs(failure)
    }

    @Test
    fun rejectedDispatchFailsTheTarget() {
        val source = CompletableFuture<String>()
        val relayed = Futures.relayOn(rejecting, source)
        source.complete("v")
        relayed.handle { _, e -> e }.join().assert().isInstanceOf(RejectedExecutionException::class.java)
        val pending = CompletableFuture<String>()
        val next = pending.handleNext(rejecting) { value, _ -> CompletableFuture.completedFuture(value) }
        pending.complete("v")
        next.handle { _, e -> e }.join().assert().isInstanceOf(RejectedExecutionException::class.java)
        val failedSource = CompletableFuture<String>()
        val relayedFailure = Futures.relayOn(Executor { it.run() }, failedSource)
        failedSource.completeExceptionally(IllegalStateException("boom"))
        relayedFailure.handle { _, e -> e }.join().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun continuationsRunInlineWhenDoneAndCatchThrows() {
        val failure = IllegalStateException("boom")
        val thrown = CompletableFuture.completedFuture("v").thenNext<String, String>(rejecting) { throw failure }
        thrown.handle { _, e -> e }.join().assert().isSameAs(failure)
        val passed = CompletableFuture.failedFuture<String>(failure).thenNext(rejecting) {
            CompletableFuture.completedFuture(it)
        }
        passed.handle { _, e -> e }.join().assert().isSameAs(failure)
        Futures.attempt<String> { throw failure }.handle { _, e -> e }.join().assert().isSameAs(failure)
        Futures.done().join().assert().isNull()
    }

    /**
     * 计数并在调用方线程上就地执行的执行器：派发次数即 [count]。
     */
    private class Counting : Executor {
        val count = AtomicInteger()

        override fun execute(command: Runnable) {
            count.incrementAndGet()
            command.run()
        }
    }

    @Test
    fun continuationRunsInlineOnlyInsideATaskOfTheSameExecutor() {
        val executor = Counting()
        val other = Counting()
        // 未在任何派发的任务中（如 I/O 线程）：派发
        val fromIo = CompletableFuture<String>()
        val next = fromIo.handleNext(executor) { value, _ -> CompletableFuture.completedFuture(value) }
        fromIo.complete("v")
        next.join().assert().isEqualTo("v")
        executor.count.get().assert().isEqualTo(1)
        // 在同一执行器派发的任务中完成：就地执行，不再派发
        val inside = CompletableFuture<String>()
        val chained = inside.handleNext(executor) { value, _ -> CompletableFuture.completedFuture(value) }
            .handleNext(executor) { value, _ -> CompletableFuture.completedFuture("$value!") }
        Futures.dispatch(executor, CompletableFuture<Void?>()) { inside.complete("w") }
        chained.join().assert().isEqualTo("w!")
        executor.count.get().assert().isEqualTo(2)
        // 在另一个执行器派发的任务中完成：派发到自己的执行器
        val foreign = CompletableFuture<String>()
        val switched = foreign.handleNext(executor) { value, _ -> CompletableFuture.completedFuture(value) }
        Futures.dispatch(other, CompletableFuture<Void?>()) { foreign.complete("x") }
        switched.join().assert().isEqualTo("x")
        executor.count.get().assert().isEqualTo(3)
    }

    @Test
    fun inlineContinuationsAreBoundedInDepth() {
        val executor = Counting()
        val head = CompletableFuture<Int>()
        var tail: CompletableFuture<Int> = head
        val stages = Futures.MAX_INLINE_DEPTH * 3
        repeat(stages) {
            tail = tail.handleNext(executor) { value, _ -> CompletableFuture.completedFuture(requireNotNull(value) + 1) }
        }
        Futures.dispatch(executor, CompletableFuture<Void?>()) { head.complete(0) }
        tail.join().assert().isEqualTo(stages)
        // 1 次启动链的派发；之后每满 MAX_INLINE_DEPTH 层就地嵌套，改为派发一次（新任务从第 0 层开始）
        executor.count.get().assert().isEqualTo(1 + stages / (Futures.MAX_INLINE_DEPTH + 1))
    }

    @Test
    fun markIsRestoredWhenATaskThrows() {
        val executor = Counting()
        val failure = IllegalStateException("boom")
        runCatching {
            Futures.dispatch(executor, CompletableFuture<Void?>()) { throw failure }
        }.exceptionOrNull().assert().isSameAs(failure)
        // 任务结束后本线程不再被视为续接：仍然派发
        val pending = CompletableFuture<String>()
        val next = pending.handleNext(executor) { value, _ -> CompletableFuture.completedFuture(value) }
        pending.complete("v")
        next.join().assert().isEqualTo("v")
        executor.count.get().assert().isEqualTo(2)
    }

    @Test
    fun continuationsCompletedOnAnotherThreadAreDispatchedToTheExecutor() {
        val io = Executors.newSingleThreadExecutor { Thread(it, "test-io") }
        val pool = Executors.newSingleThreadExecutor { Thread(it, "test-async") }
        try {
            val source = CompletableFuture<String>()
            val ranOn = source.handleNext(pool) { _, _ ->
                CompletableFuture.completedFuture(Thread.currentThread().name)
            }.handleNext(pool) { first, _ ->
                CompletableFuture.completedFuture("$first,${Thread.currentThread().name}")
            }
            io.execute { source.complete("v") }
            ranOn.get(10, TimeUnit.SECONDS).assert().isEqualTo("test-async,test-async")
        } finally {
            io.shutdownNow()
            pool.shutdownNow()
        }
    }

    @Test
    fun withoutInlineDispatchesInsideItsScopeAndRestoresTheMark() {
        val executor = Counting()
        val failure = IllegalStateException("boom")
        Futures.dispatch(executor, CompletableFuture<Void?>()) {
            // 作用域内完成的续接派发出去
            val inside = CompletableFuture<String>()
            val next = inside.handleNext(executor) { value, _ -> CompletableFuture.completedFuture(value) }
            Futures.withoutInline { inside.complete("v") }
            next.join().assert().isEqualTo("v")
            executor.count.get().assert().isEqualTo(2)
            // 嵌套：内层看到的是已清除的标记，结束后仍是清除状态，外层结束后才恢复
            val nested = CompletableFuture<String>()
            val nestedNext = nested.handleNext(executor) { value, _ -> CompletableFuture.completedFuture(value) }
            Futures.withoutInline { Futures.withoutInline { nested.complete("n") } }
            nestedNext.join().assert().isEqualTo("n")
            executor.count.get().assert().isEqualTo(3)
            // 作用域以异常结束后标记恢复：再次就地执行
            runCatching { Futures.withoutInline<Unit> { throw failure } }.exceptionOrNull().assert().isSameAs(failure)
            val after = CompletableFuture<String>()
            val inline = after.handleNext(executor) { value, _ -> CompletableFuture.completedFuture(value) }
            after.complete("w")
            inline.join().assert().isEqualTo("w")
            executor.count.get().assert().isEqualTo(3)
        }
    }

    /**
     * 线程上只在执行派发的任务期间有标记对象：结束后移除，只读取（未标记的线程上完成续接、withoutInline）从不创建。
     * 否则 I/O 线程、commonPool 等线程上会留下本模块的对象，在需要重新部署的容器里钉住 classloader。
     */
    @Test
    fun markLeavesNothingOnTheThread() {
        val field = Futures::class.java.getDeclaredField("inline").apply { isAccessible = true }
        val local = field.get(Futures) as ThreadLocal<*>
        val executor = Counting()
        // 未标记的线程上完成续接：派发，不创建对象
        val pending = CompletableFuture<String>()
        val next = pending.handleNext(executor) { value, _ ->
            // 派发的任务期间有标记
            local.get().assert().isNotNull()
            CompletableFuture.completedFuture(value)
        }
        pending.complete("v")
        next.join().assert().isEqualTo("v")
        local.get().assert().isNull()
        Futures.withoutInline { local.get() }.assert().isNull()
        // 嵌套派发（在调用方线程上执行任务的执行器）：结束后恢复外层，最外层结束后移除
        Futures.dispatch(executor, CompletableFuture<Void?>()) {
            Futures.dispatch(Counting(), CompletableFuture<Void?>()) { local.get().assert().isNotNull() }
            local.get().assert().isNotNull()
        }
        local.get().assert().isNull()
    }
}
