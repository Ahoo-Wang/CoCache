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

import me.ahoo.cache.CacheFactory
import me.ahoo.cache.client.ClientSideCacheFactory
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.consistency.CoherentCacheFactory
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.converter.KeyConverterFactory
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.join.JoinKeyExtractorFactory
import me.ahoo.cache.join.proxy.DefaultJoinCacheProxyFactory
import me.ahoo.cache.join.proxy.JoinCacheProxyFactory
import me.ahoo.cache.proxy.CacheProxyFactory
import me.ahoo.cache.proxy.DefaultCacheProxyFactory
import me.ahoo.cache.source.CacheSourceFactory
import me.ahoo.cache.spring.SpringCacheFactory
import me.ahoo.cache.spring.cache.CoCacheManager
import me.ahoo.cache.spring.client.SpringClientSideCacheFactory
import me.ahoo.cache.spring.converter.SpringKeyConverterFactory
import me.ahoo.cache.spring.join.SpringJoinKeyExtractorFactory
import me.ahoo.cache.spring.redis.RedisCacheEvictedEventBus
import me.ahoo.cache.spring.redis.RedisDistributedCacheFactory
import me.ahoo.cache.spring.source.SpringCacheSourceFactory
import me.ahoo.cache.util.ClientIdGenerator
import me.ahoo.cache.util.HostClientIdGenerator
import me.ahoo.cosid.machine.HostAddressSupplier
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import tools.jackson.databind.ObjectMapper

/**
 * CoCache AutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration(
    after = [DataRedisAutoConfiguration::class]
)
@ConditionalOnCoCacheEnabled
@EnableConfigurationProperties(CoCacheProperties::class)
class CoCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(value = [ClientIdGenerator::class, HostAddressSupplier::class])
    fun defaultHostClientIdGenerator(): ClientIdGenerator {
        return ClientIdGenerator.HOST
    }

    @Bean
    @ConditionalOnMissingBean
    fun cacheFactory(beanFactory: ListableBeanFactory): CacheFactory {
        return SpringCacheFactory(beanFactory)
    }

    @Bean
    fun coCacheManager(cacheFactory: CacheFactory): CoCacheManager {
        return CoCacheManager(cacheFactory)
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
    fun coherentCacheFactory(cacheEvictedEventBus: CacheEvictedEventBus): CoherentCacheFactory {
        return DefaultCoherentCacheFactory(cacheEvictedEventBus)
    }

    @Bean
    @ConditionalOnMissingBean
    fun cacheSourceFactory(beanFactory: BeanFactory): CacheSourceFactory {
        return SpringCacheSourceFactory(beanFactory)
    }

    @Bean
    @ConditionalOnMissingBean
    fun clientSideCacheFactory(beanFactory: BeanFactory): ClientSideCacheFactory {
        return SpringClientSideCacheFactory(beanFactory)
    }

    @Bean
    @ConditionalOnMissingBean
    fun distributedCacheFactory(
        beanFactory: BeanFactory,
        objectMapper: ObjectMapper,
        redisTemplate: StringRedisTemplate
    ): DistributedCacheFactory {
        return RedisDistributedCacheFactory(beanFactory, objectMapper, redisTemplate)
    }

    @Bean
    @ConditionalOnMissingBean
    fun keyConverterFactory(appContext: ApplicationContext): KeyConverterFactory {
        return SpringKeyConverterFactory(appContext)
    }

    @Suppress("LongParameterList")
    @Bean
    @ConditionalOnMissingBean
    fun cacheProxyFactory(
        coherentCacheFactory: CoherentCacheFactory,
        clientIdGenerator: ClientIdGenerator,
        clientSideCacheFactory: ClientSideCacheFactory,
        distributedCacheFactory: DistributedCacheFactory,
        cacheSourceResolver: CacheSourceFactory,
        keyConverterFactory: KeyConverterFactory
    ): CacheProxyFactory {
        return DefaultCacheProxyFactory(
            coherentCacheFactory,
            clientIdGenerator,
            clientSideCacheFactory,
            distributedCacheFactory,
            cacheSourceResolver,
            keyConverterFactory
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun joinKeyExtractorFactory(beanFactory: BeanFactory): JoinKeyExtractorFactory {
        return SpringJoinKeyExtractorFactory(beanFactory)
    }

    @Bean
    @ConditionalOnMissingBean
    fun joinCacheProxyFactory(
        cacheFactory: CacheFactory,
        joinKeyExtractorFactory: JoinKeyExtractorFactory
    ): JoinCacheProxyFactory {
        return DefaultJoinCacheProxyFactory(cacheFactory, joinKeyExtractorFactory)
    }

    @Configuration
    @ConditionalOnClass(HostAddressSupplier::class)
    class CosIdHostAddressSupplierAutoConfiguration {
        @Bean
        @ConditionalOnBean(HostAddressSupplier::class)
        fun inetUtilsHostClientIdGenerator(hostAddressSupplier: HostAddressSupplier): ClientIdGenerator {
            return HostClientIdGenerator {
                hostAddressSupplier.hostAddress
            }
        }
    }
}
