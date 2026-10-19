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

package me.ahoo.cocache.test

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * 故障 TCK：两个节点共享同一个后端，验证 L1 故障下的 G1（陈旧有界）与 G2（失效不丢失）。
 *
 * 故障由 [HookedBackend] 注入；断线用例需要后端实现 [disconnect]，否则跳过。
 * 与 [EngineSpec] 中单节点的降级与重试用例互补（如 [EngineSpec.remoteReadFailureDegradesToTheSource]、
 * [EngineSpec.failedInvalidateIsRetriedAndBypassesTheStaleRemoteMeanwhile]），这里关注跨节点的陈旧上界、重试的放弃与溢出、
 * 以及“已生效但报错”的写入。
 */
abstract class FaultSpec {
    protected abstract fun createBackend(): CoherenceBackend

    /**
     * 引擎与后端共用的时钟。
     */
    protected abstract val clock: CacheClock

    /**
     * 让时间前进 [duration]（同时作用于后端与引擎）。
     */
    protected abstract fun elapse(duration: Duration)

    /**
     * 断开 [createBackend] 所建后端的命令连接并保持断开（期间的命令应失败，而不是缓冲），关闭返回值即允许重连。
     * 后端没有连接的概念时返回 `null`（默认），断线用例跳过。
     */
    protected open fun disconnect(): AutoCloseable? = null

    /**
     * 访问缓存的方式：同步 API（默认），或经异步视图（[AsyncFaultSpec]，RFC 0004 §10.1）。
     */
    protected open val access: CacheAccess = CacheAccess.SYNC

    protected lateinit var backend: HookedBackend
    protected lateinit var cacheName: String
    private lateinit var nodeA: CacheRuntime
    private lateinit var nodeB: CacheRuntime
    private val extraNodes = CopyOnWriteArrayList<CacheRuntime>()

    private val database = ConcurrentHashMap<String, String>()
    private val loads = AtomicInteger()
    private val directExecutor = Executor { it.run() }

    @BeforeEach
    open fun setup() {
        backend = HookedBackend(createBackend())
        nodeA = newNode(RETRY)
        nodeB = newNode(RETRY)
        cacheName = "tck-fault-" + UUID.randomUUID().toString().substring(0, 8)
    }

    @AfterEach
    open fun destroy() {
        extraNodes.forEach { it.close() }
        nodeA.close()
        nodeB.close()
        backend.close()
    }

    private fun newNode(retryPolicy: RetryPolicy): CacheRuntime = CacheRuntime.builder()
        .backend(backend)
        .clock(clock)
        .refreshExecutor(directExecutor)
        .retryPolicy(retryPolicy)
        .build()

    private fun policy(): CachePolicy = CachePolicy(
        ttl = Duration.ofMinutes(10),
        ttlJitter = Duration.ZERO,
        lease = LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ofSeconds(5)),
    )

    private val loader = CacheLoader<String, String> { key ->
        loads.incrementAndGet()
        database[key]
    }

    private fun spec(policy: CachePolicy = policy()): CacheSpec<String, String> = cacheSpec(cacheName) {
        codec = TestCodec
        loader = this@FaultSpec.loader
        this.policy = policy
        access.source(this)
    }

    private fun CacheRuntime.cache(policy: CachePolicy = policy()): Cache<String, String> = access.cache(
        create(spec(policy))
    )

    private fun CacheRuntime.metrics(): CacheMetrics = requireNotNull(metrics(cacheName))

    private fun CacheRuntime.admin(): CacheAdmin = requireNotNull(admin(cacheName))

    private fun remote(key: String): RemoteRead {
        val store = backend.delegate.store(cacheName)
        return store.read("cocache:$cacheName:$key", null, null)
    }

    private fun remoteValue(key: String): String? {
        val read = remote(key) as? RemoteRead.Hit ?: return null
        return (read.envelope as? Envelope.Scalar)?.let { TestCodec.decode(it.bytes) }
    }

    /**
     * L1 整体不可用：两个节点都只能降级回源，副本以 `degradedLocalMaxAge` 限龄。另一节点的失效既写不进 L1 也发不出事件，
     * 本节点的陈旧上界就是降级限龄（G1）。
     */
    @Test
    fun degradedCopiesAreBoundedByDegradedLocalMaxAgeWhileRemoteFails() {
        val policy = policy()
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = "v0"
        backend.failAll = true
        cacheA["k"].assert().isEqualTo("v0")
        cacheB["k"].assert().isEqualTo("v0")
        loads.get().assert().isEqualTo(2)
        val lookup = cacheA.lookup("k") as Lookup.Present
        requireNotNull(lookup.expiresAt).toEpochMilli().assert()
            .isLessThanOrEqualTo(clock.nowMillis() + policy.degradedLocalMaxAge.toMillis())
        database["k"] = "v1"
        cacheB.invalidate("k")
        cacheB["k"].assert().isEqualTo("v1")
        cacheA["k"].assert().isEqualTo("v0")
        elapse(policy.degradedLocalMaxAge.plusSeconds(1))
        cacheA["k"].assert().isEqualTo("v1")
        nodeA.metrics().degradedFills.assert().isEqualTo(2)
        nodeA.metrics().remoteFailures.assert().isGreaterThanOrEqualTo(2)
        backend.failAll = false
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
    }

    /**
     * fill 失败：回源结果降级限龄进入 L2；lease 至多占用到 `lease.ttl`，其它节点不会无限等待，到期后恢复正常写回 L1。
     */
    @Test
    fun failedFillDegradesAndTheLeaseIsBoundedByItsTtl() {
        val impatient = policy().toBuilder().lease(
            LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ZERO)
        ).build()
        val cacheA = nodeA.cache(impatient)
        val cacheB = nodeB.cache(impatient)
        database["k"] = "v0"
        backend.failFill = true
        cacheA["k"].assert().isEqualTo("v0")
        cacheB["k"].assert().isEqualTo("v0")
        nodeA.metrics().degradedFills.assert().isEqualTo(1)
        nodeB.metrics().degradedFills.assert().isEqualTo(1)
        remoteValue("k").assert().isNull()
        backend.failFill = false
        elapse(impatient.lease.ttl.plus(impatient.degradedLocalMaxAge).plusSeconds(1))
        database["k"] = "v1"
        cacheB["k"].assert().isEqualTo("v1")
        remoteValue("k").assert().isEqualTo("v1")
        cacheA["k"].assert().isEqualTo("v1")
        loads.get().assert().isEqualTo(3)
    }

    /**
     * 失效重试耗尽后放弃：计入 `invalidationsDropped`、解除“重试中”。此后 L1 保留旧值，所有节点的陈旧上界是 L1 的剩余寿命。
     */
    @Test
    fun exhaustedInvalidationRetryIsDroppedAndBoundedByRemoteTtl() {
        val policy = policy()
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = "v0"
        cacheA["k"]
        cacheB["k"]
        database["k"] = "v1"
        backend.failWrites = true
        cacheA.invalidate("k")
        awaitCondition("retry given up") { nodeA.metrics().invalidationsDropped == 1L }
        nodeA.metrics().invalidationRetries.assert().isEqualTo(RETRY.maxAttempts.toLong())
        nodeA.admin().pendingInvalidationCount.assert().isEqualTo(0)
        backend.failWrites = false
        remoteValue("k").assert().isEqualTo("v0")
        cacheA["k"].assert().isEqualTo("v0")
        cacheB["k"].assert().isEqualTo("v0")
        elapse(requireNotNull(policy.ttl).plusSeconds(1))
        cacheA["k"].assert().isEqualTo("v1")
        cacheB["k"].assert().isEqualTo("v1")
    }

    /**
     * 重试队列已满：新的失败失效立即放弃（不登记“重试中”），已登记的仍绕过 L1；关闭运行时放弃剩余的重试。
     */
    @Test
    fun retryQueueOverflowDropsTheInvalidation() {
        // 退避远长于用例：已提交的重试在用例期间不会执行
        val node = newNode(
            RetryPolicy(
                maxAttempts = 1,
                initialBackoff = Duration.ofHours(1),
                maxBackoff = Duration.ofHours(1),
                capacity = 1
            )
        ).also { extraNodes.add(it) }
        val cache = node.cache()
        val writer = nodeB.cache()
        writer.put("a", "a0")
        writer.put("b", "b0")
        database["a"] = "a1"
        database["b"] = "b1"
        backend.failWrites = true
        cache.invalidate("a")
        cache.invalidate("b")
        backend.failWrites = false
        node.metrics().invalidationsDropped.assert().isEqualTo(1)
        node.admin().pendingInvalidationCount.assert().isEqualTo(1)
        cache["a"].assert().isEqualTo("a1")
        cache["b"].assert().isEqualTo("b0")
        remoteValue("a").assert().isEqualTo("a0")
        node.close()
        node.metrics().invalidationsDropped.assert().isEqualTo(2)
        node.admin().pendingInvalidationCount.assert().isEqualTo(0)
    }

    /**
     * `put` 已在 L1 生效却向调用方报错（如应答超时）：调用方得到 [CacheWriteException]，重试同一个 `put` 是幂等的，
     * 且重试的广播让所有节点收敛（非原子广播的后端上，报错的那次没有发出事件）。
     */
    @Test
    fun putThatFailsAfterApplyingIsSafeToRetry() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = "v0"
        cacheB["k"].assert().isEqualTo("v0")
        backend.afterPut = {
            if (Thread.currentThread().name == "writer") {
                throw RemoteUnavailableException("put (applied)")
            }
        }
        fork("writer") { cacheA.put("k", "v1") }.joinFailure().assert().isInstanceOf(CacheWriteException::class.java)
        backend.afterPut = null
        remoteValue("k").assert().isEqualTo("v1")
        cacheA["k"].assert().isEqualTo("v1")
        cacheA.put("k", "v1")
        remoteValue("k").assert().isEqualTo("v1")
        awaitCondition("B converges") { cacheB["k"] == "v1" }
        loads.get().assert().isEqualTo(1)
    }

    /**
     * `invalidate` 已在 L1 生效却报错：引擎按失败处理并重试，重放的 `DEL` 是幂等的（期间其它节点重新填充的值也只是被多删一次）。
     * 重试成功后两个节点收敛到新值，没有失效被丢弃。
     */
    @Test
    fun invalidateThatFailsAfterApplyingIsRetriedIdempotently() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        database["k"] = "v0"
        cacheA["k"]
        cacheB["k"]
        database["k"] = "v1"
        val attempts = AtomicInteger()
        backend.afterInvalidate = {
            if (attempts.getAndIncrement() == 0) {
                throw RemoteUnavailableException("invalidate (applied)")
            }
        }
        cacheA.invalidate("k")
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
        awaitCondition("retry succeeded") { attempts.get() >= 2 && nodeA.admin().pendingInvalidationCount == 0 }
        backend.afterInvalidate = null
        nodeA.metrics().invalidationsDropped.assert().isEqualTo(0)
        awaitCondition("B converges") { cacheB["k"] == "v1" }
        cacheA["k"].assert().isEqualTo("v1")
    }

    /**
     * 断线期间的写入向调用方报错，重连后不得被重放（否则一个已报错的 `put` 会在稍后覆盖更新的值）。
     */
    @Test
    fun writesRejectedWhileDisconnectedAreNotReplayed() {
        val cacheA = nodeA.cache()
        val cacheB = nodeB.cache()
        cacheA.put("k", "v1")
        cacheB["k"].assert().isEqualTo("v1")
        val disconnection = disconnect()
        assumeTrue(disconnection != null, "backend cannot simulate a disconnect")
        requireNotNull(disconnection).use {
            awaitCondition("commands rejected") { runCatching { remote("probe") }.isFailure }
            runCatching {
                cacheA.put(
                    "k",
                    "v2"
                )
            }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        }
        awaitCondition("reconnected") { runCatching { remote("probe") }.isSuccess }
        remoteValue("k").assert().isEqualTo("v1")
        awaitCondition("A reads the remote value") { cacheA["k"] == "v1" }
        cacheB["k"].assert().isEqualTo("v1")
    }

    private companion object {
        val RETRY = RetryPolicy(
            maxAttempts = 3,
            initialBackoff = Duration.ofMillis(10),
            maxBackoff = Duration.ofMillis(20)
        )
    }
}
