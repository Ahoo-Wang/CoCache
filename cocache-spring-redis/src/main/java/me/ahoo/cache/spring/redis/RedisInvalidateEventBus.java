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

package me.ahoo.cache.spring.redis;

import me.ahoo.cache.KeyPrefix;
import me.ahoo.cache.consistency.InvalidateEvent;
import me.ahoo.cache.consistency.InvalidateEventBus;
import me.ahoo.cache.consistency.InvalidateSubscriber;
import me.ahoo.cache.spring.redis.codec.InvalidateMessages;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * Redis Invalidate EventBus .
 *
 * @author ahoo wang
 */
@Slf4j
public class RedisInvalidateEventBus implements InvalidateEventBus, KeyPrefix {
    private final InvalidateEventBus actual;
    private final RedisMessageListenerContainer listenerContainer;
    private final String keyPrefix;
    private final PatternTopic subscribeTopic;
    private final MessageListener messageListener;
    
    public RedisInvalidateEventBus(String keyPrefix,
                                   InvalidateEventBus actual,
                                   RedisMessageListenerContainer listenerContainer) {
        this.keyPrefix = keyPrefix;
        this.subscribeTopic = PatternTopic.of(keyPrefix + "*");
        this.actual = actual;
        this.messageListener = new MessageListenerAdapter();
        this.listenerContainer = listenerContainer;
        this.listenerContainer.addMessageListener(messageListener, subscribeTopic);
    }
    
    @Override
    public String getClientId() {
        return actual.getClientId();
    }
    
    @Override
    public void publish(InvalidateEvent event) {
        actual.publish(event);
    }
    
    @Override
    public void register(InvalidateSubscriber subscriber) {
        actual.register(subscriber);
    }
    
    @Override
    public void unregister(InvalidateSubscriber subscriber) {
        actual.unregister(subscriber);
    }
    
    @Override
    public String getKeyPrefix() {
        return keyPrefix;
    }
    
    public class MessageListenerAdapter implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            String key = new String(message.getChannel(), StandardCharsets.UTF_8);
            if (!key.startsWith(getKeyPrefix())) {
                return;
            }
            
            String publisherId = InvalidateMessages.getPublisherIdFromMessageBody(new String(message.getBody(), StandardCharsets.UTF_8));
            InvalidateEvent invalidateEvent = InvalidateEvent.of(key, publisherId);
            publish(invalidateEvent);
        }
    }
}
