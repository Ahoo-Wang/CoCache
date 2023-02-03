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

import me.ahoo.cache.client.ClientSideCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.spring.redis.codec.StringToStringCodecExecutor
import me.ahoo.cache.test.MultipleInstanceSyncSpec
import org.junit.jupiter.api.BeforeEach
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.util.*

class RedisMultipleInstanceSyncTest : MultipleInstanceSyncSpec<String, String>() {
    private lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    private lateinit var redisMessageListenerContainer: RedisMessageListenerContainer
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var codecExecutor: StringToStringCodecExecutor

    @BeforeEach
    override fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        redisMessageListenerContainer = RedisMessageListenerContainer()
        redisMessageListenerContainer.setConnectionFactory(lettuceConnectionFactory)
        redisMessageListenerContainer.afterPropertiesSet()
        redisMessageListenerContainer.start()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        stringRedisTemplate.afterPropertiesSet()
        codecExecutor = StringToStringCodecExecutor(stringRedisTemplate)
        super.setup()
    }

    override fun createKeyConverter(): KeyConverter<String> = ToStringKeyConverter("")

    override fun createClientSideCache(): ClientSideCache<String> = MapClientSideCache()

    override fun createDistributedCache(): DistributedCache<String> {
        return RedisDistributedCache(
            stringRedisTemplate,
            codecExecutor,
        )
    }

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus {
        return RedisCacheEvictedEventBus(
            redisTemplate = StringRedisTemplate(lettuceConnectionFactory),
            listenerContainer = redisMessageListenerContainer,
        )
    }

    override fun createCacheName(): String {
        return "RedisMultipleInstanceSyncTest"
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }
}
