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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.internal.Ticker
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.random.Random

/**
 * 确定性多节点仿真（RFC 0005 §4.10，M2e）：三个运行时共享一个挂起式内存后端（[ScriptedBackend]），全部协程运行在
 * `kotlinx-coroutines-test` 的单线程测试调度器上；等待 lease、`withTimeout`、截止时刻（[Ticker]）与缓存时钟都走虚拟时间。
 * 同一个种子总是同一条执行。
 *
 * 每个种子随机执行（一半的种子用带版本的缓存，RFC 0005 §4.7）：
 * - 读取（`get`、`getAll`）与写入（数据源提交新版本，再在某个节点上 `invalidate`；带版本的缓存另有按这个版本的 `putIfNewer`，
 *   与保留版本的失效 `evictKeepingVersion`：之后的读取在 `E` 上取得 lease，回源的填充按版本规则写入并广播，按写入处理）；
 * - 推进调度器、推进虚拟时间；
 * - 以任意顺序、随机结局完成 L1 命令（成功、未生效的失败、生效但超时）；
 * - 以任意顺序投递、重复或丢失失效事件（丢失之后补一个订阅重建）；
 * - 完成或失败数据源的加载（快照取于调用时或完成时）；
 * - 让一个节点崩溃（持有的令牌由 `lease.ttl` 回收）。
 *
 * 以数据源的提交历史为裁判：
 * - G2：读取开始时，节点对这个 key 的下限是它已经确认的最新版本：本节点的 `invalidate` 成功返回时，那次写入提交的版本；
 *   别的节点的失效事件投递时，那次失效在 L1 上生效时数据源的版本（按版本前进的填充广播的失效：填充写入的版本，回源之后数据源可能
 *   又提交了新版本，这一帧没有确认它，#978）；订阅重建投递时，至今已在 L1 上生效的全部失效。读取返回的版本不得低于下限。
 * - G1：排空之后推进超过 `ttl + lease.ttl + localMaxAge`，每个存活的节点都读到最新版本。
 * - 令牌守恒（[Ledger]）、每个存活节点的调用都已完成、协程的取消从不以 `CancellationException` 交给用户。
 *
 * 失败时消息带种子与最近的步骤；`./gradlew :cocache-core:test --tests "*MultiNodeSimulationTest" -PsimSeed=<n>`
 * 只运行这个种子并打印全部步骤。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiNodeSimulationTest {
    @Test
    fun invalidationsAreNeverLostAcrossNodesUnderFaults() {
        val only = System.getProperty("cocache.sim.seed")?.toLong()
        if (only != null) {
            Simulation(only, trace = true).run()
            return
        }
        val simulations = List(SEEDS) { Simulation(it.toLong()).also { simulation -> simulation.run() } }
        // 带版本的种子确实走到了 E 上的 lease 与按版本前进的填充（保留版本的失效之后的回源）
        simulations.sumOf { it.advancedFills }.assert().isGreaterThan(0)
    }

    /**
     * 同一个种子总是同一条执行：重放的前提。
     */
    @Test
    fun aSeedReplaysTheSameSteps() {
        val first = Simulation(REPLAY_SEED).run()
        Simulation(REPLAY_SEED).run().assert().isEqualTo(first)
    }

    /**
     * #978：带版本的填充（版本 4）在数据源提交版本 5 之后才生效并广播，那次写入的失效没有生效；这一帧曾被标成版本 5，
     * 之后读到 L1 中的版本 4 被误判为违反 G2。默认的种子范围不含它。
     */
    @Test
    fun aFillIsConfirmedAtTheVersionItWrote() {
        Simulation(FILL_AFTER_COMMIT_SEED).run()
    }

    private class Read(val node: Int, val keys: List<String>, val floors: Map<String, Long>, val future: CompletableFuture<*>)

    private class Write(val node: Int, val key: String, val version: Long, val future: CompletableFuture<*>)

    private class Load(val key: String, val snapshot: String?, val future: CompletableFuture<String?>)

    private class Simulation(private val seed: Long, private val trace: Boolean = false) {
        private val random = Random(seed)
        private val scheduler = TestCoroutineScheduler()
        private val dispatcher = StandardTestDispatcher(scheduler)
        private val clock = CacheClock { EPOCH + scheduler.currentTime }
        private val ticker = Ticker { scheduler.currentTime * NANOS_PER_MILLI }
        private val memory = InMemoryBackend(clock)
        private val ledger = Ledger()
        private val versions = KEYS.associateWith { 0L }.toMutableMap()

        /**
         * 每个 key 至今在 L1 上生效的失效所覆盖的最新版本（生效时数据源的版本；填充广播的失效是填充写入的版本）。
         */
        private val applied = KEYS.associateWith { 0L }.toMutableMap()
        val advancedFills: Int
            get() = backend.advancedFills.get()

        // 订阅者收到的是编码 key（S4），即这里的 key
        private val backend = ScriptedBackend(memory, ledger, scriptEvents = true) { keys, filled ->
            keys.associate { key ->
                val version = filled[key] ?: versions.getValue(key)
                applied[key] = maxOf(applied.getValue(key), version)
                key to version
            }
        }
        private val nodeIds = List(NODES) { node -> NodeId(ByteArray(NodeId.SIZE) { (node + 1).toByte() }) }
        private val asyncSource = random.nextBoolean()
        private val versioned = random.nextBoolean()
        private val loads = ArrayList<Load>()
        private val runtimes = List(NODES) { node ->
            CacheRuntime.builder()
                .backend(backend.view(node))
                .clock(clock)
                .nodeId(nodeIds[node])
                .executor(dispatcher.asExecutor())
                .apply {
                    ticker = this@Simulation.ticker
                    dispatcher = this@Simulation.dispatcher
                    // 虚拟时间由关闭运行时的测试线程推进：关闭不等待
                    closeTimeout = Duration.ZERO
                }
                .build()
        }
        private val policy = CachePolicy(
            ttl = TTL,
            ttlJitter = Duration.ZERO,
            localMaxAge = LOCAL_MAX_AGE,
            degradedLocalMaxAge = DEGRADED_LOCAL_MAX_AGE,
            lease = LeasePolicy(ttl = LEASE_TTL, wait = LEASE_WAIT),
            // 失效失败时抛出：后台重试在别的线程上按真实时间执行，种子无法复现
            invalidateFailure = InvalidateFailureMode.THROW
        )
        private val caches: List<ReadableCache<String, String>> = runtimes.map { runtime ->
            if (versioned) runtime.create(versionedSpec()) else runtime.create(plainSpec())
        }

        /**
         * 带版本的数据源：值的版本就是数据源的版本（快照的版本）。
         */
        private fun versionedSpec() = versionedCacheSpec<String, String>(NAME) {
            codec = StringCodec
            if (asyncSource) {
                versionedAsyncLoader = AsyncCacheLoader { key ->
                    val snapshot = if (random.nextBoolean()) valueOf(key) else null
                    CompletableFuture<String?>().also { loads += Load(key, snapshot, it) }.thenApply { value ->
                        value?.let { Versioned<String?>(it, it.substringAfter('@').toLong()) }
                    }
                }
            } else {
                versionedLoader = CacheLoader { key -> Versioned(valueOf(key), versions.getValue(key)) }
            }
            policy = this@Simulation.policy
        }

        private fun plainSpec() = cacheSpec<String, String>(NAME) {
            codec = StringCodec
            if (asyncSource) {
                asyncLoader = AsyncCacheLoader { key ->
                    // 快照取于调用时或完成时：两种都是数据源合法的行为
                    val snapshot = if (random.nextBoolean()) valueOf(key) else null
                    CompletableFuture<String?>().also { loads += Load(key, snapshot, it) }
                }
            } else {
                loader = CacheLoader { key -> valueOf(key) }
            }
            policy = this@Simulation.policy
        }
        private val alive = BooleanArray(NODES) { true }
        private val floors = Array(NODES) { KEYS.associateWith { 0L }.toMutableMap() }
        private val reads = ArrayList<Read>()
        private val writes = ArrayList<Write>()
        private val steps = ArrayList<String>()

        private fun valueOf(key: String): String = "$key@${versions.getValue(key)}"

        private fun log(step: String) {
            val line = "t=${scheduler.currentTime} $step"
            steps += line
            if (trace) {
                println("seed[$seed] $line")
            }
        }

        /**
         * 运行并返回执行过的步骤。
         */
        fun run(): List<String> {
            repeat(STEPS) {
                step()
                settle()
            }
            drain()
            // 排空之后：全部事件都已投递，每个存活节点读到的版本不低于它的下限（G2）
            readEverything()
            // G1：越过全部寿命之后，每个存活节点都读到最新版本
            scheduler.advanceTimeBy(HEAL.toMillis())
            log("advance to heal")
            drain()
            val healed = readEverything()
            healed.forEach { (node, key, value) ->
                check(
                    value == valueOf(key),
                    "G1: node[$node] read $value for $key after healing, latest is ${valueOf(key)}"
                )
            }
            check(ledger.unreturned().isEmpty(), "tokens never filled or released: ${ledger.unreturned()}")
            runtimes.forEach { it.close() }
            return steps
        }

        @Suppress("MagicNumber", "CyclomaticComplexMethod")
        private fun step() {
            when (random.nextInt(100)) {
                in 0..19 -> startRead()
                in 20..24 -> startReadAll()
                in 25..34 -> startWrite()
                in 35..49 -> {
                    log("run")
                    scheduler.runCurrent()
                }

                in 50..74 -> resolveCommand()
                in 75..86 -> deliverEvent()
                in 87..94 -> completeLoad()
                in 95..98 -> {
                    val millis = 1L + random.nextInt(MAX_ADVANCE_MILLIS)
                    log("advance ${millis}ms")
                    scheduler.advanceTimeBy(millis)
                }

                else -> crash()
            }
        }

        private fun liveNode(): Int? = (0 until NODES).filter { alive[it] }.randomOrNull(random)

        private fun startRead() {
            val node = liveNode() ?: return
            val key = KEYS.random(random)
            log("read node[$node] $key floor=${floors[node].getValue(key)}")
            reads += readOf(node, key)
        }

        private fun readOf(node: Int, key: String): Read =
            Read(node, listOf(key), mapOf(key to floors[node].getValue(key)), caches[node].async().get(key))

        private fun startReadAll() {
            val node = liveNode() ?: return
            val keys = KEYS.shuffled(random).take(1 + random.nextInt(KEYS.size))
            log("readAll node[$node] $keys")
            reads += Read(node, keys, keys.associateWith { floors[node].getValue(it) }, caches[node].async().getAll(keys))
        }

        @Suppress("MagicNumber")
        private fun startWrite() {
            val node = liveNode() ?: return
            val key = KEYS.random(random)
            val version = versions.getValue(key) + 1
            versions[key] = version
            val how = if (versioned) random.nextInt(3) else 0
            log("write node[$node] $key -> $version ${listOf("invalidate", "putIfNewer", "evict")[how]}")
            val cache = caches[node]
            val future: CompletableFuture<*> = when (how) {
                // 带版本的缓存上没有普通的失效：运维清除（DEL + 广播，不重试）与它等价
                0 -> if (cache is VersionedCache) cache.async().purge(key) else (cache as Cache).async().invalidate(key)
                1 -> (cache as VersionedCache).async().putIfNewer(key, valueOf(key), version)
                // 保留版本的失效：能力面上的操作就地完成，这次调用不挂起
                else -> runCatching { requireNotNull(runtimes[node].admin(NAME)).invalidateEncoded(key) }
                    .fold({ CompletableFuture.completedFuture(it) }, { CompletableFuture.failedFuture<Unit>(it) })
            }
            writes += Write(node, key, version, future)
        }

        @Suppress("MagicNumber")
        private fun resolveCommand() {
            val outcome = when (random.nextInt(100)) {
                in 0..74 -> Outcome.OK
                in 75..86 -> Outcome.FAIL
                else -> Outcome.TIMEOUT
            }
            if (backend.resolveRandom(random, outcome)) {
                log("resolve $outcome")
            }
        }

        @Suppress("MagicNumber")
        private fun deliverEvent() {
            val event = backend.takeEvent(random) ?: return
            when (random.nextInt(10)) {
                0 -> {
                    log("lose event to node[${event.node}] ${event.keys}")
                    backend.lose(event)
                }

                1 -> {
                    backend.duplicate(event)
                    deliver(event)
                }

                else -> deliver(event)
            }
        }

        private fun deliver(event: Event) {
            if (!backend.deliver(event)) {
                return
            }
            log(if (event.reset) "reset node[${event.node}]" else "deliver to node[${event.node}] ${event.tag}")
            val floor = floors[event.node]
            if (event.reset) {
                // 订阅重建清空 L2：之后的读取读 L1，L1 已反映至今生效的全部失效
                KEYS.forEach { floor[it] = maxOf(floor.getValue(it), applied.getValue(it)) }
            } else if (event.origin != nodeIds[event.node]) {
                @Suppress("UNCHECKED_CAST")
                (event.tag as Map<String, Long>).forEach { (key, version) -> floor[key] = maxOf(floor.getValue(key), version) }
            }
        }

        private fun completeLoad() {
            if (loads.isEmpty()) {
                return
            }
            val load = loads.removeAt(random.nextInt(loads.size))
            if (random.nextInt(LOAD_FAILURE_ONE_IN) == 0) {
                log("load ${load.key} fails")
                load.future.completeExceptionally(IllegalStateException("source failed"))
            } else {
                val value = load.snapshot ?: valueOf(load.key)
                log("load ${load.key} = $value")
                load.future.complete(value)
            }
        }

        private fun crash() {
            if (alive.count { it } <= MIN_ALIVE) {
                return
            }
            val node = liveNode() ?: return
            log("crash node[$node]")
            alive[node] = false
            ledger.excuse(nodeIds[node])
            backend.crash(node, random)
        }

        /**
         * 处理已完成的调用：写入成功即抬高写入节点的下限；读取对照读取开始时的下限检查。
         */
        private fun settle() {
            writes.removeAll { write ->
                if (!write.future.isDone) {
                    return@removeAll false
                }
                if (!write.future.isCompletedExceptionally && alive[write.node]) {
                    val floor = floors[write.node]
                    floor[write.key] = maxOf(floor.getValue(write.key), write.version)
                }
                true
            }
            reads.removeAll { read ->
                if (!read.future.isDone) {
                    return@removeAll false
                }
                checkRead(read)
                true
            }
        }

        private fun checkRead(read: Read) {
            val result = runCatching { read.future.get() }
            val error = (result.exceptionOrNull() as? ExecutionException)?.cause
            check(error !is CancellationException, "node[${read.node}] read ${read.keys} failed with $error")
            val values: Map<String, String?> = when (val value = result.getOrNull()) {
                null -> return
                is Map<*, *> -> read.keys.associateWith { value[it] as String? }
                else -> mapOf(read.keys.single() to value as String)
            }
            values.forEach { (key, value) ->
                val version = value?.substringAfter('@')?.toLong() ?: fail("node[${read.node}] read no value for $key")
                val floor = read.floors.getValue(key)
                check(
                    version >= floor,
                    "G2: node[${read.node}] read $value for $key, but it had confirmed version $floor"
                )
            }
        }

        /**
         * 排空：推进调度器，正常完成全部命令、投递全部事件、完成全部加载，触发全部定时器，直到没有任何待办。
         */
        private fun drain() {
            var rounds = 0
            while (true) {
                check(rounds++ < MAX_DRAIN_ROUNDS, "no quiescence after $MAX_DRAIN_ROUNDS rounds")
                scheduler.runCurrent()
                settle()
                when {
                    backend.resolveOldest(Outcome.OK) -> continue
                    backend.eventCount > 0 -> deliver(requireNotNull(backend.takeEvent(random)))
                    loads.isNotEmpty() -> loads.removeAt(0).let { it.future.complete(it.snapshot ?: valueOf(it.key)) }
                    else -> {
                        scheduler.advanceUntilIdle()
                        settle()
                        if (backend.pendingCount == 0 && backend.eventCount == 0 && loads.isEmpty()) {
                            break
                        }
                    }
                }
            }
            val stuck = reads.filter { alive[it.node] }.map { "read node[${it.node}] ${it.keys}" } +
                writes.filter { alive[it.node] }.map { "write node[${it.node}] ${it.key}" }
            check(stuck.isEmpty(), "calls never completed: $stuck")
        }

        /**
         * 每个存活的节点读全部 key，排空，返回读到的值（同时按下限检查）。
         */
        private fun readEverything(): List<Triple<Int, String, String?>> {
            val issued = (0 until NODES).filter { alive[it] }.flatMap { node ->
                KEYS.map { key -> Triple(node, key, readOf(node, key).also { reads += it }) }
            }
            log("read everything")
            drain()
            return issued.map { (node, key, read) ->
                Triple(node, key, runCatching { read.future.getNow(null) as String? }.getOrElse { "failed: $it" })
            }
        }

        private fun check(condition: Boolean, message: String) {
            if (!condition) {
                fail(message)
            }
        }

        private fun fail(message: String): Nothing =
            throw AssertionError("seed[$seed]: $message\nlast steps:\n${steps.takeLast(TRACE_TAIL).joinToString("\n")}")
    }

    companion object {
        private const val NAME = "sim"
        private const val NODES = 3
        private const val MIN_ALIVE = 2
        private const val SEEDS = 300
        private const val REPLAY_SEED = 42L
        private const val FILL_AFTER_COMMIT_SEED = 530L
        private const val STEPS = 150
        private const val MAX_DRAIN_ROUNDS = 100_000
        private const val MAX_ADVANCE_MILLIS = 1500
        private const val LOAD_FAILURE_ONE_IN = 10
        private const val TRACE_TAIL = 40
        private const val EPOCH = 1_700_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private val KEYS = listOf("a", "b", "c")
        private val TTL: Duration = Duration.ofSeconds(6)
        private val LOCAL_MAX_AGE: Duration = Duration.ofSeconds(2)
        private val DEGRADED_LOCAL_MAX_AGE: Duration = Duration.ofSeconds(1)
        private val LEASE_TTL: Duration = Duration.ofSeconds(2)
        private val LEASE_WAIT: Duration = Duration.ofMillis(200)

        /**
         * 越过它之后 L1 与 L2 的全部副本都已到期。
         */
        private val HEAL: Duration = TTL + LEASE_TTL + LOCAL_MAX_AGE + Duration.ofSeconds(1)
    }
}
