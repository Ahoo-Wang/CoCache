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

package me.ahoo.cache.test

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.SetElementsCache
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.converter.KeyConverter
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [SetElementsCache] 的 TCK：两个独立的一致性缓存实例（各自的 L2 与 L1 客户端）共享同一 L1 存储与失效通道。
 *
 * [createDistributedCache] 每次调用都应返回一个新的、指向同一共享存储的客户端，且实现
 * [SetElementsStore][me.ahoo.cache.api.distributed.SetElementsStore]。
 */
abstract class SetElementsCacheSpec {
    protected lateinit var cacheName: String
    protected lateinit var keyConverter: KeyConverter<String>
    private lateinit var cacheEvictedEventBus: CacheEvictedEventBus
    private lateinit var currentClientSideCache: ClientSideCache<Set<String>>
    private lateinit var otherClientSideCache: ClientSideCache<Set<String>>
    protected lateinit var currentCache: CoherentCache<String, Set<String>>
    protected lateinit var otherCache: CoherentCache<String, Set<String>>
    private val createdCaches = mutableListOf<CoherentCache<String, Set<String>>>()

    protected abstract fun createDistributedCache(): DistributedCache<Set<String>>
    protected abstract fun createCacheEvictedEventBus(): CacheEvictedEventBus
    protected abstract fun createCacheName(): String

    @BeforeEach
    open fun setup() {
        cacheName = createCacheName()
        keyConverter = ToStringKeyConverter("$cacheName:")
        cacheEvictedEventBus = createCacheEvictedEventBus()
        currentClientSideCache = MapClientSideCache()
        otherClientSideCache = MapClientSideCache()
        currentCache = createCache("currentClientId", currentClientSideCache)
        otherCache = createCache("otherClientId", otherClientSideCache)
    }

    @AfterEach
    open fun tearDown() {
        createdCaches.forEach { it.close() }
    }

    protected fun createCache(
        clientId: String,
        clientSideCache: ClientSideCache<Set<String>> = MapClientSideCache(),
        ttlPolicy: TtlPolicy = TtlPolicy(ttl = TtlAt.FOREVER)
    ): CoherentCache<String, Set<String>> {
        return DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                distributedCache = createDistributedCache(),
                clientSideCache = clientSideCache,
                ttlPolicy = ttlPolicy,
            )
        ).also { createdCaches.add(it) }
    }

    protected fun newKey(): String = UUID.randomUUID().toString()

    @Suppress("UNCHECKED_CAST")
    protected val CoherentCache<String, Set<String>>.elements: SetElementsCache<String, String>
        get() = this as SetElementsCache<String, String>

    @Test
    fun addOntoAbsentKeyCreatesSet() {
        val key = newKey()
        currentCache.elements.addElements(key, listOf("a", "b"))
        currentCache[key].assert().isEqualTo(setOf("a", "b"))
        currentCache.getTtlAt(key).assert().isEqualTo(TtlAt.FOREVER)
        otherCache[key].assert().isEqualTo(setOf("a", "b"))
    }

    @Test
    fun addOntoMissingValueReplacesIt() {
        val key = newKey()
        // 无数据源：读取写入负缓存
        currentCache.getCache(key)!!.isMissing.assert().isTrue()
        currentCache.elements.addElements(key, listOf("a"))
        currentCache[key].assert().isEqualTo(setOf("a"))
        currentCache.getTtlAt(key).assert().isEqualTo(TtlAt.FOREVER)
        otherCache[key].assert().isEqualTo(setOf("a"))
    }

    @Test
    fun addIsIdempotent() {
        val key = newKey()
        currentCache.elements.addElements(key, listOf("a", "b"))
        currentCache.elements.addElements(key, listOf("b", "c"))
        currentCache[key].assert().isEqualTo(setOf("a", "b", "c"))
    }

    @Test
    fun removeElements() {
        val key = newKey()
        currentCache.elements.addElements(key, listOf("a", "b", "c"))
        currentCache.elements.removeElements(key, listOf("a", "absent"))
        currentCache[key].assert().isEqualTo(setOf("b", "c"))
        otherCache[key].assert().isEqualTo(setOf("b", "c"))
    }

    @Test
    fun removeLastElementEvictsKey() {
        val key = newKey()
        currentCache.elements.addElements(key, listOf("a"))
        currentCache.elements.removeElements(key, listOf("a"))
        // L1 已淘汰：读取未命中并回源（无数据源 → 负缓存）
        currentCache.getCache(key)!!.isMissing.assert().isTrue()
        otherCache[key].assert().isNull()
    }

    @Test
    fun removeFromAbsentKeyDoesNothing() {
        val key = newKey()
        currentCache.elements.removeElements(key, listOf("a"))
        currentCache.getCache(key)!!.isMissing.assert().isTrue()
    }

    @Test
    fun removeFromMissingValueKeepsIt() {
        val key = newKey()
        val missing = currentCache.getCache(key)!!
        missing.isMissing.assert().isTrue()
        otherCache.elements.removeElements(key, listOf("a"))
        val reloaded = currentCache.getCache(key)!!
        reloaded.isMissing.assert().isTrue()
        // 负缓存保留自己的短 TTL，未被延长为永久
        reloaded.isForever.assert().isFalse()
    }

    @Test
    fun emptyElementsAreNoOp() {
        val key = newKey()
        currentCache.elements.addElements(key, listOf("a"))
        currentCache[key]
        currentCache.elements.addElements(key, emptyList())
        currentCache.elements.removeElements(key, emptyList())
        currentCache[key].assert().isEqualTo(setOf("a"))
    }

    @Test
    fun finiteTtlPolicyAppliesToUpdatedKey() {
        val cache = createCache("finiteClientId", ttlPolicy = TtlPolicy(ttl = 600, ttlAmplitude = 10))
        val key = newKey()
        cache.elements.addElements(key, listOf("a"))
        val ttlAt = cache.getTtlAt(key)!!
        ttlAt.assert().isNotEqualTo(TtlAt.FOREVER)
        (ttlAt - TtlAt.currentTime()).assert().isBetween(580, 611)
    }

    @Test
    fun readYourWrites() {
        val key = newKey()
        currentCache.elements.addElements(key, listOf("a"))
        // 填充 L2
        currentCache[key].assert().isEqualTo(setOf("a"))
        currentCache.elements.addElements(key, listOf("b"))
        currentCache[key].assert().isEqualTo(setOf("a", "b"))
        currentCache.elements.removeElements(key, listOf("a"))
        currentCache[key].assert().isEqualTo(setOf("b"))
    }

    @Test
    fun invalidationReachesOtherInstance() {
        val key = newKey()
        val cacheKey = keyConverter.toStringKey(key)
        currentCache.elements.addElements(key, listOf("a"))
        // 写入广播的失效事件异步到达，可能清掉刚填入的 L2；事件处理完后再次读取必定填入 L2
        awaitCondition {
            otherCache[key]
            otherClientSideCache.getCache(cacheKey)?.value == setOf("a")
        }

        currentCache.elements.addElements(key, listOf("b"))
        awaitCondition { otherClientSideCache.getCache(cacheKey) == null }
        otherCache[key].assert().isEqualTo(setOf("a", "b"))

        currentCache.elements.removeElements(key, listOf("a"))
        awaitCondition { otherClientSideCache.getCache(cacheKey) == null }
        otherCache[key].assert().isEqualTo(setOf("b"))
    }

    @Test
    fun concurrentAddsFromTwoInstancesLoseNothing() {
        val key = newKey()
        val threads = 8
        val elementsPerThread = 50
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map { thread ->
                val cache = if (thread % 2 == 0) currentCache else otherCache
                executor.submit {
                    start.await()
                    repeat(elementsPerThread) {
                        cache.elements.addElements(key, listOf("t$thread-$it"))
                        // 交错读取，覆盖读写并发
                        cache[key]
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        val expected = (0 until threads).flatMap { thread -> (0 until elementsPerThread).map { "t$thread-$it" } }.toSet()
        currentCache[key].assert().isEqualTo(expected)
        otherCache[key].assert().isEqualTo(expected)
    }

    @Test
    fun concurrentRemovesFromTwoInstancesLoseNothing() {
        val key = newKey()
        val all = (0 until 100).map { "e$it" }
        currentCache.elements.addElements(key, all)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = all.filterIndexed { index, _ -> index % 2 == 0 }.chunked(25).mapIndexed { index, chunk ->
                val cache = if (index % 2 == 0) currentCache else otherCache
                executor.submit {
                    start.await()
                    chunk.forEach { cache.elements.removeElements(key, listOf(it)) }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        val expected = all.filterIndexed { index, _ -> index % 2 == 1 }.toSet()
        currentCache[key].assert().isEqualTo(expected)
        otherCache[key].assert().isEqualTo(expected)
    }

    protected fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met within 5 seconds." }
            Thread.sleep(10)
        }
    }
}
