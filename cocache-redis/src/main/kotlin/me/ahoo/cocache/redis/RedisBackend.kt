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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.cluster.ClusterClientOptions
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import io.lettuce.core.resource.EpollProvider
import io.lettuce.core.resource.IOUringProvider
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.RemoteStore
import java.time.Duration

/**
 * Lettuce 原生的 Redis 后端（RFC 0003 §4.7），不依赖 Spring。支持单机与 Cluster，只访问主节点。
 *
 * 推荐用 [standalone] / [cluster] 创建：后端自建客户端，断线时拒绝命令（不缓冲重放，否则已向调用方报错的写入可能稍后执行）。
 * 用 [of] 传入已有客户端时，由调用方负责客户端选项与生命周期。
 */
class RedisBackend private constructor(
    private val client: AbstractRedisClient,
    private val ownsClient: Boolean,
    private val ownedResources: ClientResources?,
    options: RedisBackendOptions,
) : CoherenceBackend {
    private val connections: RedisConnections
    private val invalidationChannel: RedisInvalidationChannel
    private val invalidateBatchSize = options.invalidateBatchSize

    /**
     * 后端自建客户端的连接名（[RedisBackendOptions.clientName]）。
     */
    val clientName: String? = options.clientName

    override val format: Int = RedisScripts.FORMAT

    /**
     * 失效通道模式。
     */
    val channelMode: RedisChannelMode = options.channelMode

    /**
     * [RedisChannelMode.SHARDED] 下恒为 `false`（脚本内不广播）。经典 Pub/Sub 下启动时探测：在把 PUBLISH 当作 SPUBLISH 执行的环境
     * （如 ElastiCache Serverless）中，脚本内广播会报跨 slot 错误，此时退回非原子模式（写入成功后由引擎单独发布）。
     */
    override val atomicBroadcast: Boolean

    init {
        // 构造失败时按相反顺序关闭已建立的部分（心跳线程、连接、自建的客户端与资源），避免泄漏
        connections = closeOnFailure(::shutdownOwned) { RedisConnections(client, options.commandTimeout) }
        invalidationChannel = closeOnFailure({
            connections.close()
            shutdownOwned()
        }) { RedisInvalidationChannel.create(connections, options) }
        atomicBroadcast = closeOnFailure({
            invalidationChannel.close()
            connections.close()
            shutdownOwned()
        }) { channelMode == RedisChannelMode.PUBSUB && probeAtomicBroadcast() }
    }

    override val channel: InvalidationChannel
        get() = invalidationChannel

    override fun store(namespace: String): RemoteStore {
        val channel = if (atomicBroadcast) {
            RedisInvalidationChannel.channelOf(
                namespace
            ).toByteArray(Charsets.UTF_8)
        } else {
            ByteArray(0)
        }
        return RedisRemoteStore(connections, channel, invalidateBatchSize)
    }

    /**
     * 只有确定性的环境错误才退回非原子模式：跨 slot（把 PUBLISH 当作 SPUBLISH 执行的环境）或无权向探测频道发布（ACL）。
     * 超时、断线等暂时性错误照常抛出，不能因一次抖动而永久降级。
     */
    private fun probeAtomicBroadcast(): Boolean {
        return try {
            connections.eval<List<Any?>>(RedisScripts.PROBE, listOf(PROBE_KEY), listOf(PROBE_CHANNEL))
            true
        } catch (e: RedisException) {
            if (!isBroadcastUnsupported(e)) {
                throw e
            }
            log.warn(e) { "Atomic broadcast is not supported by this Redis deployment - publish after writes instead." }
            false
        }
    }

    private fun shutdownOwned() {
        if (ownsClient) {
            client.shutdown()
        }
        ownedResources?.shutdown()
    }

    override fun close() {
        invalidationChannel.close()
        connections.close()
        shutdownOwned()
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private val PROBE_KEY = "cocache:probe:{a}".toByteArray(Charsets.UTF_8)
        private val PROBE_CHANNEL = "cocache:probe:{b}".toByteArray(Charsets.UTF_8)
        private val UNSUPPORTED_BROADCAST_ERRORS = listOf("CROSSSLOT", "different slot", "NOPERM", "No permissions")

        /**
         * TCP keepalive：空闲 15 s 后每 5 s 探测一次，连续 3 次无响应即判定断开（需要传输层支持扩展选项，否则使用系统默认间隔）。
         */
        private val KEEP_ALIVE_IDLE = Duration.ofSeconds(15)
        private val KEEP_ALIVE_INTERVAL = Duration.ofSeconds(5)
        private const val KEEP_ALIVE_COUNT = 3

        /**
         * `TCP_USER_TIMEOUT`：已发送的数据超过该时长未被确认即断开（仅 epoll / io_uring 传输支持）。
         */
        private val TCP_USER_TIMEOUT = Duration.ofSeconds(30)

        /**
         * 执行 [block]；失败（任何异常）时先执行 [cleanup] 再抛出。
         */
        private inline fun <T> closeOnFailure(cleanup: () -> Unit, block: () -> T): T {
            var succeeded = false
            try {
                return block().also { succeeded = true }
            } finally {
                if (!succeeded) cleanup()
            }
        }

        internal fun isBroadcastUnsupported(error: Throwable): Boolean {
            return generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
                .mapNotNull { it.message }
                .any { message -> UNSUPPORTED_BROADCAST_ERRORS.any { message.contains(it, ignoreCase = true) } }
        }

        /**
         * 自建客户端的套接字选项：开启 keepalive 与 `TCP_USER_TIMEOUT`，让半开连接在秒级内被内核发现并断开，
         * 由 Lettuce 自动重连（重新订阅后重置订阅者）。
         */
        internal fun socketOptions(
            nativeTransport: Boolean = EpollProvider.isAvailable() || IOUringProvider.isAvailable()
        ): SocketOptions = SocketOptions.builder()
            .keepAlive(
                SocketOptions.KeepAliveOptions.builder()
                    .enable()
                    .idle(KEEP_ALIVE_IDLE)
                    .interval(KEEP_ALIVE_INTERVAL)
                    .count(KEEP_ALIVE_COUNT)
                    .build()
            )
            .apply {
                // Lettuce 在非原生传输（NIO）上启用 TCP_USER_TIMEOUT 会直接报错：只在 epoll / io_uring 可用时启用
                if (nativeTransport) {
                    tcpUserTimeout(
                        SocketOptions.TcpUserTimeoutOptions.builder().enable().tcpUserTimeout(TCP_USER_TIMEOUT).build()
                    )
                }
            }
            .build()

        /**
         * 带连接名的副本（不修改调用方的 URI）。
         */
        private fun RedisURI.named(clientName: String?): RedisURI {
            return clientName?.let { RedisURI.builder(this).withClientName(it).build() } ?: this
        }

        /**
         * 单机（或 Sentinel URI）。
         */
        @JvmStatic
        @JvmOverloads
        fun standalone(uri: RedisURI, options: RedisBackendOptions = RedisBackendOptions()): RedisBackend {
            val owned = if (options.clientResources == null) DefaultClientResources.create() else null
            val client = RedisClient.create(options.clientResources ?: owned, uri.named(options.clientName))
            client.options = ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .socketOptions(socketOptions())
                .apply { options.sslOptions?.let { sslOptions(it) } }
                .build()
            return RedisBackend(client, true, owned, options)
        }

        /**
         * Redis Cluster：自适应拓扑刷新，命令与脚本按 key 路由到主节点。
         */
        @JvmStatic
        @JvmOverloads
        fun cluster(uris: List<RedisURI>, options: RedisBackendOptions = RedisBackendOptions()): RedisBackend {
            val owned = if (options.clientResources == null) DefaultClientResources.create() else null
            val client = RedisClusterClient.create(
                options.clientResources ?: owned,
                uris.map { it.named(options.clientName) }
            )
            client.setOptions(
                ClusterClientOptions.builder()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .autoReconnect(true)
                    .socketOptions(socketOptions())
                    .apply { options.sslOptions?.let { sslOptions(it) } }
                    .topologyRefreshOptions(
                        ClusterTopologyRefreshOptions.builder().enableAllAdaptiveRefreshTriggers().build()
                    )
                    .build()
            )
            return RedisBackend(client, true, owned, options)
        }

        /**
         * 使用已有客户端（[RedisClient] 或 [RedisClusterClient]）。后端关闭时不关闭该客户端。
         *
         * 客户端选项由调用方负责：断线行为应为 [ClientOptions.DisconnectedBehavior.REJECT_COMMANDS]，否则记录告警。
         */
        @JvmStatic
        @JvmOverloads
        fun of(client: AbstractRedisClient, options: RedisBackendOptions = RedisBackendOptions()): RedisBackend {
            val behavior = client.options.disconnectedBehavior
            if (behavior != ClientOptions.DisconnectedBehavior.REJECT_COMMANDS) {
                log.warn {
                    "Redis client disconnectedBehavior is $behavior, not REJECT_COMMANDS: commands buffered while disconnected " +
                        "may be replayed after a failure was already reported (e.g. a stale put overwriting a newer value)."
                }
            }
            return RedisBackend(client, false, null, options)
        }
    }
}
