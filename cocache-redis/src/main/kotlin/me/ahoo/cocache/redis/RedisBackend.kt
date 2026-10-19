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
import io.lettuce.core.cluster.ClusterClientOptions
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.resource.ClientResources
import io.lettuce.core.resource.DefaultClientResources
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.RemoteStore

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
    private val connections = RedisConnections(client, options.commandTimeout)
    private val invalidationChannel = RedisInvalidationChannel.create(connections, options)
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
    override val atomicBroadcast: Boolean = channelMode == RedisChannelMode.PUBSUB && probeAtomicBroadcast()

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

    private fun probeAtomicBroadcast(): Boolean {
        return try {
            connections.eval<List<Any?>>(RedisScripts.PROBE, listOf(PROBE_KEY), listOf(PROBE_CHANNEL))
            true
        } catch (e: RedisException) {
            log.warn(e) { "Atomic broadcast is not supported by this Redis deployment - publish after writes instead." }
            false
        }
    }

    override fun close() {
        invalidationChannel.close()
        connections.close()
        if (ownsClient) {
            client.shutdown()
        }
        ownedResources?.shutdown()
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private val PROBE_KEY = "cocache:probe:{a}".toByteArray(Charsets.UTF_8)
        private val PROBE_CHANNEL = "cocache:probe:{b}".toByteArray(Charsets.UTF_8)

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
         */
        @JvmStatic
        @JvmOverloads
        fun of(client: AbstractRedisClient, options: RedisBackendOptions = RedisBackendOptions()): RedisBackend {
            return RedisBackend(client, false, null, options)
        }
    }
}
