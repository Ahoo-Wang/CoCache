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
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubAdapter
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubListener
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Sharded Pub/Sub 失效通道（`SSUBSCRIBE` / `SPUBLISH`，Redis 7+），RFC 0003 §4.7。
 *
 * - Cluster 中 Lettuce 按频道的 slot 把 `SSUBSCRIBE` 路由到所属主节点的连接，各节点连接上的消息与确认都带着节点交给
 *   节点感知的监听器（[RedisClusterPubSubListener]）。节点连接断线重连后由 Lettuce 重新订阅，确认（`ssubscribed`）触发重置。
 * - 记录每个频道由哪个节点确认订阅（承载订阅的连接）。心跳时若 slot 的当前所属主节点不是该节点（故障切换后连接重连到了
 *   已降为副本的旧主节点、slot 迁移的退订通知丢失等），视为订阅丢失：重置订阅者、在旧节点上退订并重新订阅到当前所属节点。
 * - slot 迁移：原节点让出 slot 时向订阅者主动发送 `sunsubscribe`，Lettuce 不会自动重新订阅（#629）。
 *   收到非主动退订的 `sunsubscribed` 时立即重置订阅者并重新订阅（路由到新的所属节点），重新订阅得到确认时再重置一次；
 *   重新订阅失败（如拓扑尚未刷新）时由心跳重试，直到确认。来自非确认节点的退订（上面清理旧节点时的回执）被忽略。
 * - 心跳：单机 PING 订阅连接；Cluster 中 PING 承载订阅的各节点连接（确认订阅的节点），失败的节点连接被强制断开重连。
 */
internal class ShardedInvalidationChannel(
    connections: RedisConnections,
    heartbeat: Duration,
    ackTimeout: Duration,
) : RedisInvalidationChannel(connections, heartbeat, ackTimeout) {
    @Suppress("UNCHECKED_CAST")
    private val clusterPubSub = connections.pubSub as? StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>

    /**
     * 被服务端退订（slot 迁移）后尚未重新确认的频道，心跳时重试订阅。
     */
    private val unconfirmed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Cluster 中每个频道最近一次确认订阅的节点（`host:port`，与 Lettuce 缓存节点连接的键一致）。
     */
    private val subscribedNodes = ConcurrentHashMap<String, HostAndPort>()

    /**
     * 心跳发现订阅落在非所属节点后，在该旧节点上退订的频道：旧节点的退订回执不代表订阅丢失。
     */
    private val staleNodes = ConcurrentHashMap<String, HostAndPort>()

    init {
        val cluster = clusterPubSub
        if (cluster == null) {
            connections.pubSub.addListener(Adapter())
        } else {
            // 只用节点感知的监听器（需要确认节点），关闭向普通监听器的转发，避免重复回调
            cluster.setNodeMessagePropagation(false)
            cluster.addListener(ClusterAdapter())
        }
        start()
    }

    /**
     * 确认订阅 [namespace] 的节点（仅 Cluster），测试用。
     */
    internal fun subscribedNode(namespace: String): HostAndPort? = subscribedNodes[channelOf(namespace)]

    override fun subscribeAsync(channel: ByteArray): CompletableFuture<Void> {
        return connections.pubSub.async().ssubscribe(channel).toCompletableFuture()
    }

    override fun unsubscribeAsync(channel: ByteArray): CompletableFuture<Void> {
        return connections.pubSub.async().sunsubscribe(channel).toCompletableFuture()
    }

    override fun publish(namespace: String, frame: ByteArray) {
        connections.spublish(channelOf(namespace).toByteArray(Charsets.UTF_8), frame)
    }

    override fun publishAsync(namespace: String, frame: ByteArray): CompletableFuture<Void?> =
        connections.spublishAsync(channelOf(namespace).toByteArray(Charsets.UTF_8), frame)

    @Suppress("TooGenericExceptionCaught")
    override fun ping() {
        unconfirmed.forEach(::resubscribe)
        val cluster = clusterPubSub
        if (cluster == null) {
            connections.await(connections.pubSub.async().ping().toCompletableFuture())
            return
        }
        subscribedNodes.keys.retainAll(listeners.keys)
        staleNodes.keys.retainAll(listeners.keys)
        checkOwnership(cluster)
        // 逐一探测承载订阅的节点连接（以 host:port 取连接，与 Lettuce 路由 SSUBSCRIBE 时缓存的连接一致）；
        // 尚无确认节点记录的频道（不在待重新订阅中）探测 slot 的所属主节点
        val nodes = listeners.keys.filterNot { it in unconfirmed }.mapNotNull { channel ->
            subscribedNodes[channel] ?: ownerOf(cluster, channel)
        }.toSet()
        var failure: RuntimeException? = null
        for (node in nodes) {
            try {
                ping(cluster, node)
            } catch (e: RuntimeException) {
                // 半开连接上 Lettuce 不会自行重连：断开该节点连接，由自动重连恢复并重新订阅
                cluster.getConnectionAsync(node.host, node.port).thenAccept { disconnect(it) }
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    /**
     * Cluster 中由 [ping] 逐节点处理重连。
     */
    override fun forceReconnect() {
        if (clusterPubSub == null) {
            super.forceReconnect()
        }
    }

    /**
     * 确认订阅的节点不再是 slot 的所属主节点：该连接上不会再收到该频道的消息，视为订阅丢失。
     */
    private fun checkOwnership(cluster: StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>) {
        for ((channel, node) in subscribedNodes) {
            val owner = ownerOf(cluster, channel)
            if (owner == null || owner == node || !subscribedNodes.remove(channel, node)) {
                continue
            }
            log.warn {
                "Invalidation channel[$channel] - subscribed on [$node] but slot is owned by [$owner] - reset subscribers and resubscribe."
            }
            staleNodes[channel] = node
            unconfirmed.add(channel)
            reset(channel)
            // 在旧节点上退订（其回执来自非确认节点，被忽略），否则旧连接重连后又会在旧节点上重新订阅
            val bytes = channel.toByteArray(Charsets.UTF_8)
            cluster.getConnectionAsync(node.host, node.port)
                .thenCompose { it.async().sunsubscribe(bytes).toCompletableFuture() }
                .whenComplete { _, error ->
                    if (error != null) {
                        log.debug(
                            error
                        ) { "Invalidation channel[$channel] - unsubscribe on stale [$node] failed." }
                    }
                }
            resubscribe(channel)
        }
    }

    private fun ownerOf(cluster: StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>, channel: String): HostAndPort? {
        val uri = cluster.partitions.getMasterBySlot(SlotHash.getSlot(channel.toByteArray(Charsets.UTF_8)))?.uri ?: return null
        return HostAndPort(uri.host, uri.port)
    }

    private fun ping(cluster: StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>, node: HostAndPort) {
        connections.await(
            cluster.getConnectionAsync(node.host, node.port).thenCompose { it.async().ping().toCompletableFuture() }
        )
    }

    /**
     * 重新订阅（不等待）：确认回调负责重置并移出 [unconfirmed]；失败时留在 [unconfirmed] 中等下次心跳。
     */
    private fun resubscribe(channel: String) {
        if (!listeners.containsKey(channel)) {
            unconfirmed.remove(channel)
            return
        }
        subscribeAsync(channel.toByteArray(Charsets.UTF_8)).whenComplete { _, error ->
            if (error != null) {
                log.warn(error) { "Invalidation channel[$channel] - resubscribe failed, retry on next heartbeat." }
            }
        }
    }

    override fun acknowledged(channel: ByteArray) {
        unconfirmed.remove(String(channel, Charsets.UTF_8))
        super.acknowledged(channel)
    }

    private fun acknowledged(node: RedisClusterNode?, channel: ByteArray) {
        val name = String(channel, Charsets.UTF_8)
        node?.uri?.let { subscribedNodes[name] = HostAndPort(it.host, it.port) } ?: subscribedNodes.remove(name)
        acknowledged(channel)
    }

    /**
     * 服务端退订：频道仍有订阅者说明不是主动退订（主动退订先移除订阅者再发命令），视为订阅丢失。
     */
    private fun unsubscribed(channel: ByteArray) {
        val name = String(channel, Charsets.UTF_8)
        if (!listeners.containsKey(name)) {
            return
        }
        log.warn { "Invalidation channel[$name] unsubscribed by server (slot migration) - reset subscribers and resubscribe." }
        unconfirmed.add(name)
        reset(name)
        execute { resubscribe(name) }
    }

    /**
     * Cluster：只有确认订阅的节点发来的退订才表示订阅丢失；其它节点（已清理的旧节点）的退订忽略。
     */
    private fun unsubscribed(node: RedisClusterNode?, channel: ByteArray) {
        val name = String(channel, Charsets.UTF_8)
        val subscribed = subscribedNodes[name]
        val from = node?.uri?.let { HostAndPort(it.host, it.port) }
        val stale = from != null && (staleNodes.remove(name, from) || (subscribed != null && subscribed != from))
        if (stale) {
            log.debug { "Invalidation channel[$name] - ignore unsubscribe from stale node[$from]." }
            return
        }
        subscribedNodes.remove(name)
        unsubscribed(channel)
    }

    private inner class Adapter : RedisPubSubAdapter<ByteArray, ByteArray>() {
        override fun smessage(shardChannel: ByteArray, message: ByteArray) = dispatch(shardChannel, message)

        override fun ssubscribed(shardChannel: ByteArray, count: Long) = acknowledged(shardChannel)

        override fun sunsubscribed(shardChannel: ByteArray, count: Long) = unsubscribed(shardChannel)
    }

    private inner class ClusterAdapter : RedisClusterPubSubAdapter<ByteArray, ByteArray>() {
        override fun smessage(node: RedisClusterNode?, shardChannel: ByteArray, message: ByteArray) = dispatch(
            shardChannel,
            message
        )

        override fun ssubscribed(node: RedisClusterNode?, shardChannel: ByteArray, count: Long) = acknowledged(
            node,
            shardChannel
        )

        override fun sunsubscribed(node: RedisClusterNode?, shardChannel: ByteArray, count: Long) = unsubscribed(
            node,
            shardChannel
        )
    }

    /**
     * 节点地址。
     */
    internal data class HostAndPort(val host: String, val port: Int) {
        override fun toString(): String = "$host:$port"
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
