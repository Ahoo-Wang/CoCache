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
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.cluster.models.partitions.Partitions
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection
import io.lettuce.core.cluster.pubsub.api.async.RedisClusterPubSubAsyncCommands
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands
import io.mockk.every
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 心跳失败之后的强制重连与并发探测：Cluster 经典 Pub/Sub 经集群写入器断开默认连接，Sharded 模式并发 PING 各节点，
 * 一组阻塞等待共享一个截止时间。
 */
class RedisReconnectTest {
    /**
     * Cluster 连接的外层写入器是 Lettuce 包私有的集群写入器：经反射调用它公开的 `disconnectDefaultEndpoint()`
     * 断开默认连接（经典 Pub/Sub 的订阅连接），而不是拆不到端点、只告警。Lettuce 6.8 与 7.x 上都解析得到。
     */
    @Test
    fun disconnectUnwrapsTheClusterWriter() {
        val method = requireNotNull(RedisInvalidationChannel.disconnectDefaultEndpoint)
        method.declaringClass.name.assert().isEqualTo("io.lettuce.core.cluster.ClusterDistributionChannelWriter")
        RedisInvalidationChannel.disconnectClusterDefault(Any()).assert().isFalse()

        val writer = FakeClusterWriter()
        val fake = FakeClusterWriter::class.java.getMethod("disconnectDefaultEndpoint")
        RedisInvalidationChannel.disconnectClusterDefault(writer, fake).assert().isTrue()
        writer.disconnects.get().assert().isEqualTo(1)
        RedisInvalidationChannel.disconnectClusterDefault(writer, null).assert().isFalse()
        // 拆不出默认端点时（Lettuce 抛出 IllegalStateException）返回 false，心跳照旧告警
        val broken = BrokenClusterWriter::class.java.getMethod("disconnectDefaultEndpoint")
        RedisInvalidationChannel.disconnectClusterDefault(BrokenClusterWriter(), broken).assert().isFalse()
    }

    class FakeClusterWriter {
        val disconnects = AtomicInteger()

        fun disconnectDefaultEndpoint() {
            disconnects.incrementAndGet()
        }
    }

    class BrokenClusterWriter {
        fun disconnectDefaultEndpoint(): Unit = throw IllegalStateException("Cannot unwrap defaultWriter")
    }

    /**
     * 真实的集群订阅连接：断开默认连接之后由 Lettuce 重连（client id 改变）。
     */
    @Test
    fun disconnectForcesReconnectOfClusterPubSub() {
        Assumptions.assumeTrue(RedisTestSupport.isCluster, "Redis Cluster only")
        val client = RedisClusterClient.create(RedisTestSupport.clusterUris())
        try {
            client.connectPubSub().use { connection ->
                val before = connection.sync().clientId()
                RedisInvalidationChannel.disconnect(connection).assert().isTrue()
                awaitCondition(
                    "reconnected"
                ) { runCatching { connection.sync().clientId() != before }.getOrDefault(false) }
            }
        } finally {
            client.shutdown()
        }
    }

    /**
     * 阻塞等待一组 future：同时计时，一共至多一个超时，而不是逐个等待的 N 个；按顺序返回各自的结果，失败的映射同 join。
     */
    @Test
    fun joinAllWaitsForEveryFutureWithinOneTimeout() {
        assumeStandalone()
        val client = RedisClient.create(RedisURI.create("localhost", 6379))
        try {
            RedisConnections(client, Duration.ofMillis(300)).use { connections ->
                val runtime = IllegalStateException("runtime")
                val started = System.nanoTime()
                Thread.currentThread().interrupt()
                val outcomes = connections.joinAll(
                    listOf(
                        CompletableFuture<String>(),
                        CompletableFuture.completedFuture("v"),
                        CompletableFuture<String>(),
                        CompletableFuture.failedFuture(runtime),
                        CompletableFuture<String>(),
                    )
                )
                val elapsed = Duration.ofNanos(System.nanoTime() - started)
                // 不响应中断：等满截止时间，保留中断标志
                Thread.interrupted().assert().isTrue()
                elapsed.assert().isGreaterThanOrEqualTo(Duration.ofMillis(300))
                elapsed.assert().isLessThan(Duration.ofMillis(900))
                outcomes.map { it.getOrNull() }.assert().containsExactly(null, "v", null, null, null)
                outcomes[0].exceptionOrNull()?.message.assert().isEqualTo("Command timed out after PT0.3S.")
                outcomes[3].exceptionOrNull().assert().isSameAs(runtime)
                connections.joinAll(emptyList<CompletableFuture<String>>()).assert().isEmpty()
            }
        } finally {
            client.shutdown()
        }
    }

    /**
     * Sharded 模式的心跳并发探测各节点：先发出全部节点的 PING 再一起等待（一共至多一个 commandTimeout），
     * 每个失败的节点都被断开重连，失败合并为一个异常。
     */
    @Test
    fun shardedChannelPingsClusterNodesConcurrently() {
        val pings = CopyOnWriteArrayList<Int>()
        val nodeRequests = CopyOnWriteArrayList<Int>()
        fun nodeConnection(port: Int) = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>(
            "async" to {
                stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>(
                    "ping" to {
                        pings.add(port)
                        FutureAdapter(CompletableFuture<String>())
                    },
                )
            },
        )
        val half = SlotHash.SLOT_COUNT / 2
        val partitions = Partitions().apply {
            add(clusterNode("a", 7000, (0 until half).toList(), RedisClusterNode.NodeFlag.UPSTREAM))
            add(clusterNode("b", 7001, (half until SlotHash.SLOT_COUNT).toList(), RedisClusterNode.NodeFlag.UPSTREAM))
            updateCache()
        }
        val async = stub<RedisClusterPubSubAsyncCommands<ByteArray, ByteArray>>(
            "ssubscribe" to { completed<Void?>(null) }
        )
        val pubSub = stub<StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>>(
            "setNodeMessagePropagation" to { null },
            "addListener" to { null },
            "async" to { async },
            "getPartitions" to { partitions },
            "getConnectionAsync" to { args ->
                val port = args[1] as Int
                nodeRequests.add(port)
                CompletableFuture.completedFuture(nodeConnection(port))
            },
        )
        val connections = connectionsOf(pubSub)
        val issuedBeforeWait = AtomicInteger(-1)
        every { connections.joinAll(any<List<CompletableFuture<Any?>>>()) } answers {
            issuedBeforeWait.set(pings.size)
            firstArg<List<CompletableFuture<Any?>>>().map { Result.failure(RedisException("Command timed out")) }
        }
        val low = namespaceOnSlots(0 until half)
        val high = namespaceOnSlots(half until SlotHash.SLOT_COUNT)
        ShardedInvalidationChannel(connections, Duration.ofHours(1), Duration.ofMillis(10)).use { channel ->
            channel.subscribe(Keyspace("cocache:", low), RecordingListener())
            channel.subscribe(Keyspace("cocache:", high), RecordingListener())
            val failure = runCatching { channel.ping() }.exceptionOrNull()
            // 两个节点的 PING 都在等待之前发出
            issuedBeforeWait.get().assert().isEqualTo(2)
            pings.assert().containsExactlyInAnyOrder(7000, 7001)
            failure.assert().isInstanceOf(RedisException::class.java)
            requireNotNull(failure).suppressed.size.assert().isEqualTo(1)
            // 每个节点：一次取连接发 PING，一次取连接断开重连
            nodeRequests.filter { it == 7000 }.size.assert().isEqualTo(2)
            nodeRequests.filter { it == 7001 }.size.assert().isEqualTo(2)
        }
    }

    /**
     * 频道 `cocache:inv:{namespace}` 落在 [slots] 中的命名空间。
     */
    private fun namespaceOnSlots(slots: IntRange): String {
        return generateSequence(0) { it + 1 }.map { "ns$it" }
            .first { SlotHash.getSlot("cocache:inv:$it".toByteArray()) in slots }
    }

    private fun clusterNode(id: String, port: Int, slots: List<Int>, flag: RedisClusterNode.NodeFlag): RedisClusterNode {
        return RedisClusterNode(RedisURI.create("localhost", port), id, true, null, 0, 0, 0, slots, setOf(flag))
    }

    private fun assumeStandalone() = Assumptions.assumeFalse(RedisTestSupport.isCluster, "standalone Redis only")
}
