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
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.test.AsyncBackendProtocolSpec
import me.ahoo.cocache.test.AsyncEngineSpec
import me.ahoo.cocache.test.AsyncFaultSpec
import me.ahoo.cocache.test.AsyncMultiNodeSpec
import me.ahoo.cocache.test.AsyncSetEngineSpec
import me.ahoo.cocache.test.CacheAccess
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 异步视图的 TCK（RFC 0004 §10.1）：内存后端原生异步；[SyncOnlyBackend] 只暴露同步 SPI，验证引擎经阻塞执行器的适配。
 */
class InMemoryAsyncBackendProtocolTest : AsyncBackendProtocolSpec() {
    private val clock = ManualClock()

    override fun createBackend(): CoherenceBackend = InMemoryBackend(clock)

    override fun elapse(duration: Duration) = clock.advance(duration)
}

class InMemoryAsyncEngineTest : AsyncEngineSpec() {
    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock).also { memory = it }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

/**
 * 默认的用户路径：经异步视图读写，声明中的同步数据源保留（在阻塞执行器上执行）。
 */
class InMemoryAsyncWithSyncSourceEngineTest : AsyncEngineSpec() {
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
class InMemoryHoppingAsyncEngineTest : AsyncEngineSpec() {
    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend
    private val io = Executors.newSingleThreadExecutor { Thread(it, "tck-hop-io").apply { isDaemon = true } }

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

class SyncOnlyBackendAsyncEngineTest : AsyncEngineSpec() {
    private val manualClock = ManualClock()
    private lateinit var memory: InMemoryBackend

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = SyncOnlyBackend(InMemoryBackend(manualClock).also { memory = it })

    /**
     * 同步 SPI 在调用线程上执行：用例按线程名编排的钩子才能作用于发起调用的线程（经真正线程池的卸载见专门的用例）。
     */
    override fun configureNode(builder: CacheRuntime.Builder) {
        builder.blockingExecutor(Executor { it.run() })
    }

    override fun elapse(duration: Duration) = manualClock.advance(duration)

    override fun simulateReconnect() = memory.reconnect()
}

class InMemoryAsyncSetEngineTest : AsyncSetEngineSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class InMemoryAsyncMultiNodeTest : AsyncMultiNodeSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

class InMemoryAsyncFaultTest : AsyncFaultSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = InMemoryBackend(manualClock)

    override fun elapse(duration: Duration) = manualClock.advance(duration)
}

/**
 * 只暴露同步 SPI 的后端（隐藏内存后端的异步实现），模拟未升级的第三方后端。
 */
class SyncOnlyBackend(private val delegate: CoherenceBackend) : CoherenceBackend by delegate {
    override val channel: InvalidationChannel = object : InvalidationChannel {
        override fun subscribe(namespace: String, listener: InvalidationListener): Subscription =
            delegate.channel.subscribe(namespace, listener)

        override fun publish(namespace: String, frame: ByteArray) = delegate.channel.publish(namespace, frame)
    }

    override fun store(namespace: String): RemoteStore = object : RemoteStore by delegate.store(namespace) {}
}
