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
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor
import me.ahoo.cosid.test.MockIdGenerator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RedisMessageListenerContainerTest .
 *
 * @author ahoo wang
 */
class RedisMessageListenerContainerTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var codecExecutor: StringToStringCodecExecutor
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    lateinit var distributedCaching: RedisDistributedCache<String>
    lateinit var redisMessageListenerContainer: RedisMessageListenerContainer

    @BeforeEach
    private fun setup() {
        val redisConfig = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisConfig)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        codecExecutor = StringToStringCodecExecutor(stringRedisTemplate)
        distributedCaching =
            RedisDistributedCache(MockIdGenerator.INSTANCE.generateAsString(), stringRedisTemplate, codecExecutor)
        redisMessageListenerContainer = RedisMessageListenerContainer()
        redisMessageListenerContainer.setConnectionFactory(lettuceConnectionFactory)
        redisMessageListenerContainer.afterPropertiesSet()
        redisMessageListenerContainer.start()
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

    @Test
    fun addMessageListener() {
        val countDownLatch = CountDownLatch(1)
        val keyPrefix = "addMessageListener:"
        val patternTopic: PatternTopic = PatternTopic.of("$keyPrefix*")
        val key = keyPrefix + 1
        val messageListener = MessageListener { message, pattern ->
            Assertions.assertEquals(patternTopic.topic, String(pattern!!))
            Assertions.assertEquals(key, String(message.channel))
            countDownLatch.countDown()
        }
        redisMessageListenerContainer.addMessageListener(messageListener, patternTopic)
        TimeUnit.SECONDS.sleep(1)
        distributedCaching.setCache(key, CacheValue.missingGuard())
        Assertions.assertTrue(countDownLatch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun removeMessageListener() {
        val countDownLatch = CountDownLatch(1)
        val keyPrefix = "removeMessageListener:"
        val patternTopic: PatternTopic = PatternTopic.of("$keyPrefix*")
        val key = keyPrefix + 1
        val messageListener = MessageListener { message, pattern -> countDownLatch.countDown() }
        redisMessageListenerContainer.addMessageListener(messageListener, patternTopic)
        redisMessageListenerContainer.removeMessageListener(messageListener, patternTopic)
        TimeUnit.SECONDS.sleep(1)
        distributedCaching.setCache(key, CacheValue.missingGuard())
        Assertions.assertFalse(countDownLatch.await(1, TimeUnit.SECONDS))
    }
}
