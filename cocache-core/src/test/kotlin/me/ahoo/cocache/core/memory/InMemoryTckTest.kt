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
import me.ahoo.cocache.test.MultiNodeSpec
import me.ahoo.cocache.test.SetEngineSpec
import java.time.Duration

class InMemoryBackendProtocolTest : BackendProtocolSpec() {
    private val clock = ManualClock()

    override fun createBackend(): CoherenceBackend = InMemoryBackend(clock)

    override fun elapse(duration: Duration) = clock.advance(duration)
}

class InMemoryEngineTest : EngineSpec() {
    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock).also { memory = it }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

class InMemorySetEngineTest : SetEngineSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class InMemoryMultiNodeTest : MultiNodeSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

/**
 * 内存后端没有连接，不支持断线用例（[disconnect] 返回 `null`，用例跳过）。
 */
class InMemoryFaultTest : FaultSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
