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
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.models.partitions.Partitions
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.codec.ByteArrayCodec
import io.mockk.every
import io.mockk.mockk
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * 命令连接的心跳：连续多次 PING 失败才断开重连（一次队头阻塞不断开），成功一次即清零；健康状态只读、不访问 Redis。
 */
class CommandConnectionHeartbeatTest {
    private val timeout = Duration.ofMillis(300)

    /**
     * 经 [proxy] 连接单机 Redis 的命令连接与它的心跳（不自动调度，由用例调用 `probe()` 驱动）。握手超时短：
     * 应答被丢弃期间开始的重连握手很快失败、重试，而不是等满默认的 60 s。
     */
    private fun <T> withProxiedHeartbeat(
        failures: Int,
        block: (TcpProxy, RedisConnections, CommandConnectionHeartbeat) -> T
    ): T {
        Assumptions.assumeFalse(RedisTestSupport.isCluster, "standalone Redis only")
        RedisTestSupport.proxy().use { proxy ->
            val client = RedisClient.create(
                RedisURI.builder().withHost(
                    proxy.address
                ).withPort(proxy.port).withTimeout(Duration.ofSeconds(1)).build()
            )
            try {
                RedisConnections(client, timeout).use { connections ->
                    val heartbeat =
                        CommandConnectionHeartbeat(connections, Duration.ofHours(1), failures, schedule = false)
                    return block(proxy, connections, heartbeat)
                }
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun consecutiveFailuresForceAReconnect() {
        withProxiedHeartbeat(failures = 3) { proxy, _, heartbeat ->
            heartbeat.probe()
            heartbeat.lastFailure.assert().isNull()
            val dropping = proxy.dropReplies()
            heartbeat.probe()
            heartbeat.lastFailure.assert().startsWith("redis: ")
            heartbeat.probe()
            // 前两次失败不断开：误判会把在途写入变成结果未知
            heartbeat.reconnects.get().assert().isZero()
            heartbeat.probe()
            heartbeat.reconnects.get().assert().isEqualTo(1)
            dropping.close()
            // 断开之后 Lettuce 重连，新连接照常应答
            awaitCondition("command connection recovered") {
                heartbeat.probe()
                heartbeat.lastFailure == null
            }
            heartbeat.reconnects.get().assert().isEqualTo(1)
        }
    }

    /**
     * 一次超时（这里是 TcpProxy 的暂停，同大载荷的队头阻塞）之后成功即清零：不连续的失败从不断开。
     */
    @Test
    fun aSuccessResetsTheCount() {
        withProxiedHeartbeat(failures = 2) { proxy, _, heartbeat ->
            repeat(3) {
                proxy.pause(timeout.toMillis() * 2)
                heartbeat.probe()
                heartbeat.lastFailure.assert().isNotNull()
                awaitCondition("pause over") {
                    heartbeat.probe()
                    heartbeat.lastFailure == null
                }
            }
            heartbeat.reconnects.get().assert().isZero()
        }
    }

    /**
     * `0`：只报告状态，从不断开。
     */
    @Test
    fun zeroOnlyReports() {
        withProxiedHeartbeat(failures = 0) { proxy, _, heartbeat ->
            proxy.dropReplies().use {
                repeat(4) { heartbeat.probe() }
            }
            heartbeat.lastFailure.assert().isNotNull()
            heartbeat.reconnects.get().assert().isZero()
        }
    }

    /**
     * 断不开的连接（拆不到端点）只告警；建立不起来的连接没有可断开的。
     */
    @Test
    fun aConnectionThatCannotBeDroppedOnlyWarns() {
        withProxiedHeartbeat(failures = 1) { proxy, connections, _ ->
            val heartbeat = CommandConnectionHeartbeat(connections, Duration.ofHours(1), 1, { false }, schedule = false)
            proxy.dropReplies().use { heartbeat.probe() }
            heartbeat.reconnects.get().assert().isZero()
            heartbeat.lastFailure.assert().isNotNull()
        }
    }

    /**
     * 心跳任务兜住任何异常（否则定时任务从此不再执行），记为最近一次失败；建立不起来的节点连接没有可断开的。
     */
    @Test
    fun theScheduledTaskSurvivesFailuresAndSkipsUnestablishedConnections() {
        val connections = mockk<RedisConnections>()
        every { connections.commandConnections() } throws IllegalStateException("topology unavailable")
        CommandConnectionHeartbeat(connections, Duration.ofMillis(10), 1).use { heartbeat ->
            awaitCondition("task failure recorded") { heartbeat.lastFailure?.contains("topology unavailable") == true }
        }

        val refused = CompletableFuture.failedFuture<StatefulConnection<ByteArray, ByteArray>>(
            RedisConnectionException("Connection refused")
        )
        every { connections.commandConnections() } returns mapOf("10.0.0.1:7000" to refused)
        every { connections.joinAll(any<List<CompletableFuture<Any?>>>()) } answers {
            firstArg<List<CompletableFuture<Any?>>>().map { runCatching { it.join() } }
        }
        val disconnected = AtomicInteger()
        val heartbeat = CommandConnectionHeartbeat(
            connections,
            Duration.ofHours(1),
            1,
            { disconnected.incrementAndGet() > 0 },
            schedule = false
        )
        heartbeat.probe()
        heartbeat.lastFailure.assert().startsWith("10.0.0.1:7000: ")
        disconnected.get().assert().isZero()
        heartbeat.reconnects.get().assert().isZero()
    }

    /**
     * 集群：每个主节点的节点连接都是心跳目标（副本不是），键为 `host:port`。
     */
    @Test
    fun clusterTargetsEveryMasterNodeConnection() {
        val master = clusterNode("m", "10.0.0.1", 7000, RedisClusterNode.NodeFlag.UPSTREAM)
        val replica = clusterNode("r", "10.0.0.2", 7001, RedisClusterNode.NodeFlag.REPLICA)
        val nodeConnection = mockk<StatefulRedisConnection<ByteArray, ByteArray>>()
        val commands = mockk<StatefulRedisClusterConnection<ByteArray, ByteArray>>(relaxed = true)
        every { commands.partitions } returns Partitions().apply {
            add(master)
            add(replica)
            updateCache()
        }
        every { commands.getConnectionAsync("m") } returns CompletableFuture.completedFuture(nodeConnection)
        val client = mockk<RedisClusterClient>()
        every { client.connect(ByteArrayCodec.INSTANCE) } returns commands
        every { client.connectPubSub(ByteArrayCodec.INSTANCE) } returns mockk(relaxed = true)
        every { client.resources } returns mockk(relaxed = true)
        RedisConnections(client, Duration.ofSeconds(1)).use { connections ->
            val targets = connections.commandConnections()
            targets.keys.assert().containsExactly("10.0.0.1:7000")
            targets.getValue("10.0.0.1:7000").join().assert().isSameAs(nodeConnection)
        }
    }

    private fun clusterNode(id: String, host: String, port: Int, flag: RedisClusterNode.NodeFlag): RedisClusterNode =
        RedisClusterNode(RedisURI.create(host, port), id, true, null, 0, 0, 0, emptyList(), setOf(flag))

    /**
     * 后端的接线：每个主节点一条命令连接（单机一条），健康状态在心跳之前为正常；选项拒绝负数。
     */
    @Test
    fun theBackendPingsEveryCommandConnection() {
        RedisTestSupport.backend(
            RedisBackendOptions(
                clientName = "cocache-cmd-hb-" + java.util.UUID.randomUUID(),
                heartbeat = Duration.ofMillis(50)
            )
        ).use { backend ->
            backend.commandConnectionHealthy.assert().isTrue()
            backend.commandConnectionFailure.assert().isNull()
            val expected = if (RedisTestSupport.isCluster) RedisTestSupport.masters().size else 1
            backend.redisChannel.connections.commandConnections().size.assert().isEqualTo(expected)
            backend.commandHeartbeat.probe()
            backend.commandHeartbeat.lastFailure.assert().isNull()
        }
        runCatching { RedisBackendOptions(commandHeartbeatFailures = -1) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
