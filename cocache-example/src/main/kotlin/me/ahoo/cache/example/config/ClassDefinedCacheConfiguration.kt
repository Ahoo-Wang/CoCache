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
import com.google.common.cache.CacheBuilder
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.client.GuavaClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.CoherentCacheFactory
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.example.model.User
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cache.util.ClientIdGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

@Configuration
class ClassDefinedCacheConfiguration {
    @Bean("userCache")
    fun userCache(
        redisTemplate: StringRedisTemplate,
        coherentCacheFactory: CoherentCacheFactory,
        objectMapper: ObjectMapper,
        clientIdGenerator: ClientIdGenerator
    ): CoherentCache<String, User> {
        val clientId = clientIdGenerator.generate()
        val codecExecutor = ObjectToJsonCodecExecutor(
            User::class.java,
            redisTemplate,
            objectMapper,
        )

        val distributedCaching: DistributedCache<User> = RedisDistributedCache(redisTemplate, codecExecutor)

        return coherentCacheFactory.create(
            CoherentCacheConfiguration(
                cacheName = "userCache",
                clientId = clientId,
                keyConverter = ToStringKeyConverter(User.CACHE_KEY_PREFIX),
                distributedCache = distributedCaching,
                clientSideCache = CacheBuilder
                    .newBuilder()
                    .expireAfterAccess(Duration.ofHours(1))
                    .build<String, CacheValue<User>>().let {
                        GuavaClientSideCache(it)
                    }
            ),
        )
    }

    @Bean("mockCache")
    fun mockCache(
        coherentCacheFactory: CoherentCacheFactory,
        clientIdGenerator: ClientIdGenerator
    ): CoherentCache<String, String> {
        val distributedCaching = MockDistributedCache<String>()
        val clientId = clientIdGenerator.generate()
        return coherentCacheFactory.create(
            CoherentCacheConfiguration(
                cacheName = "mockCache",
                clientId = clientId,
                keyConverter = ToStringKeyConverter(""),
                distributedCache = distributedCaching,
            ),
        )
    }
}
