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
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.cache.spring.redis.codec.EvictedEvents
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.SubscriptionListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于 Redis Pub/Sub 的失效事件通道：每个缓存一个频道（频道名即 cacheName）。
 *
 * Pub/Sub 至多一次投递，断线期间的事件会丢失。容器每次（重新）订阅成功都会回调
 * [CacheEvictedSubscriber.onReset]，订阅者据此放弃本地副本，使陈旧度有界。
 *
 * @author ahoo wang
 */
class RedisCacheEvictedEventBus(
    private val redisTemplate: StringRedisTemplate,
    private val listenerContainer: RedisMessageListenerContainer
) : CacheEvictedEventBus {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val listeners = ConcurrentHashMap<CacheEvictedSubscriber, SubscriberListener>()

    override fun publish(event: CacheEvictedEvent) {
        try {
            redisTemplate.convertAndSend(event.cacheName, EvictedEvents.asMessage(event.key, event.publisherId))
        } catch (e: DataAccessException) {
            // 尽力而为：发布失败仅告警，不阻断调用方；接收方由 TTL 与重置兜底
            log.warn(e) { "Publish - event:[$event] failed." }
        }
    }

    override fun register(subscriber: CacheEvictedSubscriber) {
        listeners.computeIfAbsent(subscriber) {
            SubscriberListener(it).also { listener ->
                listenerContainer.addMessageListener(listener, ChannelTopic(it.cacheName))
            }
        }
    }

    override fun unregister(subscriber: CacheEvictedSubscriber) {
        listeners.remove(subscriber)?.also {
            listenerContainer.removeMessageListener(it)
        }
    }

    private class SubscriberListener(private val subscriber: CacheEvictedSubscriber) :
        MessageListener,
        SubscriptionListener {
        override fun onMessage(message: Message, pattern: ByteArray?) {
            subscriber.onEvicted(EvictedEvents.fromMessage(message))
        }

        override fun onChannelSubscribed(channel: ByteArray, count: Long) {
            log.info { "Channel[${channel.decodeToString()}] subscribed - reset subscriber:[$subscriber]." }
            subscriber.onReset()
        }
    }
}
