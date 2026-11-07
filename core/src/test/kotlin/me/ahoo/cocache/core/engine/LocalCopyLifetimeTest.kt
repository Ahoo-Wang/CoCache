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
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.suspending.SuspendingAccess
import me.ahoo.cocache.test.CacheAccess
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * L2 副本不活过它所对应的那份 L1 值（#877）：L1 报告的剩余寿命从读取（或写回）发出之前的时刻算起，
 * 解码、写回的回复、刷新读取的解码与等待 lease 消耗的时间都从中扣除；读完时 L1 已经到期的副本不写入 L2。
 *
 * 时间由 [ManualClock] 模拟：慢的 Codec 在解码时推进它（编解码结果照常正确，不改写 SPI 的返回值），慢的写回回复在 L1 执行之后推进它。
 * L1 寿命 10 s，L2 限龄 1 min（不起作用），每个用例的写入都在 t = 0。
 */
class LocalCopyLifetimeTest {
    private val clock = ManualClock(0)
    private val memory = InMemoryBackend(clock)
    private val runtimes = mutableListOf<CacheRuntime>()
    private val database = ConcurrentHashMap<String, String>()

    /**
     * 每次解码推进时钟的时长（集合按元素计）。
     */
    @Volatile
    private var decodeCost: Duration = Duration.ZERO

    /**
     * 写回（`fill` / `fillAll`）在 L1 执行之后、回复之前推进时钟的时长。
     */
    @Volatile
    private var fillReplyDelay: Duration = Duration.ZERO

    /**
     * 为 `true` 时，下一次申请 lease 的读取报告别人持有 lease，并推进时钟 [LEASE_WAIT]（等待期间流逝的时间）。
     */
    private val leasedOnce = AtomicBoolean(false)

    /**
     * 为 `true` 时刷新读取取不到刷新令牌（令牌在别的节点手里）：只从 L1 重新填充 L2。
     */
    @Volatile
    private var tokenElsewhere: Boolean = false

    private val codec = object : Codec<String> by StringCodec {
        override fun decode(bytes: ByteArray): String {
            clock.advance(decodeCost)
            return StringCodec.decode(bytes)
        }
    }

    /**
     * 只给派生缓存用（不转发条件、版本与集合能力面）。
     */
    private val timedBackend = object : CoherenceBackend by memory {
        override fun store(keyspace: Keyspace): L1Store = object : ForwardingL1Store(memory.store(keyspace)) {
            override suspend fun read(
                key: String,
                lease: LeaseRequest?,
                refresh: RefreshRequest?,
                timeout: Duration?
            ): L1Read {
                if (lease != null && leasedOnce.compareAndSet(true, false)) {
                    clock.advance(LEASE_WAIT)
                    return L1Read.Leased(Duration.ofSeconds(5))
                }
                return super.read(key, lease, refresh.takeUnless { tokenElsewhere }, timeout)
            }

            override suspend fun fill(fill: Fill): L1Fill = super.fill(fill).also { clock.advance(fillReplyDelay) }

            override suspend fun fillAll(fills: List<Fill>): List<L1Fill> =
                super.fillAll(fills).also { clock.advance(fillReplyDelay) }
        }
    }

    @AfterEach
    fun close() {
        runtimes.forEach { it.close() }
        memory.close()
    }

    private fun runtime(backend: CoherenceBackend = memory): CacheRuntime = CacheRuntime.builder()
        .backend(backend)
        .clock(clock)
        .refreshExecutor { it.run() }
        .build()
        .also { runtimes += it }

    private fun store(runtime: CacheRuntime, access: CacheAccess): Store<String, String> = access.store(
        runtime.create(
            storeSpec<String, String>(NAME) {
                codec = this@LocalCopyLifetimeTest.codec
                policy {
                    ttl = TTL
                    ttlJitter = Duration.ZERO
                    localMaxAge = LOCAL_MAX_AGE
                }
            }
        )
    )

    private fun setStore(runtime: CacheRuntime, access: CacheAccess): SetStore<String, String> = access.setStore(
        runtime.create(
            setStoreSpec<String, String>(NAME) {
                elementCodec = codec
                policy {
                    ttl = TTL
                    ttlJitter = Duration.ZERO
                    localMaxAge = LOCAL_MAX_AGE
                }
            }
        )
    )

    private fun cache(
        runtime: CacheRuntime,
        access: CacheAccess = CacheAccess.SYNC,
        refreshWindow: Duration? = null
    ): Cache<String, String> = access.cache(
        runtime.create(
            cacheSpec<String, String>(NAME) {
                codec = this@LocalCopyLifetimeTest.codec
                loader = CacheLoader { database[it] }
                policy {
                    ttl = TTL
                    ttlJitter = Duration.ZERO
                    localMaxAge = LOCAL_MAX_AGE
                    this.refreshWindow = refreshWindow
                }
                access.source(this)
            }
        )
    )

    /**
     * 运维查看同样读取并解码 L1 中的值：查看期间解码不耗时，不推进时钟。
     */
    private fun inspect(runtime: CacheRuntime, key: String, name: String = NAME): KeyInspection {
        val cost = decodeCost
        decodeCost = Duration.ZERO
        try {
            return requireNotNull(runtime.admin(name)).inspect(key)
        } finally {
            decodeCost = cost
        }
    }

    private fun localExpiry(runtime: CacheRuntime, key: String): Long {
        val local = requireNotNull(inspect(runtime, key).local) { "no local copy of $key" }
        return local.expiresAt.toEpochMilli()
    }

    /**
     * 推进到 t = [TTL]（L1 中 t = 0 写入的值到期），确认 L1 已为 MISS。
     */
    private fun untilL1Expiry(runtime: CacheRuntime, vararg keys: String) {
        decodeCost = Duration.ZERO
        clock.advance(Duration.ofMillis(TTL.toMillis() - clock.nowMillis()))
        keys.forEach { inspect(runtime, it).remote.state.assert().isEqualTo(KeyInspection.RemoteState.MISS) }
    }

    @ParameterizedTest
    @MethodSource("accesses")
    fun aSlowDecodeOfAnL1HitDoesNotOutliveL1(access: CacheAccess) {
        val runtime = runtime()
        val store = store(runtime, access)
        store.put("k", "value", TTL)
        decodeCost = Duration.ofSeconds(4)

        store["k"].assert().isEqualTo("value")
        clock.nowMillis().assert().isEqualTo(4_000L)
        localExpiry(runtime, "k").assert().isEqualTo(TTL.toMillis())

        untilL1Expiry(runtime, "k")
        val local = inspect(runtime, "k").local
        store["k"].assert().withFailMessage { "L1 expired at ${TTL.toMillis()} ms: $local" }.isNull()
    }

    /**
     * 批量读取：一次 L1 读取的全部 key 共用读取之前的时刻，前面 key 的解码时间不累加到后面的 key 上（之前 b 活到 t = 18 s）。
     */
    @ParameterizedTest
    @MethodSource("accesses")
    fun aSlowBatchDecodeDoesNotOutliveL1(access: CacheAccess) {
        val runtime = runtime()
        val store = store(runtime, access)
        store.put("a", "va", TTL)
        store.put("b", "vb", TTL)
        decodeCost = Duration.ofSeconds(4)

        store.getAll(listOf("a", "b")).assert().isEqualTo(mapOf("a" to "va", "b" to "vb"))
        clock.nowMillis().assert().isEqualTo(8_000L)
        localExpiry(runtime, "a").assert().isEqualTo(TTL.toMillis())
        localExpiry(runtime, "b").assert().isEqualTo(TTL.toMillis())

        untilL1Expiry(runtime, "a", "b")
        store.getAll(listOf("a", "b")).assert().isEmpty()
    }

    /**
     * 查询结果报告的到期时刻同样是 L1 的到期时刻（单 key 与批量）。
     */
    @ParameterizedTest
    @MethodSource("accesses")
    fun aSlowDecodeReportsTheL1ExpiryInLookups(access: CacheAccess) {
        val runtime = runtime()
        val store = store(runtime, access)
        listOf("a", "b", "c").forEach { store.put(it, "v$it", TTL) }
        decodeCost = Duration.ofSeconds(2)
        val l1Expiry = Instant.ofEpochMilli(TTL.toMillis())

        store.lookup("a").assert().isEqualTo(Lookup.Present("va", l1Expiry))
        store.lookupAll(listOf("b", "c")).assert().isEqualTo(
            mapOf("b" to Lookup.Present("vb", l1Expiry), "c" to Lookup.Present("vc", l1Expiry))
        )
    }

    /**
     * 对照：解码不耗时，L2 副本恰好活到 L1 到期，在此之前一直命中 L2。
     */
    @ParameterizedTest
    @MethodSource("accesses")
    fun aFastDecodeKeepsTheL1Expiry(access: CacheAccess) {
        val runtime = runtime()
        val store = store(runtime, access)
        store.put("k", "value", TTL)

        store["k"].assert().isEqualTo("value")
        localExpiry(runtime, "k").assert().isEqualTo(TTL.toMillis())
        clock.advance(TTL.minusMillis(1))
        val remoteHits = requireNotNull(runtime.metrics(NAME)).remoteHits
        store["k"].assert().isEqualTo("value")
        requireNotNull(runtime.metrics(NAME)).remoteHits.assert().isEqualTo(remoteHits)

        untilL1Expiry(runtime, "k")
        store["k"].assert().isNull()
    }

    @ParameterizedTest
    @MethodSource("accesses")
    fun aSlowSetElementDecodeDoesNotOutliveL1(access: CacheAccess) {
        val runtime = runtime()
        val store = setStore(runtime, access)
        store.put("k", setOf("a", "b"), TTL)
        store.put("j", setOf("c"), TTL)
        decodeCost = Duration.ofSeconds(1)

        store["k"].assert().isEqualTo(setOf("a", "b"))
        clock.nowMillis().assert().isEqualTo(2_000L)
        localExpiry(runtime, "k").assert().isEqualTo(TTL.toMillis())
        requireNotNull(runtime.admin(NAME)).clearLocal()
        // 批量：t = 2 s 读取，三个元素解码到 t = 5 s；之前 k 活到 12 s、j 活到 13 s
        store.getAll(listOf("k", "j")).assert().isEqualTo(mapOf("k" to setOf("a", "b"), "j" to setOf("c")))
        clock.nowMillis().assert().isEqualTo(5_000L)
        localExpiry(runtime, "k").assert().isEqualTo(TTL.toMillis())
        localExpiry(runtime, "j").assert().isEqualTo(TTL.toMillis())

        untilL1Expiry(runtime, "k", "j")
        val local = inspect(runtime, "k").local
        store["k"].assert().withFailMessage { "L1 expired at ${TTL.toMillis()} ms: $local" }.isEmpty()
        store.getAll(listOf("k", "j")).assert().isEmpty()
    }

    /**
     * 解码比 L1 剩余寿命还长：读到的值照常返回（读取发出时它有效），但不写入 L2，下一次读取回到 L1。
     */
    @Test
    fun aDecodeThatOutlastsL1DoesNotFillL2() {
        val runtime = runtime()
        val store = store(runtime, CacheAccess.SYNC)
        val sets = runtime.create(
            setStoreSpec<String, String>("$NAME-set") {
                elementCodec = codec
            }
        )
        decodeCost = TTL.plusSeconds(2)
        store.put("k", "value", TTL)
        store["k"].assert().isEqualTo("value")
        inspect(runtime, "k").local.assert().isNull()
        // 集合在 t = 12 s 写入，解码到 t = 24 s（L1 在 t = 22 s 到期）
        sets.put("k", setOf("a"), TTL)
        sets["k"].assert().isEqualTo(setOf("a"))
        inspect(runtime, "k", "$NAME-set").local.assert().isNull()

        decodeCost = Duration.ZERO
        store["k"].assert().isNull()
        sets["k"].assert().isEmpty()
    }

    /**
     * 回源的写回：L1 在执行 `FILL` 时开始计算寿命，回复晚到 4 s 时 L2 副本的寿命同样从发出写回之前算起（单 key 与批量）。
     */
    @ParameterizedTest
    @MethodSource("accesses")
    fun aSlowFillReplyDoesNotOutliveL1(access: CacheAccess) {
        listOf("k", "a", "b").forEach { database[it] = "v$it" }
        val runtime = runtime(timedBackend)
        val cache = cache(runtime, access)
        fillReplyDelay = Duration.ofSeconds(4)

        cache["k"].assert().isEqualTo("vk")
        localExpiry(runtime, "k").assert().isEqualTo(TTL.toMillis())
        cache.getAll(listOf("a", "b")).assert().isEqualTo(mapOf("a" to "va", "b" to "vb"))
        // 批量写回在 t = 4 s 执行
        localExpiry(runtime, "a").assert().isEqualTo(4_000L + TTL.toMillis())
        localExpiry(runtime, "b").assert().isEqualTo(4_000L + TTL.toMillis())

        fillReplyDelay = Duration.ZERO
        untilL1Expiry(runtime, "k")
        val metrics = requireNotNull(runtime.metrics(NAME))
        val loads = metrics.loads
        cache["k"].assert().isEqualTo("vk")
        metrics.loads.assert().isEqualTo(loads + 1)
    }

    /**
     * 没取得刷新令牌时从 L1 重新填充 L2：刷新读取在 t = 6 s 读到剩余 4 s，解码 3 s，副本仍在 t = 10 s 到期（之前为 t = 13 s）。
     */
    @Test
    fun aSlowRefillDecodeDoesNotOutliveL1() {
        database["k"] = "v"
        val runtime = runtime(timedBackend)
        val cache = cache(runtime, refreshWindow = Duration.ofSeconds(5))
        cache["k"].assert().isEqualTo("v")
        localExpiry(runtime, "k").assert().isEqualTo(TTL.toMillis())

        tokenElsewhere = true
        clock.advance(Duration.ofSeconds(6))
        decodeCost = Duration.ofSeconds(3)
        cache["k"].assert().isEqualTo("v")
        val metrics = requireNotNull(runtime.metrics(NAME))
        awaitCondition("the refill") { metrics.refreshSkipped == 1L }
        localExpiry(runtime, "k").assert().isEqualTo(TTL.toMillis())

        untilL1Expiry(runtime, "k")
        cache["k"].assert().isEqualTo("v")
        metrics.loads.assert().isEqualTo(2)
    }

    /**
     * 等待别人的 lease 之后的命中：寿命从找到值的那次重读之前算起。第一次读取在 t = 0 遇到 lease，等待 2 s 后重读到剩余 8 s，
     * 解码 1 s：副本在 t = 10 s 到期（从第一次读取算起是 8 s，从解码之后算起是 11 s）。
     */
    @Test
    fun aHitAfterALeaseWaitIsTimedFromTheRereadThatFoundIt() {
        database["k"] = "v"
        val runtime = runtime(timedBackend)
        val cache = cache(runtime)
        cache["k"].assert().isEqualTo("v")
        requireNotNull(runtime.admin(NAME)).clearLocal()

        leasedOnce.set(true)
        decodeCost = Duration.ofSeconds(1)
        cache["k"].assert().isEqualTo("v")
        leasedOnce.get().assert().isFalse()
        clock.nowMillis().assert().isEqualTo(LEASE_WAIT.toMillis() + 1_000L)
        localExpiry(runtime, "k").assert().isEqualTo(TTL.toMillis())
        requireNotNull(runtime.metrics(NAME)).loads.assert().isEqualTo(1)
    }

    companion object {
        private const val NAME = "lifetime"
        private val TTL: Duration = Duration.ofSeconds(10)
        private val LOCAL_MAX_AGE: Duration = Duration.ofMinutes(1)
        private val LEASE_WAIT: Duration = Duration.ofSeconds(2)

        @JvmStatic
        fun accesses(): List<CacheAccess> = listOf(CacheAccess.SYNC, CacheAccess.ASYNC, SuspendingAccess)
    }
}
