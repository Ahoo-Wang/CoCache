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
import java.util.concurrent.RejectedExecutionException

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
}
