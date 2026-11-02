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
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.RemoteUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.function.Supplier
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
 * 其间随机取消调用方的 future、随机关闭运行时（取消其中全部协程）。最后排空，断言：
 * - 每个调用都已完成（不挂起）；
 * - L1 授予引擎的每个令牌（lease、刷新令牌、回收取得的 lease），之后都以填充或释放交还过（交还命令失败也算：引擎已经交还，存储失败）。
 */
class TokenConservationPropertyTest {
    @Test
    fun tokensAreFilledOrReleasedUnderRandomFaultsAndCancellation() {
        repeat(SEEDS) { Scenario(it.toLong()).run() }
    }

    /**
     * 取得 lease 的读取仍在途时关闭运行时：读取照常完成（不可取消），之后的挂起点（等待数据源）取消协程，lease 被释放。
     * 旧的实现在关闭时立即放弃等待、即发即弃地释放：释放先于读取到达 L1 时，读取随后取得的 lease 要到 `lease.ttl` 才回收。
     */
    @Test
    fun aReadInFlightWhenTheRuntimeClosesStillReleasesItsLease() {
        val scenario = Scenario(seed = 0, nodes = 1, asyncSource = true)
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
     * 等待其它节点 lease 的重读为本调用取得了 lease，随即关闭运行时：有观测器时等待 lease 在运行时作用域的另一个协程中进行，
     * 调用方也不可取消地等待它，lease 被释放。
     */
    @Test
    fun aLeaseWaitRereadThatGrantsTheLeaseIsReleasedAfterClose() {
        val scenario =
            Scenario(seed = 0, nodes = 2, asyncSource = true, observed = true, leaseWait = Duration.ofSeconds(10))
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

    private enum class Outcome { OK, FAIL, TIMEOUT }

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

    /**
     * 令牌账本：只有在授予之后生效的交还才算数（释放先于授予到达 L1 时什么也不做）。
     */
    private class Ledger {
        private val granted = LinkedHashMap<Token, String>()
        private val returned = HashSet<Token>()

        @Synchronized
        fun grant(token: Token, what: String) {
            granted[token] = what
        }

        @Synchronized
        fun giveBack(token: Token) {
            if (granted.containsKey(token)) {
                returned += token
            }
        }

        @Synchronized
        fun unreturned(): Collection<String> = granted.filterKeys { it !in returned }.values
    }

    /**
     * 挂起的 L1 命令：[apply] 在 L1 上执行（记录授予与交还），[gives] 是命令交还的令牌，[holds] 是超时时存储自己释放的令牌。
     */
    private class Command<T>(
        val name: String,
        private val ledger: Ledger,
        private val store: RemoteStore,
        private val gives: List<Token>,
        private val holds: () -> List<Pair<String, Token>>,
        private val apply: () -> T,
    ) {
        val future = CompletableFuture<T>()

        fun resolve(outcome: Outcome) {
            when (outcome) {
                Outcome.OK -> future.complete(apply())
                Outcome.FAIL -> {
                    gives.forEach(ledger::giveBack)
                    future.completeExceptionally(RemoteUnavailableException(name))
                }

                Outcome.TIMEOUT -> {
                    apply()
                    holds().forEach { (key, token) ->
                        store.release(key, token)
                        ledger.giveBack(token)
                    }
                    future.completeExceptionally(TimeoutException("$name timed out."))
                }
            }
        }
    }

    /**
     * 挂起每条异步 L1 命令的后端：命令排队，由测试决定何时、以什么结局完成。同步 SPI 直接转给被包装的后端（性质测试只驱动异步变体）。
     */
    private class ScriptedBackend(
        private val delegate: InMemoryBackend,
        private val ledger: Ledger
    ) : CoherenceBackend by delegate {
        private val pending = ArrayList<Command<*>>()

        val pendingCount: Int
            @Synchronized get() = pending.size

        @Synchronized
        private fun enqueue(command: Command<*>) {
            pending += command
        }

        @Synchronized
        private fun take(index: (Int) -> Int): Command<*>? =
            if (pending.isEmpty()) null else pending.removeAt(index(pending.size))

        fun resolveRandom(random: Random, outcome: Outcome): Boolean = take { random.nextInt(it) }?.resolve(outcome) != null

        fun resolveOldest(outcome: Outcome): Boolean = take { 0 }?.resolve(outcome) != null

        fun resolveNewest(outcome: Outcome): Boolean = take { it - 1 }?.resolve(outcome) != null

        override fun store(namespace: String): RemoteStore =
            ScriptedStore(delegate.store(namespace) as AsyncRemoteStore)

        private inner class ScriptedStore(private val store: AsyncRemoteStore) : AsyncRemoteStore by store {
            private fun <T> command(
                name: String,
                gives: List<Token> = emptyList(),
                holds: () -> List<Pair<String, Token>> = { emptyList() },
                apply: () -> T
            ): CompletableFuture<T> = Command(name, ledger, store, gives, holds, apply).also { enqueue(it) }.future

            private fun grantOf(key: String, read: RemoteRead, lease: LeaseRequest?, refresh: RefreshRequest?) {
                if (read is RemoteRead.Miss && read.leaseGranted && lease != null) {
                    ledger.grant(lease.token, "lease on $key")
                }
                if (read is RemoteRead.Hit && read.refreshGranted && refresh != null) {
                    ledger.grant(refresh.token, "refresh token on $key")
                }
            }

            override fun readAsync(
                key: String,
                lease: LeaseRequest?,
                refresh: RefreshRequest?
            ): CompletableFuture<RemoteRead> = command(
                "read $key",
                holds = { listOfNotNull(lease?.let { key to it.token }, refresh?.let { key to it.token }) }
            ) {
                store.read(key, lease, refresh).also { grantOf(key, it, lease, refresh) }
            }

            override fun readAllAsync(
                keys: Collection<String>,
                leases: Map<String, LeaseRequest>
            ): CompletableFuture<Map<String, RemoteRead>> = command(
                "readAll $keys",
                holds = { leases.map { (key, lease) -> key to lease.token } }
            ) {
                store.readAll(keys, leases).onEach { (key, read) -> grantOf(key, read, leases[key], null) }
            }

            override fun reclaimAsync(
                key: String,
                observed: Digest,
                lease: LeaseRequest
            ): CompletableFuture<Boolean> = command("reclaim $key", holds = { listOf(key to lease.token) }) {
                val reclaimed = store.reclaim(key, observed, lease)
                if (reclaimed) {
                    ledger.grant(lease.token, "reclaimed lease on $key")
                }
                reclaimed
            }

            override fun fillAsync(fill: Fill): CompletableFuture<Boolean> = command(
                "fill ${fill.key}",
                gives = listOf(fill.token),
                holds = { listOf(fill.key to fill.token) }
            ) {
                store.fill(fill).also { ledger.giveBack(fill.token) }
            }

            override fun fillAllAsync(fills: List<Fill>): CompletableFuture<List<Boolean>> = command(
                "fillAll ${fills.map { it.key }}",
                gives = fills.map { it.token },
                holds = { fills.map { it.key to it.token } }
            ) {
                store.fillAll(fills).also { fills.forEach { ledger.giveBack(it.token) } }
            }

            override fun releaseAsync(key: String, token: Token): CompletableFuture<Void?> = command(
                "release $key",
                gives = listOf(token)
            ) {
                store.release(key, token)
                ledger.giveBack(token)
                null
            }

            override fun putAsync(
                key: String,
                envelope: Envelope,
                ttl: Duration?,
                frame: ByteArray
            ): CompletableFuture<Void?> = command("put $key") {
                store.put(key, envelope, ttl, frame)
                null
            }

            override fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> = command("putAll") {
                store.putAll(puts, frame)
                null
            }

            override fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
                command("invalidate $keys") {
                    store.invalidate(keys, frame)
                    null
                }
        }
    }

    /**
     * 不做任何事的观测器（不是 [LoadObserver.NOOP]）：让引擎经观测路径（在运行时作用域中另起协程）回源与等待 lease。
     */
    private object PassThroughObserver : LoadObserver {
        override fun <T> observe(
            cacheName: String,
            operation: LoadOperation,
            keyCount: Int,
            block: Supplier<T>
        ): T = block.get()

        override fun <T> observeAsync(
            cacheName: String,
            operation: LoadOperation,
            keyCount: Int,
            block: Supplier<CompletableFuture<T>>
        ): CompletableFuture<T> = block.get()
    }

    private class Scenario(
        private val seed: Long,
        nodes: Int = NODES,
        asyncSource: Boolean? = null,
        observed: Boolean? = null,
        leaseWait: Duration = Duration.ZERO,
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
        private val observed = observed ?: random.nextBoolean()
        private var loaded = 0

        val runtimes: List<CacheRuntime> = List(nodes) {
            CacheRuntime.builder()
                .backend(backend)
                .clock(clock)
                .asyncExecutor(executor)
                .blockingExecutor(executor)
                .apply { if (this@Scenario.observed) loadObserver(PassThroughObserver) }
                .build()
        }
        private val open = BooleanArray(nodes) { true }

        val caches = runtimes.map { runtime ->
            runtime.create(
                cacheSpec<String, String>(NAME) {
                    codec = StringCodec
                    if (this@Scenario.asyncSource) {
                        asyncLoader = AsyncCacheLoader { key -> CompletableFuture<String?>().also { loads += key to it } }
                    } else {
                        loader = CacheLoader { key -> "$key-${loaded++}" }
                    }
                    policy = CachePolicy(
                        ttlJitter = Duration.ZERO,
                        lease = LeasePolicy(wait = leaseWait),
                        // 失效失败时抛出而不是转入后台重试：重试在别的线程上按真实时间执行，种子无法复现
                        invalidateFailure = InvalidateFailureMode.THROW
                    )
                }
            )
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
            calls += when (random.nextInt(10)) {
                in 0..4 -> "get $key" to cache.get(key)
                5, 6 -> {
                    val keys = KEYS.shuffled(random).take(1 + random.nextInt(KEYS.size))
                    "getAll $keys" to cache.getAll(keys)
                }
                7, 8 -> "put $key" to cache.put(key, "$key-put-${random.nextInt(1000)}")
                else -> "invalidate $key" to cache.invalidate(key)
            }
        }

        private fun resolveLoad() {
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
            memory.store(NAME).read("cocache:$NAME:$key", null, null) is RemoteRead.Leased
    }

    companion object {
        private const val NAME = "prop"
        private const val NODES = 2
        private const val SEEDS = 400
        private const val STEPS = 120
        private const val MAX_DRAIN_ROUNDS = 100_000
        private val KEYS = listOf("a", "b", "c")
    }
}
