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
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.internal.HostAndPort
import io.lettuce.core.protocol.CommandType
import io.lettuce.core.protocol.RedisCommand
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import io.lettuce.core.resource.MappingSocketAddressResolver
import io.mockk.every
import io.mockk.mockk
import io.netty.util.Timer
import io.netty.util.TimerTask
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 命令超时之后（结果未知）：尚未写出的命令不再写出（取消 Lettuce 命令），取得或交回令牌的命令（读取、回收、填充、释放）
 * 尽力释放它们的令牌（RFC 0005 §4.3）。中断不到达存储：同步变体的调用方被中断时，等它的命令结束才返回。
 */
class RedisCommandTimeoutTest {
    private val namespace = "timeout-" + UUID.randomUUID().toString().substring(0, 8)
    private val node = NodeId.random()
    private var sequence = 0L
    private val closeables = ArrayList<AutoCloseable>()

    @AfterEach
    fun destroy() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables.add(it) }

    private fun <T> l1(block: suspend () -> T): T = Suspending.await(block)

    private fun key(name: String) = "cocache:$namespace:$name"

    private fun token() = Token(node, ++sequence)

    private fun lease() = LeaseRequest(token(), Duration.ofMinutes(1))

    private fun refresh() = RefreshRequest(Duration.ofMinutes(5), token(), Duration.ofMinutes(1))

    private fun frame(vararg keys: String) = InvalidationFrame.encode(node, keys.toList())

    private fun scalar(text: String) = Envelope.Scalar(text.toByteArray())

    private fun <T> CompletableFuture<T>.failure(): Throwable? = handle { _, e -> e }.get(10, TimeUnit.SECONDS)

    private fun Throwable?.assertTimedOut(timeout: Duration) {
        this.assert().isInstanceOf(RedisException::class.java)
        this?.message.assert().isEqualTo("Command timed out after $timeout.")
    }

    /**
     * 发往 [keys] 的命令可以暂时无法写出（[stall]）：单机时缓冲在断开的连接上（调用方的客户端 `RedisBackend.of`，默认的断线行为
     * 在重连期间缓冲命令），集群时等待建立节点连接（Lettuce 不为它们计时）。
     */
    private class Unwritable(
        val backend: RedisBackend,
        val store: L1Store,
        val keys: List<String>,
        private val stalling: () -> AutoCloseable,
        private val writable: () -> Unit,
    ) {
        /**
         * 开始阻断；关闭返回值即恢复，并等到发往 [keys] 的命令重新可以写出：此前缓冲或等待的命令已写出或已跳过，[store] 之后的命令排在它们之后。
         */
        fun stall(): AutoCloseable {
            val restore = stalling()
            return AutoCloseable {
                restore.close()
                writable()
            }
        }
    }

    private fun unwritable(timeout: Duration, count: Int): Unwritable =
        if (RedisTestSupport.isCluster) stalledNode(timeout, count) else bufferedConnection(timeout, count)

    /**
     * 单机：经 [TcpProxy] 连接的调用方客户端。阻断时断开连接并让重连停在握手上，等到新命令进入缓冲（以超时失败）才返回。
     */
    private fun bufferedConnection(timeout: Duration, count: Int): Unwritable {
        val proxy = RedisTestSupport.proxy().closing()
        val client = RedisClient.create(RedisURI.create(proxy.address, proxy.port))
        closeables.add(AutoCloseable { client.shutdown() })
        val backend = RedisBackend.of(client, RedisBackendOptions(commandTimeout = timeout)).closing()
        val store = backend.store(Keyspace("cocache:", namespace))
        // 先缓存脚本：重放的 EVALSHA 不会得到 NOSCRIPT
        l1 { store.read(key("warm"), null, null, null) }
        return Unwritable(
            backend,
            store,
            List(count) { key("late-$it") },
            stalling = {
                // 断开并让重连停在握手上：重连失败会让 Lettuce 以连接错误快速拒绝新命令，而不是缓冲
                val restore = proxy.stall(disconnect = true)
                // 客户端发现断线之后命令才进入缓冲、以超时失败（此前写出的命令以连接关闭失败）
                awaitCondition("commands are buffered") {
                    runCatching { l1 { store.read(key("probe"), null, null, null) } }.exceptionOrNull()?.message ==
                        "Command timed out after $timeout."
                }
                restore
            },
            // 重连后缓冲的命令按序重放，之后的命令排在它们之后
            writable = {
                awaitCondition(
                    "reconnected"
                ) { runCatching { l1 { store.read(key("warm"), null, null, null) } }.isSuccess }
            }
        )
    }

    /**
     * 集群：节点连接经地址映射走 [TcpProxy] 的后端，以及它尚未建立节点连接的一个主节点上的 [count] 个 key。
     * 阻断时代理暂不转发新连接，节点连接迟迟建立不起来。
     */
    private fun stalledNode(timeout: Duration, count: Int): Unwritable {
        val masters = RedisTestSupport.masters()
        val proxies = masters.associate { it.uri.port to TcpProxy(it.uri.host, it.uri.port).closing() }
        val resources = DefaultClientResources.builder()
            .socketAddressResolver(
                MappingSocketAddressResolver.create(
                    java.util.function.Function<HostAndPort, HostAndPort> { address ->
                        proxies[address.port]?.let { HostAndPort.of(it.address, it.port) } ?: address
                    }
                )
            )
            .build()
        closeables.add(AutoCloseable { resources.shutdown() })
        val backend = RedisTestSupport.backend(
            RedisBackendOptions(
                commandTimeout = timeout,
                clientResources = resources,
                clientName = "cocache-tck-" + UUID.randomUUID()
            )
        ).closing()
        val keyspace = Keyspace("cocache:", namespace)
        val store = backend.store(keyspace)
        // 启动时的原子广播探测（cocache:!probe:{a}）与创建存储时的频道探测（以存储前缀为 KEYS[1]）都没有访问过的主节点：
        // 后端还没有到它的节点连接（三个主节点中至少剩下一个）
        val visited = listOf(SlotHash.getSlot(RedisBackend.PROBE_KEY_NAME), SlotHash.getSlot(keyspace.storagePrefix))
        val target = masters.first { master -> visited.none { master.hasSlot(it) } }
        val keys = generateSequence(0) { it + 1 }
            .map { key("late-$it") }
            .filter { target.hasSlot(SlotHash.getSlot(it)) }
            .take(count)
            .toList()
        return Unwritable(
            backend,
            store,
            keys,
            stalling = { requireNotNull(proxies[target.uri.port]).stall() },
            // 第一次成功的读取说明节点连接已建立（等待它的命令此时已写出或已跳过），之后的命令排在它们之后
            writable = {
                awaitCondition("node connection established") {
                    runCatching { l1 { store.read(keys[0], null, null, null) } }.isSuccess
                }
            }
        )
    }

    /**
     * 调用方的客户端（`RedisBackend.of`）以默认的断线行为在重连期间缓冲命令：超时的写入不能在重连后重放，
     * 否则调用方已经看到失败的 `put` 稍后生效，覆盖之后写入的值。
     */
    @Test
    fun timedOutWriteBufferedWhileDisconnectedIsNotReplayed() {
        assumeFalse(RedisTestSupport.isCluster)
        val timeout = Duration.ofMillis(300)
        val connection = bufferedConnection(timeout, 2)
        val store = connection.store
        val (sync, async) = connection.keys
        connection.stall().use {
            val failure = runCatching { l1 { store.put(sync, scalar("stale"), null, frame(sync)) } }.exceptionOrNull()
            failure.assertTimedOut(timeout)
            runCatching {
                l1 {
                    store.put(
                        async,
                        scalar("stale"),
                        null,
                        frame(async)
                    )
                }
            }.exceptionOrNull().assertTimedOut(timeout)
        }
        l1 { store.read(sync, null, null, null) }.assert().isEqualTo(L1Read.Miss(false))
        l1 { store.read(async, null, null, null) }.assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 集群中等待建立节点连接的命令（首次访问某节点、拓扑变化后）Lettuce 不计时：超时之后它不能在连接建立时才写出、执行，
     * 覆盖之后写入的值。
     */
    @Test
    fun timedOutWriteWaitingForANodeConnectionIsNeverWritten() {
        assumeTrue(RedisTestSupport.isCluster)
        val timeout = Duration.ofMillis(300)
        val node = stalledNode(timeout, 2)
        val store = node.store
        val (sync, async) = node.keys
        // 先缓存脚本：否则迟到的 EVALSHA 得到 NOSCRIPT（放弃的调用不再回退 EVAL），复现不出迟到的写入
        RedisTestSupport.loadScripts(RedisScripts.PUT, RedisScripts.READ)
        node.stall().use {
            val failure = runCatching { l1 { store.put(sync, scalar("stale"), null, frame(sync)) } }.exceptionOrNull()
            failure.assertTimedOut(timeout)
            runCatching {
                l1 {
                    store.put(
                        async,
                        scalar("stale"),
                        null,
                        frame(async)
                    )
                }
            }.exceptionOrNull().assertTimedOut(timeout)
        }
        node.keys.forEach { l1 { store.read(it, null, null, null) }.assert().isEqualTo(L1Read.Miss(false)) }
    }

    /**
     * 经引擎的同步 `put` 被中断（RFC 0005 §4.3）：中断不到达存储，调用方等它的命令结束（这里是超时）才返回，
     * 以写入的失败（`CacheWriteException`，原因是超时）结束并保留中断标志；被放弃的命令尚未写出，之后也不再写出。
     * 单机（断线期间缓冲）与集群（等待节点连接）。
     */
    @Test
    fun interruptedSyncPutThroughTheEngineWaitsForItsCommandAndIsNeverWritten() {
        val timeout = Duration.ofMillis(300)
        val connection = unwritable(timeout, 1)
        val storageKey = connection.keys.single()
        RedisTestSupport.loadScripts(RedisScripts.PUT, RedisScripts.READ)
        val runtime = CacheRuntime.builder().backend(connection.backend).build().closing()
        val cache = runtime.create(storeSpec<String, String>(namespace) { codec = StringCodec })
        val key = storageKey.removePrefix("cocache:$namespace:")
        connection.stall().use {
            val caller = fork("interrupted-put") {
                val failure = runCatching { cache.put(key, "stale") }.exceptionOrNull()
                failure to Thread.currentThread().isInterrupted
            }
            // 同步变体发出命令之后停在 runBlocking 的事件循环上
            awaitCondition("caller waiting for its put") { caller.parked }
            caller.thread.interrupt()
            val (failure, interrupted) = caller.join()
            failure.assert().isInstanceOf(CacheWriteException::class.java)
            failure?.cause.assertTimedOut(timeout)
            interrupted.assert().withFailMessage { "The interrupt flag was not restored." }.isTrue()
        }
        l1 { connection.store.read(storageKey, null, null, null) }.assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 取消脚本调用（超时、等待被中断）时以异常完成 `dispatch` 返回的命令，Lettuce 因此不再写出它；NOSCRIPT 之后与超时并发发出的
     * `EVAL` 同样被完成。回退的 `EVAL` 发不出去时调用以该异常失败，而不是永远等待。
     */
    @Test
    fun cancellingAScriptCallCompletesTheDispatchedCommand() {
        val dispatched = CopyOnWriteArrayList<RedisCommand<ByteArray, ByteArray, *>>()
        var onDispatch: (RedisCommand<ByteArray, ByteArray, *>) -> Unit = {}
        val commands = mockk<StatefulRedisConnection<ByteArray, ByteArray>>(relaxed = true)
        every { commands.dispatch(any<RedisCommand<ByteArray, ByteArray, Any>>()) } answers {
            val command = firstArg<RedisCommand<ByteArray, ByteArray, Any>>()
            dispatched.add(command)
            onDispatch(command)
            command
        }
        val client = mockk<RedisClient>()
        every { client.connect(ByteArrayCodec.INSTANCE) } returns commands
        every { client.connectPubSub(ByteArrayCodec.INSTANCE) } returns mockk(relaxed = true)
        every { client.resources } returns mockk(relaxed = true)
        val keys = listOf("k".toByteArray())
        RedisConnections(client, Duration.ofSeconds(1)).use { connections ->
            val call = connections.evalAsync<List<Any?>>(RedisScripts.READ, keys, emptyList())
            call.cancel(false)
            dispatched.single().isDone.assert().isTrue()

            dispatched.clear()
            lateinit var racing: CompletableFuture<List<Any?>>
            onDispatch = { command -> if (command.type == CommandType.EVAL) racing.cancel(false) }
            racing = connections.evalAsync(RedisScripts.READ, keys, emptyList())
            dispatched[0].completeExceptionally(RedisNoScriptException("NOSCRIPT No matching script."))
            dispatched[1].type.assert().isEqualTo(CommandType.EVAL)
            dispatched[1].isDone.assert().isTrue()
            racing.isCancelled.assert().isTrue()

            dispatched.clear()
            val refused = RedisException("Connection closed")
            onDispatch = { command -> if (command.type == CommandType.EVAL) throw refused }
            val failing = connections.evalAsync<List<Any?>>(RedisScripts.READ, keys, emptyList())
            dispatched[0].completeExceptionally(RedisNoScriptException("NOSCRIPT No matching script."))
            failing.failure().assert().isSameAs(refused)
        }
    }

    /**
     * NOSCRIPT 在 `EVALSHA` 的 `dispatch` 返回之前就到达（I/O 线程先于调用方线程完成它，#808）：回退的 `EVAL` 先发出、登记，
     * `EVALSHA` 的 `dispatch` 才返回，不能顶替它。之后取消调用（直接取消，或超时：时间轮到期）时完成的是仍在排队的 `EVAL`，
     * Lettuce 因此不再写出它；NOSCRIPT 在 `dispatch` 返回之后到达时相同。中断不取消调用（RFC 0005 §4.3），超时是唯一的入口。
     */
    @Test
    fun cancellingAfterAnEarlyNoScriptCompletesTheQueuedFallback() {
        val dispatched = CopyOnWriteArrayList<RedisCommand<ByteArray, ByteArray, *>>()
        val early = AtomicBoolean(true)
        val noScript = RedisNoScriptException("NOSCRIPT No matching script.")
        val commands = mockk<StatefulRedisConnection<ByteArray, ByteArray>>(relaxed = true)
        every { commands.dispatch(any<RedisCommand<ByteArray, ByteArray, Any>>()) } answers {
            val command = firstArg<RedisCommand<ByteArray, ByteArray, Any>>()
            dispatched.add(command)
            if (early.get() && command.type == CommandType.EVALSHA) {
                command.completeExceptionally(noScript)
            }
            command
        }
        val expiries = CopyOnWriteArrayList<TimerTask>()
        val timer = mockk<Timer>()
        every { timer.newTimeout(any(), any(), any()) } answers {
            expiries.add(firstArg())
            mockk(relaxed = true)
        }
        val resources = mockk<ClientResources>(relaxed = true)
        every { resources.timer() } returns timer
        val client = mockk<RedisClient>()
        every { client.connect(ByteArrayCodec.INSTANCE) } returns commands
        every { client.connectPubSub(ByteArrayCodec.INSTANCE) } returns mockk(relaxed = true)
        every { client.resources } returns resources
        val keys = listOf("k".toByteArray())
        val timeout = Duration.ofSeconds(1)
        RedisConnections(client, timeout).use { connections ->
            fun queuedFallback(): RedisCommand<ByteArray, ByteArray, *> {
                dispatched.map { it.type }.assert().containsExactly(CommandType.EVALSHA, CommandType.EVAL)
                dispatched[1].isDone.assert().isFalse()
                return dispatched[1]
            }

            val cancelled = connections.evalAsync<List<Any?>>(RedisScripts.PUT, keys, emptyList())
            val first = queuedFallback()
            cancelled.cancel(false)
            first.isDone.assert().withFailMessage { "the queued EVAL is still writable after the call was cancelled" }
                .isTrue()

            dispatched.clear()
            val timed = connections.bounded(connections.evalAsync<List<Any?>>(RedisScripts.PUT, keys, emptyList()))
            val second = queuedFallback()
            expiries.removeAt(0).run(mockk(relaxed = true))
            timed.failure().assertTimedOut(timeout)
            second.isDone.assert().withFailMessage { "the queued EVAL is still writable after the call timed out" }
                .isTrue()

            early.set(false)
            dispatched.clear()
            val late = connections.evalAsync<List<Any?>>(RedisScripts.PUT, keys, emptyList())
            dispatched.single().completeExceptionally(noScript)
            val third = queuedFallback()
            late.cancel(false)
            third.isDone.assert().isTrue()
        }
    }

    /**
     * 超时的 fill 已经写出：Redis 照常执行，值写入；随后的释放令牌不匹配，什么也不做（不删除刚填充的值）。单机与集群。
     */
    @Test
    fun fillThatRunsAfterItsTimeoutKeepsItsValue() {
        val timeout = Duration.ofMillis(200)
        val redis = RedisTestSupport.pausable().closing()
        val backend = RedisTestSupport.backend(
            RedisBackendOptions(commandTimeout = timeout, clientResources = redis.resources)
        ).closing()
        val store = backend.store(Keyspace("cocache:", namespace))
        RedisTestSupport.loadScripts(RedisScripts.READ, RedisScripts.FILL, RedisScripts.RELEASE)
        val warm = key("{fill}warm")
        l1 { store.read(warm, null, null, null) }
        val fills = listOf(key("{fill}sync"), key("{fill}async"), key("{fill}all")).map { key ->
            val lease = lease()
            l1 { store.read(key, lease, null, null) }.assert().isEqualTo(L1Read.Miss(true))
            Fill(key, lease.token, scalar("filled"), Duration.ofMinutes(1))
        }
        redis.pause(PAUSE_MILLIS)
        runCatching { l1 { store.fill(fills[0]) } }.exceptionOrNull().assertTimedOut(timeout)
        runCatching { l1 { store.fill(fills[1]) } }.exceptionOrNull().assertTimedOut(timeout)
        runCatching { l1 { store.fillAll(listOf(fills[2])) } }.exceptionOrNull().assertTimedOut(timeout)
        awaitCondition("pause over") { runCatching { l1 { store.read(warm, null, null, null) } }.isSuccess }
        fills.forEach { fill ->
            val read = l1 { store.read(fill.key, null, null, null) } as L1Read.Hit
            read.envelope.assert().isEqualTo(scalar("filled"))
        }
    }

    /**
     * 原生的 [RedisL1Store]（RFC 0005 §4.3，M3d）：超时的写入尚未写出时不再写出（之后写出会覆盖更新的值）；结果未知的填充与释放
     * 尽力释放令牌，被取消、没有写出的填充否则留下 lease 到 `lease.ttl`。单机（断线期间缓冲）与集群（等待节点连接）。
     */
    @Test
    fun timedOutL1StoreCommandsAreNeverWrittenAndReleaseTheirTokens() {
        val timeout = Duration.ofMillis(300)
        val connection = unwritable(timeout, 6)
        val store = connection.backend.l1Store(namespace)
        val keys = connection.keys
        // 令牌由另一个后端取得（被阻断的后端在集群中还没有到这些 key 的节点连接）
        val direct = RedisTestSupport.backend().closing().store(Keyspace("cocache:", namespace))
        val tokens = keys.drop(3).associateWith { key ->
            lease().also { l1 { direct.read(key, it, null, null) }.assert().isEqualTo(L1Read.Miss(true)) }.token
        }
        fun fill(index: Int) = Fill(keys[index], tokens.getValue(keys[index]), scalar("late"), Duration.ofMinutes(1))
        RedisTestSupport.loadScripts(
            RedisScripts.PUT,
            RedisScripts.PUT_ALL,
            RedisScripts.READ,
            RedisScripts.FILL,
            RedisScripts.RELEASE
        )
        val caller = CallerDispatcher().closing()
        connection.stall().use {
            listOf(
                caller.start { store.put(keys[0], scalar("stale"), null, frame(keys[0])) },
                caller.start {
                    store.putAll(
                        listOf(Put(keys[1], scalar("stale"), null), Put(keys[2], scalar("stale"), null)),
                        frame(keys[1], keys[2])
                    )
                },
                caller.start { store.fill(fill(3)) },
                caller.start { store.fillAll(listOf(fill(4))) },
                caller.start { store.release(keys[5], tokens.getValue(keys[5])) },
            ).forEach { it.failure().assertTimedOut(timeout) }
        }
        keys.forEach { l1 { connection.store.read(it, null, null, null) }.assert().isEqualTo(L1Read.Miss(false)) }
    }

    private companion object {
        /**
         * 暂停时长：留足余量让暂停覆盖全部读取（同步调用逐个等到超时）。
         */
        const val PAUSE_MILLIS = 2_000L
    }
}
