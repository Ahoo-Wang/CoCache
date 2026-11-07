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
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 异步回源的 Observation（RFC 0005 §5：异步视图同样经 [MicrometerLoadObserver.observe]，在运行时的执行器上调用）：
 * 观测覆盖整个回源（直到数据源的 future 完成），`loadAsync` 的同步部分看得到当前观测，结果记为 success / error / timeout。
 */
class MicrometerLoadObserverAsyncTest {
    private class Recording : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<Observation.Context>()

        override fun onStop(context: Observation.Context) {
            stopped.add(context)
        }

        override fun supportsContext(context: Observation.Context): Boolean = true
    }

    private val handler = Recording()
    private val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(handler) }
    private val observer = MicrometerLoadObserver(registry)
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).loadObserver(observer).build()
    private val pending = ConcurrentHashMap<String, CompletableFuture<String?>>()
    private val scoped = CopyOnWriteArrayList<Boolean>()

    @AfterEach
    fun close() = runtime.close()

    private fun cache(timeout: Duration = Duration.ofSeconds(10)) = runtime.create(
        cacheSpec<String, String>("user") {
            codec = StringCodec
            asyncLoader = AsyncCacheLoader { key ->
                // 先登记 future 再标记：测试看到标记时 future 一定已在 pending 中
                val future = pending.computeIfAbsent(key) { CompletableFuture() }
                scoped.add(registry.currentObservation != null)
                future
            }
            policy { load = LoadPolicy(timeout = timeout) }
        }
    ).async()

    private fun Observation.Context.outcome(): String? = getLowCardinalityKeyValue(
        MicrometerLoadObserver.OUTCOME
    )?.value

    @Test
    fun observesTheWholeAsyncLoad() {
        val cache = cache()
        val call = cache.get("k")
        awaitCondition("source called") { scoped.isNotEmpty() }
        // loadAsync 在观测的作用域内调用；它的 future 完成之前观测不结束
        scoped.single().assert().isTrue()
        handler.stopped.assert().isEmpty()
        pending.getValue("k").complete("v")
        call.get(10, TimeUnit.SECONDS).assert().isEqualTo("v")
        awaitCondition("observation stopped") { handler.stopped.isNotEmpty() }
        handler.stopped.single().outcome().assert().isEqualTo(MicrometerLoadObserver.SUCCESS)

        val failure = IllegalStateException("down")
        pending["f"] = CompletableFuture.failedFuture(failure)
        cache.get("f").handle { _, e -> e }.get(10, TimeUnit.SECONDS).assert().isSameAs(failure)
        awaitCondition("failure recorded") { handler.stopped.size == 2 }
        handler.stopped.last().outcome().assert().isEqualTo(MicrometerLoadObserver.ERROR)
        handler.stopped.last().error.assert().isSameAs(failure)
    }

    @Test
    fun engineTimeoutIsRecordedAsTimeout() {
        val cache = cache(Duration.ofMillis(50))
        cache.get("never").handle { _, e -> e }.get(10, TimeUnit.SECONDS)
            .assert().isInstanceOf(SourceLoadTimeoutException::class.java)
        awaitCondition("timeout recorded") { handler.stopped.isNotEmpty() }
        handler.stopped.single().outcome().assert().isEqualTo(MicrometerLoadObserver.TIMEOUT)
    }

    @Test
    fun synchronousTimeoutIsRecordedAsTimeout() {
        // 同步调用方等待异步加载（AwaitingLoader）时，引擎的超时同样记为 timeout
        val timeout = SourceLoadTimeoutException("user", Duration.ofSeconds(1))
        runCatching {
            observer.observe<String>("user", LoadOperation.LOAD, 1) { throw timeout }
        }.exceptionOrNull().assert().isSameAs(timeout)
        handler.stopped.single().outcome().assert().isEqualTo(MicrometerLoadObserver.TIMEOUT)
        handler.stopped.single().error.assert().isSameAs(timeout)
    }

    @Test
    fun noopRegistryRunsTheBlockDirectly() {
        MicrometerLoadObserver(ObservationRegistry.NOOP).observe("user", LoadOperation.LOAD, 1) { "v" }
            .assert().isEqualTo("v")
    }
}
