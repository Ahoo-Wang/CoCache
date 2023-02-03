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

import me.ahoo.cache.consistency.CacheEvictedEvent
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.consistency.CacheEvictedSubscriber
import me.ahoo.cache.spring.redis.codec.EvictedEvents
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.util.concurrent.ConcurrentHashMap

/**
 * RedisCacheEvictedEventBus .
 *
 * @author ahoo wang
 */
class RedisCacheEvictedEventBus(
    private val redisTemplate: StringRedisTemplate,
    private val listenerContainer: RedisMessageListenerContainer,
) : CacheEvictedEventBus {
    companion object {
        private val log = LoggerFactory.getLogger(RedisCacheEvictedEventBus::class.java)
    }

    private val subscribers = ConcurrentHashMap<CacheEvictedSubscriber, MessageListenerAdapter>()

    override fun publish(event: CacheEvictedEvent) {
        redisTemplate.convertAndSend(event.cacheName, EvictedEvents.asMessage(event.key, event.publisherId))
    }

    override fun register(subscriber: CacheEvictedSubscriber) {
        if (log.isDebugEnabled) {
            log.debug("Register - subscriber:[{}].", subscriber)
        }
        subscribers.computeIfAbsent(subscriber) {
            MessageListenerAdapter(it).also { listener ->
                listenerContainer.addMessageListener(listener, ChannelTopic(it.cacheName))
            }
        }
    }

    override fun unregister(subscriber: CacheEvictedSubscriber) {
        if (log.isDebugEnabled) {
            log.debug("Unregister - subscriber:[{}].", subscriber)
        }
        subscribers.remove(subscriber)?.also {
            listenerContainer.removeMessageListener(it)
        }
    }
}

data class MessageListenerAdapter(private val subscriber: CacheEvictedSubscriber) : MessageListener {
    override fun onMessage(message: Message, pattern: ByteArray?) {
        EvictedEvents.fromMessage(message).let { subscriber.onEvicted(it) }
    }
}
