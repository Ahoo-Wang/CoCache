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

package me.ahoo.cocache.redis

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.test.BackendProtocolSpec
import me.ahoo.cocache.test.EngineSpec
import me.ahoo.cocache.test.SetEngineSpec
import java.time.Duration
import java.util.UUID

/**
 * L1 协议 TCK。短时长真实等待（令牌签发时间取自服务端时钟），长时长缩短 key 的剩余寿命。
 */
class RedisBackendProtocolTest : BackendProtocolSpec() {
    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) {
        if (duration <= Duration.ofSeconds(2)) {
            Thread.sleep(duration.toMillis())
        } else {
            RedisTestSupport.shiftTtl("cocache:$namespace:*", duration)
        }
    }
}

/**
 * 引擎 TCK：引擎使用手动时钟，推进时同时缩短 L1 中 key 的剩余寿命。
 */
class RedisEngineTest : EngineSpec() {
    private val manualClock = ManualClock()
    private val clientName = "cocache-tck-" + UUID.randomUUID()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend(
        RedisBackendOptions(clientName = clientName)
    )

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }

    override fun simulateReconnect() = RedisTestSupport.killPubSubConnections(clientName)
}

class RedisSetEngineTest : SetEngineSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

/**
 * 非原子广播后端上的协议 TCK：写入不在脚本内广播，由调用方经通道发布。
 */
class RedisNonAtomicBackendProtocolTest : BackendProtocolSpec() {
    override fun createBackend(): CoherenceBackend = RedisTestSupport.nonAtomicBackend()

    override fun elapse(duration: Duration) {
        if (duration <= Duration.ofSeconds(2)) {
            Thread.sleep(duration.toMillis())
        } else {
            RedisTestSupport.shiftTtl("cocache:$namespace:*", duration)
        }
    }
}

/**
 * 非原子广播后端上的引擎 TCK。
 */
class RedisNonAtomicEngineTest : EngineSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.nonAtomicBackend().also {
        check(!it.atomicBroadcast) { "expected a non-atomic backend" }
    }

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }

    override fun simulateReconnect() = RedisTestSupport.killPubSubConnections(
        requireNotNull(clientNameOf(backend.delegate))
    )

    private fun clientNameOf(backend: CoherenceBackend): String? = (backend as RedisBackend).clientName
}
