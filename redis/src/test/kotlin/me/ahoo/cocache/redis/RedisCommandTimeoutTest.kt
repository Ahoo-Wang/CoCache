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
import io.lettuce.core.resource.DefaultClientResources
import io.lettuce.core.resource.MappingSocketAddressResolver
import io.mockk.every
import io.mockk.mockk
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
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

/**
 * 命令超时或等待被中断之后（结果未知）：尚未写出的命令不再写出（取消 Lettuce 命令），取得或交回令牌的命令（读取、回收、填充、释放）
 * 尽力释放它们的令牌。
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

    private fun Throwable?.assertInterrupted() {
        this.assert().isInstanceOf(RedisException::class.java)
        this?.message.assert().isEqualTo("Interrupted while waiting for Redis.")
    }

    /**
     * 在已被中断的线程上执行 [block]（批量等待中第一个等待被中断之后，其余的等待就是这样），返回它抛出的异常；后端须恢复中断标志。
     */
    private fun whileInterrupted(block: () -> Unit): Throwable? {
        Thread.currentThread().interrupt()
        try {
            return runCatching(block).exceptionOrNull()
        } finally {
            Thread.interrupted().assert().withFailMessage { "The interrupt flag was not restored." }.isTrue()
        }
    }

    /**
     * 在后台线程执行 [block]，等它停在等待 Redis 应答上时中断它（`Future.cancel(true)`、Resilience4j `TimeLimiter`、
     * `shutdownNow` 都这样中断调用方），返回它抛出的异常；后端须恢复中断标志。
     */
    private fun interruptWhileWaiting(block: () -> Unit): Throwable? {
        val caller = fork("interrupted-caller") {
            val failure = runCatching(block).exceptionOrNull()
            check(Thread.currentThread().isInterrupted) { "The interrupt flag was not restored." }
            failure
        }
        awaitCondition("caller waiting for Redis") { caller.thread.state == Thread.State.TIMED_WAITING }
        caller.thread.interrupt()
        return caller.join()
    }

    /**
     * 发往 [keys] 的命令可以暂时无法写出（[stall]）：单机时缓冲在断开的连接上（调用方的客户端 `RedisBackend.of`，默认的断线行为
     * 在重连期间缓冲命令），集群时等待建立节点连接（Lettuce 不为它们计时）。
     */
    private class Unwritable(
        val store: AsyncRemoteStore,
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
        val store = backend.store(namespace) as AsyncRemoteStore
        // 先缓存脚本：重放的 EVALSHA 不会得到 NOSCRIPT
        store.read(key("warm"), null, null)
        return Unwritable(
            store,
            List(count) { key("late-$it") },
            stalling = {
                // 断开并让重连停在握手上：重连失败会让 Lettuce 以连接错误快速拒绝新命令，而不是缓冲
                val restore = proxy.stall(disconnect = true)
                // 客户端发现断线之后命令才进入缓冲、以超时失败（此前写出的命令以连接关闭失败）
                awaitCondition("commands are buffered") {
                    store.readAsync(key("probe"), null, null).failure()?.message == "Command timed out after $timeout."
                }
                restore
            },
            // 重连后缓冲的命令按序重放，之后的命令排在它们之后
            writable = {
                awaitCondition("reconnected") { runCatching { store.read(key("warm"), null, null) }.isSuccess }
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
        val store = backend.store(namespace) as AsyncRemoteStore
        // 启动时的探测（cocache:probe:{a}）没有访问过的主节点：后端还没有到它的节点连接
        val probeSlot = SlotHash.getSlot("cocache:probe:{a}")
        val target = masters.first { !it.hasSlot(probeSlot) }
        val keys = generateSequence(0) { it + 1 }
            .map { key("late-$it") }
            .filter { target.hasSlot(SlotHash.getSlot(it)) }
            .take(count)
            .toList()
        return Unwritable(
            store,
            keys,
            stalling = { requireNotNull(proxies[target.uri.port]).stall() },
            // 第一次成功的读取说明节点连接已建立（等待它的命令此时已写出或已跳过），之后的命令排在它们之后
            writable = {
                awaitCondition("node connection established") {
                    runCatching { store.read(keys[0], null, null) }.isSuccess
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
            val failure = runCatching { store.put(sync, scalar("stale"), null, frame(sync)) }.exceptionOrNull()
            failure.assertTimedOut(timeout)
            store.putAsync(async, scalar("stale"), null, frame(async)).failure().assertTimedOut(timeout)
        }
        store.read(sync, null, null).assert().isEqualTo(RemoteRead.Miss(false))
        store.read(async, null, null).assert().isEqualTo(RemoteRead.Miss(false))
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
            val failure = runCatching { store.put(sync, scalar("stale"), null, frame(sync)) }.exceptionOrNull()
            failure.assertTimedOut(timeout)
            store.putAsync(async, scalar("stale"), null, frame(async)).failure().assertTimedOut(timeout)
        }
        node.keys.forEach { store.read(it, null, null).assert().isEqualTo(RemoteRead.Miss(false)) }
    }

    /**
     * 等待被中断与超时一样（结果未知）：取消命令，尚未写出的写入永远不再写出。中断来自 `Future.cancel(true)`、`TimeLimiter`、
     * `shutdownNow`，或批量等待中前一个等待被中断（其余等待随即失败）；否则被中断的 `put` 在重连或节点连接建立之后才执行，
     * 覆盖之后写入的值。单机（断线期间缓冲）与集群（等待节点连接）。
     */
    @Test
    fun interruptedWriteIsNeverWritten() {
        val connection = unwritable(Duration.ofMillis(300), 3)
        val store = connection.store
        val (single, first, second) = connection.keys
        RedisTestSupport.loadScripts(RedisScripts.PUT, RedisScripts.PUT_ALL, RedisScripts.READ)
        connection.stall().use {
            whileInterrupted { store.put(single, scalar("stale"), null, frame(single)) }.assertInterrupted()
            whileInterrupted {
                store.putAll(
                    listOf(Put(first, scalar("stale"), null), Put(second, scalar("stale"), null)),
                    frame(first, second)
                )
            }.assertInterrupted()
        }
        connection.keys.forEach { store.read(it, null, null).assert().isEqualTo(RemoteRead.Miss(false)) }
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
            whileInterrupted { connections.eval<List<Any?>>(RedisScripts.READ, keys, emptyList()) }.assertInterrupted()
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
     * 读取超时（结果未知）：读脚本可能在超时之后才执行，授予 lease 或刷新令牌，调用方却已绕过 L1。后端尽力释放这次读取请求的令牌，
     * 否则 `lease.ttl` 内全集群对该 key 的未命中都要先等 `lease.wait`。同步、异步与批量读取；单机与集群。
     */
    @Test
    fun timedOutReadReleasesTheTokensItMayHaveTaken() {
        val timeout = Duration.ofMillis(200)
        val backend = RedisTestSupport.backend(RedisBackendOptions(commandTimeout = timeout)).closing()
        val store = backend.store(namespace) as AsyncRemoteStore
        // 同一 slot：节点连接已建立、脚本已缓存，暂停期间的读取与释放都已写出，暂停结束后按序执行。
        // 释放的脚本也要已缓存：暂停期间它同样超时，得到 NOSCRIPT 时不再回退 EVAL
        RedisTestSupport.loadScripts(RedisScripts.READ, RedisScripts.RELEASE)
        val warm = key("{lease}warm")
        val refreshed = key("{lease}refresh")
        store.put(refreshed, scalar("v"), Duration.ofMinutes(1), frame(refreshed))
        store.read(warm, null, null)
        val sync = key("{lease}sync")
        val async = key("{lease}async")
        val all = key("{lease}all")
        val allAsync = key("{lease}all-async")
        RedisTestSupport.pauseClients(PAUSE_MILLIS)
        val pending = listOf(
            store.readAsync(async, lease(), null),
            store.readAllAsync(listOf(allAsync), mapOf(allAsync to lease())),
        )
        runCatching { store.read(sync, lease(), null) }.exceptionOrNull().assertTimedOut(timeout)
        runCatching { store.readAll(listOf(all), mapOf(all to lease())) }.exceptionOrNull().assertTimedOut(timeout)
        runCatching { store.read(refreshed, null, refresh()) }.exceptionOrNull().assertTimedOut(timeout)
        pending.forEach { it.failure().assertTimedOut(timeout) }
        // 暂停结束后读取照常执行并授予令牌，随后执行的释放把它们收回：之后的读取不再看到 lease
        awaitCondition("pause over") { runCatching { store.read(warm, null, null) }.isSuccess }
        listOf(sync, async, all, allAsync).forEach {
            store.read(it, null, null).assert().isEqualTo(RemoteRead.Miss(false))
        }
        // 刷新令牌同样被收回：下一次刷新窗口内的读取能取得令牌
        val next = store.read(refreshed, null, refresh()) as RemoteRead.Hit
        next.refreshGranted.assert().isTrue()
    }

    /**
     * 等待读取时被中断（结果未知）与超时一样：读脚本照常执行、授予令牌，后端尽力释放这次读取请求的令牌。批量读取的第一个等待被中断之后，
     * 其余等待随即失败，它们的读取同样已发出、各自取得 lease，都要释放。条件回收（取得 lease）同样。单机与集群。
     */
    @Test
    fun interruptedReadReleasesTheTokensItMayHaveTaken() {
        // 超时远长于暂停：读取只会因中断失败
        val backend = RedisTestSupport.backend(RedisBackendOptions(commandTimeout = Duration.ofSeconds(10))).closing()
        val store = backend.store(namespace) as AsyncRemoteStore
        RedisTestSupport.loadScripts(RedisScripts.READ, RedisScripts.RECLAIM, RedisScripts.RELEASE)
        val warm = key("{lease}warm")
        val refreshed = key("{lease}refresh")
        val corrupt = key("{lease}corrupt")
        store.put(refreshed, scalar("v"), Duration.ofMinutes(1), frame(refreshed))
        RedisTestSupport.withCommands { it.set(corrupt, "garbage") }
        val observed = Digest.of((store.read(corrupt, null, null) as RemoteRead.Hit).envelope)
        store.read(warm, null, null)
        val sync = key("{lease}sync")
        val all = listOf(key("{lease}all-0"), key("{lease}all-1"), key("{lease}all-2"))
        RedisTestSupport.pauseClients(PAUSE_MILLIS)
        interruptWhileWaiting { store.read(sync, lease(), null) }.assertInterrupted()
        interruptWhileWaiting { store.readAll(all, all.associateWith { lease() }) }.assertInterrupted()
        interruptWhileWaiting { store.read(refreshed, null, refresh()) }.assertInterrupted()
        interruptWhileWaiting { store.reclaim(corrupt, observed, lease()) }.assertInterrupted()
        // 暂停结束后读取照常执行并授予令牌，随后执行的释放把它们收回：之后的读取不再看到 lease
        awaitCondition("pause over") { runCatching { store.read(warm, null, null) }.isSuccess }
        (all + sync + corrupt).forEach { store.read(it, null, null).assert().isEqualTo(RemoteRead.Miss(false)) }
        val next = store.read(refreshed, null, refresh()) as RemoteRead.Hit
        next.refreshGranted.assert().isTrue()
    }

    /**
     * fill 超时或等待被中断（结果未知）时尽力释放它的令牌：被取消、没有写出的 fill 否则留下 lease 到 `lease.ttl`，
     * 全集群对该 key 的未命中都要先等 `lease.wait`。释放本身超时或被中断、没有写出时同样重新发出。单机与集群。
     */
    @Test
    fun abandonedFillReleasesItsToken() {
        val timeout = Duration.ofMillis(300)
        val connection = unwritable(timeout, 11)
        val store = connection.store
        val keys = connection.keys
        // 令牌由另一个后端取得（被阻断的后端在集群中还没有到这些 key 的节点连接）
        val direct = RedisTestSupport.backend().closing().store(namespace)
        val tokens = keys.associateWith { key ->
            lease().also { direct.read(key, it, null).assert().isEqualTo(RemoteRead.Miss(true)) }.token
        }
        fun fill(index: Int) = Fill(keys[index], tokens.getValue(keys[index]), scalar("late"), Duration.ofMinutes(1))
        RedisTestSupport.loadScripts(RedisScripts.READ, RedisScripts.FILL, RedisScripts.RELEASE)
        connection.stall().use {
            runCatching { store.fill(fill(0)) }.exceptionOrNull().assertTimedOut(timeout)
            store.fillAsync(fill(1)).failure().assertTimedOut(timeout)
            runCatching { store.fillAll(listOf(fill(2), fill(3))) }.exceptionOrNull().assertTimedOut(timeout)
            store.fillAllAsync(listOf(fill(4), fill(5))).failure().assertTimedOut(timeout)
            whileInterrupted { store.fill(fill(6)) }.assertInterrupted()
            whileInterrupted { store.fillAll(listOf(fill(7), fill(8))) }.assertInterrupted()
            whileInterrupted { store.release(keys[9], tokens.getValue(keys[9])) }.assertInterrupted()
            store.releaseAsync(keys[10], tokens.getValue(keys[10])).failure().assertTimedOut(timeout)
        }
        keys.forEach { store.read(it, null, null).assert().isEqualTo(RemoteRead.Miss(false)) }
    }

    /**
     * 超时的 fill 已经写出：Redis 照常执行，值写入；随后的释放令牌不匹配，什么也不做（不删除刚填充的值）。单机与集群。
     */
    @Test
    fun fillThatRunsAfterItsTimeoutKeepsItsValue() {
        val timeout = Duration.ofMillis(200)
        val backend = RedisTestSupport.backend(RedisBackendOptions(commandTimeout = timeout)).closing()
        val store = backend.store(namespace) as AsyncRemoteStore
        RedisTestSupport.loadScripts(RedisScripts.READ, RedisScripts.FILL, RedisScripts.RELEASE)
        val warm = key("{fill}warm")
        store.read(warm, null, null)
        val fills = listOf(key("{fill}sync"), key("{fill}async"), key("{fill}all")).map { key ->
            val lease = lease()
            store.read(key, lease, null).assert().isEqualTo(RemoteRead.Miss(true))
            Fill(key, lease.token, scalar("filled"), Duration.ofMinutes(1))
        }
        RedisTestSupport.pauseClients(PAUSE_MILLIS)
        runCatching { store.fill(fills[0]) }.exceptionOrNull().assertTimedOut(timeout)
        store.fillAsync(fills[1]).failure().assertTimedOut(timeout)
        runCatching { store.fillAll(listOf(fills[2])) }.exceptionOrNull().assertTimedOut(timeout)
        awaitCondition("pause over") { runCatching { store.read(warm, null, null) }.isSuccess }
        fills.forEach { fill ->
            val read = store.read(fill.key, null, null) as RemoteRead.Hit
            read.envelope.assert().isEqualTo(scalar("filled"))
        }
    }

    private companion object {
        /**
         * 暂停时长：集群中逐个节点暂停需要时间，留足余量让暂停覆盖全部读取。
         */
        const val PAUSE_MILLIS = 2_000L
    }
}
