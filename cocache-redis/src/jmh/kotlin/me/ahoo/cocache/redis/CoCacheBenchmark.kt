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

import io.lettuce.core.RedisURI
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LocalPolicy
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 6.0 命中路径基准（RFC 0003 §7），方法名与 5.x 的 `RedisCacheBenchmark` 对应，便于逐项对比。
 *
 * 需要 localhost:6379 的 Redis：`./gradlew :cocache-redis:jmh --rerun -PjmhThreads=8 -PjmhIncludes=l2Hit`。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class CoCacheBenchmark {
    private val run = UUID.randomUUID().toString().substring(0, 8)
    private val missCounter = AtomicLong()
    private lateinit var backend: RedisBackend
    private lateinit var runtime: CacheRuntime
    private lateinit var cache: Cache<String, String>
    private lateinit var viaInterface: BenchCache
    private lateinit var mixed: List<Cache<String, String>>
    private lateinit var store: RemoteStore
    private lateinit var l1Key: String
    private lateinit var batchKeys: List<String>
    private lateinit var asyncCache: AsyncCache<String, String>
    private lateinit var asyncMixed: List<AsyncCache<String, String>>
    private lateinit var asyncStore: AsyncRemoteStore
    private lateinit var l1Hits: Cache<String, String>
    private lateinit var l1HitKeys: List<String>

    @Setup
    fun setup() {
        backend = RedisBackend.standalone(RedisURI.create("localhost", 6379))
        runtime = CacheRuntime.builder().backend(backend).build()
        val policy = CachePolicy(ttl = Duration.ofMinutes(10), ttlJitter = Duration.ofSeconds(10))
        cache = runtime.create(spec("bench-$run", policy))
        // 三个配置不同的缓存（Caffeine 会为不同配置生成不同的节点类）：验证命中路径在多态调用点下的表现
        mixed = listOf(
            cache,
            runtime.create(spec("bench-access-$run", policy.toBuilder().local(LocalPolicy(expireAfterAccess = Duration.ofMinutes(10))).build())),
            runtime.create(spec("bench-capacity-$run", policy.toBuilder().local(LocalPolicy(maximumSize = 1_000, initialCapacity = 64)).build())),
        )
        mixed.forEach {
            it.put("hot", "hot-value")
            it["hot"]
        }
        cache.put("l1", "l1-value")
        viaInterface = me.ahoo.cocache.core.proxy.CacheProxies.create(BenchCache::class.java, cache)
        store = backend.store("bench-$run")
        l1Key = "cocache:bench-$run:l1"
        val frame = InvalidationFrame.encode(NodeId.random(), emptyList())
        batchKeys = (1..BATCH_SIZE).map { "cocache:bench-$run:batch-$it" }
        batchKeys.forEach { store.put(it, Envelope.Scalar("batch-value".toByteArray()), Duration.ofMinutes(10), frame) }
        asyncCache = cache.async()
        asyncMixed = mixed.map { it.async() }
        asyncStore = store as AsyncRemoteStore
        // L1 命中经引擎：工作集远大于 L2 容量，几乎每次读取都不命中 L2、命中 L1（并填充 L2）
        l1Hits = runtime.create(spec("bench-l1-$run", policy.toBuilder().local(LocalPolicy(maximumSize = 16)).build()))
        l1HitKeys = (1..L1_HIT_KEYS).map { "l1-$it" }
        l1HitKeys.chunked(1_000).forEach { chunk -> l1Hits.putAll(chunk.associateWith { "l1-value" }) }
    }

    private fun spec(name: String, policy: CachePolicy) = cacheSpec<String, String>(name) {
        codec = StringCodec
        loader = CacheLoader { "value-$it" }
        this.policy = policy
    }

    @TearDown
    fun tearDown() {
        runtime.close()
        backend.close()
    }

    /** L2 命中：仅进程内查找（spec bean 注入的就是引擎本身，没有代理层）。 */
    @Benchmark
    fun l2Hit(): String? = cache["hot"]

    /** L2 命中，经过接口式声明的代理（`@CoCache interface`）。 */
    @Benchmark
    fun l2HitViaInterface(): String? = viaInterface["hot"]

    /** L2 命中，轮流访问三个配置不同的缓存。 */
    @Benchmark
    fun l2HitMixed(): Int {
        var length = 0
        for (each in mixed) {
            length += each["hot"]?.length ?: 0
        }
        return length
    }

    /** 异步视图的 L2 命中：同一次 Caffeine 查找，返回已完成的 future（RFC 0004 §5.8）。 */
    @Benchmark
    fun l2HitAsync(): CompletableFuture<String?> = asyncCache.get("hot")

    /** 异步视图的 L2 命中，轮流访问三个配置不同的缓存。 */
    @Benchmark
    fun l2HitAsyncMixed(): Int {
        var length = 0
        for (each in asyncMixed) {
            length += each.get("hot").getNow(null)?.length ?: 0
        }
        return length
    }

    /** 经引擎的 L1 命中（L2 不命中）：一次 EVALSHA + 解码 + 填充 L2。 */
    @Benchmark
    fun l1HitViaEngine(): String? = l1Hits[l1HitKeys[ThreadLocalRandom.current().nextInt(L1_HIT_KEYS)]]

    /** 经引擎的异步 L1 命中：同上，经异步视图（L1 结果在 Lettuce 的 I/O 线程上完成，续接切换到异步执行器）。 */
    @Benchmark
    fun l1HitViaEngineAsync(): String? = l1Hits.async().get(l1HitKeys[ThreadLocalRandom.current().nextInt(L1_HIT_KEYS)]).join()

    /** 直接读 L1：一次 EVALSHA（PTTL + GET + 头部解析）。 */
    @Benchmark
    fun l1Read(): RemoteRead = store.read(l1Key, null, null)

    /** 直接异步读 L1：与 [l1Read] 相同的命令，经 `readAsync` 等待。 */
    @Benchmark
    fun l1ReadAsync(): RemoteRead = asyncStore.readAsync(l1Key, null, null).join()

    /** 逐个读 L1 的 [BATCH_SIZE] 个 key：[BATCH_SIZE] 次往返，与 [l1BatchRead] 对比。 */
    @Benchmark
    fun l1SequentialRead(): Int = batchKeys.count { store.read(it, null, null) is RemoteRead.Hit }

    /** 批量读 L1 的 [BATCH_SIZE] 个 key：共享连接上并发发出，约一次往返。 */
    @Benchmark
    fun l1BatchRead(): Int = store.readAll(batchKeys, emptyMap()).size

    /** 完整未命中：读 L1 并取得 lease、回源、凭 lease 写回。 */
    @Benchmark
    fun missLoad(): String? = cache["miss-${missCounter.incrementAndGet()}"]

    /** 异步视图的完整未命中。 */
    @Benchmark
    fun missLoadAsync(): String? = asyncCache.get("miss-async-${missCounter.incrementAndGet()}").join()

    /** 写入：一次 Lua（覆盖写入 + PUBLISH），淘汰 L2。 */
    @Benchmark
    fun set() {
        cache.put("hot-write", "value")
    }

    /** 失效：一次 Lua（DEL + PUBLISH），淘汰 L2。 */
    @Benchmark
    fun invalidate() {
        cache.invalidate("inv")
    }

    /** 异步视图的写入。 */
    @Benchmark
    fun setAsync(): Void? = asyncCache.put("hot-write-async", "value").join()

    /** 异步视图的失效。 */
    @Benchmark
    fun invalidateAsync(): Void? = asyncCache.invalidate("inv-async").join()

    companion object {
        const val BATCH_SIZE = 100
        const val L1_HIT_KEYS = 10_000
    }
}

/**
 * 代理基准使用的缓存接口。
 */
interface BenchCache : Cache<String, String>
