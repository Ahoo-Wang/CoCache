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

import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.cluster.models.partitions.Partitions
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubListener
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection
import io.lettuce.core.cluster.pubsub.api.async.RedisClusterPubSubAsyncCommands
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands
import io.lettuce.core.resource.DefaultClientResources
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteVersionedWrite
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RedisUnitTest {
    @Test
    fun optionsAreValidated() {
        fun fails(
            block: () -> Any
        ) = runCatching(block).exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        fails { RedisBackendOptions(commandTimeout = Duration.ZERO) }
        fails { RedisBackendOptions(commandTimeout = Duration.ofSeconds(-1)) }
        fails { RedisBackendOptions(heartbeat = Duration.ZERO) }
        fails { RedisBackendOptions(heartbeat = Duration.ofSeconds(-1)) }
        fails { RedisBackendOptions(invalidateBatchSize = 0) }
    }

    @Test
    fun decodesUnrecognizedAndEdgeContent() {
        val foreign = RedisRemoteStore.decodeRead(
            listOf(2L, 1000L, "{\"legacy\":true}".toByteArray(), 0L)
        ) as RemoteRead.Hit
        foreign.format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)
        foreign.ttl.assert().isEqualTo(Duration.ofSeconds(1))

        val header = ByteArray(RedisScripts.HEADER_SIZE).also {
            it[0] = RedisScripts.MAGIC
            it[1] = 1
            it[2] = 'X'.code.toByte()
        }
        (
            RedisRemoteStore.decodeRead(
                listOf(2L, -1L, header + "p".toByteArray(), 0L)
            ) as RemoteRead.Hit
            ).format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)

        val set = RedisRemoteStore.decodeRead(
            listOf(
                3L,
                -1L,
                listOf("+a".toByteArray(), "!f".toByteArray(), "other".toByteArray(), ByteArray(0)),
                ByteArray(0)
            )
        ) as RemoteRead.Hit
        set.format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)
        (set.envelope as Envelope.Members).members.map { String(it) }.assert().containsExactly("a")
        set.ttl.assert().isNull()

        val badFormat = RedisRemoteStore.decodeRead(
            listOf(3L, 5L, listOf("!f".toByteArray()), "x".toByteArray())
        ) as RemoteRead.Hit
        badFormat.format.assert().isEqualTo(RedisRemoteStore.UNRECOGNIZED)

        val versioned = RedisRemoteStore.decodeRead(
            listOf(3L, 5L, listOf("+b".toByteArray(), "!f".toByteArray()), "1".toByteArray())
        ) as RemoteRead.Hit
        versioned.format.assert().isEqualTo(RedisScripts.FORMAT)
        (versioned.envelope as Envelope.Members).members.map { String(it) }.assert().containsExactly("b")

        RedisRemoteStore.decodeRead(listOf(1L, -5L)).assert().isEqualTo(RemoteRead.Leased(Duration.ZERO))
        runCatching {
            RedisRemoteStore.decodeRead(
                listOf(9L)
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
    }

    /**
     * 格式字节按无符号解析，与 Lua 的 `string.byte` 一致：有符号时 0x80 以上的更高格式成了负数，引擎把它当作读不懂的内容回收，
     * 而不是绕过（读路径不回收更高格式写入的值）。
     */
    @Test
    fun decodesTheFormatByteAsUnsigned() {
        listOf('V', 'M').forEach { state ->
            val header = ByteArray(RedisScripts.HEADER_SIZE).also {
                it[0] = RedisScripts.MAGIC
                it[1] = 0x80.toByte()
                it[2] = state.code.toByte()
            }
            val read = RedisRemoteStore.decodeRead(listOf(2L, -1L, header + "p".toByteArray(), 0L)) as RemoteRead.Hit
            read.format.assert().isEqualTo(0x80)
        }
    }

    /**
     * 按版本写入的结果码与头部中的版本（6.8）：版本只在令牌位置以 `VERSION_MARK` 开头时存在。
     */
    @Test
    fun decodesVersionedWritesAndHeaderVersions() {
        RedisRemoteStore.decodeVersionedWrite(listOf(1L)).assert().isEqualTo(RemoteVersionedWrite.Written)
        RedisRemoteStore.decodeVersionedWrite(
            listOf(0L, java.nio.ByteBuffer.allocate(8).putLong(-3L).array())
        ).assert().isEqualTo(RemoteVersionedWrite.Stale(-3L))
        RedisRemoteStore.decodeVersionedWrite(listOf(3L, 2L)).assert().isEqualTo(RemoteVersionedWrite.Unreadable(2))
        runCatching { RedisRemoteStore.decodeVersionedWrite(listOf(9L)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)

        val header = ByteArray(RedisScripts.HEADER_SIZE).also {
            it[0] = RedisScripts.MAGIC
            it[1] = 1
            it[2] = 'V'.code.toByte()
        }
        RedisRemoteStore.versionOf(header).assert().isEqualTo(Versioned.NO_VERSION)
        RedisScripts.VERSION_MARK.copyInto(header, RedisScripts.VERSION_OFFSET)
        RedisRemoteStore.versionOf(header).assert().isEqualTo(0L)
        // 令牌恰好以 0xC0 开头、但不是完整的标记：仍然没有版本
        header[RedisScripts.VERSION_OFFSET + 1] = 0
        RedisRemoteStore.versionOf(header).assert().isEqualTo(Versioned.NO_VERSION)
    }

    @Test
    fun putIfNewerRejectsSetsWithoutTouchingRedis() {
        val store = RedisRemoteStore(mockk(), ByteArray(0), 512)
        val members = Envelope.Members(listOf("a".toByteArray()))
        runCatching { store.putIfNewer("k", members, 1, null, ByteArray(0), false) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        store.putIfNewerAsync("k", members, 1, null, ByteArray(0), false).handle { _, error -> error }.join()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun connectionsRejectUnsupportedClients() {
        runCatching { RedisConnections(mockk<AbstractRedisClient>(), Duration.ofSeconds(1)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun awaitTranslatesFailures() {
        assumeStandalone()
        val client = RedisClient.create(RedisURI.create("localhost", 6379))
        try {
            RedisConnections(client, Duration.ofMillis(20)).use { connections ->
                runCatching {
                    connections.await(
                        CompletableFuture<Any>()
                    )
                }.exceptionOrNull().assert().isInstanceOf(RedisException::class.java)
                val checked = CompletableFuture.failedFuture<Any>(Exception("checked"))
                runCatching {
                    connections.await(
                        checked
                    )
                }.exceptionOrNull().assert().isInstanceOf(RedisException::class.java)
                val runtime = IllegalStateException("runtime")
                runCatching {
                    connections.await(
                        CompletableFuture.failedFuture<Any>(runtime)
                    )
                }.exceptionOrNull().assert().isSameAs(runtime)
                Thread.currentThread().interrupt()
                runCatching {
                    connections.await(
                        CompletableFuture<Any>()
                    )
                }.exceptionOrNull().assert().isInstanceOf(RedisException::class.java)
                Thread.interrupted().assert().isTrue()
                connections.groupBySlot(listOf("a".toByteArray(), "b".toByteArray())).size.assert().isEqualTo(1)
            }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun evalFallsBackToSourceAfterScriptFlush() {
        assumeStandalone()
        val client = RedisClient.create(RedisURI.create("localhost", 6379))
        try {
            client.connect().use { it.sync().scriptFlush() }
            RedisConnections(client, Duration.ofSeconds(2)).use { connections ->
                val result = connections.eval<List<Any?>>(
                    RedisScripts.PROBE,
                    listOf("cocache:probe".toByteArray()),
                    listOf("cocache:probe:channel".toByteArray())
                )
                result.assert().containsExactly(1L)
                val failure = runCatching {
                    connections.eval<List<Any?>>(RedisScripts.FILL, emptyList(), emptyList())
                }.exceptionOrNull()
                failure.assert().isInstanceOf(RedisException::class.java)
            }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun borrowedClientIsNotShutDown() {
        assumeStandalone()
        val client = RedisClient.create(RedisURI.create("localhost", 6379))
        try {
            RedisBackend.of(client).use { backend ->
                backend.atomicBroadcast.assert().isTrue()
                backend.format.assert().isEqualTo(RedisScripts.FORMAT)
                backend.clientName.assert().isNull()
            }
            client.connect().use { it.sync().ping().assert().isEqualTo("PONG") }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun backendWithSharedResourcesAndClientName() {
        assumeStandalone()
        val resources = io.lettuce.core.resource.DefaultClientResources.create()
        try {
            RedisBackend.standalone(
                RedisURI.create("localhost", 6379),
                RedisBackendOptions(clientResources = resources, clientName = "cocache-unit")
            ).use {
                it.clientName.assert().isEqualTo("cocache-unit")
            }
        } finally {
            resources.shutdown()
        }
    }

    @Test
    fun backendAcceptsSslOptions() {
        assumeStandalone()
        // URI 未启用 SSL 时 TLS 选项不生效：只验证选项能被应用到自建客户端
        RedisBackend.standalone(
            RedisURI.create("localhost", 6379),
            RedisBackendOptions(sslOptions = io.lettuce.core.SslOptions.builder().build())
        ).use {
            it.atomicBroadcast.assert().isTrue()
        }
    }

    @Test
    fun channelResetsOnHeartbeatFailureAndIgnoresMalformedFrames() {
        var adapter: RedisPubSubAdapter<ByteArray, ByteArray>? = null
        val async = proxy<RedisPubSubAsyncCommands<ByteArray, ByteArray>> { method, _ ->
            when (method.name) {
                "ping" -> failed<String>(RedisException("down"))
                else -> completed<Void?>(null)
            }
        }
        val pubSub = proxy<StatefulRedisPubSubConnection<ByteArray, ByteArray>> { method, args ->
            when (method.name) {
                "addListener" -> {
                    @Suppress("UNCHECKED_CAST")
                    adapter = args[0] as RedisPubSubAdapter<ByteArray, ByteArray>
                    null
                }

                "async" -> async
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        val connections = mockk<RedisConnections>()
        every { connections.pubSub } returns pubSub
        every { connections.await(any<CompletableFuture<Any?>>()) } answers { firstArg<CompletableFuture<Any?>>().get() }

        val resets = AtomicInteger()
        val received = CopyOnWriteArrayList<List<String>>()
        val listener = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                received.add(keys)
            }

            override fun onReset() {
                resets.incrementAndGet()
            }
        }
        PubSubInvalidationChannel(connections, Duration.ofMillis(10), Duration.ofMillis(10)).use { channel ->
            // 没有收到订阅确认：等待超时后照常返回
            val subscription = channel.subscribe("ns", listener)
            val second = channel.subscribe("ns", listener)
            awaitCondition("heartbeat reset") { resets.get() >= 3 }

            val handler = requireNotNull(adapter)
            handler.message("cocache:inv:ns".toByteArray(), byteArrayOf(9))
            handler.message("cocache:inv:other".toByteArray(), InvalidationFrame.encode(NodeId.random(), listOf("k")))
            handler.subscribed("cocache:inv:other".toByteArray(), 1)
            handler.message("cocache:inv:ns".toByteArray(), InvalidationFrame.encode(NodeId.random(), listOf("k")))
            // 同一订阅者订阅了两次，收到两次
            received.assert().containsExactly(listOf("k"), listOf("k"))
            second.close()
            subscription.close()
        }
    }

    @Test
    fun shardedChannelResubscribesAfterServerUnsubscribe() {
        var adapter: RedisPubSubAdapter<ByteArray, ByteArray>? = null
        val subscribes = AtomicInteger()
        val unsubscribes = AtomicInteger()
        val failNext = AtomicBoolean()
        val async = stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>(
            "ssubscribe" to {
                subscribes.incrementAndGet()
                if (failNext.getAndSet(false)) failed<Void?>(RedisException("MOVED")) else completed<Void?>(null)
            },
            "sunsubscribe" to {
                unsubscribes.incrementAndGet()
                completed<Void?>(null)
            },
            "ping" to { completed("PONG") },
        )
        val pubSub = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>(
            "addListener" to { args ->
                @Suppress("UNCHECKED_CAST")
                adapter = args[0] as RedisPubSubAdapter<ByteArray, ByteArray>
                null
            },
            "async" to { async },
        )
        val listener = RecordingListener()
        // 心跳周期足够长，由用例直接调用 ping() 驱动
        val channel = ShardedInvalidationChannel(connectionsOf(pubSub), Duration.ofHours(1), Duration.ofMillis(10))
        val subscription = channel.subscribe("ns", listener)
        val other = channel.subscribe("ns2", listener)
        subscribes.get().assert().isEqualTo(2)
        val handler = requireNotNull(adapter)
        val ns = "cocache:inv:ns".toByteArray()

        // 没有订阅者的频道被退订（主动退订的回执）：忽略
        handler.sunsubscribed("cocache:inv:absent".toByteArray(), 0)
        listener.resets.get().assert().isZero()

        // 服务端退订（slot 迁移）：立即重置并重新订阅；重新订阅失败时由心跳重置并重试，确认后不再重试
        failNext.set(true)
        handler.sunsubscribed(ns, 0)
        listener.resets.get().assert().isEqualTo(1)
        awaitCondition("resubscribe attempted") { subscribes.get() == 3 }
        channel.ping()
        subscribes.get().assert().isEqualTo(4)
        // 退订、未确认期间的心跳、确认各重置一次
        handler.ssubscribed(ns, 1)
        listener.resets.get().assert().isEqualTo(3)
        channel.ping()
        subscribes.get().assert().isEqualTo(4)

        handler.smessage(ns, InvalidationFrame.encode(NodeId.random(), listOf("k")))
        listener.received.assert().containsExactly(listOf("k"))

        // 待确认期间订阅被关闭：心跳不再重新订阅
        handler.sunsubscribed(ns, 0)
        awaitCondition("resubscribe attempted") { subscribes.get() == 5 }
        subscription.close()
        unsubscribes.get().assert().isEqualTo(1)
        channel.ping()
        channel.ping()
        subscribes.get().assert().isEqualTo(5)

        // 通道关闭后的服务端退订：不再调度重新订阅
        channel.close()
        handler.sunsubscribed("cocache:inv:ns2".toByteArray(), 0)
        subscribes.get().assert().isEqualTo(5)
        other.close()
    }

    @Test
    fun shardedChannelPingsEverySubscribedClusterNode() {
        val nodePings = AtomicInteger()
        val nodeDown = AtomicBoolean()
        val nodeAsync = stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>(
            "ping" to {
                nodePings.incrementAndGet()
                if (nodeDown.get()) failed<String>(RedisException("down")) else completed("PONG")
            },
        )
        val nodeConnection = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>("async" to { nodeAsync })
        val async = stub<RedisClusterPubSubAsyncCommands<ByteArray, ByteArray>>(
            "ssubscribe" to { completed<Void?>(null) },
        )
        var partitions = Partitions()
        val propagation = AtomicBoolean(true)
        val pubSub = stub<StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>>(
            "setNodeMessagePropagation" to { args ->
                propagation.set(args[0] as Boolean)
                null
            },
            "addListener" to { null },
            "async" to { async },
            "getPartitions" to { partitions },
            "getConnectionAsync" to { CompletableFuture.completedFuture(nodeConnection) },
        )
        ShardedInvalidationChannel(connectionsOf(pubSub), Duration.ofHours(1), Duration.ofMillis(10)).use { channel ->
            // 只用节点感知的监听器：关闭向普通监听器的转发
            propagation.get().assert().isFalse()
            channel.subscribe("a", RecordingListener())
            channel.subscribe("b", RecordingListener())
            // slot 未被覆盖（拓扑刷新中）：跳过
            channel.ping()
            nodePings.get().assert().isZero()

            partitions = singleNodePartitions()
            // 两个频道在同一节点上：只探测一次
            channel.ping()
            nodePings.get().assert().isEqualTo(1)

            nodeDown.set(true)
            runCatching { channel.ping() }.exceptionOrNull().assert().hasRootCauseInstanceOf(RedisException::class.java)
        }
    }

    @Test
    fun throwingListenerDoesNotSkipOtherListeners() {
        var adapter: RedisPubSubAdapter<ByteArray, ByteArray>? = null
        val async = stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>("subscribe" to { completed<Void?>(null) })
        val pubSub = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>(
            "addListener" to { args ->
                @Suppress("UNCHECKED_CAST")
                adapter = args[0] as RedisPubSubAdapter<ByteArray, ByteArray>
                null
            },
            "async" to { async },
        )
        val failing = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) = throw IllegalStateException("listener bug")

            override fun onReset() = Unit
        }
        val healthy = RecordingListener()
        PubSubInvalidationChannel(connectionsOf(pubSub), Duration.ofHours(1), Duration.ofMillis(10)).use { channel ->
            channel.subscribe("ns", failing)
            channel.subscribe("ns", healthy)
            requireNotNull(
                adapter
            ).message("cocache:inv:ns".toByteArray(), InvalidationFrame.encode(NodeId.random(), listOf("k")))
            healthy.received.assert().containsExactly(listOf("k"))
        }
    }

    @Test
    fun subscribeFailureRemovesChannelEntry() {
        val subscribes = AtomicInteger()
        val async = stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>(
            "subscribe" to {
                if (subscribes.incrementAndGet() == 1) failed<Void?>(RedisException("down")) else completed<Void?>(null)
            },
        )
        val pubSub = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>(
            "addListener" to { null },
            "async" to { async }
        )
        PubSubInvalidationChannel(connectionsOf(pubSub), Duration.ofHours(1), Duration.ofMillis(10)).use { channel ->
            runCatching { channel.subscribe("ns", RecordingListener()) }.exceptionOrNull().assert().isNotNull()
            // 第二个订阅者重新发出 SUBSCRIBE，而不是以为已经订阅
            channel.subscribe("ns", RecordingListener())
            subscribes.get().assert().isEqualTo(2)
        }
    }

    @Test
    fun shardedChannelResubscribesWhenAcknowledgedByNonOwner() {
        val cluster = FakeClusterPubSub()
        val owner = cluster.owner
        val replica = cluster.replica
        val pinged = cluster.pinged
        val subscribes = cluster.subscribes
        val staleUnsubscribes = cluster.staleUnsubscribes
        val pubSub = cluster.pubSub
        val listener = RecordingListener()
        ShardedInvalidationChannel(connectionsOf(pubSub), Duration.ofHours(1), Duration.ofMillis(10)).use { channel ->
            channel.subscribe("ns", listener)
            val handler = requireNotNull(cluster.listener)
            val ns = "cocache:inv:ns".toByteArray()
            handler.ssubscribed(owner, ns, 1)
            channel.subscribedNode("ns").assert().isEqualTo(ShardedInvalidationChannel.HostAndPort("localhost", 7000))
            listener.resets.get().assert().isEqualTo(1)
            channel.ping()
            pinged.assert().containsExactly(7000)
            subscribes.get().assert().isEqualTo(1)

            // 副本确认（如故障切换后连接重连到了旧主节点）：心跳判定订阅丢失，重置、在旧节点上退订并重新订阅
            handler.ssubscribed(replica, ns, 1)
            listener.resets.get().assert().isEqualTo(2)
            pinged.clear()
            channel.ping()
            listener.resets.get().assert().isEqualTo(3)
            staleUnsubscribes.get().assert().isEqualTo(1)
            subscribes.get().assert().isEqualTo(2)
            // 待确认期间不探测
            pinged.assert().isEmpty()

            // 旧节点的退订回执被忽略
            handler.sunsubscribed(replica, ns, 0)
            listener.resets.get().assert().isEqualTo(3)
            handler.ssubscribed(owner, ns, 1)
            listener.resets.get().assert().isEqualTo(4)
            // 非确认节点发来的退订同样忽略
            handler.sunsubscribed(replica, ns, 0)
            listener.resets.get().assert().isEqualTo(4)
            // 确认节点的退订（slot 迁移）：重置并重新订阅
            handler.sunsubscribed(owner, ns, 0)
            listener.resets.get().assert().isEqualTo(5)
            awaitCondition("resubscribe attempted") { subscribes.get() == 3 }
            handler.smessage(owner, ns, InvalidationFrame.encode(NodeId.random(), listOf("k")))
            listener.received.assert().containsExactly(listOf("k"))
            // 未知节点的确认：不记录节点，心跳退回探测所属主节点
            handler.ssubscribed(null, ns, 1)
            channel.subscribedNode("ns").assert().isNull()
            pinged.clear()
            channel.ping()
            pinged.assert().containsExactly(7000)
        }
    }

    /**
     * 两个节点（所属主节点 7000 与其副本 7003）的集群订阅连接桩：记录 SSUBSCRIBE、节点 PING 与旧节点上的退订。
     */
    private inner class FakeClusterPubSub {
        var listener: RedisClusterPubSubListener<ByteArray, ByteArray>? = null
        val subscribes = AtomicInteger()
        val staleUnsubscribes = AtomicInteger()
        val pinged = CopyOnWriteArrayList<Int>()
        val owner =
            clusterNode("owner", 7000, (0 until SlotHash.SLOT_COUNT).toList(), RedisClusterNode.NodeFlag.UPSTREAM)
        val replica = clusterNode("replica", 7003, emptyList(), RedisClusterNode.NodeFlag.REPLICA)
        private val partitions = Partitions().apply {
            add(owner)
            add(replica)
            updateCache()
        }
        private val async = stub<RedisClusterPubSubAsyncCommands<ByteArray, ByteArray>>(
            "ssubscribe" to {
                subscribes.incrementAndGet()
                completed<Void?>(null)
            },
        )
        val pubSub = stub<StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>>(
            "setNodeMessagePropagation" to { null },
            "addListener" to { args ->
                @Suppress("UNCHECKED_CAST")
                listener = args[0] as RedisClusterPubSubListener<ByteArray, ByteArray>
                null
            },
            "async" to { async },
            "getPartitions" to { partitions },
            "getConnectionAsync" to { args -> CompletableFuture.completedFuture(nodeConnection(args[1] as Int)) },
        )

        private fun nodeConnection(port: Int) = stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>(
            "async" to {
                stub<RedisPubSubAsyncCommands<ByteArray, ByteArray>>(
                    "ping" to {
                        pinged.add(port)
                        completed("PONG")
                    },
                    "sunsubscribe" to {
                        staleUnsubscribes.incrementAndGet()
                        completed<Void?>(null)
                    },
                )
            },
        )
    }

    private fun clusterNode(id: String, port: Int, slots: List<Int>, flag: RedisClusterNode.NodeFlag): RedisClusterNode {
        return RedisClusterNode(RedisURI.create("localhost", port), id, true, null, 0, 0, 0, slots, setOf(flag))
    }

    @Test
    fun connectionsCloseCommandConnectionWhenPubSubFails() {
        val commands = mockk<StatefulRedisConnection<ByteArray, ByteArray>>(relaxed = true)
        val client = mockk<RedisClient>()
        every { client.connect(ByteArrayCodec.INSTANCE) } returns commands
        every { client.connectPubSub(ByteArrayCodec.INSTANCE) } throws RedisConnectionException("refused")
        runCatching { RedisConnections(client, Duration.ofSeconds(1)) }.exceptionOrNull()
            .assert().isInstanceOf(RedisConnectionException::class.java)
        verify { commands.close() }
    }

    @Test
    fun backendShutsDownOwnedResourcesWhenConnectFails() {
        val resources = spyk(DefaultClientResources.create())
        mockkStatic(DefaultClientResources::class) {
            every { DefaultClientResources.create() } returns resources
            val error = runCatching {
                RedisBackend.standalone(
                    RedisURI.create("localhost", 1),
                    RedisBackendOptions(commandTimeout = Duration.ofSeconds(1))
                )
            }.exceptionOrNull()
            error.assert().isInstanceOf(RedisException::class.java)
        }
        verify { resources.shutdown() }
    }

    @Test
    fun backendClosesConnectionsWhenProbeTimesOut() {
        assumeStandalone()
        mockkConstructor(RedisConnections::class) {
            every {
                anyConstructed<RedisConnections>().eval<List<Any?>>(RedisScripts.PROBE, any(), any())
            } throws RedisCommandTimeoutException("timeout")
            // 暂时性错误不能永久降级为非原子广播：照常抛出，并关闭已建立的连接与心跳线程
            val error = runCatching { RedisBackend.standalone(RedisURI.create("localhost", 6379)) }.exceptionOrNull()
            error.assert().isInstanceOf(RedisCommandTimeoutException::class.java)
            verify { anyConstructed<RedisConnections>().close() }
        }
    }

    @Test
    fun onlyCrossSlotOrPermissionErrorsDowngradeBroadcast() {
        RedisBackend.isBroadcastUnsupported(RedisException("CROSSSLOT Keys in request don't hash to the same slot"))
            .assert().isTrue()
        RedisBackend.isBroadcastUnsupported(
            RedisException("wrapper", RedisException("ERR channel in a different slot"))
        )
            .assert().isTrue()
        RedisBackend.isBroadcastUnsupported(
            RedisException("NOPERM no permissions to access the channel")
        ).assert().isTrue()
        RedisBackend.isBroadcastUnsupported(
            RedisException(
                "ERR The user executing the script can't publish to the channel mentioned in the command script: x"
            )
        ).assert().isTrue()
        RedisBackend.isBroadcastUnsupported(RedisCommandTimeoutException("Command timed out")).assert().isFalse()
        RedisBackend.isBroadcastUnsupported(RedisException(null as String?)).assert().isFalse()
    }

    @Test
    fun ownedClientsEnableKeepAliveAndTcpUserTimeout() {
        val native = RedisBackend.socketOptions(nativeTransport = true)
        native.isKeepAlive.assert().isTrue()
        native.keepAlive.isEnabled.assert().isTrue()
        native.tcpUserTimeout.isEnabled.assert().isTrue()
        // NIO 上 Lettuce 不支持 TCP_USER_TIMEOUT（启用会导致连接失败）
        val nio = RedisBackend.socketOptions(nativeTransport = false)
        nio.isKeepAlive.assert().isTrue()
        nio.tcpUserTimeout.isEnabled.assert().isFalse()
    }

    @Test
    fun disconnectForcesReconnectOfRealConnection() {
        assumeStandalone()
        RedisInvalidationChannel.disconnect(
            stub<StatefulRedisPubSubConnection<ByteArray, ByteArray>>()
        ).assert().isFalse()
        val client = RedisClient.create(RedisURI.create("localhost", 6379))
        try {
            client.connect().use { connection ->
                val before = connection.sync().clientId()
                RedisInvalidationChannel.disconnect(connection).assert().isTrue()
                awaitCondition(
                    "reconnected"
                ) { runCatching { connection.sync().clientId() != before }.getOrDefault(false) }
            }
            // 配置了命令监听器：端点外再包一层 CommandListenerWriter，同样逐层拆开
            client.addListener(object : io.lettuce.core.event.command.CommandListener {})
            client.connect().use { connection ->
                val before = connection.sync().clientId()
                RedisInvalidationChannel.disconnect(connection).assert().isTrue()
                awaitCondition(
                    "reconnected with a command listener"
                ) { runCatching { connection.sync().clientId() != before }.getOrDefault(false) }
            }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun borrowedClientWithRejectCommands() {
        assumeStandalone()
        val client = RedisClient.create(RedisURI.create("localhost", 6379))
        client.options = io.lettuce.core.ClientOptions.builder()
            .disconnectedBehavior(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build()
        try {
            RedisBackend.of(client).use { it.atomicBroadcast.assert().isTrue() }
        } finally {
            client.shutdown()
        }
    }

    private fun singleNodePartitions(): Partitions = Partitions().apply {
        add(
            RedisClusterNode(
                RedisURI.create("localhost", 7000),
                "node-a",
                true,
                null,
                0,
                0,
                0,
                (0 until SlotHash.SLOT_COUNT).toList(),
                setOf(RedisClusterNode.NodeFlag.UPSTREAM)
            )
        )
        updateCache()
    }

    /**
     * 心跳任务抛出 Error（6.0–6.4.0 在 Lettuce 6 上强制重连时的 `NoClassDefFoundError`）之后，下一次心跳照常执行：
     * `scheduleWithFixedDelay` 的任务一旦抛出就不再执行，节点会失去自愈能力。
     */
    @Test
    fun heartbeatKeepsRunningAfterTheTaskThrowsAnError() {
        FailingHeartbeat(
            mapOf(1 to IllegalStateException("half-open"), 2 to OutOfMemoryError("transient")),
            NoClassDefFoundError("io/lettuce/core/Delegating")
        ).use { channel ->
            awaitCondition("heartbeat continues after errors") { channel.pings.get() >= 4 }
            // PING 抛出 Error 时同样重置并强制重连
            channel.reconnects.get().assert().isEqualTo(2)
            // 之后的心跳成功：失败记录被清除
            awaitCondition("heartbeat recovered") { channel.lastHeartbeatFailure == null }
        }
    }

    /**
     * PING 抛出 Error、某个订阅者的重置抛出异常：其余订阅者照常重置，强制重连照常执行。
     */
    @Test
    fun heartbeatResetsEveryListenerAndReconnectsWhenPingThrowsAnError() {
        val failingResets = AtomicInteger()
        val failing = object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) = Unit

            override fun onReset() {
                failingResets.incrementAndGet()
                throw IllegalStateException("reset failed")
            }
        }
        val healthy = RecordingListener()
        val subscribers = mapOf("cocache:inv:a" to listOf(failing, healthy), "cocache:inv:b" to listOf(healthy))
        FailingHeartbeat(mapOf(1 to StackOverflowError("ping")), null, subscribers).use { channel ->
            awaitCondition("heartbeat reconnected") { channel.reconnects.get() >= 1 }
            failingResets.get().assert().isEqualTo(1)
            healthy.resets.get().assert().isEqualTo(2)
        }
    }

    /**
     * 按 [failures]（第几次 PING → 抛出的异常）失败的心跳，强制重连时抛出 [reconnectError]（为 `null` 时不抛出）；
     * [subscribers] 在心跳启动之前登记。
     */
    private class FailingHeartbeat(
        private val failures: Map<Int, Throwable>,
        private val reconnectError: Throwable?,
        subscribers: Map<String, List<InvalidationListener>> = emptyMap()
    ) : RedisInvalidationChannel(mockk(relaxed = true), HEARTBEAT, HEARTBEAT) {
        val pings = AtomicInteger()
        val reconnects = AtomicInteger()

        init {
            subscribers.forEach { (channel, channelListeners) -> listeners[channel] = CopyOnWriteArrayList(channelListeners) }
            start()
        }

        override fun subscribeAsync(channel: ByteArray): CompletableFuture<Void> = done()

        override fun unsubscribeAsync(channel: ByteArray): CompletableFuture<Void> = done()

        override fun publish(namespace: String, frame: ByteArray) = Unit

        override fun publishAsync(namespace: String, frame: ByteArray): CompletableFuture<Void?> = done()

        override fun ping() {
            failures[pings.incrementAndGet()]?.let { throw it }
        }

        override fun forceReconnect() {
            reconnects.incrementAndGet()
            reconnectError?.let { throw it }
        }

        private fun <T> done(): CompletableFuture<T> = CompletableFuture.completedFuture(null)

        private companion object {
            val HEARTBEAT: Duration = Duration.ofMillis(10)
        }
    }

    /**
     * 这些用例验证单机客户端的行为，连接 localhost:6379；集群环境（CI 的集群任务）下跳过。
     */
    private fun assumeStandalone() = Assumptions.assumeFalse(RedisTestSupport.isCluster, "standalone Redis only")
}
