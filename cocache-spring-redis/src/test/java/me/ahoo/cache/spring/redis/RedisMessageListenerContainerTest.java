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

import me.ahoo.cache.CacheValue;
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor;
import me.ahoo.cosid.util.MockIdGenerator;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * RedisMessageListenerContainerTest .
 *
 * @author ahoo wang
 */
public class RedisMessageListenerContainerTest {
    StringRedisTemplate stringRedisTemplate;
    StringToStringCodecExecutor codecExecutor;
    LettuceConnectionFactory lettuceConnectionFactory;
    RedisDistributedCache<String> distributedCaching;
    RedisMessageListenerContainer redisMessageListenerContainer;
    
    @BeforeEach
    private void setup() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        lettuceConnectionFactory = new LettuceConnectionFactory(redisConfig);
        lettuceConnectionFactory.afterPropertiesSet();
        stringRedisTemplate = new StringRedisTemplate(lettuceConnectionFactory);
        codecExecutor = new StringToStringCodecExecutor(stringRedisTemplate);
        distributedCaching = new RedisDistributedCache<>(MockIdGenerator.INSTANCE.generateAsString(), stringRedisTemplate, codecExecutor);
        redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(lettuceConnectionFactory);
        redisMessageListenerContainer.afterPropertiesSet();
        redisMessageListenerContainer.start();
    }
    
    @SneakyThrows
    @AfterEach
    void destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy();
        }
        if (null != redisMessageListenerContainer) {
            redisMessageListenerContainer.destroy();
        }
    }
    
    @SneakyThrows
    @Test
    void addMessageListener() {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        String keyPrefix = "addMessageListener:";
        PatternTopic patternTopic = PatternTopic.of(keyPrefix + "*");
        final String CACHE_KEY = keyPrefix + 1;
        MessageListener messageListener = new MessageListener() {
            @Override
            public void onMessage(Message message, byte[] pattern) {
                Assertions.assertEquals(patternTopic.getTopic(), new String(pattern));
                Assertions.assertEquals(CACHE_KEY, new String(message.getChannel()));
                
                countDownLatch.countDown();
            }
        };
        redisMessageListenerContainer.addMessageListener(messageListener, patternTopic);
        TimeUnit.SECONDS.sleep(1);
        distributedCaching.setCache(CACHE_KEY, CacheValue.missingGuard());
        Assertions.assertTrue(countDownLatch.await(1, TimeUnit.SECONDS));
    }
    
    @SneakyThrows
    @Test
    void removeMessageListener() {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        String keyPrefix = "removeMessageListener:";
        PatternTopic patternTopic = PatternTopic.of(keyPrefix + "*");
        final String CACHE_KEY = keyPrefix + 1;
        
        MessageListener messageListener = new MessageListener() {
            @Override
            public void onMessage(Message message, byte[] pattern) {
                countDownLatch.countDown();
            }
        };
        redisMessageListenerContainer.addMessageListener(messageListener, patternTopic);
        redisMessageListenerContainer.removeMessageListener(messageListener, patternTopic);
        TimeUnit.SECONDS.sleep(1);
        distributedCaching.setCache(CACHE_KEY, CacheValue.missingGuard());
        Assertions.assertFalse(countDownLatch.await(1, TimeUnit.SECONDS));
    }
    
}
