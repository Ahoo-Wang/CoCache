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

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * 集合缓存 TCK（RFC 0003 §4.8）：L1 是唯一副本，元素增删由 L1 串行化。
 */
abstract class SetEngineSpec {
    protected abstract fun createBackend(): CoherenceBackend

    protected abstract val clock: CacheClock

    protected abstract fun elapse(duration: Duration)

    /**
     * 用例结束时是否关闭 [createBackend] 返回的后端（见 [HookedBackend.closesDelegate]）。默认 `false`：后端可能是共享的。
     * 每个用例新建后端的实现应覆盖为 `true`。
     */
    protected open val closesBackend: Boolean = false

    protected lateinit var backend: HookedBackend
    protected lateinit var nodeA: CacheRuntime
    protected lateinit var nodeB: CacheRuntime
    protected lateinit var cacheName: String

    @BeforeEach
    open fun setup() {
        backend = HookedBackend(createBackend()).apply { closesDelegate = closesBackend }
        nodeA = CacheRuntime.builder().backend(backend).clock(clock).build()
        nodeB = CacheRuntime.builder().backend(backend).clock(clock).build()
        cacheName = "tck-set-" + UUID.randomUUID().toString().substring(0, 8)
    }

    @AfterEach
    open fun destroy() {
        nodeA.close()
        nodeB.close()
        backend.close()
    }

    /**
     * 访问缓存的方式：同步 API（默认），或经异步视图（[AsyncSetEngineSpec]，RFC 0004 §10.1）。
     */
    protected open val access: CacheAccess = CacheAccess.SYNC

    protected fun CacheRuntime.setCache(policy: CachePolicy = SetCacheSpec.DEFAULT_POLICY): SetCache<String, String> = access.setCache(
        create(
            setCacheSpec(cacheName) {
                elementCodec = StringCodec
                this.policy = policy
            }
        )
    )

    @Test
    fun addCreatesSetVisibleToBothNodes() {
        val cacheA = nodeA.setCache()
        val cacheB = nodeB.setCache()
        cacheB["k"].assert().isEmpty()
        cacheB.lookup("k").assert().isInstanceOf(Lookup.Absent::class.java)
        cacheA.add("k", listOf("a", "b"))
        cacheA["k"].assert().containsExactlyInAnyOrder("a", "b")
        awaitCondition("B sees the add") { cacheB["k"] == setOf("a", "b") }
        (cacheB.lookup("k") as Lookup.Present).value.assert().containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun emptyElementWritesAreNoOps() {
        val cache = nodeA.setCache()
        cache.add("k", emptyList())
        cache.remove("k", emptyList())
        cache["k"].assert().isEmpty()
        backend.reads.get().assert().isEqualTo(1)
    }

    @Test
    fun removeLastElementDeletesTheSet() {
        val cache = nodeA.setCache()
        cache.add("k", listOf("a", "b"))
        cache.remove("k", listOf("a"))
        cache["k"].assert().containsExactly("b")
        cache.remove("k", listOf("b"))
        cache["k"].assert().isEmpty()
        cache.remove("absent", listOf("x"))
        cache["absent"].assert().isEmpty()
    }

    @Test
    fun concurrentAddsFromTwoNodesAreAllKept() {
        val cacheA = nodeA.setCache()
        val cacheB = nodeB.setCache()
        val start = CountDownLatch(1)
        val writers = (0 until 8).map { index ->
            fork("writer-$index") {
                start.await()
                (if (index % 2 == 0) cacheA else cacheB).add("k", listOf("e$index"))
            }
        }
        start.countDown()
        writers.forEach { it.join() }
        val expected = (0 until 8).map { "e$it" }.toSet()
        awaitCondition("A sees all") { cacheA["k"] == expected }
        awaitCondition("B sees all") { cacheB["k"] == expected }
    }

    @Test
    fun invalidateIsRejectedBecauseItWouldDestroyTheOnlyCopy() {
        val cache = nodeA.setCache()
        runCatching {
            cache.invalidate(
                "k"
            )
        }.exceptionOrNull().assert().isInstanceOf(UnsupportedOperationException::class.java)
        runCatching {
            cache.invalidateAll(
                listOf("k")
            )
        }.exceptionOrNull().assert().isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun clearDeletesOnBothNodes() {
        val cacheA = nodeA.setCache()
        val cacheB = nodeB.setCache()
        cacheA.add("k", listOf("a"))
        cacheB["k"].assert().containsExactly("a")
        cacheA.clear("k")
        cacheA["k"].assert().isEmpty()
        awaitCondition("B sees the clear") { cacheB["k"].isEmpty() }
    }

    @Test
    fun putReplacesAndEmptyPutClears() {
        val cache = nodeA.setCache()
        cache.add("k", listOf("a"))
        cache.put("k", setOf("x", "y"))
        cache["k"].assert().containsExactlyInAnyOrder("x", "y")
        cache.put("k", setOf("z"), Duration.ofSeconds(1))
        cache["k"].assert().containsExactly("z")
        elapse(Duration.ofSeconds(2))
        cache["k"].assert().isEmpty()
        cache.add("k", listOf("a"))
        cache.put("k", emptySet())
        cache["k"].assert().isEmpty()
    }

    @Test
    fun putAllReplacesAndEmptySetsClear() {
        val cacheA = nodeA.setCache()
        val cacheB = nodeB.setCache()
        cacheA.add("a", listOf("1"))
        cacheA.add("b", listOf("2"))
        cacheB.getAll(listOf("a", "b")).assert().isEqualTo(mapOf("a" to setOf("1"), "b" to setOf("2")))
        cacheA.putAll(mapOf("a" to setOf("x", "y"), "b" to emptySet(), "c" to setOf("z")))
        cacheA["a"].assert().containsExactlyInAnyOrder("x", "y")
        cacheA["b"].assert().isEmpty()
        cacheA["c"].assert().containsExactly("z")
        awaitCondition("B sees the batch") { cacheB["a"] == setOf("x", "y") && cacheB["b"].isEmpty() }
        cacheB["c"].assert().containsExactly("z")

        cacheA.putAll(mapOf("a" to emptySet(), "c" to emptySet()))
        cacheA["a"].assert().isEmpty()
        cacheA["c"].assert().isEmpty()
        cacheA.putAll(mapOf("d" to setOf("w")), Duration.ofSeconds(1))
        cacheA["d"].assert().containsExactly("w")
        elapse(Duration.ofSeconds(2))
        cacheA["d"].assert().isEmpty()
    }

    @Test
    fun emptyPutAllIsANoOp() {
        val cache = nodeA.setCache()
        cache.putAll(emptyMap())
        cache.putAll(emptyMap(), Duration.ofSeconds(1))
        backend.putAlls.get().assert().isEqualTo(0)
    }

    @Test
    fun getAllReturnsNonEmptySets() {
        val cache = nodeA.setCache()
        cache.add("a", listOf("1"))
        cache.add("b", listOf("2"))
        cache.getAll(listOf("a", "b", "c")).assert().isEqualTo(mapOf("a" to setOf("1"), "b" to setOf("2")))
    }

    @Test
    fun writeFailuresThrow() {
        val cache = nodeA.setCache()
        backend.failWrites = true
        runCatching {
            cache.add(
                "k",
                listOf("a")
            )
        }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        runCatching {
            cache.remove(
                "k",
                listOf("a")
            )
        }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        runCatching { cache.clear("k") }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        runCatching {
            cache.put(
                "k",
                setOf("a")
            )
        }.exceptionOrNull().assert().isInstanceOf(CacheWriteException::class.java)
        val error = runCatching { cache.putAll(mapOf("k" to setOf("a"))) }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        (error as CacheWriteException).operation.assert().isEqualTo("putAll")
    }

    @Test
    fun readFailurePropagatesBecauseThereIsNoSource() {
        val cache = nodeA.setCache()
        backend.failAll = true
        runCatching { cache["k"] }.exceptionOrNull().assert().isInstanceOf(RemoteUnavailableException::class.java)
    }

    @Test
    fun scalarInRemoteIsRejected() {
        val cache = nodeA.setCache()
        backend.delegate.store(cacheName).put(
            "cocache:$cacheName:k",
            Envelope.Scalar("x".toByteArray()),
            null,
            InvalidationFrame.encode(NodeId.random(), emptyList())
        )
        runCatching { cache["k"] }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
    }

    /**
     * 不是本协议写入的内容（如 5.x 留下的原生 Set）：没有数据源，默认 `FAIL`，抛出且不写 L2、不修改 L1。
     */
    @Test
    fun unrecognizedContentFailsByDefault() {
        val cache = nodeA.setCache()
        cache.add("k", listOf("a"))
        backend.afterRead = { _, read -> if (read is RemoteRead.Hit) read.copy(format = 0) else read }
        val error = runCatching { cache["k"] }.exceptionOrNull()
        backend.afterRead = null
        error.assert().isInstanceOf(CacheReadException::class.java)
        requireNotNull(error?.cause as? ForeignFormatException).format.assert().isEqualTo(0)
        nodeA.metrics(cacheName)?.unknownFormats.assert().isEqualTo(1L)
        cache["k"].assert().containsExactly("a")
    }

    /**
     * 显式 `RECLAIM`：按不存在处理（读到空集合），不删除，6.4 及以前的行为。
     */
    @Test
    fun unrecognizedContentReadsEmptyWhenConfigured() {
        val cache = nodeA.setCache(
            SetCacheSpec.DEFAULT_POLICY.toBuilder().foreignFormat(ForeignFormatMode.RECLAIM).build()
        )
        cache.add("k", listOf("a"))
        backend.afterRead = { _, read -> if (read is RemoteRead.Hit) read.copy(format = 0) else read }
        cache["k"].assert().isEmpty()
        backend.afterRead = null
        nodeA.metrics(cacheName)?.unknownFormats.assert().isEqualTo(1L)
    }
}
