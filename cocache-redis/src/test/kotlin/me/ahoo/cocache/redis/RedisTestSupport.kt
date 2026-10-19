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

import io.lettuce.core.KeyScanCursor
import io.lettuce.core.KillArgs
import io.lettuce.core.MigrateArgs
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisKeyCommands
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import java.time.Duration

/**
 * 集成测试的 Redis：默认 localhost:6379；设置 `SPRING_DATA_REDIS_CLUSTER_NODES` 时连接 Redis Cluster（与 CI 的集群任务一致）。
 *
 * Redis 的到期时间由服务端时钟驱动，无法推进。[shiftTtl] 把匹配 key 的剩余寿命整体缩短，模拟时间流逝。
 */
object RedisTestSupport {
    private val clusterNodes: List<RedisURI>? = System.getenv("SPRING_DATA_REDIS_CLUSTER_NODES")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?.map { node ->
            val (host, port) = node.split(':')
            RedisURI.create(host, port.toInt())
        }

    private val standaloneUri: RedisURI = RedisURI.create("localhost", 6379)

    val isCluster: Boolean = clusterNodes != null

    fun backend(
        options: RedisBackendOptions = RedisBackendOptions(
            clientName = "cocache-tck-" + java.util.UUID.randomUUID()
        )
    ): RedisBackend {
        return clusterNodes?.let { RedisBackend.cluster(it, options) } ?: RedisBackend.standalone(standaloneUri, options)
    }

    /**
     * Sharded Pub/Sub 通道的后端（非原子广播）。
     */
    fun shardedBackend(clientName: String = "cocache-tck-" + java.util.UUID.randomUUID()): RedisBackend = backend(
        RedisBackendOptions(clientName = clientName, channelMode = RedisChannelMode.SHARDED)
    )

    /**
     * 非原子广播的后端：用 ACL 禁止向探测频道发布（只允许 `cocache:inv:*`），探测失败后后端退回“写入后由引擎发布”。
     */
    fun nonAtomicBackend(): RedisBackend = aclBackend(
        "cocache-nonatomic",
        listOf("~*", "resetchannels", "&cocache:inv:*", "+@all")
    )

    /**
     * 以受限 ACL 用户连接的后端（各节点都设置该用户，ACL 不随复制同步）。[rules] 为 `ACL SETUSER` 的规则。
     */
    fun aclBackend(
        user: String,
        rules: List<String>,
        options: RedisBackendOptions = RedisBackendOptions(clientName = "cocache-tck-" + java.util.UUID.randomUUID())
    ): RedisBackend {
        val password = "$user-secret"
        forEachNode { commands ->
            val args = io.lettuce.core.protocol.CommandArgs(io.lettuce.core.codec.StringCodec.UTF8)
                .add("SETUSER").add(user).add("reset").add("on").add(">$password")
            rules.forEach { args.add(it) }
            commands.dispatch(
                io.lettuce.core.protocol.CommandType.ACL,
                io.lettuce.core.output.StatusOutput(io.lettuce.core.codec.StringCodec.UTF8),
                args
            )
        }
        val nodes = clusterNodes
        if (nodes != null) {
            return RedisBackend.cluster(
                nodes.map { RedisURI.builder(it).withAuthentication(user, password).build() },
                options
            )
        }
        return RedisBackend.standalone(
            RedisURI.builder(standaloneUri).withAuthentication(user, password).build(),
            options
        )
    }

    /**
     * 以默认用户执行任意命令（单机或集群，命令按 key 路由）。
     */
    fun <T> withCommands(block: (io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>) -> T): T {
        val nodes = clusterNodes
        if (nodes != null) {
            val client = RedisClusterClient.create(nodes)
            try {
                client.connect().use { return block(it.sync()) }
            } finally {
                client.shutdown()
            }
        }
        val client = RedisClient.create(standaloneUri)
        try {
            client.connect().use { return block(it.sync()) }
        } finally {
            client.shutdown()
        }
    }

    /**
     * 订阅经典频道 [channel]（集群中 `PUBLISH` 经集群总线到达任意节点），收到的消息交给 [onMessage]；关闭返回值即退订。
     */
    fun subscribeRaw(channel: String, onMessage: (ByteArray) -> Unit): AutoCloseable {
        val listener = object : io.lettuce.core.pubsub.RedisPubSubAdapter<ByteArray, ByteArray>() {
            override fun message(channel: ByteArray, message: ByteArray) = onMessage(message)
        }
        val codec = io.lettuce.core.codec.ByteArrayCodec.INSTANCE
        val nodes = clusterNodes
        val client: io.lettuce.core.AbstractRedisClient
        val connection: io.lettuce.core.pubsub.StatefulRedisPubSubConnection<ByteArray, ByteArray>
        if (nodes != null) {
            client = RedisClusterClient.create(nodes)
            connection = client.connectPubSub(codec)
        } else {
            client = RedisClient.create(standaloneUri)
            connection = client.connectPubSub(codec)
        }
        connection.addListener(listener)
        connection.sync().subscribe(channel.toByteArray(Charsets.UTF_8))
        return AutoCloseable {
            connection.close()
            client.shutdown()
        }
    }

    /**
     * 经 [proxy] 连接单机 Redis 的后端（用于模拟命令连接断线）。仅限单机。
     */
    fun proxiedBackend(proxy: TcpProxy): RedisBackend {
        check(!isCluster) { "Standalone only" }
        return RedisBackend.standalone(
            RedisURI.create(proxy.address, proxy.port),
            RedisBackendOptions(clientName = "cocache-tck-" + java.util.UUID.randomUUID())
        )
    }

    /**
     * 新建一个转发到单机 Redis 的 [TcpProxy]。仅限单机。
     */
    fun proxy(): TcpProxy {
        check(!isCluster) { "Standalone only" }
        return TcpProxy(standaloneUri.host, standaloneUri.port)
    }

    /**
     * 清空每个主节点的脚本缓存（`SCRIPT FLUSH`），模拟故障切换、重启或 resharding 后的脚本缓存丢失。
     */
    fun flushScripts() {
        val nodes = clusterNodes
        if (nodes != null) {
            val client = RedisClusterClient.create(nodes)
            try {
                client.connect().use { connection ->
                    connection.partitions
                        .filter { it.`is`(RedisClusterNode.NodeFlag.UPSTREAM) }
                        .forEach { node -> connection.getConnection(node.nodeId).sync().scriptFlush() }
                }
            } finally {
                client.shutdown()
            }
            return
        }
        val client = RedisClient.create(standaloneUri)
        try {
            client.connect().use { it.sync().scriptFlush() }
        } finally {
            client.shutdown()
        }
    }

    private fun forEachNode(block: (io.lettuce.core.api.sync.RedisCommands<String, String>) -> Unit) {
        val nodes = clusterNodes
        if (nodes != null) {
            val client = RedisClusterClient.create(nodes)
            try {
                client.connect().use { connection ->
                    // 集群订阅连接可能落在任意节点（包括从节点）上，ACL 也不随复制同步
                    connection.partitions.forEach { node ->
                        block(connection.getConnection(node.nodeId).sync())
                    }
                }
            } finally {
                client.shutdown()
            }
            return
        }
        val client = RedisClient.create(standaloneUri)
        try {
            client.connect().use { block(it.sync()) }
        } finally {
            client.shutdown()
        }
    }

    private fun <T> withKeys(block: (RedisKeyCommands<String, String>) -> T): T {
        val nodes = clusterNodes
        if (nodes != null) {
            val client = RedisClusterClient.create(nodes)
            try {
                client.connect().use { return block(it.sync()) }
            } finally {
                client.shutdown()
            }
        }
        val client = RedisClient.create(standaloneUri)
        try {
            client.connect().use { return block(it.sync()) }
        } finally {
            client.shutdown()
        }
    }

    /**
     * 把 [slot] 从当前主节点迁移到另一个主节点（`CLUSTER SETSLOT` IMPORTING / MIGRATING、`MIGRATE` 其中的 key、再 NODE），
     * 返回原主节点的 nodeId（用它作为 [targetNodeId] 再调用一次即可迁回）。仅限 Cluster。
     */
    fun migrateSlot(slot: Int, targetNodeId: String? = null): String {
        val client = RedisClusterClient.create(requireNotNull(clusterNodes) { "Redis Cluster only" })
        try {
            client.connect().use { connection ->
                val masters = connection.partitions.filter { it.`is`(RedisClusterNode.NodeFlag.UPSTREAM) }
                val source = masters.single { it.hasSlot(slot) }
                val target = masters.first { node ->
                    if (targetNodeId == null) node.nodeId != source.nodeId else node.nodeId == targetNodeId
                }
                val sourceCommands = connection.getConnection(source.nodeId).sync()
                val targetCommands = connection.getConnection(target.nodeId).sync()
                targetCommands.clusterSetSlotImporting(slot, source.nodeId)
                sourceCommands.clusterSetSlotMigrating(slot, target.nodeId)
                do {
                    val keys = sourceCommands.clusterGetKeysInSlot(slot, 100)
                    if (keys.isNotEmpty()) {
                        sourceCommands.migrate(
                            target.uri.host,
                            target.uri.port,
                            0,
                            5000,
                            MigrateArgs.Builder.keys(keys)
                        )
                    }
                } while (keys.isNotEmpty())
                // 先目标、再源，最后通知其余主节点，缩短各节点看到旧归属的窗口
                (listOf(target, source) + masters.filter { it.nodeId != source.nodeId && it.nodeId != target.nodeId })
                    .map { node -> connection.getConnection(node.nodeId).sync() }
                    .forEach { commands -> commands.clusterSetSlotNode(slot, target.nodeId) }
                return source.nodeId
            }
        } finally {
            client.shutdown()
        }
    }

    /**
     * 把匹配 [pattern] 的 key 的剩余寿命缩短 [duration]（缩到 0 及以下即删除）；永不过期的 key 不变。
     */
    fun shiftTtl(pattern: String, duration: Duration) {
        withKeys { commands ->
            var cursor: ScanCursor = ScanCursor.INITIAL
            do {
                val page: KeyScanCursor<String> = commands.scan(cursor, ScanArgs.Builder.matches(pattern).limit(1000))
                for (key in page.keys) {
                    val pttl = commands.pttl(key)
                    if (pttl > 0) {
                        val remaining = pttl - duration.toMillis()
                        if (remaining <= 0) commands.del(key) else commands.pexpire(key, remaining)
                    }
                }
                cursor = page
            } while (!page.isFinished)
        }
    }

    /**
     * 断开名为 [clientName] 的订阅连接（经典或 Sharded；Lettuce 会自动重连并重新订阅，触发重置）。只断开本测试的连接，不影响其它测试。
     */
    fun killPubSubConnections(clientName: String) {
        val nodes = clusterNodes
        if (nodes != null) {
            val client = RedisClusterClient.create(nodes)
            try {
                client.connect().use { connection ->
                    // 集群订阅连接可能落在任意节点（包括从节点）上，ACL 也不随复制同步
                    connection.partitions.forEach { node ->
                        val commands = connection.getConnection(node.nodeId).sync()
                        pubSubClientIds(
                            commands.clientList(),
                            clientName
                        ).forEach { id -> commands.clientKill(KillArgs.Builder.id(id)) }
                    }
                }
            } finally {
                client.shutdown()
            }
            return
        }
        val client = RedisClient.create(standaloneUri)
        try {
            client.connect().use { connection ->
                val commands = connection.sync()
                pubSubClientIds(
                    commands.clientList(),
                    clientName
                ).forEach { id -> commands.clientKill(KillArgs.Builder.id(id)) }
            }
        } finally {
            client.shutdown()
        }
    }

    /**
     * 名为 [clientName] 的订阅连接的 client id（单机）。
     */
    fun pubSubClientIds(clientName: String): List<Long> {
        val client = RedisClient.create(standaloneUri)
        try {
            client.connect().use { return pubSubClientIds(it.sync().clientList(), clientName) }
        } finally {
            client.shutdown()
        }
    }

    /**
     * `CLIENT LIST` 中的订阅计数：经典频道 `sub`、Sharded 频道 `ssub`（Redis 7+）。
     */
    private val SUBSCRIPTION_FIELDS = listOf("sub", "ssub")

    private fun pubSubClientIds(clientList: String, clientName: String): List<Long> {
        return clientList.lines()
            .map { line ->
                line.split(
                    ' '
                ).associate { field -> field.substringBefore('=') to field.substringAfter('=', "") }
            }
            .filter { it["name"] == clientName && SUBSCRIPTION_FIELDS.any { field -> (it[field]?.toIntOrNull() ?: 0) > 0 } }
            .mapNotNull { it["id"]?.toLongOrNull() }
    }
}
