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

import me.ahoo.cache.client.CaffeineClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.CoherentCacheFactory
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.cache.example.model.User
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cache.util.ClientIdGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * 不使用注解接口、直接以代码组装 [CoherentCache]。
 */
@Configuration
class ClassDefinedCacheConfiguration {
    @Bean("userCache")
    fun userCache(
        redisTemplate: StringRedisTemplate,
        coherentCacheFactory: CoherentCacheFactory,
        objectMapper: ObjectMapper,
        clientIdGenerator: ClientIdGenerator
    ): CoherentCache<String, User> {
        val codecExecutor = ObjectToJsonCodecExecutor<User>(User::class.java, redisTemplate, objectMapper)
        return coherentCacheFactory.create(
            CoherentCacheConfiguration(
                cacheName = "userCache",
                clientId = clientIdGenerator.generate(),
                keyConverter = ToStringKeyConverter(User.CACHE_KEY_PREFIX),
                distributedCache = RedisDistributedCache(redisTemplate, codecExecutor),
                clientSideCache = CaffeineClientSideCache.build(expireAfterAccess = Duration.ofHours(1))
            ),
        )
    }

    @Bean("mockCache")
    fun mockCache(
        coherentCacheFactory: CoherentCacheFactory,
        clientIdGenerator: ClientIdGenerator
    ): CoherentCache<String, String> {
        return coherentCacheFactory.create(
            CoherentCacheConfiguration(
                cacheName = "mockCache",
                clientId = clientIdGenerator.generate(),
                keyConverter = ToStringKeyConverter(""),
                distributedCache = InMemoryDistributedCache(),
            ),
        )
    }
}
