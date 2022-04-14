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
import me.ahoo.cache.eventbus.GuavaInvalidateEventBus;
import me.ahoo.cache.eventbus.InvalidateEvent;
import me.ahoo.cache.eventbus.InvalidateSubscriber;
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor;
import me.ahoo.cosid.util.MockIdGenerator;

import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import javax.annotation.Nonnull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * RedisInvalidateEventBusTest .
 *
 * @author ahoo wang
 */
class RedisInvalidateEventBusTest {
    StringRedisTemplate stringRedisTemplate;
    StringToStringCodecExecutor codecExecutor;
    LettuceConnectionFactory lettuceConnectionFactory;
    RedisDistributedCache<String> distributedCaching;
    RedisMessageListenerContainer redisMessageListenerContainer;
    RedisInvalidateEventBus invalidateEventBus;
    
    @BeforeEach
    private void setup() {
        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
        lettuceConnectionFactory = new LettuceConnectionFactory(redisStandaloneConfiguration);
        lettuceConnectionFactory.afterPropertiesSet();
        stringRedisTemplate = new StringRedisTemplate(lettuceConnectionFactory);
        codecExecutor = new StringToStringCodecExecutor(stringRedisTemplate);
        distributedCaching = new RedisDistributedCache<>(MockIdGenerator.INSTANCE.generateAsString(), stringRedisTemplate, codecExecutor);
        redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(lettuceConnectionFactory);
        redisMessageListenerContainer.afterPropertiesSet();
        redisMessageListenerContainer.start();
        invalidateEventBus = new RedisInvalidateEventBus(MockIdGenerator.INSTANCE.generateAsString()
            , new GuavaInvalidateEventBus(MockIdGenerator.INSTANCE.generateAsString())
            , redisMessageListenerContainer);
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
    void register() {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        final String CACHE_KEY = invalidateEventBus.getKeyPrefix() + 1;
        InvalidateSubscriber invalidateSubscriber = new InvalidateSubscriber() {
            @Override
            public void onInvalidate(@Nonnull InvalidateEvent invalidateEvent) {
                countDownLatch.countDown();
            }
        };
        invalidateEventBus.register(invalidateSubscriber);
        TimeUnit.SECONDS.sleep(1);
        distributedCaching.setCache(CACHE_KEY, CacheValue.missingGuard());
        Assertions.assertTrue(countDownLatch.await(2, TimeUnit.SECONDS));
    }
    
    @SneakyThrows
    @Test
    void unregister() {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        final String CACHE_KEY = invalidateEventBus.getKeyPrefix() + 1;
        InvalidateSubscriber invalidateSubscriber = new InvalidateSubscriber() {
            @Override
            public void onInvalidate(@Nonnull InvalidateEvent invalidateEvent) {
                countDownLatch.countDown();
            }
        };
        invalidateEventBus.register(invalidateSubscriber);
        invalidateEventBus.unregister(invalidateSubscriber);
        TimeUnit.SECONDS.sleep(1);
        distributedCaching.setCache(CACHE_KEY, CacheValue.missingGuard());
        Assertions.assertFalse(countDownLatch.await(1, TimeUnit.SECONDS));
    }
    
}
