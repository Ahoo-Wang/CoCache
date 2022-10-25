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
package me.ahoo.cache.example.config

import com.fasterxml.jackson.databind.ObjectMapper
import me.ahoo.cache.CacheConfig
import me.ahoo.cache.CacheManager
import me.ahoo.cache.CoherentCache
import me.ahoo.cache.converter.ExpKeyConverter
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.example.model.User
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cosid.IdGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * AppConfig.
 *
 * @author ahoo wang
 */
@Configuration
class AppConfig {
    @Bean("userCache")
    fun userCache(
        redisTemplate: StringRedisTemplate,
        cacheManager: CacheManager,
        objectMapper: ObjectMapper,
        idGenerator: IdGenerator
    ): CoherentCache<Long, User> {
        val clientId = idGenerator.generateAsString()
        val codecExecutor = ObjectToJsonCodecExecutor(
            User::class.java,
            redisTemplate,
            objectMapper
        )

        val distributedCaching: DistributedCache<User> = RedisDistributedCache(redisTemplate, codecExecutor)
        return cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = "userCache",
                clientId = clientId,
                keyConverter = ExpKeyConverter(User.CACHE_KEY_PREFIX, "#{#root}"),
                distributedCaching = distributedCaching,
            )
        )
    }

    @Bean("mockCache")
    fun mockCache(cacheManager: CacheManager, idGenerator: IdGenerator): CoherentCache<String, String> {
        val distributedCaching = MockDistributedCache<String>()
        val clientId = idGenerator.generateAsString()
        return cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = "userCache",
                clientId = clientId,
                keyConverter = ToStringKeyConverter(""),
                distributedCaching = distributedCaching,
            )
        )
    }
}
