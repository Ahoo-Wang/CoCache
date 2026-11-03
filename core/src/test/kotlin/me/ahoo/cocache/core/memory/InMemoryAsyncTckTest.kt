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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.cocache.test.FaultSpec
import me.ahoo.cocache.test.L1Capability
import me.ahoo.cocache.test.MultiNodeSpec
import me.ahoo.cocache.test.SetEngineSpec
import me.ahoo.cocache.test.VersionedSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors

/**
 * 内存后端的存储提供全部可选能力（经 `FutureL1Store.adapt` 原样保留）。
 */
private val CAPABILITIES: Set<L1Capability> = setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED, L1Capability.SETS)

/*
 * 异步变体的用例（RFC 0005 §4.10）：基础规范经 `override val access = CacheAccess.ASYNC` 改由 `async()` 视图读写，
 * 引擎另加 [AsyncVariantEngineCases] 的专有用例。内存后端是原生的 `L1Store`；[FutureBackend] 以 Java 实现者的形状暴露它
 * （`FutureBackendAsyncEngineTest`），整套 TCK 经 `FutureL1Store.adapt` 的运行见 `InMemoryFutureTckTest`。
 */

class InMemoryAsyncEngineTest : AsyncVariantEngineCases() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val access: CacheAccess = CacheAccess.ASYNC

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock).also { memory = it }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

/**
 * 默认的用户路径：经异步视图读写，声明中的同步数据源保留（在阻塞执行器上执行）。
 */
class InMemoryAsyncWithSyncSourceEngineTest : AsyncVariantEngineCases() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val access: CacheAccess = CacheAccess.ASYNC_WITH_SYNC_SOURCE

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock).also { memory = it }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

/**
 * 每个 L1 结果都在另一个线程上送达（模拟真实客户端的 I/O 线程），使异步路径的每一步都切换到异步执行器。
 */
class InMemoryHoppingAsyncEngineTest : AsyncVariantEngineCases() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend
    private val io = Executors.newSingleThreadExecutor { Thread(it, "tck-hop-io").apply { isDaemon = true } }

    override val access: CacheAccess = CacheAccess.ASYNC

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock).also { memory = it }

    @BeforeEach
    override fun setup() {
        super.setup()
        backend.ioExecutor = io
    }

    @AfterEach
    override fun destroy() {
        super.destroy()
        io.shutdownNow()
    }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

class FutureBackendAsyncEngineTest : AsyncVariantEngineCases() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val access: CacheAccess = CacheAccess.ASYNC

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = FutureBackend(InMemoryBackend(manualClock).also { memory = it })

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

class InMemoryAsyncSetEngineTest : SetEngineSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val access: CacheAccess = CacheAccess.ASYNC

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    @Test
    fun unsupportedOperationsFailTheFutureInsteadOfThrowing() {
        val engine = nodeA.create(setCacheSpec<String, String>(cacheName) { elementCodec = StringCodec })
        val async = engine.async()
        async.invalidate(
            "k"
        ).handle { _, e -> e }.join().assert().isInstanceOf(UnsupportedOperationException::class.java)
        async.invalidateAll(
            listOf("k")
        ).handle { _, e -> e }.join().assert().isInstanceOf(UnsupportedOperationException::class.java)
        async.get("k", AsyncCacheLoader { error("unused") }).handle { _, e -> e }.join()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
        async.get("k").join().assert().isEmpty()
        async.sync().assert().isSameAs(engine)
        engine.async().assert().isSameAs(async)
    }
}

class InMemoryAsyncMultiNodeTest : MultiNodeSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val access: CacheAccess = CacheAccess.ASYNC

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class InMemoryAsyncFaultTest : FaultSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val access: CacheAccess = CacheAccess.ASYNC

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class InMemoryAsyncVersionedTest : VersionedSpec() {
    override val expectedCapabilities: Set<L1Capability> = CAPABILITIES

    private val manualClock = ManualClock()

    override val access: CacheAccess = CacheAccess.ASYNC

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}
