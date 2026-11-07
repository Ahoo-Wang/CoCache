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
import io.lettuce.core.internal.HostAndPort
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import io.lettuce.core.resource.MappingSocketAddressResolver
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.VersionedL1Store
import me.ahoo.cocache.test.L1Capability
import java.time.Duration

/**
 * 集成测试的 Redis：默认 localhost:6379，可用 `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` 改为其他单机实例（与 starter 测试读取的变量相同，例如 CI 的 Valkey 任务）；
 * 设置 `SPRING_DATA_REDIS_CLUSTER_NODES` 时连接 Redis Cluster（与 CI 的集群任务一致）。
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

    private val standaloneUri: RedisURI = RedisURI.create(
        System.getenv("SPRING_DATA_REDIS_HOST") ?: "localhost",
        System.getenv("SPRING_DATA_REDIS_PORT")?.toInt() ?: 6379
    )

    val isCluster: Boolean = clusterNodes != null

    /**
     * Redis 后端的存储提供全部可选能力（三种通道模式、单机与集群都一样），TCK 的 `expectedCapabilities` 声明它。
     */
    val CAPABILITIES: Set<L1Capability> = setOf(L1Capability.CONDITIONAL, L1Capability.VERSIONED, L1Capability.SETS)

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
     * 非原子广播的后端：用 ACL 禁止向探测频道发布（只允许任意前缀的失效频道 `*inv:*`），探测失败后后端退回“写入后由引擎发布”。
     */
    fun nonAtomicBackend(): RedisBackend = aclBackend(
        "cocache-nonatomic",
        listOf("~*", "resetchannels", "&*inv:*", "+@all")
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
     * 删除 [aclBackend] 设置的 ACL 用户（各节点）。先关闭以该用户连接的后端：删除用户会断开它的连接。
     */
    fun deleteAclUser(user: String) {
        forEachNode { commands -> commands.aclDeluser(user) }
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
     * 同 [withCommands]，key 与值都是字节（用于构造与读取原始的线格式内容）。
     */
    fun <T> withBinaryCommands(
        block: (io.lettuce.core.cluster.api.sync.RedisClusterCommands<ByteArray, ByteArray>) -> T
    ): T {
        val codec = io.lettuce.core.codec.ByteArrayCodec.INSTANCE
        val nodes = clusterNodes
        if (nodes != null) {
            val client = RedisClusterClient.create(nodes)
            try {
                client.connect(codec).use { return block(it.sync()) }
            } finally {
                client.shutdown()
            }
        }
        val client = RedisClient.create(standaloneUri)
        try {
            client.connect(codec).use { return block(it.sync()) }
        } finally {
            client.shutdown()
        }
    }

    /**
     * 同 [subscribeRaw]，但跳过创建存储时的零 key 探测帧（RFC 0005 §4.7，K8）：Cluster 中经典 Pub/Sub 的帧经集群总线异步转发，
     * 订阅之前创建的存储发出的探测帧可能在订阅之后才到达。
     */
    fun subscribeFrames(channel: String, onMessage: (ByteArray) -> Unit): AutoCloseable =
        subscribeRaw(channel) { if (!isProbeFrame(it)) onMessage(it) }

    private fun isProbeFrame(message: ByteArray): Boolean =
        runCatching { InvalidationFrame.decode(message).keys.isEmpty() }.getOrDefault(false)

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
     * 经 [proxy] 连接单机 Redis 的后端（用于模拟命令连接断线）。仅限单机。[resources] 非 `null` 时用它建客户端（调用方负责 `shutdown`）。
     */
    fun proxiedBackend(proxy: TcpProxy, resources: ClientResources? = null): RedisBackend {
        check(!isCluster) { "Standalone only" }
        return RedisBackend.standalone(
            RedisURI.create(proxy.address, proxy.port),
            RedisBackendOptions(clientName = "cocache-tck-" + java.util.UUID.randomUUID(), clientResources = resources)
        )
    }

    /**
     * 连接单机 Redis 的客户端（调用方负责 `shutdown`），[resources] 为 `null` 时自建。仅限单机。
     */
    fun standaloneClient(resources: ClientResources? = null): RedisClient {
        check(!isCluster) { "Standalone only" }
        return if (resources == null) {
            RedisClient.create(standaloneUri)
        } else {
            RedisClient.create(resources, standaloneUri)
        }
    }

    /**
     * 新建一个转发到单机 Redis 的 [TcpProxy]。仅限单机。
     */
    fun proxy(): TcpProxy {
        check(!isCluster) { "Standalone only" }
        return TcpProxy(standaloneUri.host, standaloneUri.port)
    }

    /**
     * 集群的主节点（取自拓扑）。仅限 Cluster。
     */
    fun masters(): List<RedisClusterNode> {
        val client = RedisClusterClient.create(requireNotNull(clusterNodes) { "Redis Cluster only" })
        try {
            client.connect().use { connection ->
                return connection.partitions.filter { it.`is`(RedisClusterNode.NodeFlag.UPSTREAM) }
            }
        } finally {
            client.shutdown()
        }
    }

    /**
     * 在每个节点上缓存 [scripts]（`SCRIPT LOAD`），之后的 `EVALSHA` 不会得到 NOSCRIPT。
     */
    internal fun loadScripts(vararg scripts: RedisScripts.Script) {
        forEachNode { commands -> scripts.forEach { commands.scriptLoad(String(it.source, Charsets.UTF_8)) } }
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

    /**
     * 可以暂停的连接：单机一个 [TcpProxy]，集群每个节点（含从节点）一个，[PausableRedis.resources] 把节点地址映射到代理。
     * [customize] 追加其它客户端资源选项（如 `nettyCustomizer`）。用于命令超时的用例：只暂停用这些资源连接的客户端，
     * 不用服务端的 `CLIENT PAUSE`（见 [TcpProxy.pause]）。
     */
    fun pausable(customize: (ClientResources.Builder) -> Unit = {}): PausableRedis {
        val nodes = clusterNodes
        val targets = if (nodes == null) {
            listOf(HostAndPort.of(standaloneUri.host, standaloneUri.port))
        } else {
            val client = RedisClusterClient.create(nodes)
            try {
                client.connect().use { connection ->
                    connection.partitions.map { HostAndPort.of(it.uri.host, it.uri.port) }
                }
            } finally {
                client.shutdown()
            }
        }
        return PausableRedis(targets.associate { it.port to TcpProxy(it.hostText, it.port) }, customize)
    }

    /**
     * 经 [TcpProxy] 连接 Redis 的客户端资源（[pausable]）：Lettuce 解析节点地址时按端口映射到对应的代理。
     */
    class PausableRedis internal constructor(
        private val proxies: Map<Int, TcpProxy>,
        customize: (ClientResources.Builder) -> Unit,
    ) : AutoCloseable {
        val resources: ClientResources = DefaultClientResources.builder()
            .socketAddressResolver(
                MappingSocketAddressResolver.create(
                    java.util.function.Function<HostAndPort, HostAndPort> { address ->
                        proxies[address.port]?.let { HostAndPort.of(it.address, it.port) } ?: address
                    }
                )
            )
            .also(customize)
            .build()

        /**
         * 暂停经这些代理的连接 [millis] 毫秒：暂停期间发出的命令已写出，到期后按序到达 Redis 并执行（[TcpProxy.pause]）。
         */
        fun pause(millis: Long) = proxies.values.forEach { it.pause(millis) }

        override fun close() {
            resources.shutdown()
            proxies.values.forEach { it.close() }
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
     * 删除匹配 [pattern] 的全部 key（集群中遍历全部主节点）。
     */
    fun deleteKeys(pattern: String) {
        withKeys { commands ->
            var cursor: ScanCursor = ScanCursor.INITIAL
            do {
                val page: KeyScanCursor<String> = commands.scan(cursor, ScanArgs.Builder.matches(pattern).limit(1000))
                page.keys.forEach { commands.del(it) }
                cursor = page
            } while (!page.isFinished)
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
     * 名为 [clientName] 的连接在全部节点上订阅的频道数（`CLIENT LIST` 的 `sub` 与 `ssub` 之和，单机或集群）：频道退订之后不再计入。
     */
    fun subscriptionCount(clientName: String): Int {
        var count = 0
        forEachNode { commands ->
            count += clientsOf(commands.clientList(), clientName).sumOf { client ->
                SUBSCRIPTION_FIELDS.sumOf { field -> client[field]?.toIntOrNull() ?: 0 }
            }
        }
        return count
    }

    /**
     * `CLIENT LIST` 中的订阅计数：经典频道 `sub`、Sharded 频道 `ssub`（Redis 7+）。
     */
    private val SUBSCRIPTION_FIELDS = listOf("sub", "ssub")

    private fun pubSubClientIds(clientList: String, clientName: String): List<Long> {
        return clientsOf(clientList, clientName)
            .filter { SUBSCRIPTION_FIELDS.any { field -> (it[field]?.toIntOrNull() ?: 0) > 0 } }
            .mapNotNull { it["id"]?.toLongOrNull() }
    }

    /**
     * `CLIENT LIST` 中名为 [clientName] 的连接，每个为字段名到值的映射。
     */
    private fun clientsOf(clientList: String, clientName: String): List<Map<String, String>> {
        return clientList.lines()
            .map { line ->
                line.split(
                    ' '
                ).associate { field -> field.substringBefore('=') to field.substringAfter('=', "") }
            }
            .filter { it["name"] == clientName }
    }
}

/**
 * 存储的条件读写能力面（Redis 后端总是提供）。
 */
internal val L1Store.conditionalSurface: ConditionalL1Store
    get() = requireNotNull(conditional()) { "${javaClass.name} does not provide the conditional capability." }

/**
 * 存储的按版本写入能力面（Redis 后端总是提供）。
 */
internal val L1Store.versionedSurface: VersionedL1Store
    get() = requireNotNull(versioned()) { "${javaClass.name} does not provide the versioned capability." }

/**
 * 存储的集合能力面（Redis 后端总是提供）。
 */
internal val L1Store.setSurface: SetL1Store
    get() = requireNotNull(sets()) { "${javaClass.name} does not provide the sets capability." }
