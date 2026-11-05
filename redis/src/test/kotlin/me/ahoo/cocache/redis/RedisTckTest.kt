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

import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.test.BackendProtocolSpec
import me.ahoo.cocache.test.CommandCancellation
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.EngineSpec
import me.ahoo.cocache.test.FaultSpec
import me.ahoo.cocache.test.L1Capability
import me.ahoo.cocache.test.MultiNodeSpec
import me.ahoo.cocache.test.SetEngineSpec
import me.ahoo.cocache.test.Suspending
import me.ahoo.cocache.test.VersionedSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * L1 协议 TCK。短时长真实等待（令牌签发时间取自服务端时钟），长时长缩短 key 的剩余寿命。
 */
class RedisBackendProtocolTest : BackendProtocolSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    /**
     * 经 [TcpProxy] 连接的后端：[pause] 只暂停本用例的连接，不用服务端的 `CLIENT PAUSE`。
     */
    private lateinit var redis: RedisTestSupport.PausableRedis

    override fun createBackend(): CoherenceBackend {
        redis = RedisTestSupport.pausable()
        return RedisTestSupport.backend(
            RedisBackendOptions(clientName = "cocache-tck-" + UUID.randomUUID(), clientResources = redis.resources)
        )
    }

    @AfterEach
    override fun destroy() {
        super.destroy()
        redis.close()
    }

    /**
     * 暂停期间发出的命令已写出、到期后按序执行：超时的读取之后才执行、取得 lease，随后的异步释放把它收回（K14）。
     * 暂停时长固定，关闭返回值什么也不做，用例等暂停自己结束。
     */
    override fun pause(): AutoCloseable {
        // 先建立节点连接、缓存脚本：暂停期间不必握手，读取不会因 NOSCRIPT 回退成另一条命令
        Suspending.await { store.read(key(), null, null, null) }
        RedisTestSupport.loadScripts(RedisScripts.READ, RedisScripts.READ_IF_CHANGED, RedisScripts.RELEASE)
        redis.pause(PAUSE_MILLIS)
        return AutoCloseable { }
    }

    private companion object {
        const val PAUSE_MILLIS = 3_000L
    }

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
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

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
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

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
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

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
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

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

/**
 * Sharded Pub/Sub 通道上的协议 TCK：脚本内不广播，由调用方经通道 `SPUBLISH`。
 */
class RedisShardedBackendProtocolTest : BackendProtocolSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override fun createBackend(): CoherenceBackend = RedisTestSupport.shardedBackend().also {
        check(!it.atomicBroadcast) { "expected a non-atomic backend" }
    }

    override fun elapse(duration: Duration) {
        if (duration <= Duration.ofSeconds(2)) {
            Thread.sleep(duration.toMillis())
        } else {
            RedisTestSupport.shiftTtl("cocache:$namespace:*", duration)
        }
    }
}

/**
 * Sharded Pub/Sub 通道上的引擎 TCK（含断线重连后重新订阅触发重置）。
 */
class RedisShardedEngineTest : EngineSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

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

class RedisMultiNodeTest : MultiNodeSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

class RedisNonAtomicMultiNodeTest : MultiNodeSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

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

class RedisShardedMultiNodeTest : MultiNodeSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.shardedBackend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

/**
 * `pubsub` 后端上的故障 TCK：单机时经 [TcpProxy] 连接 Redis，以支持断线用例（[disconnect]）与底层命令被 Lettuce 取消的用例
 * （[commandCancellation]，经 [LettuceChannels] 记录的连接重置）；集群时直接连接，这两个用例跳过。
 */
abstract class RedisProxiedFaultSpec : FaultSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()
    private var proxy: TcpProxy? = null
    private val channels = LettuceChannels()
    private var resources: ClientResources? = null

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend {
        if (RedisTestSupport.isCluster) {
            return RedisTestSupport.backend()
        }
        val proxy = RedisTestSupport.proxy().also { this.proxy = it }
        val resources = DefaultClientResources.builder().nettyCustomizer(channels).build().also { this.resources = it }
        return RedisTestSupport.proxiedBackend(proxy, resources)
    }

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }

    override fun disconnect(): AutoCloseable? = proxy?.cut()

    override fun commandCancellation(): CommandCancellation? = proxy?.let { LettuceCancellation(it, channels) }

    @AfterEach
    override fun destroy() {
        super.destroy()
        resources?.shutdown()
        proxy?.close()
    }
}

/**
 * 故障 TCK（`pubsub`，同步），另含 Redis 特有的 `NOSCRIPT` 回退用例。
 */
class RedisFaultTest : RedisProxiedFaultSpec() {
    /**
     * 脚本缓存丢失（故障切换、重启、resharding）之后，每个脚本的第一次 `EVALSHA` 得到 `NOSCRIPT`，改用 `EVAL` 重试。
     * 每个操作之前都清空脚本缓存，覆盖读、填充、释放、覆盖写、失效、批量与集合元素写入。
     */
    @Test
    fun everyOperationSurvivesScriptCacheLoss() {
        val failing = AtomicBoolean(false)
        val spec = cacheSpec<String, String>(cacheName) {
            codec = EngineSpec.TestCodec
            loader = CacheLoader { key ->
                check(!failing.get()) { "source down" }
                "$key-v"
            }
        }
        val setSpec = setStoreSpec<String, String>("$cacheName-set") { elementCodec = StringCodec }
        val runtimes = (1..2).map { CacheRuntime.builder().backend(backend).clock(clock).build() }
        try {
            val (cacheA, cacheB) = runtimes.map { it.create(spec) }
            val (setA, setB) = runtimes.map { it.create(setSpec) }
            val remote = backend.delegate.store(Keyspace("cocache:", cacheName))
            fun remoteRead(
                key: String
            ) = Suspending.await { remote.read("cocache:$cacheName:$key", null, null, null) }

            RedisTestSupport.flushScripts()
            cacheA["k"].assert().isEqualTo("k-v")
            (remoteRead("k") as L1Read.Hit).envelope
                .assert().isEqualTo(Envelope.Scalar(EngineSpec.TestCodec.encode("k-v")))

            RedisTestSupport.flushScripts()
            cacheB["k"].assert().isEqualTo("k-v")
            requireNotNull(runtimes[1].metrics(cacheName)).remoteHits.assert().isEqualTo(1)

            RedisTestSupport.flushScripts()
            cacheA.put("k", "p")
            awaitCondition("B sees the put") { cacheB["k"] == "p" }

            RedisTestSupport.flushScripts()
            cacheA.invalidate("k")
            remoteRead("k").assert().isEqualTo(L1Read.Miss(false))
            awaitCondition("B sees the invalidation") { cacheB["k"] == "k-v" }

            RedisTestSupport.flushScripts()
            cacheA.getAll(listOf("x", "y")).assert().isEqualTo(mapOf("x" to "x-v", "y" to "y-v"))

            RedisTestSupport.flushScripts()
            failing.set(true)
            runCatching { cacheA["released"] }.exceptionOrNull().assert().isInstanceOf(
                IllegalStateException::class.java
            )
            remoteRead("released").assert().isEqualTo(L1Read.Miss(false))

            RedisTestSupport.flushScripts()
            setA.add("s", listOf("a", "b"))
            RedisTestSupport.flushScripts()
            setA.remove("s", listOf("a"))
            awaitCondition("B sees the set") { setB["s"] == setOf("b") }
            runtimes.forEach { runtime ->
                requireNotNull(runtime.metrics(cacheName)).let {
                    it.remoteFailures.assert().isEqualTo(0)
                    it.writeFailures.assert().isEqualTo(0)
                }
            }
        } finally {
            runtimes.forEach { it.close() }
        }
    }
}

class RedisNonAtomicFaultTest : FaultSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

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

class RedisShardedFaultTest : FaultSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.shardedBackend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

/**
 * 按版本有序的写入的 TCK：三种后端（pubsub、非原子、sharded）。
 */
class RedisVersionedTest : VersionedSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.backend()

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}

class RedisNonAtomicVersionedTest : VersionedSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

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

class RedisShardedVersionedTest : VersionedSpec() {
    override val expectedCapabilities: Set<L1Capability> = RedisTestSupport.CAPABILITIES

    override val closesBackend: Boolean = true

    private val manualClock = ManualClock()

    override val clock: CacheClock = manualClock

    override fun createBackend(): CoherenceBackend = RedisTestSupport.shardedBackend().also {
        check(!it.atomicBroadcast) { "expected a non-atomic backend" }
    }

    override fun elapse(duration: Duration) {
        manualClock.advance(duration)
        RedisTestSupport.shiftTtl("cocache:$cacheName:*", duration)
    }
}
