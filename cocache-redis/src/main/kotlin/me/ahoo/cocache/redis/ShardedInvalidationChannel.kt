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
import io.lettuce.core.RedisURI
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Sharded Pub/Sub 失效通道（`SSUBSCRIBE` / `SPUBLISH`，Redis 7+），RFC 0003 §4.7。
 *
 * - Cluster 中 Lettuce 按频道的 slot 把 `SSUBSCRIBE` 路由到所属主节点的连接，并开启节点消息转发（`setNodeMessagePropagation`），
 *   各节点连接上的消息与确认都汇总到同一个监听器。节点连接断线重连后由 Lettuce 重新订阅，确认（`ssubscribed`）触发重置。
 * - slot 迁移：原节点让出 slot 时向订阅者主动发送 `sunsubscribe`，Lettuce 不会自动重新订阅（#629）。
 *   收到非主动退订的 `sunsubscribed` 时立即重置订阅者并重新订阅（路由到新的所属节点），重新订阅得到确认时再重置一次；
 *   重新订阅失败（如拓扑尚未刷新）时由心跳重试，直到确认。
 * - 心跳：单机 PING 订阅连接；Cluster 中 PING 承载订阅的各主节点连接。
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

    init {
        clusterPubSub?.setNodeMessagePropagation(true)
        connections.pubSub.addListener(Adapter())
        start()
    }

    override fun subscribeAsync(channel: ByteArray): CompletableFuture<Void> {
        return connections.pubSub.async().ssubscribe(channel).toCompletableFuture()
    }

    override fun unsubscribeAsync(channel: ByteArray): CompletableFuture<Void> {
        return connections.pubSub.async().sunsubscribe(channel).toCompletableFuture()
    }

    override fun publish(namespace: String, frame: ByteArray) {
        connections.spublish(channelOf(namespace).toByteArray(Charsets.UTF_8), frame)
    }

    override fun ping() {
        unconfirmed.forEach(::resubscribe)
        val cluster = clusterPubSub
        if (cluster == null) {
            connections.await(connections.pubSub.async().ping().toCompletableFuture())
            return
        }
        // 订阅分布在各主节点的连接上：逐一探测承载订阅的节点（与 Lettuce 按 slot 路由时一样以 host:port 取连接）
        listeners.keys
            .mapNotNull { cluster.partitions.getMasterBySlot(SlotHash.getSlot(it.toByteArray(Charsets.UTF_8)))?.uri }
            .distinctBy { it.host to it.port }
            .forEach { uri -> ping(cluster, uri) }
    }

    private fun ping(cluster: StatefulRedisClusterPubSubConnection<ByteArray, ByteArray>, uri: RedisURI) {
        connections.await(
            cluster.getConnectionAsync(uri.host, uri.port).thenCompose { it.async().ping().toCompletableFuture() }
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

    private inner class Adapter : RedisPubSubAdapter<ByteArray, ByteArray>() {
        override fun smessage(shardChannel: ByteArray, message: ByteArray) = dispatch(shardChannel, message)

        override fun ssubscribed(shardChannel: ByteArray, count: Long) = acknowledged(shardChannel)

        override fun sunsubscribed(shardChannel: ByteArray, count: Long) = unsubscribed(shardChannel)
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
