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
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 挂起调用方以 `ThreadContextElement` 传播父 Observation 时（#876），[MicrometerLoadObserver] 在它之下创建 `cocache.load`，
 * 数据源的同步部分看到的当前观测是这个子观测，而不是被再次装上的调用方元素盖回的父观测。声明的异步数据源与调用级挂起 loader 相同。
 */
class MicrometerLoadObserverSuspendingTest {
    private enum class Source { DECLARED, CALL_LEVEL }

    /**
     * 与 MDC、Observation 的协程传播方式相同：每次恢复时打开捕获的父观测的 scope，离开时关闭它。
     */
    private class ParentObservation(private val parent: Observation) : ThreadContextElement<Observation.Scope> {
        companion object Key : CoroutineContext.Key<ParentObservation>

        override val key: CoroutineContext.Key<*> = Key

        override fun updateThreadContext(context: CoroutineContext): Observation.Scope = parent.openScope()

        override fun restoreThreadContext(context: CoroutineContext, oldState: Observation.Scope) = oldState.close()
    }

    private class RecordingHandler : ObservationHandler<Observation.Context> {
        val started = CopyOnWriteArrayList<Observation.Context>()

        override fun onStart(context: Observation.Context) {
            started.add(context)
        }

        override fun supportsContext(context: Observation.Context): Boolean = true
    }

    @Test
    fun aDeclaredAsyncLoaderSeesTheChildObservationUnderAPropagatedParent() =
        verify(Source.DECLARED, propagatedParent = true)

    @Test
    fun aCallLevelSuspendingLoaderSeesTheChildObservationUnderAPropagatedParent() =
        verify(Source.CALL_LEVEL, propagatedParent = true)

    @Test
    fun aSuspendingReadWithoutAPropagatedParentSeesTheChildObservation() =
        verify(Source.DECLARED, propagatedParent = false)

    @Test
    fun theSyncViewSeesTheChildObservationUnderTheSamePropagatedParent() =
        verify(Source.DECLARED, propagatedParent = true, sync = true)

    private fun verify(source: Source, propagatedParent: Boolean, sync: Boolean = false): Unit = runBlocking<Unit> {
        val recording = RecordingHandler()
        val registry = ObservationRegistry.create().apply { observationConfig().observationHandler(recording) }
        val seen = AtomicReference<Observation.Context?>()
        val parent = Observation.start("request", registry)
        try {
            InMemoryBackend().use { backend ->
                val observer = MicrometerLoadObserver(registry)
                CacheRuntime.builder().backend(backend).loadObserver(observer).build().use { runtime ->
                    val cache = runtime.create(
                        cacheSpec<String, String>("observed") {
                            codec = StringCodec
                            if (source == Source.DECLARED) {
                                asyncLoader = AsyncCacheLoader {
                                    seen.set(registry.currentObservation?.context)
                                    CompletableFuture.completedFuture("value")
                                }
                            } else {
                                externallySourced()
                            }
                        }
                    )
                    val callerContext = if (propagatedParent) ParentObservation(parent) else EmptyCoroutineContext
                    withContext(callerContext) {
                        val value = when {
                            sync -> cache["key"]
                            source == Source.DECLARED -> cache.suspending().get("key")
                            else -> cache.suspending().get("key") {
                                seen.set(registry.currentObservation?.context)
                                "value"
                            }
                        }
                        value.assert().isEqualTo("value")
                    }
                    recording.started.map { it.name }.assert().containsExactly("request", MicrometerLoadObserver.NAME)
                    val child = recording.started.last()
                    seen.get()?.name.assert().isEqualTo(MicrometerLoadObserver.NAME)
                    seen.get().assert().isSameAs(child)
                    if (propagatedParent) {
                        child.parentObservation?.contextView.assert().isSameAs(parent.context)
                    }
                }
            }
        } finally {
            parent.stop()
        }
    }
}
