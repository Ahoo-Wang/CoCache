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
import me.ahoo.cache.CoherentCache;
import me.ahoo.cache.client.MapClientSideCache;
import me.ahoo.cache.converter.KeyConverter;
import me.ahoo.cache.converter.ToStringKeyConverter;
import me.ahoo.cache.distributed.DistributedCache;
import me.ahoo.cache.consistency.InvalidateEvent;
import me.ahoo.cache.source.NoOpCacheSource;
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor;
import me.ahoo.cosid.test.MockIdGenerator;

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
 * MutilInstanceTest .
 *
 * @author ahoo wang
 */
public class MutilInstanceTest {
    String CLIENT_ID_1 = MockIdGenerator.INSTANCE.generateAsString();
    String CLIENT_ID_2 = MockIdGenerator.INSTANCE.generateAsString();
    KeyConverter<String> keyConverter = new ToStringKeyConverter<>("test:mutil:");
    StringRedisTemplate stringRedisTemplate;
    StringToStringCodecExecutor codecExecutor;
    LettuceConnectionFactory lettuceConnectionFactory;
    RedisMessageListenerContainer redisMessageListenerContainer;
    
    @SneakyThrows
    @BeforeEach
    private void setup() {
        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
        lettuceConnectionFactory = new LettuceConnectionFactory(redisStandaloneConfiguration);
        lettuceConnectionFactory.afterPropertiesSet();
        stringRedisTemplate = new StringRedisTemplate(lettuceConnectionFactory);
        codecExecutor = new StringToStringCodecExecutor(stringRedisTemplate);
        
        redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(lettuceConnectionFactory);
        redisMessageListenerContainer.afterPropertiesSet();
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
    void notifyInvalidate() {
        
        final String CACHE_KEY = MockIdGenerator.INSTANCE.generateAsString();
        final CacheValue<String> CACHE_VALUE = CacheValue.forever(CACHE_KEY);
        
        DistributedCache<String> distributedCaching1 = new RedisDistributedCache<String>(CLIENT_ID_1, stringRedisTemplate, codecExecutor);
        CountDownLatch countDownLatch1 = new CountDownLatch(1);
        CoherentCache<String, String> coCaching1 = new RedisCoherentCacheBuilder<String, String>()
            .keyConverter(keyConverter)
            .cacheSource(new NoOpCacheSource<>())
            .clientSideCaching(new MapClientSideCache<String>() {
                @Override
                public void onInvalidate(@Nonnull InvalidateEvent invalidateEvent) {
                    super.onInvalidate(invalidateEvent);
                    countDownLatch1.countDown();
                }
            })
            .distributedCaching(distributedCaching1)
            .listenerContainer(redisMessageListenerContainer)
            .build();
        
        CountDownLatch countDownLatch2 = new CountDownLatch(1);
        DistributedCache<String> distributedCaching2 = new RedisDistributedCache<String>(CLIENT_ID_2, stringRedisTemplate, codecExecutor);
        CoherentCache<String, String> coCaching2 = new RedisCoherentCacheBuilder<String, String>()
            .keyConverter(keyConverter)
            .cacheSource(new NoOpCacheSource<>())
            .clientSideCaching(new MapClientSideCache<String>() {
                @Override
                public void onInvalidate(@Nonnull InvalidateEvent invalidateEvent) {
                    super.onInvalidate(invalidateEvent);
                    countDownLatch2.countDown();
                }
            })
            .distributedCaching(distributedCaching2)
            .listenerContainer(redisMessageListenerContainer)
            .build();
        /*
         **** Very important ****
         */
        redisMessageListenerContainer.start();
        Assertions.assertNull(coCaching1.get(CACHE_KEY));
        Assertions.assertNull(coCaching2.get(CACHE_KEY));
        
        coCaching1.setCache(CACHE_KEY, CACHE_VALUE);
        Assertions.assertFalse(countDownLatch1.await(2, TimeUnit.SECONDS));
        Assertions.assertTrue(countDownLatch2.await(2, TimeUnit.SECONDS));
        
        CacheValue<String> cacheValue1 = coCaching1.getCache(CACHE_KEY);
        Assertions.assertEquals(CACHE_VALUE, cacheValue1);
        CacheValue<String> cacheValue2 = coCaching2.getCache(CACHE_KEY);
        Assertions.assertEquals(CACHE_VALUE, cacheValue2);
    }
}
