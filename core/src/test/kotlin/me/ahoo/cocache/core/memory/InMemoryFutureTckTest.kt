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

package me.ahoo.cocache.core.memory

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.test.BackendProtocolSpec
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.cocache.test.CommandCancellation
import me.ahoo.cocache.test.EngineSpec
import me.ahoo.cocache.test.FaultSpec
import me.ahoo.cocache.test.L1Capability
import me.ahoo.cocache.test.MultiNodeSpec
import me.ahoo.cocache.test.SetEngineSpec
import me.ahoo.cocache.test.VersionedSpec
import java.time.Duration

/*
 * Java 实现者的路径（RFC 0005 §4.3）：整套 TCK 以 [FutureBackend] 运行，内存后端以 `FutureL1Store` / `FutureInvalidationChannel`
 * 的形状经 `adapt` 交给引擎，与第三方 Java 后端继承规范时相同（默认的 `CacheAccess.SYNC`）。异步变体经它运行的引擎用例见
 * `FutureBackendAsyncEngineTest`。
 */

/**
 * 内存后端的存储提供全部可选能力，经 `FutureL1Store.adapt` 原样保留。
 */
private val CAPABILITIES: Set<L1Capability> = setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED, L1Capability.SETS)

class InMemoryFutureBackendProtocolTest : BackendProtocolSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val clock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override fun createBackend(): CoherenceBackend = FutureBackend(InMemoryBackend(clock).also { memory = it })

    override fun elapse(duration: Duration) = clock.advance(duration)

    /**
     * 经 `FutureL1Store.adapt` 的读取同样带着时限到达内存后端（时限由存储自己执行，适配器原样传递）。
     */
    override fun pause(): AutoCloseable = memory.stall()
}

class InMemoryFutureEngineTest : EngineSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = FutureBackend(InMemoryBackend(manualClock).also { memory = it })

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

class InMemoryFutureSetEngineTest : SetEngineSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = FutureBackend(InMemoryBackend(manualClock))

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class InMemoryFutureMultiNodeTest : MultiNodeSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = FutureBackend(InMemoryBackend(manualClock))

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

/**
 * 底层命令被取消的用例经 [FutureBackend.cancellation]：存储返回的 future 被取消，适配器按 L1 故障报告。
 */
class InMemoryFutureFaultTest : FaultSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()
    private lateinit var future: FutureBackend

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = FutureBackend(InMemoryBackend(manualClock)).also { future = it }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun commandCancellation(): CommandCancellation = future.cancellation()
}

/**
 * 同 [InMemoryFutureFaultTest]，经异步视图：被取消的 future 在测试线程上完成，续接在运行时的执行器上恢复。
 */
class InMemoryFutureAsyncFaultTest : FaultSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()
    private lateinit var future: FutureBackend

    override val access: CacheAccess = CacheAccess.ASYNC

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = FutureBackend(InMemoryBackend(manualClock)).also { future = it }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun commandCancellation(): CommandCancellation = future.cancellation()
}

class InMemoryFutureVersionedTest : VersionedSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = FutureBackend(InMemoryBackend(manualClock))

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
