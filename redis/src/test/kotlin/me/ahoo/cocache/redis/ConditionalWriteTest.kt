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

import io.lettuce.core.ScriptOutputType
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.redis.migration.Redis5x
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * 按摘要的条件读写在真实 Redis 上的线上行为（standalone、Cluster、Valkey）：
 * - 摘要未变的条件读取只回复一个结果码，不传输正文；
 * - 读不懂的内容（5.x 的 String / 原生 Set / Hash、更高格式版本、本协议的集合）默认失败，key 与到期时间原样保留、不广播；
 * - 写入按 `put` 的规则设置到期时间，冲突不改变到期时间、不广播；
 * - 多个运行时上的并发“读—改—条件写”不丢失更新。
 */
class ConditionalWriteTest {
    private val run = UUID.randomUUID().toString()
    private val backend = RedisTestSupport.backend()
    private val runtime = CacheRuntime.builder().backend(backend).build()
    private val frame = InvalidationFrame.encode(NodeId.random(), emptyList())

    @AfterEach
    fun close() {
        runtime.close()
        backend.close()
        RedisTestSupport.deleteKeys("*$run*")
    }

    private fun cache(
        name: String,
        on: CacheRuntime = runtime,
        ttl: Duration? = null
    ): Cache<String, String> = on.create(
        cacheSpec(name) {
            codec = StringCodec
            policy {
                this.ttl = ttl
                ttlJitter = Duration.ZERO
            }
        }
    )

    private fun digestOf(value: String) = Digest.of(Envelope.Scalar(value.toByteArray()))

    /**
     * 直接执行条件读取脚本，看 Redis 的回复本身：摘要未变时回复只有结果码与格式版本（两个整数），正文不上线。
     */
    @Test
    fun unchangedReadRepliesWithoutTheBody() {
        val name = "body-$run"
        val key = "cocache:$name:doc"
        val document = "x".repeat(1 shl 20)
        val cache = cache(name)
        cache.put("doc", document)
        val source = String(RedisScripts.READ_IF_CHANGED.source, Charsets.UTF_8)
        fun reply(known: String): List<Any?> = RedisTestSupport.withCommands {
            it.eval<List<Any?>>(source, ScriptOutputType.MULTI, arrayOf(key), known)
        }

        reply(
            digestOf(document).toString()
        ).assert().containsExactly(RedisScripts.COND_UNCHANGED, RedisScripts.FORMAT.toLong())
        val changed = reply(digestOf("older").toString())
        changed.size.assert().isEqualTo(3)
        (changed[2] as String).length.assert().isEqualTo(document.length)

        cache.getIfChanged("doc", digestOf(document)).assert().isEqualTo(ConditionalGet.Unchanged)
        cache.async().getIfChanged("doc", digestOf(document)).join().assert().isEqualTo(ConditionalGet.Unchanged)
        requireNotNull(runtime.metrics(name)).conditionalUnchanged.assert().isEqualTo(2L)
    }

    private fun assertForeignRead(error: Throwable?, storageKey: String, format: Int) {
        error.assert().isInstanceOf(CacheReadException::class.java)
        (error as CacheReadException).operation.assert().isEqualTo("getIfChanged")
        val cause = requireNotNull(error.cause as? ForeignFormatException)
        cause.storageKey.assert().isEqualTo(storageKey)
        cause.format.assert().isEqualTo(format)
        cause.newer.assert().isEqualTo(format > RedisScripts.FORMAT)
    }

    private fun assertForeignWrite(error: Throwable?, storageKey: String, format: Int) {
        error.assert().isInstanceOf(CacheWriteException::class.java)
        (error as CacheWriteException).operation.assert().isEqualTo("putIfMatch")
        val cause = requireNotNull(error.cause as? ForeignFormatException)
        cause.storageKey.assert().isEqualTo(storageKey)
        cause.format.assert().isEqualTo(format)
    }

    private fun writeNewerValue(key: String, ttlMillis: Long) {
        // 36 字节头：magic、格式版本 2、状态 V、保留、24 字节令牌、8 字节签发时间；载荷 "v"
        RedisTestSupport.withCommands {
            it.eval<String>(
                "return redis.call('SET', KEYS[1], '\\192\\2V\\0' .. string.rep('\\0', 32) .. 'v', 'PX', ARGV[1])",
                ScriptOutputType.STATUS,
                arrayOf(key),
                ttlMillis.toString()
            )
        }
    }

    /**
     * 5.x 遗留与更高版本写入的内容：默认（`FAIL`）条件读写都失败（同步与异步），内容、类型与到期时间原样保留，不广播。
     */
    @Test
    fun foreignAndNewerContentFailsAndIsKept() {
        val name = "foreign-$run"
        fun key(id: String) = "cocache:$name:$id"
        Redis5x.string(key("string"), "{\"id\":1}", ttlSeconds = 3600)
        Redis5x.set(key("set"), listOf("a", "b"), ttlSeconds = 3600)
        Redis5x.hash(key("hash"), mapOf("+a" to ""), ttlSeconds = 3600)
        Redis5x.hash(key("protocol-set"), mapOf("!f" to "1", "+a" to ""), ttlSeconds = 3600)
        writeNewerValue(key("newer"), 3_600_000)
        val expected = mapOf("string" to 0, "set" to 0, "hash" to 0, "protocol-set" to 1, "newer" to 2)
        val cache = cache(name)
        val frames = CopyOnWriteArrayList<ByteArray>()
        RedisTestSupport.subscribeFrames(
            RedisInvalidationChannel.channelOf(Keyspace("cocache:", name))
        ) { frames.add(it) }.use {
            for ((id, format) in expected) {
                assertForeignRead(runCatching { cache.getIfChanged(id, null) }.exceptionOrNull(), key(id), format)
                assertForeignRead(
                    runCatching { cache.async().getIfChanged(id, null).get() }.exceptionOrNull()?.cause,
                    key(id),
                    format
                )
                assertForeignWrite(runCatching { cache.putIfMatch(id, null, "v") }.exceptionOrNull(), key(id), format)
                val asyncError = runCatching {
                    cache.async().putIfMatch(
                        id,
                        digestOf("v"),
                        "v"
                    ).get()
                }.exceptionOrNull()
                asyncError.assert().isInstanceOf(ExecutionException::class.java)
                assertForeignWrite(asyncError?.cause, key(id), format)
            }
            cache.putIfMatch("marker", null, "m").assert().isInstanceOf(ConditionalPut.Written::class.java)
            awaitCondition("marker broadcast") { frames.isNotEmpty() }
            frames.map { InvalidationFrame.decode(it).keys }.assert().containsExactly(listOf(key("marker")))
        }
        Redis5x.get(key("string")).assert().isEqualTo("{\"id\":1}")
        Redis5x.members(key("set")).assert().containsExactlyInAnyOrder("a", "b")
        Redis5x.fields(key("hash")).assert().isEqualTo(mapOf("+a" to ""))
        Redis5x.fields(key("protocol-set")).assert().isEqualTo(mapOf("!f" to "1", "+a" to ""))
        Redis5x.type(key("newer")).assert().isEqualTo("string")
        expected.keys.forEach { Redis5x.pttl(key(it)).assert().isBetween(3_000_000L, 3_600_000L) }
        val metrics = requireNotNull(runtime.metrics(name))
        metrics.unknownFormats.assert().isEqualTo(4L * expected.size)
        metrics.writeFailures.assert().isEqualTo(2L * expected.size)
    }

    /**
     * `foreignFormat` 只决定读取。有数据源、显式 `RECLAIM` 的缓存上，条件读写遇到不是本协议写入的内容（格式 0，这里是 5.x 的原生 Set）
     * 同样失败，内容、类型与到期时间原样保留：从不视为没有值，`expected = null` 的写入不会整体覆盖它。普通读取照常条件回收后回源。
     */
    @Test
    fun reclaimDoesNotTreatForeignContentAsAbsent() {
        val name = "reclaim-$run"
        val key = "cocache:$name:k"
        Redis5x.set(key, listOf("a"), ttlSeconds = 3600)
        val cache = runtime.create(
            cacheSpec<String, String>(name) {
                codec = StringCodec
                loader = CacheLoader { "loaded" }
                policy { foreignFormat = ForeignFormatMode.RECLAIM }
            }
        )
        assertForeignRead(runCatching { cache.getIfChanged("k", null) }.exceptionOrNull(), key, 0)
        assertForeignWrite(runCatching { cache.putIfMatch("k", null, "v") }.exceptionOrNull(), key, 0)
        assertForeignWrite(runCatching { cache.putIfMatch("k", digestOf("a"), "v") }.exceptionOrNull(), key, 0)
        Redis5x.type(key).assert().isEqualTo("set")
        Redis5x.members(key).assert().containsExactly("a")
        Redis5x.pttl(key).assert().isBetween(3_000_000L, 3_600_000L)
        requireNotNull(runtime.metrics(name)).reclaims.assert().isEqualTo(0L)

        cache["k"].assert().isEqualTo("loaded")
        requireNotNull(runtime.metrics(name)).reclaims.assert().isEqualTo(1L)
        cache.getIfChanged("k", null).assert().isEqualTo(ConditionalGet.Present("loaded", digestOf("loaded")))
    }

    /**
     * 到期时间：写入按 `put` 的规则（显式时长、策略寿命、`ttl = null` 时清除旧的到期时间）；冲突与条件读取不改变它。
     */
    @Test
    fun ttlFollowsPutAndIsKeptOnConflict() {
        val name = "ttl-$run"
        val key = "cocache:$name:k"
        val cache = cache(name)
        cache.putIfMatch("k", null, "v1", Duration.ofSeconds(30))
        Redis5x.pttl(key).assert().isBetween(25_000L, 30_000L)
        RedisTestSupport.shiftTtl(key, Duration.ofSeconds(10))
        val shifted = Redis5x.pttl(key)
        shifted.assert().isBetween(15_000L, 20_000L)
        cache.putIfMatch(
            "k",
            digestOf("stale"),
            "x",
            Duration.ofDays(1)
        ).assert().isEqualTo(ConditionalPut.Conflict(digestOf("v1")))
        cache.getIfChanged("k", digestOf("v1")).assert().isEqualTo(ConditionalGet.Unchanged)
        cache.getIfChanged("k", null).assert().isInstanceOf(ConditionalGet.Present::class.java)
        Redis5x.pttl(key).assert().isBetween(1L, shifted)

        // 策略 ttl = null：写入清除到期时间（与 put 相同）
        cache.putIfMatch("k", digestOf("v1"), "v2").assert().isInstanceOf(ConditionalPut.Written::class.java)
        Redis5x.pttl(key).assert().isEqualTo(-1L)

        val expiring = cache("expiring-$run", ttl = Duration.ofMinutes(5))
        expiring.putIfMatch("k", null, "v").assert().isInstanceOf(ConditionalPut.Written::class.java)
        Redis5x.pttl("cocache:expiring-$run:k").assert().isBetween(290_000L, 300_000L)
    }

    /**
     * 写入读不懂的内容：不是本协议写入的 String、`!f` 为 `0` 的 Hash、更高格式版本的值、状态字节不认识的本协议 String。
     */
    private fun writeUnreadableShapes(key: (String) -> String) {
        Redis5x.string(key("foreign"), "plain")
        Redis5x.hash(key("zero"), mapOf("!f" to "0"))
        writeNewerValue(key("newer"), 60_000)
        // 本协议的 String 头，但状态字节不认识
        RedisTestSupport.withCommands {
            it.eval<String>(
                "return redis.call('SET', KEYS[1], '\\192\\1X\\0' .. string.rep('\\0', 32))",
                ScriptOutputType.STATUS,
                key("unknown-state")
            )
        }
    }

    /**
     * 存储层的判定（一次脚本内原子完成）：没有值的三种状态、本协议的值、读不懂的内容，读与写各自的结果。
     */
    @Test
    fun storeClassifiesEveryShape() = Suspending.await<Unit> {
        val namespace = "store-$run"
        val store = backend.store(Keyspace("cocache:", namespace))
        fun key(id: String) = "cocache:$namespace:$id"
        store.put(key("negative"), Envelope.Absent, null, frame)
        store.read(key("leased"), LeaseRequest(Token(NodeId.random(), 1), Duration.ofMinutes(1)), null, null)
        store.put(key("value"), Envelope.Scalar("v".toByteArray()), null, frame)
        store.setSurface.addMembers(key("set"), listOf("m".toByteArray()), null, frame)
        writeUnreadableShapes(::key)

        val conditional = store.conditionalSurface
        val w = Envelope.Scalar("w".toByteArray())
        listOf("absent", "negative", "leased").forEach {
            conditional.readIfChanged(key(it), null, null).assert().isEqualTo(L1ConditionalRead.Absent)
        }
        val changed = conditional.readIfChanged(key("value"), digestOf("other"), null) as L1ConditionalRead.Changed
        changed.envelope.assert().isEqualTo(Envelope.Scalar("v".toByteArray()))
        changed.format.assert().isEqualTo(RedisScripts.FORMAT)
        conditional.readIfChanged(key("value"), digestOf("v"), null)
            .assert().isEqualTo(L1ConditionalRead.Unchanged(RedisScripts.FORMAT))
        mapOf("set" to 1, "foreign" to 0, "zero" to 0, "newer" to 2, "unknown-state" to 0).forEach { (id, format) ->
            conditional.readIfChanged(key(id), null, null).assert().isEqualTo(L1ConditionalRead.Unreadable(format))
            conditional.putIfMatch(
                key(id),
                null,
                w,
                null,
                frame
            ).assert().isEqualTo(L1ConditionalWrite.Unreadable(format))
            conditional.putIfMatch(key(id), digestOf("plain"), w, null, frame)
                .assert().isEqualTo(L1ConditionalWrite.Unreadable(format))
        }
        Redis5x.get(key("foreign")).assert().isEqualTo("plain")
        Redis5x.fields(key("zero")).assert().isEqualTo(mapOf("!f" to "0"))

        conditional.putIfMatch(
            key("value"),
            null,
            w,
            null,
            frame
        ).assert().isEqualTo(L1ConditionalWrite.Conflict(digestOf("v")))
        conditional.putIfMatch(
            key("value"),
            digestOf("v"),
            w,
            null,
            frame
        ).assert().isEqualTo(L1ConditionalWrite.Written)
        conditional.putIfMatch(key("leased"), null, w, null, frame).assert().isEqualTo(L1ConditionalWrite.Written)
        conditional.putIfMatch(key("negative"), null, w, null, frame).assert().isEqualTo(L1ConditionalWrite.Written)
        conditional.readIfChanged(key("negative"), digestOf("w"), null)
            .assert().isEqualTo(L1ConditionalRead.Unchanged(RedisScripts.FORMAT))
        Redis5x.type(key("newer")).assert().isEqualTo("string")
    }

    /**
     * 三个运行时（各自的连接与 L2）上 12 个线程同时对一个计数做“读—改—条件写”，一半经异步视图：每次增量恰好生效一次。
     * 第一轮强制竞争：全部线程读到同一个值后才同时写，恰好一个成功，因此冲突至少有 `threads - 1` 次。
     */
    @Test
    fun concurrentReadModifyWriteAcrossRuntimesLosesNoUpdate() {
        val name = "counter-$run"
        val backends = (1..2).map { RedisTestSupport.backend() }
        val others = backends.map { CacheRuntime.builder().backend(it).build() }
        try {
            val caches = listOf(cache(name)) + others.map { cache(name, on = it) }
            val threads = 12
            val increments = 20
            val firstRead = CyclicBarrier(threads)
            val workers = (0 until threads).map { index ->
                val cache = caches[index % caches.size]
                fork("cas-$index") {
                    val async = index % 2 == 1
                    var conflicts = increment(cache, async, firstRead)
                    repeat(increments - 1) { conflicts += increment(cache, async) }
                    conflicts
                }
            }
            val conflicts = workers.sumOf { it.join() }
            conflicts.assert().isGreaterThanOrEqualTo(threads - 1)
            caches[1].getIfChanged("n", null)
                .assert().isEqualTo(
                    ConditionalGet.Present(
                        (threads * increments).toString(),
                        digestOf((threads * increments).toString())
                    )
                )
            val counted = (listOf(runtime) + others).sumOf { requireNotNull(it.metrics(name)).conditionalConflicts }
            counted.assert().isEqualTo(conflicts.toLong())
        } finally {
            others.forEach { it.close() }
            backends.forEach { it.close() }
        }
    }

    /**
     * 一次增量；返回期间遇到的冲突次数。[barrier] 非空时第一次读取之后在它上面等齐全部线程再写。
     */
    private fun increment(cache: Cache<String, String>, async: Boolean, barrier: CyclicBarrier? = null): Int {
        var conflicts = 0
        var gate = barrier
        while (true) {
            val current = if (async) cache.async().getIfChanged("n", null).join() else cache.getIfChanged("n", null)
            val (value, digest) = (current as? ConditionalGet.Present)?.let { it.value.toInt() to it.digest } ?: (0 to null)
            gate?.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            gate = null
            val next = (value + 1).toString()
            val result = if (async) {
                cache.async().putIfMatch(
                    "n",
                    digest,
                    next
                ).join()
            } else {
                cache.putIfMatch("n", digest, next)
            }
            if (result is ConditionalPut.Written) {
                return conflicts
            }
            conflicts++
        }
    }

    private companion object {
        const val BARRIER_TIMEOUT_SECONDS = 30L
    }
}
