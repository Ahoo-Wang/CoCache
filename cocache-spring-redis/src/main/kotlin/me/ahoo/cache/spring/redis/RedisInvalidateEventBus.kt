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

import me.ahoo.cache.KeyPrefix
import me.ahoo.cache.consistency.InvalidateEvent
import me.ahoo.cache.consistency.InvalidateEventBus
import me.ahoo.cache.consistency.InvalidateSubscriber
import me.ahoo.cache.spring.redis.codec.InvalidateMessages
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.nio.charset.StandardCharsets

/**
 * Redis Invalidate EventBus .
 *
 * @author ahoo wang
 */
class RedisInvalidateEventBus(
    override val keyPrefix: String,
    actual: InvalidateEventBus,
    listenerContainer: RedisMessageListenerContainer
) : InvalidateEventBus, KeyPrefix {
    private val actual: InvalidateEventBus
    private val listenerContainer: RedisMessageListenerContainer
    private val subscribeTopic: PatternTopic = PatternTopic.of("$keyPrefix*")
    private val messageListener: MessageListener

    init {
        this.actual = actual
        messageListener = MessageListenerAdapter()
        this.listenerContainer = listenerContainer
        this.listenerContainer.addMessageListener(messageListener, subscribeTopic)
    }

    override val clientId: String
        get() = actual.clientId

    override fun publish(event: InvalidateEvent) {
        actual.publish(event)
    }

    override fun register(subscriber: InvalidateSubscriber) {
        actual.register(subscriber)
    }

    override fun unregister(subscriber: InvalidateSubscriber) {
        actual.unregister(subscriber)
    }

    inner class MessageListenerAdapter : MessageListener {
        override fun onMessage(message: Message, pattern: ByteArray?) {
            val key = String(message.channel, StandardCharsets.UTF_8)
            if (!key.startsWith(keyPrefix)) {
                return
            }
            val publisherId =
                InvalidateMessages.getPublisherIdFromMessageBody(String(message.body, StandardCharsets.UTF_8))
            val invalidateEvent = InvalidateEvent(key, publisherId)
            publish(invalidateEvent)
        }
    }
}
