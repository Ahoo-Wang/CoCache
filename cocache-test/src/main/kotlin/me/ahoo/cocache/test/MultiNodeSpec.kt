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

package me.ahoo.cocache.test

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.EngineSpec.TestCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * 多节点 TCK：三个节点（运行时）共享同一个后端，覆盖 RFC 0003 §4.4 中跨节点的交错与全集群的计数断言。
 *
 * 与 [EngineSpec] 互补，不重复其中的用例：
 * - 失效晚于 lease、早于 fill（持有者的 fill 被拒绝）：[EngineSpec.remoteInvalidationDuringLoadRejectsTheStaleFill]；
 * - 同节点失效与并发读：[EngineSpec.sameNodeInvalidateRacingAReadLeavesNoStaleLocalCopy]；
 * - 同节点并发 `put`：[EngineSpec.concurrentPutsOnTheSameNodeKeepLocalConsistentWithRemote]；
 * - 失效重试期间单节点绕过 L1：[EngineSpec.failedInvalidateIsRetriedAndBypassesTheStaleRemoteMeanwhile]。
 *
 * 竞态用 latch 与 [HookedBackend] 的钩子编排；跨节点效果（事件到达）用带超时的轮询等待。
 */
abstract class MultiNodeSpec {
    protected abstract fun createBackend(): CoherenceBackend

    /**
     * 引擎与后端共用的时钟。
     */
    protected abstract val clock: CacheClock

    /**
     * 让时间前进 [duration]（同时作用于后端与引擎）。
     */
    protected abstract fun elapse(duration: Duration)

    /**
     * 访问缓存的方式：同步 API（默认），或经异步视图（[AsyncMultiNodeSpec]，RFC 0004 §10.1）。
     */
    protected open val access: CacheAccess = CacheAccess.SYNC

    protected lateinit var backend: HookedBackend
    protected lateinit var cacheName: String
    private lateinit var nodes: List<CacheRuntime>

    private val database = ConcurrentHashMap<String, String>()
    private val loads = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 每次回源读到数据之后调用（用于在回源中途编排竞态）。
     */
    @Volatile
    private var afterLoad: ((String) -> Unit)? = null

    private val directExecutor = Executor { it.run() }

    @BeforeEach
    open fun setup() {
        backend = HookedBackend(createBackend())
        nodes = (1..NODES).map { newNode() }
        cacheName = "tck-multi-" + UUID.randomUUID().toString().substring(0, 8)
    }

    @AfterEach
    open fun destroy() {
        nodes.forEach { it.close() }
        backend.close()
    }

    private fun newNode(): CacheRuntime = CacheRuntime.builder()
        .backend(backend)
        .clock(clock)
        .refreshExecutor(directExecutor)
        // 足够多的重试次数：失效重试期间的用例需要“重试中”状态持续到故障解除
        .retryPolicy(
            RetryPolicy(maxAttempts = 200, initialBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(50))
        )
        .build()

    private fun policy(): CachePolicy = CachePolicy(
        ttl = Duration.ofMinutes(10),
        ttlJitter = Duration.ZERO,
        lease = LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ofSeconds(5)),
    )

    private val loader = CacheLoader<String, String> { key ->
        loads.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        val value = database[key]
        afterLoad?.invoke(key)
        value
    }

    private fun spec(policy: CachePolicy = policy()): CacheSpec<String, String> = cacheSpec(cacheName) {
        codec = TestCodec
        loader = this@MultiNodeSpec.loader
        this.policy = policy
        access.source(this)
    }

    private fun caches(policy: CachePolicy = policy()): List<Cache<String, String>> {
        val spec = spec(policy)
        return nodes.map { access.cache(it.create(spec)) }
    }

    private fun CacheRuntime.metrics(): CacheMetrics = requireNotNull(metrics(cacheName))

    private fun loadsOf(key: String): Int = loads[key]?.get() ?: 0

    private fun sum(metric: (CacheMetrics) -> Long): Long = nodes.sumOf { metric(it.metrics()) }

    private fun remote(key: String): RemoteRead {
        val store = backend.delegate.store(cacheName)
        return store.read("cocache:$cacheName:$key", null, null)
    }

    private fun remoteValue(key: String): String? {
        val read = remote(key) as? RemoteRead.Hit ?: return null
        return (read.envelope as? Envelope.Scalar)?.let { TestCodec.decode(it.bytes) }
    }

    /**
     * 等待除 [writer] 之外的每个节点都收到至少 [count] 条失效事件。
     */
    private fun awaitEventsExcept(writer: Int, count: Long = 1) {
        nodes.forEachIndexed { index, node ->
            if (index != writer) {
                awaitCondition(
                    "node $index received $count invalidation(s)"
                ) { node.metrics().invalidationsReceived >= count }
            }
        }
    }

    /**
     * 每个节点、每个 key 各 [THREADS_PER_NODE] 个线程同时未命中。回源被挡住，直到每个 key 恰好有一个回源者、
     * 其余每个节点都在等待它的 lease（节点内其余线程经 SingleFlight 合并），然后放行，断言每个 key 全集群只回源一次。
     */
    private fun assertConcurrentMissesLoadOncePerKey(caches: List<Cache<String, String>>, keys: List<String>) {
        val loadsBefore = keys.associateWith { loadsOf(it) }
        val leaseWaitsBefore = sum { it.leaseWaits }
        val missesBefore = sum { it.misses }
        val release = CountDownLatch(1)
        afterLoad = { release.awaitOrFail("release loads") }
        val workers = caches.flatMapIndexed { node, cache ->
            keys.flatMap { key -> (1..THREADS_PER_NODE).map { i -> key to fork("node$node-$key-$i") { cache[key] } } }
        }
        awaitCondition("one loader per key, every other node waiting on its lease") {
            keys.all { loadsOf(it) - loadsBefore.getValue(it) == 1 } &&
                sum { it.leaseWaits } - leaseWaitsBefore == (NODES - 1L) * keys.size &&
                sum { it.misses } - missesBefore == workers.size.toLong()
        }
        release.countDown()
        workers.forEach { (key, worker) -> worker.join().assert().isEqualTo(database[key]) }
        afterLoad = null
        keys.forEach { key ->
            (loadsOf(key) - loadsBefore.getValue(key)).assert().withFailMessage {
                "loads of $key"
            }.isEqualTo(1)
        }
    }

    @Test
    fun concurrentMissesOnEveryNodeLoadOncePerKey() {
        val caches = caches()
        val keys = listOf("a", "b", "c")
        keys.forEach { database[it] = "$it-v" }
        assertConcurrentMissesLoadOncePerKey(caches, keys)
        keys.forEach { remoteValue(it).assert().isEqualTo("$it-v") }
    }

    /**
     * §4.4 `t_d < t_l`：失效先于任何 lease。每个节点的 L2 都被淘汰（本节点由写入方顺序，其它节点由事件），
     * 随后全集群的并发未命中只回源一次且都读到新值。
     */
    @Test
    fun invalidationBeforeLeaseReloadsOnceAcrossNodes() {
        val caches = caches()
        database["k"] = "v0"
        caches.forEach { it["k"].assert().isEqualTo("v0") }
        loadsOf("k").assert().isEqualTo(1)
        database["k"] = "v1"
        caches[0].invalidate("k")
        awaitEventsExcept(writer = 0)
        remote("k").assert().isEqualTo(RemoteRead.Miss(false))
        assertConcurrentMissesLoadOncePerKey(caches, listOf("k"))
        loadsOf("k").assert().isEqualTo(2)
    }

    /**
     * §4.4 `t_d > t_l`，第三个节点正在等待 lease：失效销毁持有者的 lease，等待者重读时取得新 lease，回源读到新值并写入 L1；
     * 持有者随后的 fill 被拒绝，它的 L2 填充也被事件递增的戳挡住。
     */
    @Test
    fun leaseWaiterTakesAFreshLeaseAfterInvalidation() {
        val caches = caches()
        val (holder, writer, waiter) = caches
        database["k"] = "v0"
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        afterLoad = {
            if (Thread.currentThread().name == "holder") {
                loading.countDown()
                release.awaitOrFail("release holder")
            }
        }
        val onHolder = fork("holder") { holder["k"] }
        loading.awaitOrFail("holder loading under its lease")
        val onWaiter = fork("waiter") { waiter["k"] }
        awaitCondition("waiter waiting on the lease") { nodes[2].metrics().leaseWaits == 1L }
        database["k"] = "v1"
        writer.invalidate("k")
        onWaiter.join().assert().isEqualTo("v1")
        remoteValue("k").assert().isEqualTo("v1")
        awaitCondition("holder received the invalidation") { nodes[0].metrics().invalidationsReceived >= 1 }
        release.countDown()
        onHolder.join().assert().isEqualTo("v0")
        afterLoad = null
        nodes[0].metrics().fillRejected.assert().isEqualTo(1)
        nodes[0].metrics().staleFillsDiscarded.assert().isEqualTo(1)
        remoteValue("k").assert().isEqualTo("v1")
        caches.forEach { it["k"].assert().isEqualTo("v1") }
        loadsOf("k").assert().isEqualTo(2)
    }

    /**
     * 失效晚于 fill：持有者已写入 L1、尚未写 L2 时，另一节点失效且事件已到达持有者。持有者的 L2 填充必须被撤销，
     * 其它节点已有的副本被事件淘汰。
     */
    @Test
    fun invalidationAfterFillFencesTheFillersLocalCopy() {
        val (filler, writer, observer) = caches()
        database["k"] = "v0"
        val filled = CountDownLatch(1)
        val resume = CountDownLatch(1)
        backend.afterFill = { _, _ ->
            if (Thread.currentThread().name == "filler") {
                filled.countDown()
                resume.awaitOrFail("resume filler")
            }
        }
        val onFiller = fork("filler") { filler["k"] }
        filled.awaitOrFail("filler wrote L1")
        observer["k"].assert().isEqualTo("v0")
        database["k"] = "v1"
        writer.invalidate("k")
        awaitEventsExcept(writer = 1)
        resume.countDown()
        onFiller.join().assert().isEqualTo("v0")
        backend.afterFill = null
        nodes[0].metrics().staleFillsDiscarded.assert().isEqualTo(1)
        listOf(filler, writer, observer).forEach { it["k"].assert().isEqualTo("v1") }
        loadsOf("k").assert().isEqualTo(2)
    }

    /**
     * 跨节点的 L2 栅栏：读者读到 L1 旧值之后、写 L2 之前，另一节点的 `put` 事件到达读者。读者本次返回旧值（合法的事件延迟），
     * 但 L2 不得留下旧值。
     */
    @Test
    fun remotePutBetweenReadAndLocalFillIsFenced() {
        val (writer, reader) = caches()
        writer.put("k", "v0")
        awaitCondition("reader received the first put") { nodes[1].metrics().invalidationsReceived >= 1 }
        val readerParked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        backend.afterRead = { _, read ->
            if (Thread.currentThread().name == "reader") {
                readerParked.countDown()
                resume.awaitOrFail("resume reader")
            }
            read
        }
        val onReader = fork("reader") { reader["k"] }
        readerParked.awaitOrFail("reader read the old remote value")
        writer.put("k", "v1")
        awaitCondition("reader received the second put") { nodes[1].metrics().invalidationsReceived >= 2 }
        resume.countDown()
        onReader.join().assert().isEqualTo("v0")
        backend.afterRead = null
        nodes[1].metrics().staleFillsDiscarded.assert().isEqualTo(1)
        reader["k"].assert().isEqualTo("v1")
    }

    /**
     * 跨节点并发 `put`：先写 L1 的一方在广播与第二次淘汰之前停住，另一节点随后写入。非原子广播的后端上，
     * 两条事件到达的顺序与 L1 中的写入顺序相反。每个节点最终都必须与 L1（后写入的值）一致。
     */
    @Test
    fun concurrentPutsAcrossNodesConvergeOnTheRemoteValue() {
        val caches = caches()
        database["k"] = "v0"
        caches.forEach { it["k"].assert().isEqualTo("v0") }
        val firstWritten = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        backend.afterPut = {
            if (Thread.currentThread().name == "first") {
                firstWritten.countDown()
                releaseFirst.awaitOrFail("release first put")
            }
        }
        val first = fork("first") { caches[0].put("k", "v1") }
        firstWritten.awaitOrFail("first put reached the remote store")
        caches[1].put("k", "v2")
        awaitCondition("observer refilled from the second put") { caches[2]["k"] == "v2" }
        releaseFirst.countDown()
        first.join()
        backend.afterPut = null
        remoteValue("k").assert().isEqualTo("v2")
        awaitCondition("every node converges on the remote value") { caches.all { it["k"] == "v2" } }
        loadsOf("k").assert().isEqualTo(1)
    }

    /**
     * 失效写入 L1 失败、仍在重试中：写入方节点上的并发未命中合并为一次回源、绕过 L1 且不写 L1；
     * 其它节点在重试成功之前读到 L1 的旧值（事件尚未发出，合法），重试成功后全集群收敛到新值。
     */
    @Test
    fun missesWhileAnInvalidationRetryIsPendingBypassRemoteAndConverge() {
        val caches = caches()
        database["k"] = "v0"
        caches.forEach { it["k"].assert().isEqualTo("v0") }
        database["k"] = "v1"
        backend.failWrites = true
        caches[0].invalidate("k")
        requireNotNull(nodes[0].admin(cacheName)).pendingInvalidationCount.assert().isEqualTo(1)
        val loadsBefore = loadsOf("k")
        val missesBefore = nodes[0].metrics().misses
        val release = CountDownLatch(1)
        afterLoad = { release.awaitOrFail("release bypassing load") }
        val misses = (1..THREADS_PER_NODE).map { fork("writer-node-$it") { caches[0]["k"] } }
        awaitCondition("misses coalesced behind one load") {
            loadsOf("k") - loadsBefore == 1 && nodes[0].metrics().misses - missesBefore == THREADS_PER_NODE.toLong()
        }
        release.countDown()
        misses.forEach { it.join().assert().isEqualTo("v1") }
        afterLoad = null
        (loadsOf("k") - loadsBefore).assert().isEqualTo(1)
        nodes[0].metrics().degradedFills.assert().isEqualTo(1)
        remoteValue("k").assert().isEqualTo("v0")
        caches[1]["k"].assert().isEqualTo("v0")
        backend.failWrites = false
        awaitCondition("retry succeeded") { requireNotNull(nodes[0].admin(cacheName)).pendingInvalidationCount == 0 }
        awaitEventsExcept(writer = 0)
        awaitCondition("every node converges on the new value") { caches.all { it["k"] == "v1" } }
    }

    /**
     * 提前刷新：每个节点、每个 key 同时命中刷新窗口。全集群每个 key 只有一次刷新回源，其余节点只从 L1 重新填充 L2。
     */
    @Test
    fun refreshAheadLoadsOncePerKeyAcrossNodes() {
        val refreshing = CachePolicy(
            ttl = Duration.ofSeconds(10),
            ttlJitter = Duration.ZERO,
            localMaxAge = Duration.ofSeconds(8),
            refreshWindow = Duration.ofSeconds(5),
            lease = LeasePolicy(ttl = Duration.ofSeconds(30), wait = Duration.ofSeconds(5)),
        )
        val caches = caches(refreshing)
        val keys = listOf("a", "b")
        keys.forEach { database[it] = "$it-0" }
        caches.forEach { cache -> keys.forEach { cache[it].assert().isEqualTo("$it-0") } }
        keys.forEach { loadsOf(it).assert().isEqualTo(1) }
        keys.forEach { database[it] = "$it-1" }
        elapse(Duration.ofSeconds(6))
        val release = CountDownLatch(1)
        afterLoad = { release.awaitOrFail("release refresh loads") }
        val hits = caches.flatMapIndexed { node, cache -> keys.map { key -> fork("node$node-$key") { cache[key] } } }
        awaitCondition("one refresh load per key, every other hit returned") {
            keys.all { loadsOf(it) == 2 } && hits.count { !it.thread.isAlive } == hits.size - keys.size
        }
        release.countDown()
        hits.forEach { it.join() }
        afterLoad = null
        sum { it.refreshes }.assert().isEqualTo(hits.size.toLong())
        keys.forEach { remoteValue(it).assert().isEqualTo("$it-1") }
        awaitCondition("every node refilled from L1") { caches.all { cache -> keys.all { cache[it] == "$it-1" } } }
        keys.forEach { key -> loadsOf(key).assert().withFailMessage { "loads of $key" }.isEqualTo(2) }
    }

    private companion object {
        const val NODES = 3
        const val THREADS_PER_NODE = 3
    }
}
