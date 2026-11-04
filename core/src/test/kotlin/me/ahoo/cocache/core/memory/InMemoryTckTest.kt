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
import me.ahoo.cocache.test.EngineSpec
import me.ahoo.cocache.test.FaultSpec
import me.ahoo.cocache.test.L1Capability
import me.ahoo.cocache.test.MultiNodeSpec
import me.ahoo.cocache.test.SetEngineSpec
import me.ahoo.cocache.test.VersionedSpec
import java.time.Duration

/**
 * 内存后端的存储提供全部可选能力。
 */
private val CAPABILITIES: Set<L1Capability> = setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED, L1Capability.SETS)

class InMemoryBackendProtocolTest : BackendProtocolSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val clock = ManualClock()

    override fun createBackend(): CoherenceBackend = InMemoryBackend(clock)

    override fun elapse(duration: Duration) = clock.advance(duration)

    /**
     * 内存后端的卡顿钩子：卡顿期间超时的操作不执行（相当于尚未写出的命令），不会取得令牌。
     */
    override fun pause(): AutoCloseable = (backend as InMemoryBackend).stall()
}

class InMemoryEngineTest : EngineSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock).also { memory = it }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()

    override fun pause(): AutoCloseable = memory.stall()
}

class InMemorySetEngineTest : SetEngineSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class InMemoryMultiNodeTest : MultiNodeSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

/**
 * 内存后端没有连接，不支持断线用例（[disconnect] 返回 `null`，用例跳过）。
 */
class InMemoryFaultTest : FaultSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class InMemoryVersionedTest : VersionedSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
