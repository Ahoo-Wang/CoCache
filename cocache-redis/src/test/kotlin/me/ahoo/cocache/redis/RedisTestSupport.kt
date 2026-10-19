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
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisKeyCommands
import io.lettuce.core.cluster.RedisClusterClient
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
     * 非原子广播的后端：用 ACL 禁止向探测频道发布（只允许 `cocache:inv:*`），探测失败后后端退回“写入后由引擎发布”。
     */
    fun nonAtomicBackend(): RedisBackend {
        val user = "cocache-nonatomic"
        val password = "nonatomic-secret"
        forEachUpstream { commands ->
            commands.dispatch(
                io.lettuce.core.protocol.CommandType.ACL,
                io.lettuce.core.output.StatusOutput(io.lettuce.core.codec.StringCodec.UTF8),
                io.lettuce.core.protocol.CommandArgs(io.lettuce.core.codec.StringCodec.UTF8)
                    .add("SETUSER").add(user).add("reset").add("on").add(">$password")
                    .add("~*").add("resetchannels").add("&cocache:inv:*").add("+@all")
            )
        }
        val options = RedisBackendOptions(clientName = "cocache-tck-" + java.util.UUID.randomUUID())
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

    private fun forEachUpstream(block: (io.lettuce.core.api.sync.RedisCommands<String, String>) -> Unit) {
        val nodes = clusterNodes
        if (nodes != null) {
            val client = RedisClusterClient.create(nodes)
            try {
                client.connect().use { connection ->
                    connection.partitions.filter {
                        it.`is`(io.lettuce.core.cluster.models.partitions.RedisClusterNode.NodeFlag.UPSTREAM)
                    }.forEach { node ->
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
     * 断开名为 [clientName] 的 Pub/Sub 连接（Lettuce 会自动重连并重新订阅，触发重置）。只断开本测试的连接，不影响其它测试。
     */
    fun killPubSubConnections(clientName: String) {
        val nodes = clusterNodes
        if (nodes != null) {
            val client = RedisClusterClient.create(nodes)
            try {
                client.connect().use { connection ->
                    connection.partitions.filter {
                        it.`is`(io.lettuce.core.cluster.models.partitions.RedisClusterNode.NodeFlag.UPSTREAM)
                    }.forEach { node ->
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

    private fun pubSubClientIds(clientList: String, clientName: String): List<Long> {
        return clientList.lines()
            .map { line ->
                line.split(
                    ' '
                ).associate { field -> field.substringBefore('=') to field.substringAfter('=', "") }
            }
            .filter { it["name"] == clientName && (it["sub"]?.toIntOrNull() ?: 0) > 0 }
            .mapNotNull { it["id"]?.toLongOrNull() }
    }
}
