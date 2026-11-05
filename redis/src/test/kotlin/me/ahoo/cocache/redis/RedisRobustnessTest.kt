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
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Suspending
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
     * 其它程序写入的原生 Set / List / ZSet 落在缓存 key 上：读取按无法识别的内容返回（不报 WRONGTYPE），
     * 以引擎同样的摘要（[Digest.of]）条件回收为 lease，然后正常填充。
     */
    @Test
    fun nativeCollectionUnderCacheKeyIsReclaimedAndFilled() {
        RedisTestSupport.backend().use { backend ->
            val store = backend.store(Keyspace("cocache:", namespace))
            val keys = listOf(key("set"), key("list"), key("zset"))
            RedisTestSupport.withCommands { commands ->
                commands.sadd(keys[0], "a", "b")
                commands.rpush(keys[1], "a", "b")
                commands.zadd(keys[2], 1.0, "a")
            }
            for (key in keys) {
                // 不申请 lease 的读取同样不能失败
                (
                    Suspending.await {
                        store.read(key, null, null, null)
                    } as L1Read.Hit
                    ).format.assert().isEqualTo(RedisL1Store.UNRECOGNIZED)
                val lease = lease()
                val read = Suspending.await { store.read(key, lease, null, null) } as L1Read.Hit
                read.format.assert().isEqualTo(RedisL1Store.UNRECOGNIZED)
                Suspending.await { store.reclaim(key, ContentFingerprint.of(read.envelope), lease) }.assert().isTrue()
                Suspending.await { store.read(key, null, null, null) }.assert().isInstanceOf(L1Read.Leased::class.java)
                val fill = Fill(key, lease.token, Envelope.Scalar("v".toByteArray()), Duration.ofSeconds(60))
                Suspending.await { store.fill(fill) }.assert().isInstanceOf(L1Fill.Filled::class.java)
                val filled = Suspending.await { store.read(key, null, null, null) } as L1Read.Hit
                filled.format.assert().isEqualTo(RedisScripts.FORMAT)
                String((filled.envelope as Envelope.Scalar).bytes).assert().isEqualTo("v")
            }
            // 内容在读取之后变了：摘要不匹配，不回收
            RedisTestSupport.withCommands { it.sadd(key("changed"), "a") }
            val lease = lease()
            val read = Suspending.await { store.read(key("changed"), lease, null, null) } as L1Read.Hit
            RedisTestSupport.withCommands { it.set(key("changed"), "other") }
            Suspending.await {
                store.reclaim(
                    key("changed"),
                    ContentFingerprint.of(read.envelope),
                    lease
                )
            }.assert().isFalse()
        }
    }

    /**
     * 集合缓存 key 上是外来内容：原生 Set 与没有 `!f` 的 Hash 读取失败，`add` / `remove` 拒绝，内容保留。
     * 集合缓存没有数据源，不接受 `RECLAIM`：整体覆盖外来内容就是删除唯一副本。
     */
    @Test
    fun setCacheFailsOnForeignContentAndKeepsIt() {
        RedisTestSupport.backend().use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val index: SetStore<String, String> = runtime.create(
                    setStoreSpec<String, String>(namespace) { elementCodec = StringCodec }
                )
                RedisTestSupport.withCommands { commands ->
                    commands.sadd(key("native"), "x")
                    commands.hset(key("hash"), "+a", "")
                }
                for (name in listOf("native", "hash")) {
                    runCatching { index[name] }.exceptionOrNull().assert().isInstanceOf(CacheReadException::class.java)
                    runCatching { index.add(name, listOf("b")) }.exceptionOrNull().assert().isInstanceOf(
                        CacheWriteException::class.java
                    )
                    runCatching { index.remove(name, listOf("a")) }.exceptionOrNull().assert().isInstanceOf(
                        CacheWriteException::class.java
                    )
                }
                RedisTestSupport.withCommands { commands ->
                    commands.smembers(key("native")).assert().containsExactly("x")
                    commands.hgetall(key("hash")).assert().isEqualTo(mapOf("+a" to ""))
                }
            }
        }
    }

    /**
     * ACL 缺少读脚本用到的命令（升级前未加 `+hkeys`，或缺 `+get`）：读取以 ACL 错误失败
     * （措辞随 Redis 版本不同），
     * 不能被当作无法识别的内容去回收——key 仍在，内容不变。
     */
    @Test
    fun readFailsWithAclErrorWithoutTouchingContentWhenAclLacksReadCommands() {
        RedisTestSupport.backend().use { admin ->
            val store = admin.store(Keyspace("cocache:", namespace))
            Suspending.await {
                store.put(
                    key("set"),
                    Envelope.Members(listOf("a".toByteArray(), "b".toByteArray())),
                    null,
                    ByteArray(0)
                )
            }
            Suspending.await { store.put(key("value"), Envelope.Scalar("v".toByteArray()), null, ByteArray(0)) }
        }
        // 只有 `!f` 的 Hash：其摘要与“无法识别的空内容”相同，误判为损坏时会被回收成 lease
        RedisTestSupport.withCommands { commands -> commands.hset(key("empty"), "!f", "1") }
        fun denied(type: Class<out Throwable> = RedisException::class.java, block: () -> Any?) {
            val error = runCatching(block).exceptionOrNull()
            error.assert().isInstanceOf(type)
            // 措辞随版本不同：7.0 为 "The user executing the script can't run this command"，7.2+ 为 "ACL failure in script … no permissions"
            val messages = generateSequence(error) { it.cause }.mapNotNull { it.message?.lowercase() }.toList()
            val markers = listOf("acl", "noperm", "permission", "can't run this command")
            messages.any { message -> markers.any { it in message } }
                .assert().withFailMessage { "Expected an ACL error but was $messages" }.isTrue()
        }
        for (missing in listOf("-hkeys", "-get")) {
            RedisTestSupport.aclBackend(
                "cocache-no${missing.drop(1)}",
                listOf("~*", "&*", "+@all", missing)
            ).use { backend ->
                val store = backend.store(Keyspace("cocache:", namespace))
                denied { Suspending.await { store.read(key("set"), lease(), null, null) } }
                denied { Suspending.await { store.read(key("empty"), lease(), null, null) } }
                if (missing == "-get") denied { Suspending.await { store.read(key("value"), lease(), null, null) } }
                CacheRuntime.builder().backend(backend).build().use { runtime ->
                    val index: SetStore<String, String> = runtime.create(
                        setStoreSpec<String, String>(namespace) { elementCodec = StringCodec }
                    )
                    // SetStore 的 L1 读失败是 Unknown，以 CacheReadException 抛出，ACL 错误是它的 cause
                    denied(CacheReadException::class.java) { index["set"] }
                    denied(CacheReadException::class.java) { index["empty"] }
                }
            }
        }
        RedisTestSupport.withCommands { commands ->
            commands.hkeys(key("set")).assert().containsExactlyInAnyOrder("!f", "+a", "+b")
            commands.hkeys(key("empty")).assert().containsExactly("!f")
            commands.type(key("value")).assert().isEqualTo("string")
        }
    }

    /**
     * 运维文档（wiki/operations/index.md#acl）给出的最小 ACL：缓存与集合缓存的全部操作都能执行。
     * 文档中的命令列表与脚本不一致时这里失败。
     */
    @Test
    fun documentedAclAllowsEveryOperation() {
        Assumptions.assumeTrue(System.getenv("SPRING_DATA_REDIS_CLUSTER_NODES") == null)
        val rules = (
            "~cocache:* resetchannels &cocache:inv:* &cocache:!probe:* +@connection +evalsha +eval +publish +subscribe " +
                "+unsubscribe +spublish +ssubscribe +sunsubscribe +get +getrange +strlen +set +del +pttl +type +hgetall +hkeys +hmget +hset " +
                "+hdel +hlen +pexpire +persist +setrange +time"
            ).split(" ")
        RedisTestSupport.aclBackend("cocache-documented", rules).use { backend ->
            backend.atomicBroadcast.assert().isTrue()
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val users = runtime.create(
                    me.ahoo.cocache.core.cacheSpec<String, String>("acl-$namespace") {
                        codec = StringCodec
                        loader = me.ahoo.cocache.api.CacheLoader { "v-$it" }
                    }
                )
                users["1"].assert().isEqualTo("v-1")
                users.put("1", "v1")
                users.invalidate("1")
                users.getAll(listOf("1", "2")).assert().hasSize(2)
                val index: SetStore<String, String> = runtime.create(
                    setStoreSpec<String, String>("acl-set-$namespace") { elementCodec = StringCodec }
                )
                index.add("k", listOf("a", "b"))
                index.remove("k", listOf("a"))
                awaitCondition { index["k"] == setOf("b") }
                index.clear("k")
            }
        }
    }

    /**
     * 读取各主节点的 maxmemory-policy（INFO memory）：不需要 CONFIG（托管 Redis 通常禁用它）；INFO 也不可用时返回 null，不抛出。
     */
    @Test
    fun readsMaxmemoryPolicyOrReturnsNullWhenUnavailable() {
        val expected = RedisTestSupport.withCommands { it.configGet("maxmemory-policy").getValue("maxmemory-policy") }
        RedisTestSupport.backend().use { backend ->
            val policies = requireNotNull(backend.maxmemoryPolicies())
            policies.assert().isNotEmpty()
            // 测试环境的各主节点配置一致：与 CONFIG GET 读到的相同
            policies.values.forEach { it.assert().isEqualTo(expected) }
        }
        val noConfig = "~cocache:* resetchannels &cocache:inv:* &cocache:!probe:* +@all -config".split(" ")
        RedisTestSupport.aclBackend("cocache-noconfig", noConfig).use { backend ->
            requireNotNull(backend.maxmemoryPolicies()).values.forEach { it.assert().isEqualTo(expected) }
        }
        // 集群中 Lettuce 发现拓扑也需要 INFO，只在单机上验证 INFO 被禁用的情形
        Assumptions.assumeFalse(RedisTestSupport.isCluster)
        val noInfo = "~cocache:* resetchannels &cocache:inv:* &cocache:!probe:* +@all -info".split(" ")
        RedisTestSupport.aclBackend("cocache-noinfo", noInfo).use { backend ->
            backend.maxmemoryPolicies().assert().isNull()
        }
    }

    /**
     * INFO memory 的回复按行解析（CRLF 分隔、带节标题）；没有 maxmemory_policy 字段时按读不到处理（RedisException）。
     */
    @Test
    fun parsesTheMaxmemoryPolicyFieldOfInfoMemory() {
        val info = "# Memory\r\nused_memory:1024\r\nmaxmemory:0\r\nmaxmemory_policy:volatile-lfu\r\nallocator_frag_ratio:1.0\r\n"
        RedisConnections.maxmemoryPolicyOf(info).assert().isEqualTo("volatile-lfu")
        runCatching { RedisConnections.maxmemoryPolicyOf("# Memory\r\nused_memory:1024\r\n") }.exceptionOrNull()
            .assert().isInstanceOf(RedisException::class.java).hasMessageContaining("maxmemory_policy")
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
            val store = backend.store(Keyspace("cocache:", namespace))
            val leases = mapOf(allowed to lease(), denied to lease())
            val error = runCatching {
                Suspending.await {
                    store.readAll(
                        listOf(allowed, denied),
                        leases,
                        null
                    )
                }
            }.exceptionOrNull()
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
                runCatching {
                    backend.channel.subscribe(Keyspace("cocache:", "denied"), listener)
                }.exceptionOrNull().assert().isNotNull()
            }
            backend.channel.subscribe(Keyspace("cocache:", "allowed"), listener).close()
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
            backend.channel.subscribe(Keyspace("cocache:", "healthy-$namespace"), listener).use {
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
            val subscription = backend.channel.subscribe(Keyspace("cocache:", "heartbeat"), listener)
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
            val store = admin.store(Keyspace("cocache:", namespace))
            Suspending.await {
                store.put(
                    key("value"),
                    Envelope.Scalar("v".toByteArray()),
                    Duration.ofSeconds(10),
                    ByteArray(0)
                )
            }
        }
        RedisTestSupport.aclBackend(
            "cocache-readonly-writes",
            listOf("~*", "&*", "+@all", "-set", "-setrange")
        ).use { backend ->
            val store = backend.store(Keyspace("cocache:", namespace))
            Suspending.await { store.read(key("absent"), lease(), null, null) }.assert().isEqualTo(L1Read.Miss(false))
            val refresh = RefreshRequest(Duration.ofSeconds(60), token(), Duration.ofSeconds(10))
            val hit = Suspending.await { store.read(key("value"), null, refresh, null) } as L1Read.Hit
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
        RedisTestSupport.subscribeRaw(RedisInvalidationChannel.channelOf(Keyspace("cocache:", namespace))) {
            frames.add(it)
        }.use {
            RedisTestSupport.backend(
                RedisBackendOptions(invalidateBatchSize = 2, clientName = "cocache-tck-" + UUID.randomUUID())
            ).use { backend ->
                backend.atomicBroadcast.assert().isTrue()
                val store = backend.store(Keyspace("cocache:", namespace))
                awaitProbeFrame(frames)
                val keys = (1..5).map { key("k$it") }
                val origin = NodeId.random()
                Suspending.await { store.invalidate(keys, InvalidationFrame.encode(origin, keys)) }
                awaitCondition("all keys broadcast") {
                    frames.flatMap { InvalidationFrame.decode(it).keys }.toSet() == keys.toSet()
                }
                val decoded = frames.map { InvalidationFrame.decode(it) }
                decoded.forEach { frame ->
                    frame.origin.assert().isEqualTo(origin)
                    frame.keys.size.assert().isLessThanOrEqualTo(2)
                }
                decoded.sumOf { it.keys.size }.assert().isEqualTo(keys.size)
                // 每组一帧：Cluster 上组数取决于 key 落在几个 slot（至少 3 组），等齐全部组再进入下一步，
                // 否则上一步迟到的帧会落进下一步的断言
                val groups = decoded.size

                // 帧与 key 不一致（无法安全拆分）或畸形：原样广播
                frames.clear()
                val foreign = InvalidationFrame.encode(origin, listOf("other"))
                Suspending.await { store.invalidate(keys, foreign) }
                awaitCondition("unchanged frames") { frames.size == groups }
                frames.forEach { it.assert().isEqualTo(foreign) }
                frames.clear()
                val malformed = byteArrayOf(9)
                Suspending.await { store.invalidate(keys, malformed) }
                awaitCondition("malformed frames") { frames.size == groups }
                frames.forEach { it.assert().isEqualTo(malformed) }
            }
        }
    }

    /**
     * 创建存储时向频道发布的零 key 探测帧（K8）：等它到达再清空，之后收到的都是写入的帧（同一频道上的帧有序）。
     */
    private fun awaitProbeFrame(frames: MutableList<ByteArray>) {
        awaitCondition("probe frame") { frames.isNotEmpty() }
        frames.single().let { InvalidationFrame.decode(it).keys.assert().isEmpty() }
        frames.clear()
    }

    /**
     * 分组批量写入：按 `invalidateBatchSize` 切分（Cluster 先按 slot 分组），每组一个脚本，只广播本组的 key；
     * 同一脚本内混合多种形状时，按每个 key 的载荷个数截取参数。
     */
    @Test
    fun putAllWritesEveryShapeAndBroadcastsOnlyEachGroupsKeys() {
        val frames = CopyOnWriteArrayList<ByteArray>()
        RedisTestSupport.subscribeRaw(RedisInvalidationChannel.channelOf(Keyspace("cocache:", namespace))) {
            frames.add(it)
        }.use {
            RedisTestSupport.backend(
                RedisBackendOptions(invalidateBatchSize = 2, clientName = "cocache-tck-" + UUID.randomUUID())
            ).use { backend ->
                val store = backend.store(Keyspace("cocache:", namespace))
                awaitProbeFrame(frames)
                val shapes = listOf(
                    Envelope.Members(listOf("a".toByteArray(), "b".toByteArray())),
                    Envelope.Scalar("v".toByteArray()),
                    Envelope.Absent,
                    Envelope.Members(listOf("c".toByteArray())),
                    Envelope.Scalar(ByteArray(0)),
                )
                val puts = shapes.mapIndexed { index, envelope ->
                    Put(key("k$index"), envelope, if (index % 2 == 0) null else Duration.ofMinutes(1))
                }
                val keys = puts.map { it.key }
                val origin = NodeId.random()
                // 重复的 key 以后者为准
                Suspending.await {
                    store.putAll(
                        puts + Put(keys[1], Envelope.Scalar("last".toByteArray()), null),
                        InvalidationFrame.encode(origin, keys)
                    )
                }
                awaitCondition("all keys broadcast") {
                    frames.flatMap { InvalidationFrame.decode(it).keys }.toSet() == keys.toSet()
                }
                val decoded = frames.map { InvalidationFrame.decode(it) }
                decoded.forEach { frame ->
                    frame.origin.assert().isEqualTo(origin)
                    frame.keys.size.assert().isLessThanOrEqualTo(2)
                }
                decoded.sumOf { it.keys.size }.assert().isEqualTo(keys.size)

                fun hit(key: String) = Suspending.await { store.read(key, null, null, null) } as L1Read.Hit
                fun members(key: String) = (hit(key).envelope as Envelope.Members).members.map { String(it) }
                members(keys[0]).assert().containsExactlyInAnyOrder("a", "b")
                hit(keys[0]).ttl.assert().isNull()
                hit(keys[1]).envelope.assert().isEqualTo(Envelope.Scalar("last".toByteArray()))
                hit(keys[1]).ttl.assert().isNull()
                hit(keys[2]).envelope.assert().isEqualTo(Envelope.Absent)
                members(keys[3]).assert().containsExactly("c")
                requireNotNull(hit(keys[3]).ttl).assert().isLessThanOrEqualTo(Duration.ofMinutes(1))
                hit(keys[4]).envelope.assert().isEqualTo(Envelope.Scalar(ByteArray(0)))
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
            val channel = backend.redisChannel as ShardedInvalidationChannel
            val listener = RecordingListener()
            val subscription = channel.subscribe(Keyspace("cocache:", namespace), listener)

            @Suppress("UNCHECKED_CAST")
            val cluster = channel.connections.pubSub as StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>
            val channelName = RedisInvalidationChannel.channelOf(
                Keyspace("cocache:", namespace)
            ).toByteArray(Charsets.UTF_8)
            val owner = requireNotNull(cluster.partitions.getMasterBySlot(SlotHash.getSlot(channelName)))
            val ownerAddress = ShardedInvalidationChannel.HostAndPort(owner.uri.host, owner.uri.port)
            awaitCondition(
                "acknowledged by owner"
            ) { channel.subscribedNode(Keyspace("cocache:", namespace)) == ownerAddress }
            val replica = cluster.partitions.first {
                it.`is`(RedisClusterNode.NodeFlag.REPLICA) && it.slaveOf == owner.nodeId
            }
            val resetsBefore = listener.resets.get()

            cluster.getConnection(replica.uri.host, replica.uri.port).sync().ssubscribe(channelName)
            awaitCondition("reset and resubscribed to owner") {
                listener.resets.get() > resetsBefore && channel.subscribedNode(Keyspace("cocache:", namespace)) == ownerAddress
            }
            val frame = InvalidationFrame.encode(NodeId.random(), listOf("cocache:$namespace:after"))
            awaitCondition("event after resubscribe") {
                Suspending.await { channel.publish(Keyspace("cocache:", namespace), frame) }
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
