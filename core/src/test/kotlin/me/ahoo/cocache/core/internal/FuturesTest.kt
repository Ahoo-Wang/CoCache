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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class FuturesTest {
    /**
     * 在调用方线程上就地执行的执行器。
     */
    private val direct = Executor { it.run() }

    @Test
    fun unwrapStripsJdkWrappers() {
        val cause = IllegalStateException("boom")
        Futures.unwrap(CompletionException(ExecutionException(cause))).assert().isSameAs(cause)
        Futures.unwrap(CompletionException(null as Throwable?)).assert().isInstanceOf(CompletionException::class.java)
        Futures.unwrap(cause).assert().isSameAs(cause)
    }

    @Test
    fun attemptTurnsThrowsIntoFailedFutures() {
        val failure = IllegalStateException("boom")
        Futures.attempt<String> { throw failure }.handle { _, e -> e }.join().assert().isSameAs(failure)
        Futures.attempt { CompletableFuture.completedFuture("v") }.join().assert().isEqualTo("v")
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
    fun awaitOriginalReturnsTheValueAndThrowsTheUnwrappedFailure() {
        runBlocking { CompletableFuture.completedFuture("v").awaitOriginal() }.assert().isEqualTo("v")
        val failure = IllegalStateException("boom")
        val error = runCatching {
            runBlocking { CompletableFuture.failedFuture<String>(CompletionException(failure)).awaitOriginal() }
        }.exceptionOrNull()
        error.assert().isSameAs(failure)
    }

    /**
     * 派发的任务执行期间线程带有该执行器的标记；别的执行器、任务之外都没有标记；拒绝时原样抛出。
     */
    @Test
    fun executeMarksTheThreadOnlyForItsOwnExecutor() {
        val other = Executor { it.run() }
        Futures.onExecutor(direct).assert().isFalse()
        var inside = false
        var otherInside = true
        Futures.execute(direct) {
            inside = Futures.onExecutor(direct)
            otherInside = Futures.onExecutor(other)
        }
        inside.assert().isTrue()
        otherInside.assert().isFalse()
        Futures.onExecutor(direct).assert().isFalse()
        val rejection = RejectedExecutionException("full")
        runCatching { Futures.execute({ throw rejection }) {} }.exceptionOrNull().assert().isSameAs(rejection)
    }

    @Test
    fun markIsRestoredWhenATaskThrows() {
        val failure = IllegalStateException("boom")
        runCatching { Futures.execute(direct) { throw failure } }.exceptionOrNull().assert().isSameAs(failure)
        Futures.onExecutor(direct).assert().isFalse()
    }

    /**
     * [Futures.withoutInline] 与它的挂起版本在作用域内清除标记，结束（含异常）后恢复。
     */
    @Test
    fun withoutInlineClearsTheMarkInsideItsScopeAndRestoresIt() {
        val failure = IllegalStateException("boom")
        Futures.execute(direct) {
            Futures.withoutInline { Futures.onExecutor(direct) }.assert().isFalse()
            Futures.onExecutor(direct).assert().isTrue()
            runCatching { Futures.withoutInline<Unit> { throw failure } }.exceptionOrNull().assert().isSameAs(failure)
            Futures.onExecutor(direct).assert().isTrue()
            runBlocking { Futures.withoutInlineSuspending { Futures.onExecutor(direct) } }.assert().isFalse()
            Futures.onExecutor(direct).assert().isTrue()
        }
        Futures.withoutInline { Futures.onExecutor(direct) }.assert().isFalse()
        runBlocking { Futures.withoutInlineSuspending { Futures.onExecutor(direct) } }.assert().isFalse()
    }

    /**
     * 线程上只在执行派发的任务期间有标记对象：结束后移除，只读取（未标记的线程上 onExecutor、withoutInline）从不创建。
     * 否则 I/O 线程、commonPool 等线程上会留下本模块的对象，在需要重新部署的容器里钉住 classloader。
     */
    @Test
    fun markLeavesNothingOnTheThread() {
        val field = Futures::class.java.getDeclaredField("inline").apply { isAccessible = true }
        val local = field.get(Futures) as ThreadLocal<*>
        Futures.onExecutor(direct)
        local.get().assert().isNull()
        Futures.withoutInline { local.get() }.assert().isNull()
        // 嵌套派发（在调用方线程上执行任务的执行器）：结束后恢复外层，最外层结束后移除
        val other = Executor { it.run() }
        Futures.execute(direct) {
            Futures.execute(other) { Futures.onExecutor(other).assert().isTrue() }
            Futures.onExecutor(direct).assert().isTrue()
            local.get().assert().isNotNull()
        }
        local.get().assert().isNull()
    }
}
