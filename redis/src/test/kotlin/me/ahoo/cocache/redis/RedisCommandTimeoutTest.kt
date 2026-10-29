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
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.test.Concurrency.awaitCondition
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
 * 命令超时之后（结果未知）：尚未写出的命令不再写出（超时时取消 Lettuce 命令），超时的读取可能取得的令牌被尽力释放。
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

    private fun frame(vararg keys: String) = InvalidationFrame.encode(node, keys.toList())

    private fun scalar(text: String) = Envelope.Scalar(text.toByteArray())

    private fun <T> CompletableFuture<T>.failure(): Throwable? = handle { _, e -> e }.get(10, TimeUnit.SECONDS)

    private fun Throwable?.assertTimedOut(timeout: Duration) {
        this.assert().isInstanceOf(RedisException::class.java)
        this?.message.assert().isEqualTo("Command timed out after $timeout.")
    }

    /**
     * 调用方的客户端（`RedisBackend.of`）以默认的断线行为在重连期间缓冲命令：超时的写入不能在重连后重放，
     * 否则调用方已经看到失败的 `put` 稍后生效，覆盖之后写入的值。
     */
    @Test
    fun timedOutWriteBufferedWhileDisconnectedIsNotReplayed() {
        assumeFalse(RedisTestSupport.isCluster)
        val proxy = RedisTestSupport.proxy().closing()
        val client = RedisClient.create(RedisURI.create(proxy.address, proxy.port))
        closeables.add(AutoCloseable { client.shutdown() })
        val timeout = Duration.ofMillis(300)
        val backend = RedisBackend.of(client, RedisBackendOptions(commandTimeout = timeout)).closing()
        val store = backend.store(namespace) as AsyncRemoteStore
        // 先缓存脚本：重放的 EVALSHA 不会得到 NOSCRIPT
        store.read(key("warm"), null, null)
        // 断开并让重连停在握手上：重连失败会让 Lettuce 以连接错误快速拒绝新命令，而不是缓冲
        val restore = proxy.stall(disconnect = true)
        // 客户端发现断线之后命令才进入缓冲、以超时失败（此前写出的命令以连接关闭失败）
        awaitCondition("commands are buffered") {
            store.readAsync(key("probe"), null, null).failure()?.message == "Command timed out after $timeout."
        }
        runCatching {
            store.put(key("sync"), scalar("stale"), null, frame(key("sync")))
        }.exceptionOrNull().assertTimedOut(timeout)
        store.putAsync(key("async"), scalar("stale"), null, frame(key("async"))).failure().assertTimedOut(timeout)
        restore.close()
        // 重连后缓冲的命令按序重放，这次读取排在它们之后
        awaitCondition("reconnected") { runCatching { store.read(key("warm"), null, null) }.isSuccess }
        store.read(key("sync"), null, null).assert().isEqualTo(RemoteRead.Miss(false))
        store.read(key("async"), null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    /**
     * 集群中等待建立节点连接的命令（首次访问某节点、拓扑变化后）Lettuce 不计时：超时之后它不能在连接建立时才写出、执行，
     * 覆盖之后写入的值。节点连接经地址映射走 [TcpProxy]，代理暂不转发时连接迟迟建立不起来。
     */
    @Test
    fun timedOutWriteWaitingForANodeConnectionIsNeverWritten() {
        assumeTrue(RedisTestSupport.isCluster)
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
        val timeout = Duration.ofMillis(300)
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
            .take(2)
            .toList()
        // 先缓存脚本：否则迟到的 EVALSHA 得到 NOSCRIPT（放弃的调用不再回退 EVAL），复现不出迟到的写入
        RedisTestSupport.loadScripts(RedisScripts.PUT, RedisScripts.READ)
        val release = requireNotNull(proxies[target.uri.port]).stall()
        runCatching {
            store.put(keys[0], scalar("stale"), null, frame(keys[0]))
        }.exceptionOrNull().assertTimedOut(timeout)
        store.putAsync(keys[1], scalar("stale"), null, frame(keys[1])).failure().assertTimedOut(timeout)
        release.close()
        // 第一次成功的读取说明节点连接已建立（等待它的命令此时已写出或已跳过），之后的读取排在它们之后
        awaitCondition("node connection established") { runCatching { store.read(keys[0], null, null) }.isSuccess }
        keys.forEach { store.read(it, null, null).assert().isEqualTo(RemoteRead.Miss(false)) }
    }

    /**
     * 取消脚本调用（超时）时以异常完成 `dispatch` 返回的命令，Lettuce 因此不再写出它；NOSCRIPT 之后与超时并发发出的 `EVAL`
     * 同样被完成。回退的 `EVAL` 发不出去时调用以该异常失败，而不是永远等待。
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
        fun lease() = LeaseRequest(token(), Duration.ofMinutes(1))
        fun refresh() = RefreshRequest(Duration.ofMinutes(5), token(), Duration.ofMinutes(1))
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

    private companion object {
        /**
         * 暂停时长：集群中逐个节点暂停需要时间，留足余量让暂停覆盖全部读取。
         */
        const val PAUSE_MILLIS = 2_000L
    }
}
