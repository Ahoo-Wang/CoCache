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
package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.CacheManager
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.spring.redis.RedisCacheEvictedEventBus
import me.ahoo.cache.util.ClientIdGenerator
import me.ahoo.cache.util.HostClientIdGenerator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.commons.util.InetUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * CoCache AutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration(
    after = [RedisAutoConfiguration::class],
    afterName = ["org.springframework.cloud.commons.util.UtilAutoConfiguration"]
)
@ConditionalOnCoCacheEnabled
@EnableConfigurationProperties(CoCacheProperties::class)
class CoCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(value = [ClientIdGenerator::class, InetUtils::class])
    fun defaultHostClientIdGenerator(): ClientIdGenerator {
        return ClientIdGenerator.HOST
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(RedisConnectionFactory::class)
    fun cocacheRedisMessageListenerContainer(
        redisConnectionFactory: RedisConnectionFactory
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(redisConnectionFactory)
        return container
    }

    @Bean
    @ConditionalOnMissingBean
    fun cacheEvictedEventBus(
        redisTemplate: StringRedisTemplate,
        cocacheRedisMessageListenerContainer: RedisMessageListenerContainer
    ): CacheEvictedEventBus {
        return RedisCacheEvictedEventBus(redisTemplate, cocacheRedisMessageListenerContainer)
    }

    @Bean
    @ConditionalOnMissingBean
    fun cacheManager(cacheEvictedEventBus: CacheEvictedEventBus): CacheManager {
        return CacheManager(cacheEvictedEventBus)
    }

    @Configuration
    @ConditionalOnClass(InetUtils::class)
    class CloudUtilAutoConfiguration {
        @Bean
        @ConditionalOnBean(InetUtils::class)
        fun inetUtilsHostClientIdGenerator(inetUtils: InetUtils): ClientIdGenerator {
            return HostClientIdGenerator {
                inetUtils.findFirstNonLoopbackHostInfo().ipAddress
            }
        }
    }
}
