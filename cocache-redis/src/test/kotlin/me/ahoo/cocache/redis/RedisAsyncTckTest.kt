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
import me.ahoo.cocache.api.spi.AsyncInvalidationChannel
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.test.AsyncBackendProtocolSpec
import me.ahoo.cocache.test.AsyncEngineSpec
import me.ahoo.cocache.test.AsyncFaultSpec
import me.ahoo.cocache.test.AsyncMultiNodeSpec
import me.ahoo.cocache.test.AsyncSetEngineSpec
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/*
 * 异步视图在 Redis 上的 TCK 矩阵（RFC 0004 §10.1）：与同步矩阵相同的三种后端（pubsub、非原子、sharded），
 * Redis 后端原生异步（AsyncRemoteStore / AsyncInvalidationChannel），每个 L1 结果都在 Lettuce 的 I/O 线程上完成。
 */

private fun elapseProtocol(namespace: String, duration: Duration) {
    if (duration <= Duration.ofSeconds(2)) {
        Thread.sleep(duration.toMillis())
    } else {
        RedisTestSupport.shiftTtl("cocache:$namespace:*", duration)
    }
}

class RedisAsyncBackendProtocolTest : AsyncBackendProtocolSpec() {
    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) = elapseProtocol(namespace, duration)

    @Test
    fun redisBackendIsNativelyAsync() {
        asyncStore.assert().isInstanceOf(AsyncRemoteStore::class.java)
        backend.channel.assert().isInstanceOf(AsyncInvalidationChannel::class.java)
    }
}

class RedisNonAtomicAsyncBackendProtocolTest : AsyncBackendProtocolSpec() {
    override fun createBackend(): CoherenceBackend = RedisTestSupport.nonAtomicBackend()

    override fun elapse(duration: Duration) = elapseProtocol(namespace, duration)
}

class RedisShardedAsyncBackendProtocolTest : AsyncBackendProtocolSpec() {
    override fun createBackend(): CoherenceBackend = RedisTestSupport.shardedBackend().also {
        check(!it.atomicBroadcast) { "expected a non-atomic backend" }
    }

    override fun elapse(duration: Duration) = elapseProtocol(namespace, duration)
}

class RedisAsyncEngineTest : AsyncEngineSpec() {
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

/**
 * 默认的用户路径：异步视图 + 同步数据源（在阻塞执行器上执行）。
 */
class RedisAsyncWithSyncSourceEngineTest : AsyncEngineSpec() {
    private val manualClock = ManualClock()
    private val clientName = "cocache-tck-" + UUID.randomUUID()

    override val access: CacheAccess = CacheAccess.ASYNC_WITH_SYNC_SOURCE

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

class RedisNonAtomicAsyncEngineTest : AsyncEngineSpec() {
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
        requireNotNull((backend.delegate as RedisBackend).clientName)
    )
}

class RedisShardedAsyncEngineTest : AsyncEngineSpec() {
    private val manualClock = ManualClock()
    private val clientName = "cocache-tck-" + UUID.randomUUID()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.shardedBackend(clientName)

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }

    override fun simulateReconnect() = RedisTestSupport.killPubSubConnections(clientName)
}

class RedisAsyncSetEngineTest : AsyncSetEngineSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

class RedisAsyncMultiNodeTest : AsyncMultiNodeSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

class RedisNonAtomicAsyncMultiNodeTest : AsyncMultiNodeSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.nonAtomicBackend().also {
        check(!it.atomicBroadcast) { "expected a non-atomic backend" }
    }

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

class RedisShardedAsyncMultiNodeTest : AsyncMultiNodeSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.shardedBackend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

/**
 * 故障 TCK（`pubsub`）：单机时经 [TcpProxy] 连接 Redis，以支持断线用例。
 */
class RedisAsyncFaultTest : AsyncFaultSpec() {
    private val manualClock = ManualClock()
    private var proxy: TcpProxy? = null

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend {
        if (RedisTestSupport.isCluster) {
            return RedisTestSupport.backend()
        }
        val proxy = RedisTestSupport.proxy().also { this.proxy = it }
        return RedisTestSupport.proxiedBackend(proxy)
    }

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }

    override fun disconnect(): AutoCloseable? = proxy?.cut()

    @AfterEach
    override fun destroy() {
        super.destroy()
        proxy?.close()
    }
}

class RedisNonAtomicAsyncFaultTest : AsyncFaultSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.nonAtomicBackend().also {
        check(!it.atomicBroadcast) { "expected a non-atomic backend" }
    }

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

class RedisShardedAsyncFaultTest : AsyncFaultSpec() {
    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.shardedBackend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}
