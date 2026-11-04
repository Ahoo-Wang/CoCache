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
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.cluster.ClusterClientOptions
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import io.lettuce.core.resource.EpollProvider
import io.lettuce.core.resource.IOUringProvider
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1RejectedException
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.NodeId
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
        // 自建的客户端按 commandTimeout 为每条命令计时（timeoutOptions），见 CommandTimeouts
        val timeouts = when {
            !ownsClient -> CommandTimeouts.TIMER
            client is RedisClusterClient -> CommandTimeouts.CLIENT_WRITTEN
            else -> CommandTimeouts.CLIENT
        }
        connections = closeOnFailure(::shutdownOwned) { RedisConnections(client, options.commandTimeout, timeouts) }
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

    /**
     * 失效通道：发布挂起（Lettuce 的异步命令，不占线程），订阅阻塞（RFC 0005 §4.3）。
     */
    override val channel: InvalidationChannel
        get() = invalidationChannel

    /**
     * 具体类型的通道（模块内部，供测试查看订阅状态）。
     */
    internal val redisChannel: RedisInvalidationChannel
        get() = invalidationChannel

    /**
     * 失效通道心跳是否正常：最近一次心跳成功或尚未执行心跳时为 `true`。只读，供健康检查使用。
     */
    val heartbeatHealthy: Boolean
        get() = invalidationChannel.lastHeartbeatFailure == null

    /**
     * 最近一次心跳失败的原因；心跳正常时为 `null`。
     */
    val heartbeatFailure: String?
        get() = invalidationChannel.lastHeartbeatFailure

    /**
     * 各主节点的 `maxmemory-policy`（键为 `host:port`，单机为 `redis`），供启动检查判断集合缓存的唯一副本会不会被驱逐。
     * 读取 `INFO memory`（托管 Redis 通常禁用 `CONFIG`，但允许 `INFO`）；`INFO` 不可用（被禁用、改名、ACL 无权限）时返回 `null`，
     * 由调用方决定怎么处理。
     */
    fun maxmemoryPolicies(): Map<String, String>? {
        return try {
            connections.maxmemoryPolicies()
        } catch (e: RedisException) {
            log.info { "Cannot read maxmemory-policy from INFO memory (${e.message})." }
            null
        } catch (e: L1RejectedException) {
            log.info { "Cannot read maxmemory-policy from INFO memory (${e.message})." }
            null
        }
    }

    /**
     * [keyspace] 的存储：原生挂起的 [RedisL1Store]（RFC 0005 §4.3），与后端共用连接。提供全部能力：条件读写、按版本有序的写入
     * （[RedisScripts.PUT_IF_NEWER]，一次往返）与集合成员的增删。
     *
     * 创建时探测实际频道 `{keyPrefix}inv:{namespace}`（[probeChannel]），阻塞一次往返；无权发布时抛出 [IllegalStateException]。
     */
    override fun store(keyspace: Keyspace): L1Store {
        val channel = RedisInvalidationChannel.channelOf(keyspace).toByteArray(Charsets.UTF_8)
        probeChannel(keyspace, channel)
        return RedisL1Store(connections, if (atomicBroadcast) channel else ByteArray(0), invalidateBatchSize)
    }

    /**
     * 按存储探测实际频道（RFC 0005 §4.7，K8）：向 [keyspace] 的频道发布一个零 key 的帧（监听者什么也不做）。原子广播时经探测脚本，
     * 以该前缀下的 key（[Keyspace.storagePrefix]）为 `KEYS[1]`，与写入脚本受同样的 key 与频道 ACL 约束；否则是普通的发布
     * （`PUBLISH` 或 `SPUBLISH`，同引擎写入之后的发布）。
     *
     * 构造时的原子广播探测发往固定的探测频道，代表不了带前缀的实际频道。漏配时每个写脚本都在 `PUBLISH` 处报错，而 Redis 脚本出错
     * 不回滚，之前的 `SET` / `DEL` 已经生效，其它节点陈旧到 `localMaxAge`。所以无权发布（ACL）时创建失败，不降级为非原子广播：
     * 这是配置错误，不是环境属性。超时、断线等其它错误照常抛出。
     */
    private fun probeChannel(keyspace: Keyspace, channel: ByteArray) {
        try {
            if (atomicBroadcast) {
                val key = keyspace.storagePrefix.toByteArray(Charsets.UTF_8)
                connections.join(
                    connections.evalAsync<Any?>(RedisScripts.PROBE, listOf(key), listOf(channel, PROBE_FRAME))
                )
            } else {
                invalidationChannel.publishBlocking(channel, PROBE_FRAME)
            }
        } catch (e: RedisException) {
            if (!isPermissionDenied(e)) {
                throw e
            }
            throw IllegalStateException(
                "Redis denied publishing to the invalidation channel[${String(channel, Charsets.UTF_8)}] of $keyspace: allow the channel " +
                    "pattern [${RedisInvalidationChannel.channelPatternOf(keyspace)}] in the ACL of this connection's user " +
                    "(atomic broadcast also needs the key pattern [~${keyspace.storagePrefix}*]). Without it every write " +
                    "would fail at its broadcast after changing L1. Redis replied: ${e.message}",
                e
            )
        }
    }

    /**
     * 只有确定性的环境错误才退回非原子模式：跨 slot（把 PUBLISH 当作 SPUBLISH 执行的环境）或无权向探测频道发布（ACL）。
     * 超时、断线等暂时性错误照常抛出，不能因一次抖动而永久降级。
     */
    private fun probeAtomicBroadcast(): Boolean {
        return try {
            connections.join(
                connections.evalAsync<List<Any?>>(
                    RedisScripts.PROBE,
                    listOf(PROBE_KEY),
                    listOf(PROBE_CHANNEL, PROBE_FRAME)
                )
            )
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

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 幂等：重复关闭什么也不做。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        invalidationChannel.close()
        connections.close()
        shutdownOwned()
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * 原子广播探测的 key 与频道（RFC 0005 §4.7）：放在默认前缀的内部位置 `cocache:!…`，任何命名空间都产生不了它
         * （命名空间为 `probe` 的缓存也不会与它相撞）；两个 hash tag 落在不同的 slot。
         */
        internal const val PROBE_KEY_NAME = "cocache:!probe:{a}"
        internal const val PROBE_CHANNEL_NAME = "cocache:!probe:{b}"
        private val PROBE_KEY = PROBE_KEY_NAME.toByteArray(Charsets.UTF_8)
        private val PROBE_CHANNEL = PROBE_CHANNEL_NAME.toByteArray(Charsets.UTF_8)

        /**
         * 探测发布的帧：零 key 的合法帧，来源为全零的节点身份（监听者什么也不做）。
         */
        private val PROBE_FRAME = InvalidationFrame.encode(NodeId(ByteArray(NodeId.SIZE)), emptyList())

        // Redis 7.0 在脚本内被 ACL 拒绝 PUBLISH 时报 "can't publish to the channel"，7.2+ 报 NOPERM
        private val PERMISSION_ERRORS = listOf("NOPERM", "No permissions", "can't publish to the channel")
        private val UNSUPPORTED_BROADCAST_ERRORS = listOf("CROSSSLOT", "different slot") + PERMISSION_ERRORS

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

        internal fun isBroadcastUnsupported(error: Throwable): Boolean = matches(error, UNSUPPORTED_BROADCAST_ERRORS)

        /**
         * ACL 拒绝（key、频道或命令）。
         */
        internal fun isPermissionDenied(error: Throwable): Boolean = matches(error, PERMISSION_ERRORS)

        private fun matches(error: Throwable, patterns: List<String>): Boolean {
            return generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
                .mapNotNull { it.message }
                .any { message -> patterns.any { message.contains(it, ignoreCase = true) } }
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
         * 自建客户端的命令超时：每条命令（含订阅连接上的命令）由 Lettuce 按 [commandTimeout] 计时（Netty 时间轮，
         * 精度为一个刻度，默认 100 ms），超时以 `RedisCommandTimeoutException` 完成（RFC 0004 §14）。单机的异步视图只做异常映射；
         * 集群中它替换 Lettuce 默认的 60 s 连接超时，不增加开销。
         * 关闭维护期间的放宽（Lettuce 7 默认在收到维护通知时把超时放宽 10 s），上界始终是 [commandTimeout]，与同步等待一致。
         * 这一项只有 Lettuce 7 才有（`relaxedTimeoutsDuringMaintenance`、`DISABLED_TIMEOUT`）：Spring Boot 4.0 管理的 Lettuce 6.8
         * 没有维护通知，也没有这两个符号，此时跳过，维护期间的超时行为保持 Lettuce 的默认（6.x 上即不放宽）。
         */
        internal fun timeoutOptions(commandTimeout: Duration): TimeoutOptions {
            val builder = TimeoutOptions.builder()
                .timeoutCommands(true)
                .fixedTimeout(commandTimeout)
            disableMaintenanceRelaxation(builder)
            return builder.build()
        }

        /**
         * 关闭维护期间的超时放宽；当前 Lettuce 没有这一项（6.x）时什么也不做并返回 `false`。
         * [relax] 只供测试替换：符号在执行到时才解析，缺失时抛出 `NoSuchMethodError` / `NoSuchFieldError`。
         */
        internal fun disableMaintenanceRelaxation(
            builder: TimeoutOptions.Builder,
            relax: (TimeoutOptions.Builder) -> Unit = ::relaxNever
        ): Boolean {
            return try {
                relax(builder)
                true
            } catch (_: IncompatibleClassChangeError) {
                false
            }
        }

        private fun relaxNever(builder: TimeoutOptions.Builder) {
            builder.relaxedTimeoutsDuringMaintenance(TimeoutOptions.DISABLED_TIMEOUT)
        }

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
                .timeoutOptions(timeoutOptions(options.commandTimeout))
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
                    .timeoutOptions(timeoutOptions(options.commandTimeout))
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
         * 客户端选项由调用方负责：断线行为应为 [ClientOptions.DisconnectedBehavior.REJECT_COMMANDS]，否则记录告警（断线期间命令被缓冲，
         * 各自等满 `commandTimeout` 才失败；超时的脚本命令不会在重连后重放）。
         * 异步命令的超时仍由后端以 `commandTimeout` 另外计时（客户端自己的命令超时未知，客户端资源的时间轮）。
         */
        @JvmStatic
        @JvmOverloads
        fun of(client: AbstractRedisClient, options: RedisBackendOptions = RedisBackendOptions()): RedisBackend {
            val behavior = client.options.disconnectedBehavior
            if (behavior != ClientOptions.DisconnectedBehavior.REJECT_COMMANDS) {
                log.warn {
                    "Redis client disconnectedBehavior is $behavior, not REJECT_COMMANDS: commands are buffered while disconnected, " +
                        "so each one waits for the command timeout instead of failing fast."
                }
            }
            return RedisBackend(client, false, null, options)
        }
    }
}
