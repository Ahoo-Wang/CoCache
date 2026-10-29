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

package me.ahoo.cocache.coroutines

import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * 挂起视图与异步视图的 L2 命中（RFC 0004 §7）：挂起视图的 L2 命中不挂起、不分配协程状态，只比异步视图多一次上下文查找。
 *
 * 挂起调用经 `startCoroutineUninterceptedOrReturn` 直接发起（不经 `runBlocking`），命中时同步返回结果：
 * 测到的是视图本身的开销，而不是协程构建器的开销。
 */
@State(Scope.Benchmark)
open class SuspendingBenchmark {
    private lateinit var runtime: CacheRuntime
    private lateinit var async: AsyncCache<String, String>
    private lateinit var suspending: SuspendingCache<String, String>
    private val hit: suspend () -> String? = ::suspendingHit

    @Setup(Level.Trial)
    fun setup() {
        runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
        val cache = runtime.create(cacheSpec<String, String>("bench") { codec = StringCodec })
        cache.put(KEY, "value")
        cache[KEY]
        async = cache.async()
        suspending = cache.suspending()
    }

    @TearDown(Level.Trial)
    fun tearDown() = runtime.close()

    private suspend fun suspendingHit(): String? = suspending.get(KEY)

    @Benchmark
    fun asyncL2Hit(): String? = async.get(KEY).getNow(null)

    @Benchmark
    fun suspendingL2Hit(): Any? {
        val result = hit.startCoroutineUninterceptedOrReturn(Completion)
        check(result !== COROUTINE_SUSPENDED) { "an L2 hit must not suspend" }
        return result
    }

    private object Completion : Continuation<String?> {
        override val context: CoroutineContext = EmptyCoroutineContext

        override fun resumeWith(result: Result<String?>) = Unit
    }

    private companion object {
        const val KEY = "k"
    }
}
