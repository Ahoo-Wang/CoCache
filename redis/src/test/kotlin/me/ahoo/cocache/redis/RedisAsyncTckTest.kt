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
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.cocache.test.L1Capability
import me.ahoo.cocache.test.MultiNodeSpec
import me.ahoo.cocache.test.SetEngineSpec
import me.ahoo.cocache.test.VersionedSpec
import java.time.Duration
import java.util.UUID

/*
 * 异步变体在 Redis 上的冒烟矩阵：每个规范族一份，只在 `pubsub` 后端上，基础规范经 `override val access = CacheAccess.ASYNC`
 * 改由 `async()` 视图读写。
 *
 * `RedisL1Store` 只有一种挂起形状（M3d）：同步与异步变体调用同一组存储方法，差别只在引擎（续接在哪个调度器上恢复），
 * 变体的语义由 cocache-core 以内存后端覆盖。这里保留的是真实客户端才有的部分：L1 结果在 Lettuce 的 I/O 线程上完成，
 * 续接改在运行时的执行器上恢复（[RedisAsyncThreadCases] 断言这些线程）。非原子与 `sharded` 后端的差别在存储与通道的代码里，
 * 由同步矩阵（`RedisTckTest`）覆盖；保留同步数据源（`CacheAccess.ASYNC_WITH_SYNC_SOURCE`）只是引擎的执行器选择，见 cocache-core。
 * 协议 TCK（`BackendProtocolSpec`）不经引擎，没有变体，见 `RedisTckTest`。
 */

class RedisAsyncEngineTest : RedisAsyncThreadCases() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val access: CacheAccess = CacheAccess.ASYNC

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()
    private val clientName = "cocache-tck-" + UUID.randomUUID()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend(
        RedisBackendOptions(clientName = clientName)
    )

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:v8:$cacheName:*", duration)
    }

    override fun simulateReconnect() = RedisTestSupport.killPubSubConnections(clientName)
}

class RedisAsyncSetEngineTest : SetEngineSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val access: CacheAccess = CacheAccess.ASYNC

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:v8:$cacheName:*", duration)
    }
}

class RedisAsyncMultiNodeTest : MultiNodeSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val access: CacheAccess = CacheAccess.ASYNC

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:v8:$cacheName:*", duration)
    }
}

/**
 * 故障 TCK 经异步视图：单机时含断线与底层命令被 Lettuce 取消的用例（被取消的命令在 I/O 线程上结束，续接在执行器上恢复）。
 */
class RedisAsyncFaultTest : RedisProxiedFaultSpec() {
    override val access: CacheAccess = CacheAccess.ASYNC
}

class RedisAsyncVersionedTest : VersionedSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val access: CacheAccess = CacheAccess.ASYNC

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:v8:$cacheName:*", duration)
    }
}
