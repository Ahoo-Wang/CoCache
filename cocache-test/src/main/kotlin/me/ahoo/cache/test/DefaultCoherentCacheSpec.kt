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

import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.consistency.CacheEvictedEvent
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

abstract class DefaultCoherentCacheSpec<K, V> : CacheSpec<K, V>() {
    companion object {
        private val CACHE_SOURCE_VALUE = ThreadLocal<CacheValue<*>>()
    }

    private lateinit var keyConverter: KeyConverter<K>
    private lateinit var clientSideCache: ClientSideCache<V>
    private lateinit var distributedCache: DistributedCache<V>
    private lateinit var cacheEvictedEventBus: CacheEvictedEventBus
    private lateinit var coherentCache: CoherentCache<K, V>
    protected lateinit var cacheName: String
    protected val clientId: String = UUID.randomUUID().toString()

    private val cacheSource = object : CacheSource<K, V> {
        override fun loadCacheValue(key: K): CacheValue<V>? {
            Thread.sleep(100)
            @Suppress("UNCHECKED_CAST")
            return CACHE_SOURCE_VALUE.get() as CacheValue<V>?
        }
    }

    @BeforeEach
    override fun setup() {
        keyConverter = createKeyConverter()
        clientSideCache = createClientSideCache()
        distributedCache = createDistributedCache()
        cacheEvictedEventBus = createCacheEvictedEventBus()
        cacheName = createCacheName()
        coherentCache = DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                clientSideCache = clientSideCache,
                distributedCache = distributedCache,
                cacheSource = cacheSource
            )
        )
        super.setup()
    }

    protected abstract fun createKeyConverter(): KeyConverter<K>
    protected abstract fun createClientSideCache(): ClientSideCache<V>
    protected abstract fun createDistributedCache(): DistributedCache<V>
    protected abstract fun createCacheEvictedEventBus(): CacheEvictedEventBus
    protected abstract fun createCacheName(): String
    override fun createCache(): Cache<K, V> {
        return coherentCache
    }

    @Test
    fun getFromCacheSource() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        CACHE_SOURCE_VALUE.set(cacheValue)
        coherentCache[key].assert().isEqualTo(value)
        CACHE_SOURCE_VALUE.remove()
    }

    @Test
    fun getExpiredValueFromCacheSourceDoesNotPopulateCaches() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue(value, ComputedTtlAt.at(-5))
        CACHE_SOURCE_VALUE.set(cacheValue)
        val cacheKey = keyConverter.toStringKey(key)

        try {
            coherentCache.getCache(key)!!.isExpired.assert().isTrue()
            clientSideCache.getCache(cacheKey).assert().isNull()
            distributedCache.getCache(cacheKey).assert().isNull()
        } finally {
            CACHE_SOURCE_VALUE.remove()
        }
    }

    @Test
    fun onEvicted() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        coherentCache.setCache(key, cacheValue)
        val cacheKey = keyConverter.toStringKey(key)
        val event = CacheEvictedEvent(cacheName, cacheKey, "")
        coherentCache.onEvicted(event)
        clientSideCache[cacheKey].assert().isNull()
        distributedCache[cacheKey].assert().isEqualTo(value)
        coherentCache[key].assert().isEqualTo(value)
    }

    @Test
    fun onEvictedWhenLoop() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        coherentCache.setCache(key, cacheValue)
        val cacheKey = keyConverter.toStringKey(key)
        val event = CacheEvictedEvent(cacheName, cacheKey, clientId)
        coherentCache.onEvicted(event)
        clientSideCache[cacheKey].assert().isEqualTo(value)
        distributedCache[cacheKey].assert().isEqualTo(value)
        coherentCache[key].assert().isEqualTo(value)
    }

    @Test
    fun onEvictedWhenCacheNameNotMatch() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        coherentCache.setCache(key, cacheValue)
        val cacheKey = keyConverter.toStringKey(key)
        val event = CacheEvictedEvent(UUID.randomUUID().toString(), cacheKey, "")
        coherentCache.onEvicted(event)
        clientSideCache[cacheKey].assert().isEqualTo(value)
        distributedCache[cacheKey].assert().isEqualTo(value)
        coherentCache[key].assert().isEqualTo(value)
    }

    @ParameterizedTest
    @ValueSource(ints = [10, 100, 1000])
    fun `should prevent cache breakdown under high concurrency`(threadCount: Int) {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)

        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = ConcurrentLinkedQueue<Any?>()
        val callCount = AtomicInteger()

        val concurrentCache = DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                clientSideCache = clientSideCache,
                distributedCache = distributedCache,
                cacheSource = object : CacheSource<K, V> {
                    override fun loadCacheValue(key: K): CacheValue<V> {
                        callCount.incrementAndGet()
                        Thread.sleep(100) // 放大并发窗口
                        return cacheValue
                    }
                }
            )
        )

        try {
            repeat(threadCount) {
                executor.submit {
                    startLatch.await()
                    results.add(concurrentCache[key])
                    finishLatch.countDown()
                }
            }

            startLatch.countDown()
            val allFinished = finishLatch.await(5, TimeUnit.SECONDS)
            allFinished.assert()
                .withFailMessage { "finished=${threadCount - finishLatch.count}/$threadCount, callCount=${callCount.get()}" }
                .isTrue()
            results.all { it == value }.assert().isTrue()
            callCount.get().assert().isOne() // 核心断言
        } finally {
            executor.shutdownNow()
            // 共享 setup() 的 distributedCache/clientSideCache：close 会同时关闭外层 coherentCache 引用的分布式缓存，断言须在此之前完成
            concurrentCache.close()
        }
    }

    @Test
    fun closeUnregistersSubscriber() {
        val (key, value) = createCacheEntry()
        val cacheValue = DefaultCacheValue.forever(value)
        coherentCache.setCache(key, cacheValue)
        val cacheKey = keyConverter.toStringKey(key)

        coherentCache.close()

        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, "remote-client-id"))
        clientSideCache[cacheKey].assert().isEqualTo(value)
    }

    @Test
    fun closeIsIdempotentAndCacheStillUsable() {
        val (key, value) = createCacheEntry()
        CACHE_SOURCE_VALUE.set(DefaultCacheValue.forever(value))
        try {
            coherentCache.close()
            coherentCache.close()
            coherentCache[key].assert().isEqualTo(value)
        } finally {
            CACHE_SOURCE_VALUE.remove()
        }
    }

    @Test
    fun `eviction during in-flight load discards stale write-back`() {
        val (key, value) = createCacheEntry()
        val cacheKey = keyConverter.toStringKey(key)
        val staleValue = DefaultCacheValue.forever(value)
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<CacheValue<V>?>()

        val cache = DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                clientSideCache = clientSideCache,
                distributedCache = distributedCache,
                cacheSource = object : CacheSource<K, V> {
                    override fun loadCacheValue(key: K): CacheValue<V> {
                        loadStarted.countDown()
                        releaseLoad.await()
                        return staleValue
                    }
                }
            )
        )
        val loaderThread = Thread {
            result.set(cache.getCache(key))
            finished.countDown()
        }
        try {
            loaderThread.start()
            loadStarted.await(5, TimeUnit.SECONDS).assert().isTrue()

            // 模拟远端实例在回源在途时发布失效事件
            cache.onEvicted(CacheEvictedEvent(cacheName, cacheKey, "remote-client-id"))

            releaseLoad.countDown()
            finished.await(5, TimeUnit.SECONDS).assert().isTrue()
            loaderThread.join(5000)
            loaderThread.isAlive.assert().isFalse()

            result.get().assert().isEqualTo(staleValue)
            clientSideCache.getCache(cacheKey).assert().isNull()
            distributedCache.getCache(cacheKey).assert().isNull()
        } finally {
            cache.close()
        }
    }

    @Test
    fun `eviction during in-flight load discards missing-guard write-back`() {
        val (key, value) = createCacheEntry()
        val cacheKey = keyConverter.toStringKey(key)
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<CacheValue<V>?>()

        val cache = DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                clientSideCache = clientSideCache,
                distributedCache = distributedCache,
                cacheSource = object : CacheSource<K, V> {
                    override fun loadCacheValue(key: K): CacheValue<V>? {
                        loadStarted.countDown()
                        releaseLoad.await()
                        return null
                    }
                }
            )
        )
        val loaderThread = Thread {
            result.set(cache.getCache(key))
            finished.countDown()
        }
        try {
            loaderThread.start()
            loadStarted.await(5, TimeUnit.SECONDS).assert().isTrue()

            cache.onEvicted(CacheEvictedEvent(cacheName, cacheKey, "remote-client-id"))

            releaseLoad.countDown()
            finished.await(5, TimeUnit.SECONDS).assert().isTrue()
            loaderThread.join(5000)
            loaderThread.isAlive.assert().isFalse()

            result.get().assert().isNull()
            clientSideCache.getCache(cacheKey).assert().isNull()
            distributedCache.getCache(cacheKey).assert().isNull()
        } finally {
            cache.close()
        }
    }
}
