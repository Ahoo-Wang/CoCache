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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.internal.Ticker
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 带版本的缓存的本地副本在填充没有确认写入时的处理（TLA+ 模型 `docs/spec` 的 F2、F3）：结果未知的带版本填充按写入处理，
 * L2 不按版本后退。
 */
class VersionedLocalCopyTest {
    private val clock = ManualClock()
    private val database = ConcurrentHashMap<String, Versioned<String?>>()

    /**
     * 刷新策略：寿命 10 s，窗口 5 s，L2 限龄 8 s，降级限龄 1 s；lease 寿命长于测试的时间推进，刷新令牌不会被别的读取抢走。
     *
     * @param leaseWait 等待别人的 lease 的时长；`null` 为默认
     */
    private fun versioned(
        runtime: CacheRuntime,
        leaseWait: Duration? = null,
        loader: CacheLoader<String, Versioned<String?>> = CacheLoader { database[it] }
    ): VersionedCache<String, String> = runtime.create(
        versionedCacheSpec<String, String>(NAME) {
            codec = StringCodec
            versionedLoader = loader
            policy {
                ttl = Duration.ofSeconds(10)
                ttlJitter = Duration.ZERO
                localMaxAge = Duration.ofSeconds(8)
                degradedLocalMaxAge = Duration.ofSeconds(1)
                refreshWindow = Duration.ofSeconds(5)
                load = LoadPolicy(timeout = Duration.ofSeconds(1))
                leaseWait?.let { lease = LeasePolicy(wait = it) }
            }
        }
    )

    /**
     * F2：刷新的 `FILL` 已在 L1 执行、让版本前进（存储广播的帧在本节点被过滤），回复却失败（结果未知）。本节点按写入处理：递增戳、
     * 淘汰本地副本。绕过 SingleFlight 的同节点读取者（刷新卡住超过 `load.timeout` 之后领头的读取）在这之前读到版本 1，
     * 它对 L2 的填充因此被拒绝；否则在刷新的降级副本到期之后，L2 停在版本 1，而 L1 已是版本 2。
     */
    @Test
    fun aVersionedFillWithAnUnknownOutcomeFencesReadersThatReadBeforeIt() {
        val ticks = AtomicLong()
        val backend = HookedBackend(InMemoryBackend(clock))
        val refreshed = CountDownLatch(1)
        val loaderEntered = CountDownLatch(1)
        val loaderRelease = CountDownLatch(1)
        val blockLoader = AtomicBoolean(false)
        val builder = CacheRuntime.builder().backend(backend).clock(clock).refreshExecutor(
            Executor { refresh ->
                Thread {
                    refresh.run()
                    refreshed.countDown()
                }.start()
            }
        )
        builder.ticker = Ticker { ticks.get() }
        builder.build().use { runtime ->
            val cache = versioned(runtime) { key ->
                if (blockLoader.compareAndSet(true, false)) {
                    loaderEntered.countDown()
                    loaderRelease.awaitOrFail()
                }
                database[key]
            }
            database["k"] = Versioned("v1", 1)
            cache["k"].assert().isEqualTo("v1")

            // 进入刷新窗口：刷新取得 V(1) 上的刷新令牌，在 loader 中卡住
            clock.advance(Duration.ofSeconds(6))
            database["k"] = Versioned("v2", 2)
            blockLoader.set(true)
            cache["k"].assert().isEqualTo("v1")
            loaderEntered.awaitOrFail()

            // 越过 load.timeout，L2 的副本到期：下一次读取注销卡住的刷新、自己领头，读到 V(1) 之后停住
            ticks.addAndGet(Duration.ofSeconds(2).toNanos())
            clock.advance(Duration.ofMillis(2500))
            val readerRead = CountDownLatch(1)
            val readerRelease = CountDownLatch(1)
            backend.afterRead = { _, read ->
                readerRead.countDown()
                readerRelease.awaitOrFail()
                read
            }
            val reader = Concurrency.fork("reader") { cache["k"] }
            readerRead.awaitOrFail()
            backend.afterRead = null

            // 刷新的 FILL(2) 在 L1 执行、让版本前进，回复失败
            backend.afterFill = { _, _ -> throw IllegalStateException("reply lost") }
            loaderRelease.countDown()
            refreshed.awaitOrFail()
            backend.afterFill = null

            // 刷新若写入了降级副本（t = 8.5 s 起限龄 1 s），等它到期，排除 L2 不后退的规则（F3）的掩护。读取者读到的版本 1
            // 仍在寿命内（L1 在 t = 10 s 到期，#877），挡住它的填充的是失效戳，不是到期
            clock.advance(Duration.ofMillis(1200))
            readerRelease.countDown()
            reader.join().assert().isEqualTo("v1")

            cache["k"].assert().isEqualTo("v2")
            val metrics = requireNotNull(runtime.metrics(NAME))
            metrics.fillRejected.assert().isEqualTo(1)
            metrics.staleFillsDiscarded.assert().isEqualTo(1)
        }
    }

    /**
     * F2，非原子广播的后端：存储从不广播（内存存储的广播发到节点没有订阅的频道），结果未知的带版本填充由引擎发布失效，
     * 另一个节点因此淘汰版本 1 的副本。
     */
    @Test
    fun aVersionedFillWithAnUnknownOutcomeIsBroadcastWithoutAtomicBroadcast() {
        val memory = InMemoryBackend(clock)
        val channel = InMemoryBackend(clock).channel
        val backend = HookedBackend(
            object : CoherenceBackend by memory {
                override val atomicBroadcast: Boolean = false
                override val channel: InvalidationChannel = channel
            }
        )
        val runtimeA = CacheRuntime.builder().backend(backend).clock(clock).refreshExecutor { it.run() }.build()
        val runtimeB = CacheRuntime.builder().backend(backend).clock(clock).build()
        runtimeA.use {
            runtimeB.use {
                val cacheA = versioned(runtimeA)
                val cacheB = versioned(runtimeB)
                database["k"] = Versioned("v1", 1)
                cacheA["k"].assert().isEqualTo("v1")
                cacheB["k"].assert().isEqualTo("v1")
                val metricsB = requireNotNull(runtimeB.metrics(NAME))
                val received = metricsB.invalidationsReceived

                clock.advance(Duration.ofSeconds(6))
                database["k"] = Versioned("v2", 2)
                backend.afterFill = { _, _ -> throw IllegalStateException("reply lost") }
                cacheA["k"].assert().isEqualTo("v1")
                backend.afterFill = null

                awaitCondition("B received the invalidation") { metricsB.invalidationsReceived > received }
                cacheB["k"].assert().isEqualTo("v2")
                cacheA["k"].assert().isEqualTo("v2")
            }
        }
    }

    /**
     * F3：刷新取得令牌，落后的 loader（读从库）返回更旧的版本或“不存在”，而 `FILL` 没有回复版本拒绝（令牌已被另一次读取抢走，
     * 回复不带版本）。刷新照常降级填充 L2，但 L2 不按版本后退：保留版本 5 的副本，计入 `staleFillsDiscarded`。
     */
    @Test
    fun aRefreshNeverMovesTheLocalCopyBackInVersion() {
        val memory = InMemoryBackend(clock)
        val preempt = AtomicBoolean(false)
        val preempting = object : CoherenceBackend by memory {
            override fun store(keyspace: Keyspace): L1Store = object : ForwardingL1Store(memory.store(keyspace)) {
                override suspend fun fill(fill: Fill): L1Fill {
                    if (!preempt.get()) {
                        return super.fill(fill)
                    }
                    // 令牌已被另一次读取抢走：这次填充令牌不匹配，回复不带版本
                    delegate.release(fill.key, fill.token)
                    return L1Fill.Rejected(currentVersion = null)
                }
            }
        }
        CacheRuntime.builder().backend(preempting).clock(clock).refreshExecutor { it.run() }.build().use { runtime ->
            val cache = versioned(runtime)
            listOf("older", "absent").forEach {
                cache.putIfNewer(it, "v5", 5).assert().isEqualTo(VersionedPut.Written)
                cache.getVersioned(it).assert().isEqualTo(Versioned("v5", 5))
            }
            database["older"] = Versioned("v4", 4)
            preempt.set(true)

            clock.advance(Duration.ofSeconds(6))
            listOf("older", "absent").forEach {
                cache[it].assert().isEqualTo("v5")
                cache.getVersioned(it).assert().isEqualTo(Versioned("v5", 5))
            }
            val metrics = requireNotNull(runtime.metrics(NAME))
            metrics.fillRejected.assert().isEqualTo(2)
            metrics.staleFillsDiscarded.assert().isEqualTo(2)
        }
    }

    /**
     * F3 的规则也挡住未命中路径：卡住的 leader 的 loader 读到落后的版本 1，超过 `load.timeout` 被注销之后，下一次读取领头、
     * 遇到它的 lease 直接绕过 L1 回源，以降级限龄缓存版本 2（还没到刷新点）。leader 随后凭 lease 写回版本 1（`L` 上的填充照常写入），
     * 它对 L2 的填充遇到版本更新的副本，保留副本、计入 `staleFillsDiscarded`。
     */
    @Test
    fun aLaggingLeaderDoesNotReplaceANewerLocalCopy() {
        val ticks = AtomicLong()
        val loaderEntered = CountDownLatch(1)
        val loaderRelease = CountDownLatch(1)
        val blockLoader = AtomicBoolean(true)
        val builder = CacheRuntime.builder().backend(InMemoryBackend(clock)).clock(clock)
        builder.ticker = Ticker { ticks.get() }
        builder.build().use { runtime ->
            val cache = versioned(runtime, leaseWait = Duration.ZERO) { key ->
                val answer = database[key]
                if (blockLoader.compareAndSet(true, false)) {
                    loaderEntered.countDown()
                    loaderRelease.awaitOrFail()
                }
                answer
            }
            database["k"] = Versioned("v1", 1)
            val leader = Concurrency.fork("leader") { cache["k"] }
            loaderEntered.awaitOrFail()

            ticks.addAndGet(Duration.ofSeconds(2).toNanos())
            database["k"] = Versioned("v2", 2)
            cache["k"].assert().isEqualTo("v2")

            loaderRelease.countDown()
            leader.join().assert().isEqualTo("v1")
            cache["k"].assert().isEqualTo("v2")
            requireNotNull(runtime.metrics(NAME)).staleFillsDiscarded.assert().isEqualTo(1)
        }
    }

    private companion object {
        const val NAME = "copies"
    }
}
