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

import me.ahoo.cache.CacheValue
import me.ahoo.cache.CoherentCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.source.NoOpCacheSource
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor
import me.ahoo.cosid.test.MockIdGenerator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MutilInstanceTest .
 *
 * @author ahoo wang
 */
class MutilInstanceTest {
    private val CLIENT_ID_1: String = MockIdGenerator.INSTANCE.generateAsString()
    private val CLIENT_ID_2: String = MockIdGenerator.INSTANCE.generateAsString()
    private val keyConverter: KeyConverter<String> = ToStringKeyConverter<String>("test:mutil:")
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var codecExecutor: StringToStringCodecExecutor
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    lateinit var redisMessageListenerContainer: RedisMessageListenerContainer

    @BeforeEach
    private fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        codecExecutor = StringToStringCodecExecutor(stringRedisTemplate)
        redisMessageListenerContainer = RedisMessageListenerContainer()
        redisMessageListenerContainer.setConnectionFactory(lettuceConnectionFactory)
        redisMessageListenerContainer.afterPropertiesSet()
    }

    @AfterEach
    fun destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy()
        }
        if (null != redisMessageListenerContainer) {
            redisMessageListenerContainer.destroy()
        }
    }

    @Disabled
    @Test
    fun notifyInvalidate() {
        val key: String = MockIdGenerator.INSTANCE.generateAsString()
        val value = CacheValue.forever(key)
        val distributedCaching1: DistributedCache<String> =
            RedisDistributedCache<String>(CLIENT_ID_1, stringRedisTemplate, codecExecutor)
        val countDownLatch1 = CountDownLatch(1)
        val coCaching1: CoherentCache<String, String> = RedisCoherentCacheBuilder<String, String>()
            .keyConverter(keyConverter)
            .cacheSource(NoOpCacheSource.noOp())
            .clientSideCaching(MapClientSideCache())
            .distributedCaching(distributedCaching1)
            .listenerContainer(redisMessageListenerContainer)
            .build()
        val countDownLatch2 = CountDownLatch(1)
        TODO()
        val distributedCaching2: DistributedCache<String> =
            RedisDistributedCache<String>(CLIENT_ID_2, stringRedisTemplate, codecExecutor)
        val coCaching2: CoherentCache<String, String> = RedisCoherentCacheBuilder<String, String>()
            .keyConverter(keyConverter)
            .cacheSource(NoOpCacheSource.noOp())
            .clientSideCaching(MapClientSideCache())
            .distributedCaching(distributedCaching2)
            .listenerContainer(redisMessageListenerContainer)
            .build()
        /*
         **** Very important ****
         */
        redisMessageListenerContainer.start()
        Assertions.assertNull(coCaching1[key])
        Assertions.assertNull(coCaching2[key])
        coCaching1.setCache(key, value)
        Assertions.assertFalse(countDownLatch1.await(2, TimeUnit.SECONDS))
        Assertions.assertTrue(countDownLatch2.await(2, TimeUnit.SECONDS))
        val cacheValue1: CacheValue<String>? = coCaching1.getCache(key)
        Assertions.assertEquals(value, cacheValue1)
        val cacheValue2: CacheValue<String>? = coCaching2.getCache(key)
        Assertions.assertEquals(value, cacheValue2)
    }
}
