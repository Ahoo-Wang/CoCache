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

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.event.connection.DisconnectedEvent
import io.lettuce.core.protocol.CommandHandler
import io.lettuce.core.protocol.RedisCommand
import io.lettuce.core.resource.NettyCustomizer
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteConditionalRead
import me.ahoo.cocache.api.spi.RemoteConditionalWrite
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteVersionedWrite
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [backend] 的原生存储，与后端共用连接与广播频道（M3d 第一阶段：后端的 `store` 仍经 [RedisRemoteStore] 适配，这里直接构造
 * [RedisL1Store]；第二阶段由 `RedisBackend.store` 返回它）。
 */
internal fun RedisBackend.l1Store(
    namespace: String,
    invalidateBatchSize: Int = RedisBackendOptions().invalidateBatchSize
): RedisL1Store {
    val field = RedisBackend::class.java.getDeclaredField("connections").apply { isAccessible = true }
    val name = RedisInvalidationChannel.channelOf(namespace)
    val channel = if (atomicBroadcast) name.toByteArray(Charsets.UTF_8) else ByteArray(0)
    return RedisL1Store(field.get(this) as RedisConnections, channel, invalidateBatchSize)
}

/**
 * 原生的 Redis `L1Store`（RFC 0005 §4.3）直接对 Redis 的用例：每个操作与能力面、广播、在调用方的调度器上恢复、批量先全部发出再等待、
 * 命令超时、结果未知时释放令牌、Lettuce 取消的命令按 L1 故障报告。单机与集群（[RedisTestSupport]）。
 */
class RedisL1StoreTest {
    private val namespace = "l1-" + UUID.randomUUID().toString().substring(0, 8)
    private val node = NodeId.random()
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private val closeables = ArrayList<AutoCloseable>()
    private val caller = CallerDispatcher().closing()

    @AfterEach
    fun destroy() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables.add(it) }

    private fun key(name: String) = "cocache:$namespace:$name"

    private fun token() = Token(node, sequence.incrementAndGet())

    private fun lease() = LeaseRequest(token(), Duration.ofMinutes(1))

    private fun refresh() = RefreshRequest(Duration.ofMinutes(5), token(), Duration.ofMinutes(1))

    private fun frame(vararg keys: String) = InvalidationFrame.encode(node, keys.toList())

    private fun scalar(text: String, version: Long = me.ahoo.cocache.api.Versioned.NO_VERSION) =
        Envelope.Scalar(text.toByteArray(), version)

    private fun <T> run(block: suspend () -> T): T = caller.run(block)

    private fun Throwable?.assertTimedOut(timeout: Duration) {
        this.assert().isInstanceOf(RedisException::class.java)
        this?.message.assert().isEqualTo("Command timed out after $timeout.")
    }

    private fun RemoteRead?.members(): Set<String> =
        (this as RemoteRead.Hit).envelope.let { it as Envelope.Members }.members.map { String(it) }.toSet()

    @Test
    fun everyOperationRoundTrips() {
        val store = RedisTestSupport.backend().closing().l1Store(namespace)
        val a = key("a")
        run {
            store.read(a, null, null).assert().isEqualTo(RemoteRead.Miss(false))
            val first = lease()
            store.read(a, first, null).assert().isEqualTo(RemoteRead.Miss(true))
            (store.read(a, lease(), null) as RemoteRead.Leased).remaining.isZero.assert().isFalse()
            store.fill(Fill(a, token(), scalar("other"), Duration.ofMinutes(1))).assert().isFalse()
            store.fill(Fill(a, first.token, scalar("v"), Duration.ofMinutes(1))).assert().isTrue()
            val hit = store.read(a, null, null) as RemoteRead.Hit
            hit.envelope.assert().isEqualTo(scalar("v"))
            hit.format.assert().isEqualTo(RedisScripts.FORMAT)
            // 带版本的填充：读取原样返回版本
            val d = key("d")
            val versionedLease = lease()
            store.read(d, versionedLease, null).assert().isEqualTo(RemoteRead.Miss(true))
            store.fill(Fill(d, versionedLease.token, scalar("dv", 7), Duration.ofMinutes(1))).assert().isTrue()
            (store.read(d, null, null) as RemoteRead.Hit).envelope.assert().isEqualTo(scalar("dv", 7))
            // 剩余寿命（1 分钟）在刷新窗口（5 分钟）内：取得刷新令牌；释放之后可以再次取得
            val granted = refresh()
            (store.read(a, null, granted) as RemoteRead.Hit).refreshGranted.assert().isTrue()
            (store.read(a, null, refresh()) as RemoteRead.Hit).refreshGranted.assert().isFalse()
            store.release(a, granted.token)
            (store.read(a, null, refresh()) as RemoteRead.Hit).refreshGranted.assert().isTrue()

            // 回收：摘要不符时不动，相符时换成 lease 占位
            store.reclaim(a, Digest.of(scalar("other")), lease()).assert().isFalse()
            val reclaimed = lease()
            store.reclaim(a, Digest.of(scalar("v")), reclaimed).assert().isTrue()
            store.read(a, null, null).assert().isInstanceOf(RemoteRead.Leased::class.java)
            store.release(a, reclaimed.token)
            store.read(a, null, null).assert().isEqualTo(RemoteRead.Miss(false))
            store.invalidate(listOf(d), frame(d))
        }
    }

    @Test
    fun everyBatchOperationRoundTrips() {
        val store = RedisTestSupport.backend().closing().l1Store(namespace)
        val a = key("a")
        run {
            // 批量：重复的 key 只读一次，结果包含全部 key
            val b = key("b")
            val c = key("c")
            store.put(b, scalar("b"), null, frame(b))
            val leases = mapOf(a to lease(), c to lease())
            val reads = store.readAll(listOf(a, b, c, a), leases)
            reads.keys.assert().containsExactly(a, b, c)
            reads[a].assert().isEqualTo(RemoteRead.Miss(true))
            (reads[b] as RemoteRead.Hit).ttl.assert().isNull()
            reads[c].assert().isEqualTo(RemoteRead.Miss(true))
            store.fillAll(
                listOf(
                    Fill(a, leases.getValue(a).token, Envelope.Absent, Duration.ofMinutes(1), 3),
                    Fill(c, token(), scalar("c"), null),
                )
            ).assert().containsExactly(true, false)
            store.readAll(emptyList(), emptyMap()).assert().isEmpty()
            store.fillAll(emptyList()).assert().isEmpty()

            store.putAll(
                listOf(Put(a, scalar("pa"), null), Put(c, scalar("pc", 2), Duration.ofMinutes(1))),
                frame(a, c)
            )
            (store.read(a, null, null) as RemoteRead.Hit).envelope.assert().isEqualTo(scalar("pa"))
            (store.read(c, null, null) as RemoteRead.Hit).envelope.assert().isEqualTo(scalar("pc", 2))
            store.invalidate(listOf(a, b, c), frame(a, b, c))
            store.readAll(listOf(a, b, c), emptyMap()).values.forEach { it.assert().isEqualTo(RemoteRead.Miss(false)) }
        }
    }

    @Test
    fun everyFacetRoundTrips() {
        val store = RedisTestSupport.backend().closing().l1Store(namespace)
        val conditional = store.conditional()
        val versioned = store.versioned()
        val sets = store.sets()
        // 同一个存储每次返回同样的能力面
        store.conditional().assert().isSameAs(conditional)
        store.versioned().assert().isSameAs(versioned)
        store.sets().assert().isSameAs(sets)
        val c = key("conditional")
        val v = key("versioned")
        val s = key("set")
        val foreign = key("foreign")
        RedisTestSupport.withCommands { it.set(foreign, "garbage") }
        run {
            conditional.readIfChanged(c, null).assert().isEqualTo(RemoteConditionalRead.Absent)
            conditional.putIfMatch(
                c,
                null,
                scalar("one"),
                null,
                frame(c)
            ).assert().isEqualTo(RemoteConditionalWrite.Written)
            val one = Digest.of(scalar("one"))
            conditional.putIfMatch(c, null, scalar("two"), null, frame(c))
                .assert().isEqualTo(RemoteConditionalWrite.Conflict(one))
            conditional.readIfChanged(c, one).assert().isEqualTo(RemoteConditionalRead.Unchanged(RedisScripts.FORMAT))
            val changed = conditional.readIfChanged(c, Digest.of(scalar("two"))) as RemoteConditionalRead.Changed
            changed.envelope.assert().isEqualTo(scalar("one"))
            conditional.putIfMatch(foreign, null, scalar("x"), null, frame(foreign))
                .assert().isEqualTo(RemoteConditionalWrite.Unreadable(0))

            versioned.putIfNewer(v, scalar("v2"), 2, null, frame(v)).assert().isEqualTo(RemoteVersionedWrite.Written)
            versioned.putIfNewer(v, scalar("v1"), 1, null, frame(v)).assert().isEqualTo(RemoteVersionedWrite.Stale(2))
            versioned.putIfNewer(v, Envelope.Absent, 3, null, frame(v)).assert().isEqualTo(RemoteVersionedWrite.Written)
            (store.read(v, null, null) as RemoteRead.Hit).envelope.assert().isEqualTo(Envelope.Absent)
            versioned.putIfNewer(foreign, scalar("x"), 9, null, frame(foreign))
                .assert().isEqualTo(RemoteVersionedWrite.Unreadable(0))
            // 集合不能按版本写入：发出之前同步失败
            runCatching { versioned.putIfNewer(v, Envelope.Members(emptyList()), 4, null, frame(v)) }
                .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)

            sets.addMembers(
                s,
                listOf("m1".toByteArray(), "m2".toByteArray()),
                Duration.ofMinutes(1),
                frame(s)
            ).assert().isNull()
            store.read(s, null, null).members().assert().containsExactlyInAnyOrder("m1", "m2")
            sets.removeMembers(s, listOf("m1".toByteArray()), null, frame(s)).assert().isNull()
            store.read(s, null, null).members().assert().containsExactly("m2")
            sets.removeMembers(s, listOf("m2".toByteArray()), null, frame(s)).assert().isNull()
            store.read(s, null, null).assert().isEqualTo(RemoteRead.Miss(false))
            sets.addMembers(foreign, listOf("m".toByteArray()), null, frame(foreign)).assert().isEqualTo(0)
            sets.removeMembers(foreign, listOf("m".toByteArray()), null, frame(foreign)).assert().isEqualTo(0)
        }
    }

    /**
     * 原子广播：写入在同一个脚本中把帧发布到本命名空间的频道；分成多组时每组只广播本组的 key。
     */
    @Test
    fun writesBroadcastOnTheNamespaceChannel() {
        val backend = RedisTestSupport.backend().closing()
        assumeTrue(backend.atomicBroadcast)
        val store = backend.l1Store(namespace, invalidateBatchSize = 1)
        val received = CopyOnWriteArrayList<List<String>>()
        RedisTestSupport.subscribeRaw(RedisInvalidationChannel.channelOf(namespace)) {
            received.add(InvalidationFrame.decode(it).keys)
        }.closing()
        val (a, b, c) = listOf(key("a"), key("b"), key("c"))
        run {
            store.put(a, scalar("v"), null, frame(a))
            store.putAll(listOf(Put(a, scalar("v"), null), Put(b, scalar("v"), null)), frame(a, b))
            store.invalidate(listOf(a, b, c), frame(a, b, c))
            store.conditional().putIfMatch(c, null, scalar("v"), null, frame(c))
            store.versioned().putIfNewer(c, scalar("v"), 1, null, frame(c))
            store.sets().addMembers(key("s"), listOf("m".toByteArray()), null, frame(key("s")))
        }
        awaitCondition("every broadcast received") { received.size == 9 }
        received.assert().containsExactlyInAnyOrder(
            listOf(a), listOf(a), listOf(b), listOf(a), listOf(b), listOf(c), listOf(c), listOf(c), listOf(key("s"))
        )
    }

    /**
     * 等待 Redis 时不占线程，续接经调用方的调度器恢复：挂起点之后的代码在调用方的线程上执行，不在 Lettuce 的 I/O 线程上。
     */
    @Test
    fun callerResumesOnItsOwnDispatcher() {
        val store = RedisTestSupport.backend().closing().l1Store(namespace)
        val a = key("a")
        val threads = run {
            val threads = ArrayList<String>()
            store.put(a, scalar("v"), null, frame(a))
            threads.add(Thread.currentThread().name)
            store.read(a, lease(), refresh())
            threads.add(Thread.currentThread().name)
            store.readAll(listOf(a, key("b")), mapOf(key("b") to lease()))
            threads.add(Thread.currentThread().name)
            store.invalidate(listOf(a, key("b")), frame(a, key("b")))
            threads.add(Thread.currentThread().name)
            threads
        }
        threads.forEach { it.assert().isEqualTo(caller.threadName) }
    }

    /**
     * 批量操作先发出全部命令再一起等待（约一次往返）：连接暂停期间，全部命令都已写出，调用仍在等待；暂停结束后全部完成。
     * 同一 slot（集群中节点连接已建立、不必在暂停期间握手）。
     */
    @Test
    fun batchesSendEveryCommandBeforeWaiting() {
        val written = WrittenCommands()
        val redis = RedisTestSupport.pausable { it.nettyCustomizer(written) }.closing()
        val backend = RedisTestSupport.backend(
            RedisBackendOptions(commandTimeout = Duration.ofSeconds(10), clientResources = redis.resources)
        ).closing()
        val store = backend.l1Store(namespace, invalidateBatchSize = 1)
        RedisTestSupport.loadScripts(
            RedisScripts.READ,
            RedisScripts.FILL,
            RedisScripts.PUT_ALL,
            RedisScripts.INVALIDATE
        )
        val warm = key("{batch}warm")
        run { store.read(warm, null, null) }
        val keys = List(4) { key("{batch}$it") }
        val batches: List<suspend () -> Unit> = listOf(
            { store.readAll(keys, keys.associateWith { lease() }) },
            { store.fillAll(keys.map { Fill(it, token(), scalar("v"), null) }) },
            { store.putAll(keys.map { Put(it, scalar("v"), null) }, frame(*keys.toTypedArray())) },
            { store.invalidate(keys, frame(*keys.toTypedArray())) },
        )
        batches.forEach { batch ->
            written.clear()
            redis.pause(1_000)
            val pending = caller.start { batch() }
            awaitCondition("every command of the batch written") { written.count(keys) == keys.size }
            pending.isDone.assert().isFalse()
            pending.await()
        }
    }

    /**
     * 每个操作受 `commandTimeout` 限制：超过时以 [RedisException] 失败，连接随后照常可用。单机与集群。
     */
    @Test
    fun everyOperationIsBoundedByTheCommandTimeout() {
        val timeout = Duration.ofMillis(200)
        val redis = RedisTestSupport.pausable().closing()
        val backend = RedisTestSupport.backend(
            RedisBackendOptions(commandTimeout = timeout, clientResources = redis.resources)
        ).closing()
        val store = backend.l1Store(namespace)
        val a = key("{slow}a")
        val b = key("{slow}b")
        val members = listOf("m".toByteArray())
        run { store.read(a, null, null) }
        redis.pause(PAUSE_MILLIS)
        val operations: List<suspend () -> Any?> = listOf(
            { store.read(a, null, null) },
            { store.readAll(listOf(a, b), emptyMap()) },
            { store.fill(Fill(a, token(), scalar("v"), null)) },
            { store.fillAll(listOf(Fill(a, token(), scalar("v"), null))) },
            { store.release(a, token()) },
            { store.reclaim(a, Digest.of(scalar("v")), lease()) },
            { store.put(a, scalar("v"), null, frame(a)) },
            { store.putAll(listOf(Put(a, scalar("v"), null)), frame(a)) },
            { store.invalidate(listOf(a, b), frame(a, b)) },
            { store.conditional().readIfChanged(a, null) },
            { store.conditional().putIfMatch(a, null, scalar("v"), null, frame(a)) },
            { store.versioned().putIfNewer(a, scalar("v"), 1, null, frame(a)) },
            { store.sets().addMembers(b, members, null, frame(b)) },
            { store.sets().removeMembers(b, members, null, frame(b)) },
        )
        // 同时发出：都在暂停期间超时
        operations.map { operation -> caller.start { operation() } }.forEachIndexed { index, call ->
            val failure = call.failure()
            failure.assert().withFailMessage { "operation #$index did not time out" }.isNotNull()
            failure.assertTimedOut(timeout)
        }
        awaitCondition("pause over") { runCatching { run { store.read(key("{slow}warm"), null, null) } }.isSuccess }
    }

    /**
     * 取得令牌的命令超时（结果未知）：脚本可能在超时之后才执行、授予 lease 或刷新令牌，存储异步释放这次请求的令牌。
     * 读取、批量读取、刷新与条件回收；暂停结束后命令照常执行，随后执行的释放把令牌收回。单机与集群。
     */
    @Test
    fun timedOutTokenCommandsReleaseTheirTokens() {
        val timeout = Duration.ofMillis(200)
        val redis = RedisTestSupport.pausable().closing()
        val backend = RedisTestSupport.backend(
            RedisBackendOptions(commandTimeout = timeout, clientResources = redis.resources)
        ).closing()
        val store = backend.l1Store(namespace)
        // 同一 slot、脚本已缓存：暂停期间的命令与释放都已写出，暂停结束后按序执行（释放同样超时，得到 NOSCRIPT 时不再回退 EVAL）
        RedisTestSupport.loadScripts(RedisScripts.READ, RedisScripts.RECLAIM, RedisScripts.RELEASE)
        val warm = key("{lease}warm")
        val single = key("{lease}single")
        val all = listOf(key("{lease}all-0"), key("{lease}all-1"))
        val refreshed = key("{lease}refresh")
        val corrupt = key("{lease}corrupt")
        RedisTestSupport.withCommands { it.set(corrupt, "garbage") }
        val observed = run {
            store.put(refreshed, scalar("v"), Duration.ofMinutes(1), frame(refreshed))
            store.read(warm, null, null)
            Digest.of((store.read(corrupt, null, null) as RemoteRead.Hit).envelope)
        }
        redis.pause(PAUSE_MILLIS)
        listOf(
            caller.start { store.read(single, lease(), null) },
            caller.start { store.readAll(all, all.associateWith { lease() }) },
            caller.start { store.read(refreshed, null, refresh()) },
            caller.start { store.reclaim(corrupt, observed, lease()) },
        ).forEach { it.failure().assertTimedOut(timeout) }
        awaitCondition("pause over") { runCatching { run { store.read(warm, null, null) } }.isSuccess }
        run {
            (all + single + corrupt).forEach { store.read(it, null, null).assert().isEqualTo(RemoteRead.Miss(false)) }
            (store.read(refreshed, null, refresh()) as RemoteRead.Hit).refreshGranted.assert().isTrue()
        }
    }

    /**
     * Lettuce 取消的命令（这里是关闭时取消断线期间缓冲的命令）按结果未知的 L1 故障报告：[RedisException]，cause 是 Lettuce 的取消，
     * 从不以 `CancellationException` 抛出（否则引擎把它当作调用方被取消）。仅限单机（经 [TcpProxy] 断线）。
     */
    @Test
    fun commandCancelledByLettuceFailsAsAnL1Fault() {
        assumeFalse(RedisTestSupport.isCluster)
        val proxy = RedisTestSupport.proxy().closing()
        // 调用方的客户端，默认的断线行为：断线期间缓冲命令，关闭时 Lettuce 取消它们
        val client = RedisClient.create(RedisURI.create(proxy.address, proxy.port))
        closeables.add(AutoCloseable { client.shutdown() })
        val backend = RedisBackend.of(client, RedisBackendOptions(commandTimeout = Duration.ofSeconds(10))).closing()
        val store = backend.l1Store(namespace)
        run { store.read(key("warm"), null, null) }
        // 命令连接与订阅连接都经代理：两条都断开之后，新命令进入断线缓冲（重连停在握手上）
        val disconnected = CountDownLatch(2)
        val events = client.resources.eventBus().get()
            .filter { it is DisconnectedEvent }
            .subscribe { disconnected.countDown() }
        closeables.add(AutoCloseable { events.dispose() })
        proxy.stall(disconnect = true).closing()
        disconnected.await(10, TimeUnit.SECONDS).assert().isTrue()
        val pending = listOf(
            caller.start { store.read(key("a"), lease(), null) },
            caller.start { store.put(key("b"), scalar("v"), null, frame(key("b"))) },
        )
        // 单线程的调度器按序执行：空任务结束时，两个调用都已发出命令（进入缓冲）并挂起
        caller.run { }
        pending.forEach { it.isDone.assert().isFalse() }
        backend.close()
        pending.forEach { call ->
            val failure = call.failure()
            failure.assert().isInstanceOf(RedisException::class.java)
            failure?.cause.assert().isInstanceOf(java.util.concurrent.CancellationException::class.java)
            RedisConnections.isNotExecuted(requireNotNull(failure)).assert().isFalse()
        }
    }

    /**
     * 命令已写出并执行、应答丢失之后断线（RFC 0005 round 3 K14）：Lettuce 以 `Connection disconnected` 排空在途的读取，结果未知。
     * 存储释放这次读取取得的 lease；断线期间释放被拒绝（`REJECT_COMMANDS`），按退避重发，重连之后到达：lease 在 `lease.ttl`（1 分钟）
     * 之前就被收回。仅限单机（经 [TcpProxy]）。
     */
    @Test
    fun leaseTakenByAReadWhoseReplyWasLostIsReleasedAfterReconnecting() {
        assumeFalse(RedisTestSupport.isCluster)
        val proxy = RedisTestSupport.proxy().closing()
        val store = RedisTestSupport.proxiedBackend(proxy).closing().l1Store(namespace)
        val a = key("a")
        RedisTestSupport.loadScripts(RedisScripts.READ, RedisScripts.RELEASE)
        run { store.read(key("warm"), null, null) }
        RedisTestSupport.withCommands { commands ->
            val lost = proxy.dropReplies()
            val call = caller.start { store.read(a, LeaseRequest(token(), Duration.ofMinutes(1)), null) }
            awaitCondition("the read placed its lease") { commands.exists(a) == 1L }
            val cut = proxy.cut()
            val failure = call.failure()
            failure.assert().isInstanceOf(RedisException::class.java)
            RedisConnections.isNotExecuted(requireNotNull(failure)).assert().isFalse()
            lost.close()
            cut.close()
            awaitCondition("the lease released after reconnecting") { commands.exists(a) == 0L }
        }
    }

    /**
     * 记录写出的命令的首个 key（插在 Lettuce 的 `CommandHandler` 之前，同 [RedisCommandTimeoutTest]）：记录到的命令必然发往 Redis。
     */
    private class WrittenCommands : NettyCustomizer {
        private val keys = ConcurrentHashMap.newKeySet<String>()

        override fun afterChannelInitialized(channel: Channel) {
            val pipeline = channel.pipeline()
            val handler = pipeline.context(CommandHandler::class.java) ?: return
            pipeline.addBefore(
                handler.name(),
                "written-commands",
                object : ChannelOutboundHandlerAdapter() {
                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                        (msg as? Collection<*> ?: listOf(msg)).forEach { command ->
                            (command as? RedisCommand<*, *, *>)?.args?.firstEncodedKey?.let {
                                keys.add(StandardCharsets.UTF_8.decode(it).toString())
                            }
                        }
                        ctx.write(msg, promise)
                    }
                }
            )
        }

        fun clear() = keys.clear()

        fun count(expected: Collection<String>): Int = expected.count { it in keys }
    }

    private companion object {
        /**
         * 暂停时长：留足余量让暂停覆盖全部命令。
         */
        const val PAUSE_MILLIS = 2_000L
    }
}
