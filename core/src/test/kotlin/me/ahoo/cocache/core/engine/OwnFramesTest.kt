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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.RemoteUnavailableException
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 结果未知的写入之后本节点自己的帧（RFC 0005 §4.6，P12）：在 `localMaxAge`（这里 8 s）之内不过滤，之后照常过滤。
 * 迟到执行的写入以 [HookedBackend.deferPuts] 模拟，迟到的带版本 `FILL` 的广播直接以本节点的身份发布。
 */
class OwnFramesTest {
    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val backend = HookedBackend(memory)
    private val runtime = CacheRuntime.builder().backend(backend).clock(clock).build()
    private val database = ConcurrentHashMap<String, String>()
    private val versions = ConcurrentHashMap<String, Versioned<String?>>()

    @AfterEach
    fun destroy() = runtime.close()

    private fun cache(): Cache<String, String> = runtime.create(
        cacheSpec<String, String>(NAME) {
            codec = StringCodec
            loader = CacheLoader { database[it] }
            policy {
                ttl = Duration.ofMinutes(10)
                ttlJitter = Duration.ZERO
                localMaxAge = LOCAL_MAX_AGE
                invalidateFailure = InvalidateFailureMode.THROW
            }
        }
    )

    /**
     * lease 寿命 2 s：失败的 `FILL` 留下的 lease 到期之后，下一次读取重新取得 lease 并照常填充。
     */
    private fun versioned(): VersionedCache<String, String> = runtime.create(
        versionedCacheSpec<String, String>(NAME) {
            codec = StringCodec
            versionedLoader = CacheLoader { versions[it] }
            policy {
                ttl = Duration.ofMinutes(10)
                ttlJitter = Duration.ZERO
                localMaxAge = LOCAL_MAX_AGE
                lease = LeasePolicy(ttl = Duration.ofSeconds(2))
            }
        }
    )

    private fun metrics(): CacheMetrics = requireNotNull(runtime.metrics(NAME))

    /**
     * 以本节点的身份发布 [key] 的失效帧（迟到执行的写入在原子广播的后端上发出的那一帧）。
     */
    private fun ownFrame(key: String) {
        val frame = InvalidationFrame.encode(runtime.nodeId, listOf("${CacheSpec.DEFAULT_KEY_PREFIX}$NAME:$key"))
        Suspending.await { memory.channel.publish(Keyspace(CacheSpec.DEFAULT_KEY_PREFIX, NAME), frame) }
    }

    private fun failedPut(cache: Cache<String, String>, key: String, value: String) {
        val failure = runCatching { cache.put(key, value) }.exceptionOrNull()
        failure.assert().isInstanceOf(CacheWriteException::class.java)
        (failure as CacheWriteException).outcomeUnknown.assert().withFailMessage { "the put may still run" }.isTrue()
    }

    /**
     * 迟到的 `put` 的帧在窗口之内淘汰本节点在报错之后填入的旧副本；窗口之后同样的帧被过滤，旧副本留到它自己的 `localMaxAge`。
     * 本节点自己的帧从不计入 `invalidationsReceived`。
     */
    @Test
    fun ownFramesOfAnUnknownPutAreAppliedUntilLocalMaxAge() {
        val cache = cache()
        database["k"] = "v0"
        cache["k"].assert().isEqualTo("v0")

        backend.deferPuts = true
        failedPut(cache, "k", "v1")
        cache["k"].assert().isEqualTo("v0")
        backend.applyDeferredPuts().assert().isEqualTo(1)
        cache["k"].assert().withFailMessage { "the late put's own frame did not evict the stale copy" }.isEqualTo("v1")

        failedPut(cache, "k", "v2")
        backend.deferPuts = false
        clock.advance(LOCAL_MAX_AGE.dividedBy(2))
        cache["k"].assert().isEqualTo("v1")
        clock.advance(LOCAL_MAX_AGE.dividedBy(2).plusMillis(1))
        backend.applyDeferredPuts().assert().isEqualTo(1)
        cache["k"].assert().withFailMessage { "an own frame after the window evicted the copy" }.isEqualTo("v1")
        clock.advance(LOCAL_MAX_AGE.dividedBy(2))
        cache["k"].assert().isEqualTo("v2")
        metrics().invalidationsReceived.assert().isZero()
    }

    /**
     * 写入确定没有执行（存储在写出之前就拒绝）时不登记：之后本节点自己的帧照常被过滤。
     */
    @Test
    fun rejectedWritesKeepFilteringOwnFrames() {
        val cache = cache()
        database["k"] = "v0"
        cache["k"].assert().isEqualTo("v0")
        backend.rejectWrites = true
        runCatching { cache.put("k", "v1") }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        backend.rejectWrites = false
        cache["k"].assert().isEqualTo("v0")
        val remoteHits = metrics().remoteHits
        ownFrame("k")
        cache["k"].assert().isEqualTo("v0")
        metrics().remoteHits.assert().isEqualTo(remoteHits)
    }

    /**
     * 登记超过上限（`AbstractEngine.MAX_OWN_FRAMES`）时删除过期的登记，仍在窗口内的 key 照常处理。
     */
    @Test
    fun expiredRegistrationsArePrunedPastTheLimit() {
        val cache = cache()
        backend.failWrites = true
        val many = (0..OWN_FRAMES_LIMIT).associate { "old-$it" to "v" }
        runCatching { cache.putAll(many) }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        clock.advance(LOCAL_MAX_AGE.plusMillis(1))
        runCatching { cache.putAll(many + ("k" to "v1")) }.exceptionOrNull()
            .assert().isInstanceOf(CacheWriteException::class.java)
        backend.failWrites = false
        database["k"] = "v0"
        cache["k"].assert().isEqualTo("v0")
        val remoteHits = metrics().remoteHits
        ownFrame("k")
        cache["k"].assert().isEqualTo("v0")
        metrics().remoteHits.assert()
            .withFailMessage { "the own frame did not evict the copy" }.isEqualTo(remoteHits + 1)
    }

    /**
     * 结果未知的带版本填充（TLA+ F2 的残余）：单 key 与批量的未命中都登记。lease 到期之后的读取把版本 1 填进 L2，
     * 迟到的 `FILL` 广播的帧（来源是本节点）淘汰它。
     */
    @Test
    fun ownFramesOfAnUnknownVersionedFillAreApplied() {
        val cache = versioned()
        versions["a"] = Versioned("a1", 1)
        versions["b"] = Versioned("b1", 1)
        backend.failFill = true
        cache["a"].assert().isEqualTo("a1")
        cache.getAll(listOf("b")).assert().isEqualTo(mapOf("b" to "b1"))
        backend.failFill = false
        clock.advance(Duration.ofSeconds(3))
        cache["a"].assert().isEqualTo("a1")
        cache.getAll(listOf("b")).assert().isEqualTo(mapOf("b" to "b1"))

        val remoteHits = metrics().remoteHits
        cache["a"]
        cache["b"]
        metrics().remoteHits.assert().isEqualTo(remoteHits)
        ownFrame("a")
        ownFrame("b")
        cache["a"].assert().isEqualTo("a1")
        cache["b"].assert().isEqualTo("b1")
        metrics().remoteHits.assert()
            .withFailMessage { "own frames did not evict the copies" }.isEqualTo(remoteHits + 2)
    }

    /**
     * #829：同一 key 的两次结果未知的写入的登记交错。put A 先取时刻（t=0）、在登记之前停住（[PausingClock]）；
     * t=4s 另一次写入 B 结果未知并登记（截止 12s）；A 之后才以较早的时刻登记（截止 8s）。登记按 key 取最大值，A 不缩短 B 的窗口：
     * t=6s 从仍是旧值的 L1 填入 L2 的副本（到 14s），在 t=10s 被迟到执行的 put 的帧淘汰。B 是单 key 的 put（同样迟到执行）
     * 或批量的 putAll（结果未知、没有执行）时都一样。
     */
    @Test
    fun anEarlierTimeRegisteredLaterDoesNotShortenTheWindow() {
        val laterWrites: List<Pair<String, (Cache<String, String>) -> Unit>> = listOf(
            "v2" to { cache -> failedPut(cache, "k", "v2") },
            "v1" to { cache ->
                backend.failWrites = true
                val failure = runCatching { cache.putAll(mapOf("k" to "v2")) }.exceptionOrNull()
                backend.failWrites = false
                (failure as CacheWriteException).outcomeUnknown.assert().isTrue()
            },
        )
        for ((expected, laterWrite) in laterWrites) {
            val start = clock.nowMillis()
            val pausing = PausingClock(clock)
            val pausingBackend = object : CoherenceBackend by backend {
                override fun store(keyspace: Keyspace): L1Store = object : ForwardingL1Store(backend.store(keyspace)) {
                    override suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
                        try {
                            super.put(key, envelope, ttl, frame)
                        } catch (e: RemoteUnavailableException) {
                            // 存储报告结果未知：这个线程下一次读时钟（登记）时停住
                            pausing.pauseNextRead()
                            throw e
                        }
                    }
                }
            }
            CacheRuntime.builder().backend(pausingBackend).clock(pausing).build().use { runtime ->
                val cache = runtime.create(
                    cacheSpec<String, String>("late$expected") {
                        codec = StringCodec
                        loader = CacheLoader { database[it] }
                        policy {
                            ttl = Duration.ofMinutes(10)
                            ttlJitter = Duration.ZERO
                            localMaxAge = LOCAL_MAX_AGE
                            invalidateFailure = InvalidateFailureMode.THROW
                        }
                    }
                )
                database["k"] = "v0"
                cache["k"].assert().isEqualTo("v0")
                backend.deferPuts = true
                val first = fork("put A") { failedPut(cache, "k", "v1") }
                pausing.paused.awaitOrFail("put A read the time")
                clock.advance(Duration.ofSeconds(4))
                laterWrite(cache)
                pausing.resume.countDown()
                first.join()
                backend.deferPuts = false

                clock.advance(Duration.ofSeconds(2))
                cache["k"].assert().isEqualTo("v0")
                clock.advance(Duration.ofSeconds(4))
                backend.applyDeferredPuts().assert().isEqualTo(if (expected == "v2") 2 else 1)
                clock.nowMillis().assert().isEqualTo(start + Duration.ofSeconds(10).toMillis())
                cache["k"].assert().withFailMessage { "the own frame inside the later window was filtered" }
                    .isEqualTo(expected)
            }
            clock.advance(LOCAL_MAX_AGE)
        }
    }

    /**
     * 包装 [delegate]：[pauseNextRead] 之后，调用它的线程下一次读时钟时先取得时刻，再停在 [resume] 上（取得时刻之后被抢占）。
     * 只停一次。
     */
    private class PausingClock(private val delegate: CacheClock) : CacheClock {
        private val armed = AtomicBoolean()

        @Volatile
        private var target: Thread? = null
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)

        fun pauseNextRead() {
            if (armed.compareAndSet(false, true)) {
                target = Thread.currentThread()
            }
        }

        override fun nowMillis(): Long {
            val now = delegate.nowMillis()
            if (target === Thread.currentThread()) {
                target = null
                paused.countDown()
                resume.awaitOrFail("resume the paused clock read")
            }
            return now
        }
    }

    private companion object {
        const val NAME = "own"
        const val OWN_FRAMES_LIMIT = 1024
        val LOCAL_MAX_AGE: Duration = Duration.ofSeconds(8)
    }
}
