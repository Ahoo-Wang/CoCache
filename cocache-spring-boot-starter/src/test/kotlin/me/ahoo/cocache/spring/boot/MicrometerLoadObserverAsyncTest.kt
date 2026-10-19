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

package me.ahoo.cocache.spring.boot

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 异步回源的 Observation（RFC 0004 §8.1）：从调用 block 到其 future 完成，结果记为 success / error / timeout。
 */
class MicrometerLoadObserverAsyncTest {
    private class Recording : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<Observation.Context>()
        var scoped: Observation? = null

        override fun onStop(context: Observation.Context) {
            stopped.add(context)
        }

        override fun supportsContext(context: Observation.Context): Boolean = true
    }

    private val handler = Recording()
    private val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(handler) }
    private val observer = MicrometerLoadObserver(registry)

    private fun Observation.Context.outcome(): String? = getLowCardinalityKeyValue(
        MicrometerLoadObserver.OUTCOME
    )?.value

    @Test
    fun recordsTheOutcomeWhenTheFutureCompletes() {
        val pending = CompletableFuture<String>()
        val result = observer.observeAsync("user", LoadOperation.LOAD, 1) {
            // block 在观测的作用域内调用
            handler.scoped = registry.currentObservation
            pending
        }
        handler.scoped.assert().isNotNull()
        handler.stopped.assert().isEmpty()
        pending.complete("v")
        result.join().assert().isEqualTo("v")
        handler.stopped.single().outcome().assert().isEqualTo(MicrometerLoadObserver.SUCCESS)

        val failure = IllegalStateException("down")
        observer.observeAsync("user", LoadOperation.LOAD_ALL, 2) { CompletableFuture.failedFuture<String>(failure) }
            .handle { _, e -> e }.join().assert().isSameAs(failure)
        handler.stopped.last().outcome().assert().isEqualTo(MicrometerLoadObserver.ERROR)
        handler.stopped.last().error.assert().isSameAs(failure)

        observer.observeAsync("user", LoadOperation.LOAD, 1) {
            CompletableFuture.failedFuture<String>(SourceLoadTimeoutException("user", Duration.ofSeconds(1)))
        }.handle { _, e -> e }.join()
        handler.stopped.last().outcome().assert().isEqualTo(MicrometerLoadObserver.TIMEOUT)
    }

    @Test
    fun synchronousThrowIsRecordedAndRethrown() {
        val failure = IllegalArgumentException("sync")
        runCatching {
            observer.observeAsync<String>("user", LoadOperation.LOAD, 1) { throw failure }
        }.exceptionOrNull().assert().isSameAs(failure)
        handler.stopped.single().outcome().assert().isEqualTo(MicrometerLoadObserver.ERROR)
    }

    @Test
    fun noopRegistryRunsTheBlockDirectly() {
        val noop = MicrometerLoadObserver(ObservationRegistry.NOOP)
        val future = CompletableFuture.completedFuture("v")
        noop.observeAsync("user", LoadOperation.LOAD, 1) { future }.assert().isSameAs(future)
    }
}
