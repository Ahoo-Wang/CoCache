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

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.cocache.core.engine.AsyncContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor

/**
 * SingleFlight 为没有 [ExecutionMode] 的调用方执行 leader 时的协程上下文（RFC 0005 §4.4，K2、K6）。
 */
class SingleFlightCallerContextTest {
    private val flights = SingleFlight<String, String>()
    private val context = AsyncContext(ForkJoinPool.commonPool(), ForkJoinPool.commonPool())
    private val scope = context.scope

    @AfterEach
    fun tearDown() {
        context.close()
    }

    /**
     * 没有 [ExecutionMode] 的调用方（RFC 0005 §4.4，K2、K6）：leader 在作用域中执行，元素（`ThreadContextElement`、`CoroutineName`）
     * 取自调用方，`Job` 与调度器取自作用域，调用方被取消不取消它。
     */
    @Test
    fun aLeaderWithoutAnExecutionModeKeepsTheCallersElementsButNotItsJob(): Unit = runBlocking<Unit> {
        val trace = ThreadLocal<String?>()
        val seen = AtomicReference<String?>()
        val leaderJob = AtomicReference<Job?>()
        val leaderDispatcher = AtomicReference<Any?>()
        val callerJob = coroutineContext[Job]
        withContext(trace.asContextElement("trace-1") + CoroutineName("caller")) {
            flights.executeSuspending("k", scope) {
                // 不能写 coroutineContext：它会解析为外层 withContext 的 CoroutineScope（调用方的上下文）
                val leader = currentCoroutineContext()
                seen.set(trace.get() + "/" + leader[CoroutineName]?.name)
                leaderJob.set(leader[Job])
                leaderDispatcher.set(leader[ContinuationInterceptor])
                "v"
            }
        }.assert().isEqualTo("v")
        seen.get().assert().isEqualTo("trace-1/caller")
        leaderDispatcher.get().assert().isSameAs(scope.coroutineContext[ContinuationInterceptor])
        val leader = requireNotNull(leaderJob.get())
        leader.assert().isNotSameAs(callerJob)
        generateSequence(leader.parent) { it.parent }.toList().assert().doesNotContain(callerJob)
    }
}
