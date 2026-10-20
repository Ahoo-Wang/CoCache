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

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.cocache.test.EngineSpec
import me.ahoo.cocache.test.MultiNodeSpec
import me.ahoo.cocache.test.SetEngineSpec
import java.time.Duration

/**
 * 同步 TCK 的每个用例经挂起视图重跑（内存后端）：挂起视图只是异步视图的封装，协议行为必须与同步 API 完全一致。
 */
class SuspendingEngineTest : EngineSpec() {
    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val access: CacheAccess = SuspendingAccess
    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock).also { memory = it }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

class SuspendingSetEngineTest : SetEngineSpec() {
    private val manualClock = ManualClock()

    override val access: CacheAccess = SuspendingAccess
    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class SuspendingMultiNodeTest : MultiNodeSpec() {
    private val manualClock = ManualClock()

    override val access: CacheAccess = SuspendingAccess
    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
