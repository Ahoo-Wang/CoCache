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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

class FuturesTest {
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

    @Test
    fun joinOriginalReturnsTheValueAndThrowsTheUnwrappedFailure() {
        CompletableFuture.completedFuture("v").joinOriginal().assert().isEqualTo("v")
        val failure = IllegalStateException("boom")
        runCatching { CompletableFuture.failedFuture<String>(CompletionException(failure)).joinOriginal() }
            .exceptionOrNull().assert().isSameAs(failure)
        val cancelled = CompletableFuture<String>().apply { cancel(true) }
        val error = runCatching { cancelled.joinOriginal() }.exceptionOrNull()
        error.assert().isInstanceOf(CancellationException::class.java)
    }
}
