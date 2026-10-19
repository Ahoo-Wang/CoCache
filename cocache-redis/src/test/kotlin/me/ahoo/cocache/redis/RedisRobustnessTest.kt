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

import io.lettuce.core.RedisException
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Redis 后端的故障与边界场景（真实 Redis；集群环境下同样运行，除非注明）。
 */
class RedisRobustnessTest {
    private val node = NodeId.random()
    private val seq = AtomicLong()
    private val namespace = "robust-" + UUID.randomUUID()

    private fun token() = Token(node, seq.incrementAndGet())

    private fun lease() = LeaseRequest(token(), Duration.ofSeconds(60))

    private fun key(name: String) = "cocache:$namespace:$name"

    /**
     * 5.x 遗留的原生 Set / List / ZSet 落在缓存 key 上：读取按无法识别的内容返回（不报 WRONGTYPE），
     * 以引擎同样的摘要（[Digest.of]）条件回收为 lease，然后正常填充。
     */
    @Test
    fun nativeCollectionUnderCacheKeyIsReclaimedAndFilled() {
        RedisTestSupport.backend().use { backend ->
            val store = backend.store(namespace)
            val keys = listOf(key("set"), key("list"), key("zset"))
            RedisTestSupport.withCommands { commands ->
                commands.sadd(keys[0], "a", "b")
                commands.rpush(keys[1], "a", "b")
                commands.zadd(keys[2], 1.0, "a")
            }
            for (key in keys) {
                // 不申请 lease 的读取同样不能失败
                (store.read(key, null, null) as RemoteRead.Hit).format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)
                val lease = lease()
                val read = store.read(key, lease, null) as RemoteRead.Hit
                read.format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)
                store.reclaim(key, Digest.of(read.envelope), lease).assert().isTrue()
                store.read(key, null, null).assert().isInstanceOf(RemoteRead.Leased::class.java)
                store.fill(
                    Fill(key, lease.token, Envelope.Scalar("v".toByteArray()), Duration.ofSeconds(60))
                ).assert().isTrue()
                val filled = store.read(key, null, null) as RemoteRead.Hit
                filled.format.assert().isEqualTo(RedisScripts.FORMAT)
                String((filled.envelope as Envelope.Scalar).bytes).assert().isEqualTo("v")
            }
            // 内容在读取之后变了：摘要不匹配，不回收
            RedisTestSupport.withCommands { it.sadd(key("changed"), "a") }
            val lease = lease()
            val read = store.read(key("changed"), lease, null) as RemoteRead.Hit
            RedisTestSupport.withCommands { it.set(key("changed"), "other") }
            store.reclaim(key("changed"), Digest.of(read.envelope), lease).assert().isFalse()
        }
    }

    /**
     * 批量读取中一个 key 失败：其余读取取得的 lease 被释放，而不是等 lease.ttl 到期。
     */
    @Test
    fun readAllReleasesGrantedLeasesWhenAnyReadFails() {
        val allowed = "cocache:allowed-$namespace:k"
        val denied = "cocache:denied-$namespace:k"
        RedisTestSupport.aclBackend(
            "cocache-readall",
            listOf("resetkeys", "~cocache:allowed-*", "&*", "+@all")
        ).use { backend ->
            val store = backend.store(namespace)
            val leases = mapOf(allowed to lease(), denied to lease())
            val error = runCatching { store.readAll(listOf(allowed, denied), leases) }.exceptionOrNull()
            error.assert().isInstanceOf(RedisException::class.java)
            awaitCondition("granted lease released") {
                RedisTestSupport.withCommands { it.exists(allowed) } == 0L
            }
        }
    }

    /**
     * 订阅失败：不能留下频道条目，否则命名空间永久收不到失效，之后的订阅者也跳过 SUBSCRIBE 而“成功”。
     */
    @Test
    fun failedSubscribeDoesNotLeaveNamespaceDeaf() {
        RedisTestSupport.aclBackend(
            "cocache-subscribe",
            listOf("~*", "resetchannels", "&cocache:inv:allowed*", "+@all")
        ).use { backend ->
            val listener = RecordingListener()
            repeat(2) {
                runCatching { backend.channel.subscribe("denied", listener) }.exceptionOrNull().assert().isNotNull()
            }
            backend.channel.subscribe("allowed", listener).close()
        }
    }

    /**
     * 心跳正常时健康状态为正常（很短的心跳周期，订阅期间会执行多次心跳）。
     */
    @Test
    fun heartbeatHealthyWhenPingSucceeds() {
        RedisTestSupport.backend(
            RedisBackendOptions(clientName = "cocache-hb-" + UUID.randomUUID(), heartbeat = Duration.ofMillis(20))
        ).use { backend ->
            backend.heartbeatHealthy.assert().isTrue()
            val listener = RecordingListener()
            backend.channel.subscribe("healthy-$namespace", listener).use {
                listener.resets.get().assert().isGreaterThanOrEqualTo(1)
                backend.heartbeatHealthy.assert().isTrue()
                backend.heartbeatFailure.assert().isNull()
            }
        }
    }

    /**
     * 心跳失败（这里用 ACL 禁止 PING 模拟）：重置订阅者并强制断开订阅连接，由 Lettuce 重连并重新订阅。
     */
    @Test
    fun heartbeatFailureForcesReconnect() {
        Assumptions.assumeFalse(RedisTestSupport.isCluster, "standalone Redis only")
        val clientName = "cocache-heartbeat-" + UUID.randomUUID()
        RedisTestSupport.aclBackend(
            "cocache-heartbeat",
            listOf("~*", "&*", "+@all", "-ping"),
            RedisBackendOptions(clientName = clientName, heartbeat = Duration.ofMillis(200))
        ).use { backend ->
            val listener = RecordingListener()
            val subscription = backend.channel.subscribe("heartbeat", listener)
            val before = RedisTestSupport.pubSubClientIds(clientName)
            before.assert().hasSize(1)
            awaitCondition("pub/sub connection replaced") {
                val after = RedisTestSupport.pubSubClientIds(clientName)
                after.isNotEmpty() && after != before
            }
            listener.resets.get().assert().isGreaterThanOrEqualTo(2)
            // 健康检查可见的只读状态：心跳持续失败
            awaitCondition("heartbeat failure recorded") { !backend.heartbeatHealthy }
            backend.heartbeatFailure.assert().isNotNull()
            subscription.close()
        }
    }

    /**
     * 内存不足等写入失败（这里用 ACL 禁止 SET / SETRANGE 模拟）：读脚本降级，不抛出。
     */
    @Test
    fun readDegradesWhenLeaseOrRefreshWriteFails() {
        RedisTestSupport.backend().use { admin ->
            admin.store(
                namespace
            ).put(key("value"), Envelope.Scalar("v".toByteArray()), Duration.ofSeconds(10), ByteArray(0))
        }
        RedisTestSupport.aclBackend(
            "cocache-readonly-writes",
            listOf("~*", "&*", "+@all", "-set", "-setrange")
        ).use { backend ->
            val store = backend.store(namespace)
            store.read(key("absent"), lease(), null).assert().isEqualTo(RemoteRead.Miss(false))
            val refresh = RefreshRequest(Duration.ofSeconds(60), token(), Duration.ofSeconds(10))
            val hit = store.read(key("value"), null, refresh) as RemoteRead.Hit
            hit.refreshGranted.assert().isFalse()
            String((hit.envelope as Envelope.Scalar).bytes).assert().isEqualTo("v")
        }
    }

    /**
     * 分组失效：每组只广播本组的 key，而不是每组都广播全部 key（O(N²)）。
     */
    @Test
    fun invalidateBroadcastsOnlyEachGroupsKeys() {
        val frames = CopyOnWriteArrayList<ByteArray>()
        RedisTestSupport.subscribeRaw(RedisInvalidationChannel.channelOf(namespace)) { frames.add(it) }.use {
            RedisTestSupport.backend(
                RedisBackendOptions(invalidateBatchSize = 2, clientName = "cocache-tck-" + UUID.randomUUID())
            ).use { backend ->
                backend.atomicBroadcast.assert().isTrue()
                val store = backend.store(namespace)
                val keys = (1..5).map { key("k$it") }
                val origin = NodeId.random()
                store.invalidate(keys, InvalidationFrame.encode(origin, keys))
                awaitCondition("all keys broadcast") {
                    frames.flatMap { InvalidationFrame.decode(it).keys }.toSet() == keys.toSet()
                }
                val decoded = frames.map { InvalidationFrame.decode(it) }
                decoded.forEach { frame ->
                    frame.origin.assert().isEqualTo(origin)
                    frame.keys.size.assert().isLessThanOrEqualTo(2)
                }
                decoded.sumOf { it.keys.size }.assert().isEqualTo(keys.size)

                // 帧与 key 不一致（无法安全拆分）或畸形：原样广播
                frames.clear()
                val foreign = InvalidationFrame.encode(origin, listOf("other"))
                store.invalidate(keys, foreign)
                awaitCondition("unchanged frames") { frames.size >= 3 }
                frames.forEach { it.assert().isEqualTo(foreign) }
                frames.clear()
                val malformed = byteArrayOf(9)
                store.invalidate(keys, malformed)
                awaitCondition("malformed frames") { frames.size >= 3 }
                frames.forEach { it.assert().isEqualTo(malformed) }
            }
        }
    }

    /**
     * Cluster：订阅由非所属节点（这里是所属主节点的副本）确认时，心跳视为订阅丢失，重置并重新订阅到所属主节点。
     */
    @Test
    fun shardedSubscriptionAcknowledgedByNonOwnerIsResubscribed() {
        Assumptions.assumeTrue(RedisTestSupport.isCluster, "Redis Cluster only")
        RedisTestSupport.backend(
            RedisBackendOptions(
                channelMode = RedisChannelMode.SHARDED,
                heartbeat = Duration.ofMillis(200),
                clientName = "cocache-tck-" + UUID.randomUUID()
            )
        ).use { backend ->
            val channel = backend.channel as ShardedInvalidationChannel
            val listener = RecordingListener()
            val subscription = channel.subscribe(namespace, listener)

            @Suppress("UNCHECKED_CAST")
            val cluster = channel.connections.pubSub as StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>
            val channelName = RedisInvalidationChannel.channelOf(namespace).toByteArray(Charsets.UTF_8)
            val owner = requireNotNull(cluster.partitions.getMasterBySlot(SlotHash.getSlot(channelName)))
            val ownerAddress = ShardedInvalidationChannel.HostAndPort(owner.uri.host, owner.uri.port)
            awaitCondition("acknowledged by owner") { channel.subscribedNode(namespace) == ownerAddress }
            val replica = cluster.partitions.first {
                it.`is`(RedisClusterNode.NodeFlag.REPLICA) && it.slaveOf == owner.nodeId
            }
            val resetsBefore = listener.resets.get()

            cluster.getConnection(replica.uri.host, replica.uri.port).sync().ssubscribe(channelName)
            awaitCondition("reset and resubscribed to owner") {
                listener.resets.get() > resetsBefore && channel.subscribedNode(namespace) == ownerAddress
            }
            val frame = InvalidationFrame.encode(NodeId.random(), listOf("after"))
            awaitCondition("event after resubscribe") {
                channel.publish(namespace, frame)
                listener.received.contains(listOf("after"))
            }
            subscription.close()
        }
    }

    private class RecordingListener : InvalidationListener {
        val resets = AtomicInteger()
        val received = CopyOnWriteArrayList<List<String>>()

        override fun onInvalidated(keys: List<String>, origin: NodeId) {
            received.add(keys)
        }

        override fun onReset() {
            resets.incrementAndGet()
        }
    }
}
