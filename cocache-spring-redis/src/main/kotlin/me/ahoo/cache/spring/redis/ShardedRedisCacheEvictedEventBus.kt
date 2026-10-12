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

package me.ahoo.cache.spring.redis

import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubAdapter
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.cache.spring.redis.codec.EvictedEvents
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.LongAdder

/**
 * 基于 Redis Sharded Pub/Sub（SPUBLISH / SSUBSCRIBE，Redis 7+）的失效事件通道。
 *
 * 普通 PUBLISH 在 Redis Cluster 中会广播到所有节点；Sharded Pub/Sub 只在频道所属 slot 的分片内传播，
 * 集群规模增大时开销不随节点数增长。频道名、消息格式与 [RedisCacheEvictedEventBus] 相同（`key@@publisherId`），
 * 但 SPUBLISH 与 PUBLISH 互不相通：**全集群必须同时切换**。
 *
 * 重置语义与 [RedisCacheEvictedEventBus] 一致：订阅者注册时立即 [CacheEvictedSubscriber.onReset]；
 * 连接断开后 Lettuce 自动重连并重新订阅，重新订阅的确认（`ssubscribed`）触发该频道所有订阅者的 `onReset`，
 * 以此兜底断线期间丢失的事件。
 *
 * 直接使用 Lettuce（Spring Data Redis 尚不支持 Sharded Pub/Sub）。
 */
class ShardedRedisCacheEvictedEventBus private constructor(
    private val pubSubConnection: StatefulRedisPubSubConnection<String, String>,
    private val publishConnection: StatefulConnection<String, String>,
    private val spublish: (String, String) -> Unit
) : CacheEvictedEventBus, EvictionPublishStats, AutoCloseable {
    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * 由 Lettuce 客户端创建：[RedisClusterClient] 使用集群 Pub/Sub 连接（按 slot 路由订阅），
         * [RedisClient] 使用单机连接。
         */
        @JvmStatic
        fun create(client: AbstractRedisClient): ShardedRedisCacheEvictedEventBus {
            return when (client) {
                is RedisClusterClient -> {
                    val pubSub = client.connectPubSub(StringCodec.UTF8)
                    pubSub.setNodeMessagePropagation(true)
                    val publisher = client.connect(StringCodec.UTF8)
                    ShardedRedisCacheEvictedEventBus(pubSub, publisher) { channel, message ->
                        publisher.sync().spublish(channel, message)
                    }.also { bus -> pubSub.addListener(bus.ClusterListener()) }
                }

                is RedisClient -> {
                    val pubSub = client.connectPubSub(StringCodec.UTF8)
                    val publisher = client.connect(StringCodec.UTF8)
                    ShardedRedisCacheEvictedEventBus(pubSub, publisher) { channel, message ->
                        publisher.sync().spublish(channel, message)
                    }.also { bus -> pubSub.addListener(bus.StandaloneListener()) }
                }

                else -> throw IllegalArgumentException("Unsupported Redis client: ${client.javaClass.name}.")
            }
        }
    }

    private val subscribers = ConcurrentHashMap<String, MutableSet<CacheEvictedSubscriber>>()

    /**
     * 已确认过订阅的频道：其后再次收到确认即为断线重连后的重新订阅。
     */
    private val confirmedChannels = ConcurrentHashMap.newKeySet<String>()
    private val publishFailures = LongAdder()

    override val publishFailureCount: Long
        get() = publishFailures.sum()

    override fun publish(event: CacheEvictedEvent) {
        try {
            spublish(event.cacheName, EvictedEvents.asMessage(event.key, event.publisherId))
        } catch (e: RedisException) {
            // 尽力而为：发布失败仅告警，不阻断调用方；接收方由 TTL 与重置兜底
            publishFailures.increment()
            log.warn(e) { "Publish - event:[$event] failed." }
        }
    }

    override fun register(subscriber: CacheEvictedSubscriber) {
        val channel = subscriber.cacheName
        var firstOnChannel = false
        subscribers.compute(channel) { _, existing ->
            (existing ?: CopyOnWriteArraySet<CacheEvictedSubscriber>().also { firstOnChannel = true })
                .apply { add(subscriber) }
        }
        if (firstOnChannel) {
            pubSubConnection.sync().ssubscribe(channel)
        }
        // 注册时立即重置：放弃订阅建立前可能已缓存的副本（与 RedisMessageListenerContainer 的订阅回调等价）
        subscriber.onReset()
    }

    override fun unregister(subscriber: CacheEvictedSubscriber) {
        val channel = subscriber.cacheName
        var lastOnChannel = false
        subscribers.computeIfPresent(channel) { _, existing ->
            existing.remove(subscriber)
            if (existing.isEmpty()) {
                lastOnChannel = true
                null
            } else {
                existing
            }
        }
        if (lastOnChannel) {
            confirmedChannels.remove(channel)
            pubSubConnection.sync().sunsubscribe(channel)
        }
    }

    private fun onMessage(channel: String, message: String) {
        val event = EvictedEvents.fromMessage(channel, message)
        subscribers[channel]?.forEach { it.onEvicted(event) }
    }

    private fun onSubscribed(channel: String) {
        if (confirmedChannels.add(channel)) {
            // 首次确认：register() 已经重置过订阅者
            return
        }
        log.info { "Channel[$channel] resubscribed - reset subscribers." }
        subscribers[channel]?.forEach { it.onReset() }
    }

    override fun close() {
        pubSubConnection.close()
        publishConnection.close()
    }

    private inner class StandaloneListener : RedisPubSubAdapter<String, String>() {
        override fun smessage(channel: String, message: String) = onMessage(channel, message)
        override fun ssubscribed(channel: String, count: Long) = onSubscribed(channel)
    }

    private inner class ClusterListener : RedisClusterPubSubAdapter<String, String>() {
        override fun smessage(node: RedisClusterNode, channel: String, message: String) = onMessage(channel, message)
        override fun ssubscribed(node: RedisClusterNode, channel: String, count: Long) = onSubscribed(channel)
    }
}
