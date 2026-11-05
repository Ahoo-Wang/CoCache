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

package me.ahoo.cocache.core

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.BackendProtocolSpec
import me.ahoo.cocache.test.EngineSpec
import me.ahoo.cocache.test.FaultSpec
import me.ahoo.cocache.test.L1Capability
import me.ahoo.cocache.test.MultiNodeSpec
import me.ahoo.cocache.test.SetEngineSpec
import me.ahoo.cocache.test.VersionedSpec
import java.time.Duration

/*
 * 没有任何可选能力的后端（RFC 0005 §4.3）跑整套 TCK：声明为空集合，能力声明的检查通过；需要能力的用例
 * （条件读写、集合、按版本写入）跳过，其余用例照常通过。检验 TCK 只按声明跳过，基本协议不依赖可选能力。
 */

private fun strippedBackend(clock: ManualClock, memory: (InMemoryBackend) -> Unit = {}): CoherenceBackend =
    StrippedBackend(InMemoryBackend(clock).also(memory), conditional = false, versioned = false, sets = false)

class StrippedBackendProtocolTest : BackendProtocolSpec() {
    override val expectedCapabilities: Set<L1Capability> = emptySet()

    private val clock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override fun createBackend(): CoherenceBackend = strippedBackend(clock) { memory = it }

    override fun elapse(duration: Duration) = clock.advance(duration)

    override fun pause(): AutoCloseable = memory.stall()
}

class StrippedEngineTest : EngineSpec() {
    override val expectedCapabilities: Set<L1Capability> = emptySet()

    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = strippedBackend(manualClock) { memory = it }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

class StrippedSetEngineTest : SetEngineSpec() {
    override val expectedCapabilities: Set<L1Capability> = emptySet()

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = strippedBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class StrippedMultiNodeTest : MultiNodeSpec() {
    override val expectedCapabilities: Set<L1Capability> = emptySet()

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = strippedBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class StrippedFaultTest : FaultSpec() {
    override val expectedCapabilities: Set<L1Capability> = emptySet()

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = strippedBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class StrippedVersionedTest : VersionedSpec() {
    override val expectedCapabilities: Set<L1Capability> = emptySet()

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = strippedBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
