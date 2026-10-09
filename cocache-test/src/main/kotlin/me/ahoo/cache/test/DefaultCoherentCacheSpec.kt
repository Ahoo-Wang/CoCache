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
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.cache.api.converter.KeyConverter
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
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
import java.util.concurrent.atomic.AtomicReference

/**
 * [me.ahoo.cache.consistency.DefaultCoherentCache] 的兼容性规格：缓存语义 + 一致性不变量。
 *
 * 竞态用例一律以 latch 编排（不使用 sleep），并通过 `finished` latch 确保后台线程真实完成。
 */
abstract class DefaultCoherentCacheSpec<K, V> : CacheSpec<K, V>() {
    protected lateinit var keyConverter: KeyConverter<K>
    protected lateinit var clientSideCache: ClientSideCache<V>
    protected lateinit var distributedCache: DistributedCache<V>
    protected lateinit var cacheEvictedEventBus: CacheEvictedEventBus
    protected lateinit var coherentCache: CoherentCache<K, V>
    protected lateinit var cacheName: String
    protected val clientId: String = UUID.randomUUID().toString()
    protected val ttlPolicy = TtlPolicy(ttl = 60, ttlAmplitude = 0, missingTtl = 10)

    /**
     * 默认数据源：委托给可替换的 [loader]，并统计调用次数。
     */
    protected val sourceCalls = AtomicInteger()

    @Volatile
    protected var loader: (K) -> CacheValue<V>? = { null }

    private val cacheSource = CacheSource<K, V> { key ->
        sourceCalls.incrementAndGet()
        loader(key)
    }

    protected abstract fun createKeyConverter(): KeyConverter<K>
    protected abstract fun createClientSideCache(): ClientSideCache<V>
    protected abstract fun createDistributedCache(): DistributedCache<V>
    protected abstract fun createCacheEvictedEventBus(): CacheEvictedEventBus
    protected abstract fun createCacheName(): String

    @BeforeEach
    override fun setup() {
        keyConverter = createKeyConverter()
        clientSideCache = createClientSideCache()
        distributedCache = createDistributedCache()
        cacheEvictedEventBus = createCacheEvictedEventBus()
        cacheName = createCacheName()
        coherentCache = createCoherentCache()
        super.setup()
    }

    @AfterEach
    open fun tearDown() {
        coherentCache.close()
    }

    protected fun createCoherentCache(
        distributedCache: DistributedCache<V> = this.distributedCache,
        cacheSource: CacheSource<K, V> = this.cacheSource
    ): CoherentCache<K, V> {
        return DefaultCoherentCacheFactory(cacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                clientSideCache = clientSideCache,
                distributedCache = distributedCache,
                cacheSource = cacheSource,
                ttlPolicy = ttlPolicy
            )
        )
    }

    override fun createCache(): Cache<K, V> {
        return coherentCache
    }

    private val CoherentCache<K, V>.subscriber: CacheEvictedSubscriber
        get() = this as CacheEvictedSubscriber

    private fun K.cacheKey(): String = keyConverter.toStringKey(this)

    @Test
    fun getFromCacheSourcePopulatesBothLevels() {
        val (key, value) = createCacheEntry()
        loader = { CacheValue.forever(value) }

        coherentCache[key].assert().isEqualTo(value)

        clientSideCache.getCache(key.cacheKey())?.value.assert().isEqualTo(value)
        distributedCache.getCache(key.cacheKey())?.value.assert().isEqualTo(value)
        coherentCache[key].assert().isEqualTo(value)
        sourceCalls.get().assert().isOne()
    }

    @Test
    fun getFromCacheSourceNullWritesMissingWithMissingTtl() {
        val (key, _) = createCacheEntry()

        val actual = coherentCache.getCache(key)

        actual.assert().isNotNull()
        requireNotNull(actual).isMissing.assert().isTrue()
        actual.ttlAt.assert().isLessThanOrEqualTo(TtlAt.at(ttlPolicy.missingTtl))
        requireNotNull(distributedCache.getCache(key.cacheKey())).isMissing.assert().isTrue()
        coherentCache[key].assert().isNull()
        sourceCalls.get().assert().isOne()
    }

    @Test
    fun getExpiredValueFromCacheSourceDoesNotPopulateCaches() {
        val (key, value) = createCacheEntry()
        loader = { CacheValue.of(value, TtlAt.at(-5)) }

        requireNotNull(coherentCache.getCache(key)).isExpired.assert().isTrue()

        clientSideCache.getCache(key.cacheKey()).assert().isNull()
        distributedCache.getCache(key.cacheKey()).assert().isNull()
    }

    @Test
    fun getFromDistributedCacheFillsClientSide() {
        val (key, value) = createCacheEntry()
        distributedCache.setCache(key.cacheKey(), CacheValue.forever(value))

        coherentCache[key].assert().isEqualTo(value)

        clientSideCache.getCache(key.cacheKey())?.value.assert().isEqualTo(value)
        sourceCalls.get().assert().isZero()
    }

    @Test
    fun setPublishesEvictedEvent() {
        val (key, value) = createCacheEntry()
        val received = awaitEvent(key.cacheKey()) {
            coherentCache[key] = value
        }
        received.publisherId.assert().isEqualTo(clientId)
    }

    @Test
    fun evictPublishesEvictedEvent() {
        val (key, _) = createCacheEntry()
        val received = awaitEvent(key.cacheKey()) {
            coherentCache.evict(key)
        }
        received.publisherId.assert().isEqualTo(clientId)
    }

    private fun awaitEvent(cacheKey: String, action: () -> Unit): CacheEvictedEvent {
        val received = AtomicReference<CacheEvictedEvent>()
        val latch = CountDownLatch(1)
        val subscribed = CountDownLatch(1)
        val listener = object : CacheEvictedSubscriber {
            override val cacheName: String = this@DefaultCoherentCacheSpec.cacheName
            override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
                if (cacheEvictedEvent.key == cacheKey) {
                    received.set(cacheEvictedEvent)
                    latch.countDown()
                }
            }

            override fun onReset() {
                subscribed.countDown()
            }
        }
        cacheEvictedEventBus.register(listener)
        try {
            // 订阅建立时通道回调 onReset，之后发布的事件才保证可达
            subscribed.await(5, TimeUnit.SECONDS).assert().isTrue()
            action()
            latch.await(5, TimeUnit.SECONDS).assert().isTrue()
            return received.get()
        } finally {
            cacheEvictedEventBus.unregister(listener)
        }
    }

    @Test
    fun onEvictedFromRemoteEvictsClientSideOnly() {
        val (key, value) = createCacheEntry()
        coherentCache[key] = value

        coherentCache.subscriber.onEvicted(CacheEvictedEvent(cacheName, key.cacheKey(), "remote"))

        clientSideCache.getCache(key.cacheKey()).assert().isNull()
        distributedCache.getCache(key.cacheKey())?.value.assert().isEqualTo(value)
        coherentCache[key].assert().isEqualTo(value)
    }

    @Test
    fun onEvictedIgnoresSelfPublished() {
        val (key, value) = createCacheEntry()
        coherentCache[key] = value

        coherentCache.subscriber.onEvicted(CacheEvictedEvent(cacheName, key.cacheKey(), clientId))

        clientSideCache.getCache(key.cacheKey())?.value.assert().isEqualTo(value)
    }

    @Test
    fun onEvictedIgnoresOtherCacheName() {
        val (key, value) = createCacheEntry()
        coherentCache[key] = value

        coherentCache.subscriber.onEvicted(CacheEvictedEvent(UUID.randomUUID().toString(), key.cacheKey(), "remote"))

        clientSideCache.getCache(key.cacheKey())?.value.assert().isEqualTo(value)
    }

    @Test
    fun onResetClearsClientSide() {
        val (key, value) = createCacheEntry()
        coherentCache[key] = value

        coherentCache.subscriber.onReset()

        clientSideCache.size.assert().isZero()
        coherentCache[key].assert().isEqualTo(value)
    }

    @ParameterizedTest
    @ValueSource(ints = [10, 100])
    fun `concurrent misses load the source once`(threadCount: Int) {
        val (key, value) = createCacheEntry()
        val release = CountDownLatch(1)
        loader = {
            release.await(5, TimeUnit.SECONDS)
            CacheValue.forever(value)
        }
        val results = ConcurrentLinkedQueue<V?>()
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val futures = (1..threadCount).map {
                executor.submit { results.add(coherentCache[key]) }
            }
            // 等待 leader 进入回源后放行
            awaitCondition { sourceCalls.get() == 1 }
            release.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        results.size.assert().isEqualTo(threadCount)
        results.all { it == value }.assert().isTrue()
        sourceCalls.get().assert().isOne()
    }

    @Test
    fun `source failure propagates the original exception to concurrent callers`() {
        val (key, _) = createCacheEntry()
        val release = CountDownLatch(1)
        val failure = IllegalStateException("source down")
        loader = {
            release.await(5, TimeUnit.SECONDS)
            throw failure
        }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = (1..2).map {
            Thread {
                runCatching { coherentCache.getCache(key) }.onFailure { errors.add(it) }
            }.also { it.start() }
        }
        awaitCondition { sourceCalls.get() == 1 }
        // 第二个线程或作为 follower 共享 leader 的异常，或在 leader 结束后自行回源得到同一异常
        release.countDown()
        threads.forEach { it.join(5000) }

        errors.size.assert().isEqualTo(2)
        errors.all { it === failure }.assert().isTrue()
        distributedCache.getCache(key.cacheKey()).assert().isNull()
        clientSideCache.getCache(key.cacheKey()).assert().isNull()
    }

    @Test
    fun `recursive load of the same key fails fast`() {
        val (key, _) = createCacheEntry()
        loader = { coherentCache.getCache(it) }

        val error = runCatching { coherentCache.getCache(key) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `local evict during in-flight load discards stale write-back`() {
        assertInFlightLoadDiscarded(staleValue = { CacheValue.forever(it) }) { key ->
            coherentCache.evict(key)
        }
    }

    @Test
    fun `local set during in-flight load keeps the newer value`() {
        val (key, value) = createCacheEntry()
        val (_, newerValue) = createCacheEntry()
        runInFlightLoad(key, CacheValue.forever(value)) {
            coherentCache[key] = newerValue
        }
        distributedCache.getCache(key.cacheKey())?.value.assert().isEqualTo(newerValue)
        coherentCache[key].assert().isEqualTo(newerValue)
    }

    @Test
    fun `remote eviction during in-flight load discards stale write-back`() {
        assertInFlightLoadDiscarded(staleValue = { CacheValue.forever(it) }) { key ->
            coherentCache.subscriber.onEvicted(CacheEvictedEvent(cacheName, key.cacheKey(), "remote"))
        }
    }

    @Test
    fun `remote eviction during in-flight load discards missing write-back`() {
        assertInFlightLoadDiscarded(staleValue = { null }) { key ->
            coherentCache.subscriber.onEvicted(CacheEvictedEvent(cacheName, key.cacheKey(), "remote"))
        }
    }

    @Test
    fun `reset during in-flight load discards stale write-back`() {
        assertInFlightLoadDiscarded(staleValue = { CacheValue.forever(it) }) { _ ->
            coherentCache.subscriber.onReset()
        }
    }

    private fun assertInFlightLoadDiscarded(staleValue: (V) -> CacheValue<V>?, invalidate: (K) -> Unit) {
        val (key, value) = createCacheEntry()
        runInFlightLoad(key, staleValue(value)) {
            invalidate(key)
        }
        clientSideCache.getCache(key.cacheKey()).assert().isNull()
        distributedCache.getCache(key.cacheKey()).assert().isNull()
    }

    /**
     * 在回源进行中执行 [duringLoad]，随后放行回源并等待其完成。
     */
    private fun runInFlightLoad(key: K, loaded: CacheValue<V>?, duringLoad: () -> Unit) {
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val finished = CountDownLatch(1)
        loader = {
            loadStarted.countDown()
            releaseLoad.await(5, TimeUnit.SECONDS)
            loaded
        }
        val loaderThread = Thread {
            coherentCache.getCache(key)
            finished.countDown()
        }
        loaderThread.start()
        loadStarted.await(5, TimeUnit.SECONDS).assert().isTrue()
        duringLoad()
        releaseLoad.countDown()
        finished.await(5, TimeUnit.SECONDS).assert().isTrue()
    }

    /**
     * 读己之写：线程先更新数据源并 evict，再 get —— 即使存在开始于 evict 之前的在途回源，也不得读到 evict 之前的旧值。
     */
    @Test
    fun `get after own evict never shares a load that started before the evict`() {
        val (key, value) = createCacheEntry()
        val (_, newValue) = createCacheEntry()
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val calls = AtomicInteger()
        loader = {
            if (calls.incrementAndGet() == 1) {
                // 第一次回源读到 evict 之前的旧数据，并阻塞到测试放行
                loadStarted.countDown()
                releaseLoad.await(5, TimeUnit.SECONDS)
                CacheValue.forever(value)
            } else {
                CacheValue.forever(newValue)
            }
        }
        val leaderFinished = CountDownLatch(1)
        Thread {
            coherentCache.getCache(key)
            leaderFinished.countDown()
        }.start()
        loadStarted.await(5, TimeUnit.SECONDS).assert().isTrue()

        val result = AtomicReference<V?>()
        val evicted = CountDownLatch(1)
        val readerFinished = CountDownLatch(1)
        val reader = Thread {
            // 数据源已更新为 newValue（由第二次回源体现）
            coherentCache.evict(key)
            evicted.countDown()
            result.set(coherentCache[key])
            readerFinished.countDown()
        }
        reader.start()
        // evict 完成后 reader 只做本地 L2 查找，随后的 WAITING 只可能是加入了在途回源
        evicted.await(5, TimeUnit.SECONDS).assert().isTrue()
        awaitCondition { reader.state == Thread.State.WAITING }
        releaseLoad.countDown()

        leaderFinished.await(5, TimeUnit.SECONDS).assert().isTrue()
        readerFinished.await(5, TimeUnit.SECONDS).assert().isTrue()
        result.get().assert().isEqualTo(newValue)
        calls.get().assert().isEqualTo(2)
    }

    @Test
    fun `remote eviction during distributed read discards client side fill`() {
        val (key, value) = createCacheEntry()
        distributedCache.setCache(key.cacheKey(), CacheValue.forever(value))
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val blockingDistributedCache = object : DistributedCache<V> by distributedCache {
            override fun getCache(key: String): CacheValue<V>? {
                val cacheValue = distributedCache.getCache(key)
                readStarted.countDown()
                releaseRead.await(5, TimeUnit.SECONDS)
                return cacheValue
            }
        }
        val cache = createCoherentCache(distributedCache = blockingDistributedCache)
        try {
            val readerThread = Thread {
                cache.getCache(key)
                finished.countDown()
            }
            readerThread.start()
            readStarted.await(5, TimeUnit.SECONDS).assert().isTrue()
            cache.subscriber.onEvicted(CacheEvictedEvent(cacheName, key.cacheKey(), "remote"))
            releaseRead.countDown()
            finished.await(5, TimeUnit.SECONDS).assert().isTrue()

            clientSideCache.getCache(key.cacheKey()).assert().isNull()
        } finally {
            cacheEvictedEventBus.unregister(cache.subscriber)
        }
    }

    @Test
    fun closeUnregistersSubscriber() {
        val (key, value) = createCacheEntry()
        coherentCache[key] = value
        coherentCache.close()

        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, key.cacheKey(), "remote"))

        clientSideCache.getCache(key.cacheKey())?.value.assert().isEqualTo(value)
    }

    @Test
    fun closeIsIdempotentAndCacheStillUsable() {
        val (key, value) = createCacheEntry()
        loader = { CacheValue.forever(value) }
        coherentCache.close()
        coherentCache.close()
        coherentCache[key].assert().isEqualTo(value)
    }

    protected fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met within 5 seconds." }
            Thread.onSpinWait()
        }
    }
}
