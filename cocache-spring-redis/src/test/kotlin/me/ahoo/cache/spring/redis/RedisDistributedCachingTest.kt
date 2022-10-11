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

import me.ahoo.cache.CacheValue.Companion.forever
import me.ahoo.cache.TtlAt
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor
import me.ahoo.cosid.test.MockIdGenerator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * RedisDistributedCachingTest .
 *
 * @author ahoo wang
 */
internal class RedisDistributedCachingTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var codecExecutor: StringToStringCodecExecutor
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    lateinit var distributedCaching: RedisDistributedCache<String>

    @BeforeEach
    private fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        codecExecutor = StringToStringCodecExecutor(stringRedisTemplate)
        distributedCaching =
            RedisDistributedCache(MockIdGenerator.INSTANCE.generateAsString(), stringRedisTemplate, codecExecutor)
    }

    @AfterEach
    fun destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy()
        }
    }

    @Test
    fun setThenGet() {
        val key = "setThenGet"
        val value = forever("setThenGet")
        distributedCaching.setCache(key, value)
        Assertions.assertEquals(value, distributedCaching.getCache(key))
    }

    @get:Test
    val expireAt: Unit
        get() {
            val key = MockIdGenerator.INSTANCE.generateAsString()
            var ttlAt = distributedCaching.getExpireAt(key)
            Assertions.assertNull(ttlAt)
            val value = forever(key)
            distributedCaching.setCache(key, value)
            ttlAt = distributedCaching.getExpireAt(key)
            Assertions.assertEquals(TtlAt.FOREVER, ttlAt)
        }

    @Test
    fun setThenEvict() {
        val key = "setThenEvict"
        val value = forever("setThenEvict")
        distributedCaching.setCache(key, value)
        distributedCaching.evict(key)
        Assertions.assertNull(distributedCaching[key])
    } //
    //    @SneakyThrows
    //    @Test
    //    void notifyInvalidate() {
    //        final String key = CacheKey.forever("notifyInvalidate");
    //        final CacheValue<String> value = CacheValue.forever("notifyInvalidate");
    //        CountDownLatch countDownLatch = new CountDownLatch(1);
    //        RedisDistributedCaching<String> distributedCaching = new RedisDistributedCachingBuilder<String>()
    //            .commandConnection(new RedisConnection<>(redisClient.connect()))
    //            .codecExecutorProvider(StringToStringCodecExecutor::new)
    //            .redirect(new RedisConnection<>(redisClient.connectPubSub()))
    //            .build();
    //        distributedCaching.get(key);
    //        distributedCaching.addListener(key -> {
    //            if (key.equals(key)) {
    //                countDownLatch.countDown();
    //            }
    //        });
    //        distributedCaching.set(key, value);
    //        Assertions.assertTrue(countDownLatch.await(2, TimeUnit.SECONDS));
    //    }
    //
    //    @SneakyThrows
    //    @Test
    //    void notifyInvalidateWhenNoLoop() {
    //        final String key = CacheKey.forever("notifyInvalidateWhenNoLoop");
    //        final CacheValue<String> value = CacheValue.forever("notifyInvalidateWhenNoLoop");
    //        CountDownLatch countDownLatch = new CountDownLatch(1);
    //        RedisDistributedCaching<String> distributedCaching = new RedisDistributedCachingBuilder<String>()
    //            .commandConnection(new RedisConnection<>(redisClient.connect()))
    //            .codecExecutorProvider(StringToStringCodecExecutor::new)
    //            .redirect(new RedisConnection<>(redisClient.connectPubSub()))
    //            .noloop()
    //            .build();
    //        distributedCaching.get(key);
    //        distributedCaching.addListener(key -> {
    //            if (key.equals(key)) {
    //                countDownLatch.countDown();
    //            }
    //        });
    //        distributedCaching.set(key, value);
    //        Assertions.assertFalse(countDownLatch.await(2, TimeUnit.SECONDS));
    //    }
}
