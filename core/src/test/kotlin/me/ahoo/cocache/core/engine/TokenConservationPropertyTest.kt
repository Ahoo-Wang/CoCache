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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.random.Random

/**
 * 令牌守恒的性质测试（RFC 0005 §4.4、I-H，M2d）：取得的令牌最终被填充或释放，L1 命令与令牌步骤不可取消。
 *
 * 异步变体的协程全部在 [ManualExecutor] 上、由测试线程按种子决定的随机顺序推进；L1 命令由 [ScriptedBackend] 挂起，
 * 同样按随机顺序、以随机结局完成。结局：
 * - 正常完成；
 * - 失败且未生效；
 * - 生效但调用方看到超时：存储按 SPI 约定自己释放命令取得或交还的令牌（不变量“释放每个令牌”）。
 *
 * 命令完成的顺序是任意的（SPI 不保证同一连接上的顺序），所以“先发出释放、读取仍在途”会让读取随后取得的令牌泄漏。
 * 其间随机取消调用方的 future、随机关闭运行时（取消其中全部协程）。一半的种子用带版本的缓存（RFC 0005 §4.7）：数据源给出随机的版本，
 * 另有按版本写入与保留版本的失效（运维的按 key 删除，留下 `E`），因此读取会在 `E` 上取得 lease，填充会被版本规则拒绝（脚本清零令牌）
 * 或让版本前进。最后排空，断言：
 * - 每个调用都已完成（不挂起）；
 * - L1 授予引擎的每个令牌（lease、刷新令牌、回收取得的 lease），之后都以填充或释放交还过（交还命令失败也算：引擎已经交还，存储失败）。
 */
class TokenConservationPropertyTest {
    @Test
    fun tokensAreFilledOrReleasedUnderRandomFaultsAndCancellation() {
        val scenarios = List(SEEDS) { Scenario(it.toLong()).also { scenario -> scenario.run() } }
        // 带版本的种子确实走到了按版本前进与被版本规则拒绝的填充
        scenarios.sumOf { it.backend.advancedFills.get() }.assert().isGreaterThan(0)
        scenarios.sumOf { it.backend.versionRejectedFills.get() }.assert().isGreaterThan(0)
    }

    /**
     * 版本拒绝的填充交还令牌（脚本清零它）：lease 取在 `E` 上，数据源的版本更旧，lease 不留到 `lease.ttl`。
     */
    @Test
    fun aVersionRejectedFillOnEvictedContentReturnsItsLease() {
        val scenario = Scenario(seed = 0, nodes = 1, asyncSource = true, versioned = true)
        val cache = scenario.caches[0] as VersionedCache<String, String>
        val write = cache.async().putIfNewer("a", "a-written", 5)
        scenario.runAll()
        write.isDone.assert().isTrue()
        requireNotNull(scenario.runtimes[0].admin(NAME)).invalidateEncoded("a")
        val call = cache.async().get("a")
        scenario.backend.resolveOldest(Outcome.OK)
        scenario.runAll()
        scenario.versionedLoads.size.assert().isEqualTo(1)
        scenario.completeVersionedLoad(version = 3)
        scenario.drain(newestFirst = false)

        scenario.assertConserved()
        call.join().assert().isEqualTo("a-loaded")
        scenario.leased("a").assert().isFalse()
        scenario.runtimes[0].metrics(NAME)?.fillRejected.assert().isEqualTo(1L)
    }

    /**
     * 取得 lease 的读取仍在途时关闭运行时：读取照常完成（不可取消），之后的挂起点（等待数据源）取消协程，lease 被释放。
     * 旧的实现在关闭时立即放弃等待、即发即弃地释放：释放先于读取到达 L1 时，读取随后取得的 lease 要到 `lease.ttl` 才回收。
     */
    @Test
    fun aReadInFlightWhenTheRuntimeClosesStillReleasesItsLease() {
        val scenario = Scenario(seed = 0, nodes = 1, asyncSource = true, versioned = false)
        val call = scenario.caches[0].async().get("a")
        scenario.backend.pendingCount.assert().isEqualTo(1)
        scenario.runtimes[0].close()
        // 新发出的命令先完成：旧实现的释放会抢在在途的读取之前
        scenario.drain(newestFirst = true)

        scenario.assertConserved()
        call.isCompletedExceptionally.assert().isTrue()
        scenario.leased("a").assert().isFalse()
    }

    /**
     * 等待其它节点 lease 的重读为本调用取得了 lease，随即关闭运行时：重读不可取消地完成，lease 被释放。
     * 有观测器时的同一情形见 `AsyncLoadObserverTest`：观测在运行时的执行器上阻塞等待回源，单线程手动推进的执行器无法驱动它。
     */
    @Test
    fun aLeaseWaitRereadThatGrantsTheLeaseIsReleasedAfterClose() {
        val scenario =
            Scenario(
                seed = 0,
                nodes = 2,
                asyncSource = true,
                leaseWait = Duration.ofSeconds(10),
                versioned = false
            )
        // 节点 0 取得 lease，加载挂起
        val holder = scenario.caches[0].async().get("a")
        scenario.backend.resolveOldest(Outcome.OK)
        scenario.runAll()
        scenario.loads.size.assert().isEqualTo(1)
        // 节点 1 读到别人的 lease，进入等待；退避之后的重读挂起
        val waiter = scenario.caches[1].async().get("a")
        scenario.backend.resolveOldest(Outcome.OK)
        awaitCondition("lease-wait reread issued") {
            scenario.runAll()
            scenario.backend.pendingCount == 1
        }
        // 节点 0 的加载失败并释放 lease；然后关闭节点 1，再交付它的重读：重读取得 lease
        scenario.loads.removeFirst().second.completeExceptionally(IllegalStateException("source down"))
        scenario.runtimes[1].close()
        scenario.drain(newestFirst = true)

        scenario.assertConserved()
        holder.isCompletedExceptionally.assert().isTrue()
        waiter.isCompletedExceptionally.assert().isTrue()
        scenario.leased("a").assert().isFalse()
    }

    /**
     * 手动驱动的执行器：任务排队，由测试线程逐个执行。kotlinx 的延迟线程也可能提交任务（等待 lease 的退避），所以线程安全。
     */
    private class ManualExecutor : Executor {
        private val tasks = ArrayList<Runnable>()

        @Synchronized
        override fun execute(command: Runnable) {
            tasks += command
        }

        @Synchronized
        fun take(random: Random?): Runnable? = when {
            tasks.isEmpty() -> null
            random == null -> tasks.removeAt(0)
            else -> tasks.removeAt(random.nextInt(tasks.size))
        }
    }

    private class Scenario(
        private val seed: Long,
        nodes: Int = NODES,
        asyncSource: Boolean? = null,
        leaseWait: Duration = Duration.ZERO,
        versioned: Boolean? = null,
    ) {
        private val random = Random(seed)
        private val clock = ManualClock()
        private val memory = InMemoryBackend(clock)
        private val ledger = Ledger()
        val backend = ScriptedBackend(memory, ledger)
        private val executor = ManualExecutor()
        val loads = ArrayList<Pair<String, CompletableFuture<String?>>>()
        private val calls = ArrayList<Pair<String, CompletableFuture<*>>>()
        private val asyncSource = asyncSource ?: random.nextBoolean()
        private val versioned = versioned ?: random.nextBoolean()
        private var loaded = 0

        /**
         * 带版本的数据源的版本：随机，可能比 L1 中的旧（填充被拒绝）、相等或更新。
         */
        val versionedLoads = ArrayList<Pair<String, CompletableFuture<Versioned<String?>?>>>()

        val runtimes: List<CacheRuntime> = List(nodes) { node ->
            CacheRuntime.builder()
                .backend(backend.view(node))
                .clock(clock)
                .executor(executor)
                .apply {
                    // 任务与命令由关闭运行时的测试线程推进：关闭不等待，之后由 drain 排空
                    closeTimeout = Duration.ZERO
                }
                .build()
        }
        private val open = BooleanArray(nodes) { true }

        private val policy = CachePolicy(
            ttlJitter = Duration.ZERO,
            lease = LeasePolicy(wait = leaseWait),
            // 失效失败时抛出而不是转入后台重试：重试在别的线程上按真实时间执行，种子无法复现
            invalidateFailure = InvalidateFailureMode.THROW
        )

        val caches: List<ReadableCache<String, String>> = runtimes.map { runtime ->
            if (this@Scenario.versioned) runtime.create(versionedSpec()) else runtime.create(plainSpec())
        }

        private fun versionedSpec() = versionedCacheSpec<String, String>(NAME) {
            codec = StringCodec
            if (this@Scenario.asyncSource) {
                versionedAsyncLoader = AsyncCacheLoader { key ->
                    CompletableFuture<Versioned<String?>?>().also { versionedLoads += key to it }
                }
            } else {
                versionedLoader = CacheLoader { key -> Versioned("$key-${loaded++}", randomVersion()) }
            }
            policy = this@Scenario.policy
        }

        private fun plainSpec() = cacheSpec<String, String>(NAME) {
            codec = StringCodec
            if (this@Scenario.asyncSource) {
                asyncLoader = AsyncCacheLoader { key -> CompletableFuture<String?>().also { loads += key to it } }
            } else {
                loader = CacheLoader { key -> "$key-${loaded++}" }
            }
            policy = this@Scenario.policy
        }

        fun run() {
            repeat(STEPS) { step() }
            drain(newestFirst = false)
            assertConserved()
            runtimes.forEach { it.close() }
            drain(newestFirst = false)
            assertConserved()
        }

        @Suppress("MagicNumber")
        private fun step() {
            when (random.nextInt(100)) {
                in 0..24 -> startCall()
                in 25..54 -> executor.take(random)?.run()
                in 55..84 -> backend.resolveRandom(random, randomOutcome())
                in 85..94 -> resolveLoad()
                in 95..97 -> calls.randomOrNull(random)?.second?.cancel(true)
                else -> closeNode()
            }
        }

        @Suppress("MagicNumber")
        private fun randomOutcome(): Outcome = when (random.nextInt(100)) {
            in 0..69 -> Outcome.OK
            in 70..84 -> Outcome.FAIL
            else -> Outcome.TIMEOUT
        }

        @Suppress("MagicNumber")
        private fun startCall() {
            val node = random.nextInt(runtimes.size)
            if (!open[node]) {
                return
            }
            val cache = caches[node].async()
            val key = KEYS.random(random)
            if (versioned && random.nextInt(3) == 0) {
                startVersionedWrite(node, key)
                return
            }
            calls += when (random.nextInt(10)) {
                in 0..4 -> "get $key" to cache.get(key)
                5, 6 -> {
                    val keys = KEYS.shuffled(random).take(1 + random.nextInt(KEYS.size))
                    "getAll $keys" to cache.getAll(keys)
                }
                7, 8 -> put(node, key)
                else -> invalidate(node, key)
            }
        }

        /**
         * 覆盖写入：派生缓存的 `put`；带版本的缓存上没有它，按版本写入。
         */
        private fun put(node: Int, key: String): Pair<String, CompletableFuture<*>> = when (val cache = caches[node]) {
            is VersionedCache -> "putIfNewer $key" to cache.async().putIfNewer(key, "$key-put", randomVersion())
            else -> "put $key" to (cache as Cache).async().put(key, "$key-put-${random.nextInt(1000)}")
        }

        /**
         * 删除：派生缓存的 `invalidate`；带版本的缓存上没有它，运维清除（DEL + 广播，不重试）与它等价。
         */
        private fun invalidate(
            node: Int,
            key: String
        ): Pair<String, CompletableFuture<*>> = when (val cache = caches[node]) {
            is VersionedCache -> "purge $key" to cache.async().purge(key)
            else -> "invalidate $key" to (cache as Cache).async().invalidate(key)
        }

        /**
         * 带版本的缓存上的写入：按版本写入、墓碑，或保留版本的失效（留下 `E`；能力面上的操作就地完成，调用不挂起）。
         */
        private fun startVersionedWrite(node: Int, key: String) {
            val cache = (caches[node] as VersionedCache<String, String>).async()
            calls += when (random.nextInt(3)) {
                0 -> "putIfNewer $key" to cache.putIfNewer(key, "$key-v", randomVersion())
                1 -> "invalidateIfNewer $key" to cache.invalidateIfNewer(key, randomVersion())
                else -> "evict $key" to CompletableFuture.completedFuture(
                    runCatching { requireNotNull(runtimes[node].admin(NAME)).invalidateEncoded(key) }.getOrNull()
                )
            }
        }

        private fun randomVersion(): Long = 1L + random.nextInt(MAX_VERSION)

        /**
         * 以 [version] 完成最早的带版本加载。
         */
        fun completeVersionedLoad(version: Long) {
            val (key, future) = versionedLoads.removeAt(0)
            future.complete(Versioned("$key-loaded", version))
        }

        private fun resolveLoad() {
            if (versionedLoads.isNotEmpty() && (loads.isEmpty() || random.nextBoolean())) {
                val (key, future) = versionedLoads.removeAt(random.nextInt(versionedLoads.size))
                if (random.nextInt(5) == 0) {
                    future.completeExceptionally(IllegalStateException("source failed"))
                } else {
                    future.complete(Versioned("$key-${loaded++}", randomVersion()))
                }
                return
            }
            if (loads.isEmpty()) {
                return
            }
            val (key, future) = loads.removeAt(random.nextInt(loads.size))
            if (random.nextInt(5) == 0) {
                future.completeExceptionally(IllegalStateException("source failed"))
            } else {
                future.complete("$key-${loaded++}")
            }
        }

        private fun closeNode() {
            val node = random.nextInt(runtimes.size)
            if (open[node]) {
                open[node] = false
                runtimes[node].close()
            }
        }

        fun runAll() {
            while (true) {
                executor.take(null)?.run() ?: return
            }
        }

        /**
         * 排空：执行全部任务，正常完成全部命令与加载，直到没有任何待办。
         */
        fun drain(newestFirst: Boolean) {
            var rounds = 0
            while (true) {
                check(rounds++ < MAX_DRAIN_ROUNDS) { "seed[$seed]: no quiescence after $MAX_DRAIN_ROUNDS rounds." }
                val task = executor.take(if (newestFirst) null else random)
                if (task != null) {
                    task.run()
                    continue
                }
                val resolved = when {
                    newestFirst -> backend.resolveNewest(Outcome.OK)
                    else -> backend.resolveRandom(random, Outcome.OK)
                }
                if (resolved) {
                    continue
                }
                if (loads.isNotEmpty()) {
                    val (key, future) = loads.removeAt(0)
                    future.complete("$key-drained")
                    continue
                }
                if (versionedLoads.isNotEmpty()) {
                    val (key, future) = versionedLoads.removeAt(0)
                    future.complete(Versioned("$key-drained", randomVersion()))
                    continue
                }
                return
            }
        }

        fun assertConserved() {
            calls.filterNot { it.second.isDone }.map { it.first }.assert()
                .withFailMessage { "seed[$seed]: calls never completed." }
                .isEmpty()
            ledger.unreturned().assert()
                .withFailMessage { "seed[$seed]: tokens never filled or released: ${ledger.unreturned()}" }
                .isEmpty()
            // 协程的取消从不以 CancellationException 交给用户（运行时关闭交出 IllegalStateException）；调用方自己取消的除外
            calls.filter { it.second.isCompletedExceptionally && !it.second.isCancelled }.forEach { (name, future) ->
                val cause = (runCatching { future.get() }.exceptionOrNull() as? ExecutionException)?.cause
                (cause is CancellationException).assert()
                    .withFailMessage { "seed[$seed]: $name failed with $cause" }
                    .isFalse()
            }
        }

        fun leased(key: String): Boolean =
            Suspending.await {
                memory.store(
                    Keyspace("cocache:", NAME)
                ).read("cocache:$NAME:$key", null, null, null)
            } is L1Read.Leased
    }

    companion object {
        private const val NAME = "prop"
        private const val NODES = 2
        private const val SEEDS = 400
        private const val STEPS = 120
        private const val MAX_DRAIN_ROUNDS = 100_000
        private const val MAX_VERSION = 5
        private val KEYS = listOf("a", "b", "c")
    }
}
