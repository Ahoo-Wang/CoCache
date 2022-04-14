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
import me.ahoo.cache.TtlAt;
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor;
import me.ahoo.cosid.util.MockIdGenerator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * RedisDistributedCachingTest .
 *
 * @author ahoo wang
 */
class RedisDistributedCachingTest {
    StringRedisTemplate stringRedisTemplate;
    StringToStringCodecExecutor codecExecutor;
    LettuceConnectionFactory lettuceConnectionFactory;
    RedisDistributedCache<String> distributedCaching;
    
    @BeforeEach
    private void setup() {
        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
        lettuceConnectionFactory = new LettuceConnectionFactory(redisStandaloneConfiguration);
        lettuceConnectionFactory.afterPropertiesSet();
        stringRedisTemplate = new StringRedisTemplate(lettuceConnectionFactory);
        codecExecutor = new StringToStringCodecExecutor(stringRedisTemplate);
        distributedCaching = new RedisDistributedCache<>(MockIdGenerator.INSTANCE.generateAsString(), stringRedisTemplate, codecExecutor);
    }
    
    @AfterEach
    void destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy();
        }
    }
    
    @Test
    void setThenGet() {
        final String CACHE_KEY = "setThenGet";
        final CacheValue<String> CACHE_VALUE = CacheValue.forever("setThenGet");
        distributedCaching.setCache(CACHE_KEY, CACHE_VALUE);
        Assertions.assertEquals(CACHE_VALUE, distributedCaching.getCache(CACHE_KEY));
    }
    
    @Test
    void getExpireAt() {
        final String CACHE_KEY = MockIdGenerator.INSTANCE.generateAsString();
        
        Long ttlAt = distributedCaching.getExpireAt(CACHE_KEY);
        Assertions.assertNull(ttlAt);
        final CacheValue<String> CACHE_VALUE = CacheValue.forever(CACHE_KEY);
        distributedCaching.setCache(CACHE_KEY, CACHE_VALUE);
        ttlAt = distributedCaching.getExpireAt(CACHE_KEY);
        Assertions.assertEquals(TtlAt.FOREVER, ttlAt);
    }

    @Test
    void setThenEvict() {
        final String CACHE_KEY = "setThenEvict";
        final CacheValue<String> CACHE_VALUE = CacheValue.forever("setThenEvict");
        distributedCaching.setCache(CACHE_KEY, CACHE_VALUE);
        distributedCaching.evict(CACHE_KEY);
        Assertions.assertNull(distributedCaching.get(CACHE_KEY));
    }
//
//    @SneakyThrows
//    @Test
//    void notifyInvalidate() {
//        final String CACHE_KEY = CacheKey.forever("notifyInvalidate");
//        final CacheValue<String> CACHE_VALUE = CacheValue.forever("notifyInvalidate");
//        CountDownLatch countDownLatch = new CountDownLatch(1);
//        RedisDistributedCaching<String> distributedCaching = new RedisDistributedCachingBuilder<String>()
//            .commandConnection(new RedisConnection<>(redisClient.connect()))
//            .codecExecutorProvider(StringToStringCodecExecutor::new)
//            .redirect(new RedisConnection<>(redisClient.connectPubSub()))
//            .build();
//        distributedCaching.get(CACHE_KEY);
//        distributedCaching.addListener(key -> {
//            if (CACHE_KEY.equals(key)) {
//                countDownLatch.countDown();
//            }
//        });
//        distributedCaching.set(CACHE_KEY, CACHE_VALUE);
//        Assertions.assertTrue(countDownLatch.await(2, TimeUnit.SECONDS));
//    }
//
//    @SneakyThrows
//    @Test
//    void notifyInvalidateWhenNoLoop() {
//        final String CACHE_KEY = CacheKey.forever("notifyInvalidateWhenNoLoop");
//        final CacheValue<String> CACHE_VALUE = CacheValue.forever("notifyInvalidateWhenNoLoop");
//        CountDownLatch countDownLatch = new CountDownLatch(1);
//        RedisDistributedCaching<String> distributedCaching = new RedisDistributedCachingBuilder<String>()
//            .commandConnection(new RedisConnection<>(redisClient.connect()))
//            .codecExecutorProvider(StringToStringCodecExecutor::new)
//            .redirect(new RedisConnection<>(redisClient.connectPubSub()))
//            .noloop()
//            .build();
//        distributedCaching.get(CACHE_KEY);
//        distributedCaching.addListener(key -> {
//            if (CACHE_KEY.equals(key)) {
//                countDownLatch.countDown();
//            }
//        });
//        distributedCaching.set(CACHE_KEY, CACHE_VALUE);
//        Assertions.assertFalse(countDownLatch.await(2, TimeUnit.SECONDS));
//    }
}
