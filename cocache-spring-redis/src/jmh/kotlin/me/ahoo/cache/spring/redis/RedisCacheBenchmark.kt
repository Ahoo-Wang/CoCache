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

package me.ahoo.cache.spring.redis

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.client.CaffeineClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 命中路径基准：L2 命中、L1 读取、完整回源、写入。
 *
 * 修改 L2、时钟、Redis 读取或编排读路径时运行，并与上一版本对比（单线程与多线程）。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class RedisCacheBenchmark {
    private val run = UUID.randomUUID().toString()
    private val keyPrefix = "bench:$run:"
    private val missCounter = AtomicLong()
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var listenerContainer: RedisMessageListenerContainer
    private lateinit var distributedCache: DistributedCache<String>
    private lateinit var cache: CoherentCache<String, String>
    private lateinit var proxy: BenchCache
    private lateinit var mixed: List<CoherentCache<String, String>>
    private val batchKeys = (1..BATCH_SIZE).map { "${keyPrefix}batch-$it" }

    companion object {
        const val BATCH_SIZE = 100
    }

    @Setup
    fun setup() {
        connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration()).apply {
            afterPropertiesSet()
            start()
        }
        val redisTemplate = StringRedisTemplate(connectionFactory).apply { afterPropertiesSet() }
        listenerContainer = RedisMessageListenerContainer().apply {
            setConnectionFactory(this@RedisCacheBenchmark.connectionFactory)
            afterPropertiesSet()
            start()
        }
        distributedCache = RedisDistributedCache(redisTemplate, StringToStringCodecExecutor(redisTemplate))
        cache = DefaultCoherentCacheFactory(RedisCacheEvictedEventBus(redisTemplate, listenerContainer)).create(
            CoherentCacheConfiguration(
                cacheName = "bench-$run",
                clientId = UUID.randomUUID().toString(),
                keyConverter = ToStringKeyConverter(keyPrefix),
                distributedCache = distributedCache,
                clientSideCache = CaffeineClientSideCache.build(),
                cacheSource = { key -> CacheValue.of("value-$key", TtlAt.at(60)) },
                ttlPolicy = TtlPolicy(ttl = 60, ttlAmplitude = 10, missingTtl = 60),
            ),
        )
        cache["hot"] = "hot-value"
        // 三个配置不同的缓存（与 6.0 的 CoCacheBenchmark.l2HitMixed 对照）
        val factory = DefaultCoherentCacheFactory(RedisCacheEvictedEventBus(redisTemplate, listenerContainer))
        mixed = listOf(
            cache,
            factory.create(
                CoherentCacheConfiguration(
                    cacheName = "bench-access-$run",
                    clientId = UUID.randomUUID().toString(),
                    keyConverter = ToStringKeyConverter("${keyPrefix}access:"),
                    distributedCache = distributedCache,
                    clientSideCache = CaffeineClientSideCache.build(expireAfterAccess = java.time.Duration.ofMinutes(10)),
                    cacheSource = { key -> CacheValue.of("value-$key", TtlAt.at(60)) },
                    ttlPolicy = TtlPolicy(ttl = 600, ttlAmplitude = 10, missingTtl = 60),
                ),
            ),
            factory.create(
                CoherentCacheConfiguration(
                    cacheName = "bench-capacity-$run",
                    clientId = UUID.randomUUID().toString(),
                    keyConverter = ToStringKeyConverter("${keyPrefix}capacity:"),
                    distributedCache = distributedCache,
                    clientSideCache = CaffeineClientSideCache.build(maximumSize = 1_000, initialCapacity = 64),
                    cacheSource = { key -> CacheValue.of("value-$key", TtlAt.at(60)) },
                    ttlPolicy = TtlPolicy(ttl = 600, ttlAmplitude = 10, missingTtl = 60),
                ),
            ),
        )
        mixed.forEach {
            it["hot"] = "hot-value"
            it["hot"]
        }
        proxy = java.lang.reflect.Proxy.newProxyInstance(
            BenchCache::class.java.classLoader,
            arrayOf(BenchCache::class.java),
            me.ahoo.cache.proxy.CacheInvocationHandler(BenchCache::class.java, cache, me.ahoo.cache.annotation.coCacheMetadata<BenchCache>()),
        ) as BenchCache
        cache["l1"] = "l1-value"
        batchKeys.forEach { distributedCache.setCache(it, CacheValue.of("batch-value", TtlAt.at(600))) }
    }

    @TearDown
    fun tearDown() {
        mixed.drop(1).forEach { it.close() }
        cache.close()
        listenerContainer.destroy()
        connectionFactory.destroy()
    }

    /** L2 命中：仅进程内查找。 */
    @Benchmark
    fun l2Hit(): String? = cache["hot"]

    /** L2 命中，轮流访问三个配置不同的缓存。 */
    @Benchmark
    fun l2HitMixed(): Int {
        var length = 0
        for (each in mixed) {
            length += each["hot"]?.length ?: 0
        }
        return length
    }

    /** L2 命中，经过用户实际调用的 JDK 动态代理（`@CoCache` 接口）。 */
    @Benchmark
    fun l2HitViaProxy(): String? = proxy["hot"]

    /** 直接读 L1（不经 L2）：一次 Lua 往返取回 TTL 与值。 */
    @Benchmark
    fun l1Read(): CacheValue<String>? = distributedCache.getCache("${keyPrefix}l1")

    /** 逐个读 L1 的 [BATCH_SIZE] 个 key：[BATCH_SIZE] 次往返，与 [l1BatchRead] 对比。 */
    @Benchmark
    fun l1SequentialRead(): Int = batchKeys.count { distributedCache.getCache(it) != null }

    /** 批量读 L1 的 [BATCH_SIZE] 个 key：一次 pipeline 往返。 */
    @Benchmark
    fun l1BatchRead(): Int = distributedCache.getCaches(batchKeys).size

    /** 完整未命中：L2、L1 均未命中，回源并写回 L1 + L2。 */
    @Benchmark
    fun missLoad(): String? = cache["miss-${missCounter.incrementAndGet()}"]

    /** 写入：L1 + L2 + 失效广播。 */
    @Benchmark
    fun set() {
        cache["hot-write"] = "value"
    }

    /** 失效：淘汰 L2、L1 并广播（两次往返）。 */
    @Benchmark
    fun invalidate() {
        cache.evict("inv")
    }
}

/**
 * 代理基准使用的缓存接口。
 */
interface BenchCache : me.ahoo.cache.api.Cache<String, String>
