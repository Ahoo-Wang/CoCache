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

import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.test.consistency.CacheEvictedEventBusSpec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * RedisCacheEvictedEventBusTest .
 *
 * @author ahoo wang
 */
internal class RedisCacheEvictedEventBusTest : CacheEvictedEventBusSpec() {

    private lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    private lateinit var redisMessageListenerContainer: RedisMessageListenerContainer

    @BeforeEach
    fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        redisMessageListenerContainer = RedisMessageListenerContainer()
        redisMessageListenerContainer.setConnectionFactory(lettuceConnectionFactory)
        redisMessageListenerContainer.afterPropertiesSet()
        /*
         **** Very important ****
         */
        redisMessageListenerContainer.start()
    }

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus {
        return RedisCacheEvictedEventBus(
            redisTemplate = StringRedisTemplate(lettuceConnectionFactory),
            listenerContainer = redisMessageListenerContainer
        )
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
}
