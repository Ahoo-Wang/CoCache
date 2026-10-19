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

package me.ahoo.cocache.spring.boot

import me.ahoo.cache.spring.boot.starter.CoCacheAutoConfiguration
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import me.ahoo.cocache.spring.tx.TransactionalInvalidator
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import org.springframework.cache.CacheManager
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.ClassUtils
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * CoCache 6.0 自动配置（`cocache.next.enabled=true` 时启用）。
 *
 * 排在 5.x 自动配置之前：6.0 的 [CacheManager] 先注册，5.x 的因 `@ConditionalOnMissingBean` 退让。
 */
@AutoConfiguration(after = [DataRedisAutoConfiguration::class], before = [CoCacheAutoConfiguration::class])
@ConditionalOnProperty(prefix = CoCacheNextProperties.PREFIX, name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(CoCacheNextProperties::class)
class CoCacheNextAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CoherenceBackend::class)
    @ConditionalOnBean(DataRedisConnectionDetails::class)
    fun cocacheNextBackend(details: DataRedisConnectionDetails, properties: CoCacheNextProperties): CoherenceBackend {
        return RedisBackends.create(details, properties.redis.toOptions())
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(CoherenceBackend::class)
    fun cocacheNextRuntime(backend: CoherenceBackend): CacheRuntime = CacheRuntime.builder().backend(backend).build()

    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheNextAssembler(
        runtime: CacheRuntime,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        properties: CoCacheNextProperties,
    ): CacheAssembler = CacheAssembler(runtime, customizers.orderedStream().toList(), properties)

    @Bean
    @ConditionalOnBean(CacheAssembler::class)
    fun cocacheNextAnnotatedCacheFactory(
        beanFactory: ListableBeanFactory,
        assembler: CacheAssembler,
        mappers: ObjectProvider<ObjectMapper>,
    ): AnnotatedCacheFactory = AnnotatedCacheFactory(
        beanFactory,
        assembler,
        mappers.getIfAvailable { JsonMapper.builder().findAndAddModules().build() }
    )

    @Bean
    @ConditionalOnMissingBean(CacheManager::class)
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheNextCacheManager(runtime: CacheRuntime, beanFactory: ConfigurableBeanFactory): CacheManager {
        val manager = CoCacheSpringCacheManager(runtime)
        // 有 spring-tx 时把 put / evict / clear 推迟到事务提交之后：提交前失效会让并发回源读到旧数据（G2）
        if (!ClassUtils.isPresent(TRANSACTION_SYNCHRONIZATION, beanFactory.beanClassLoader)) {
            return manager
        }
        return TransactionAwareCacheManagerProxy(manager)
    }

    /**
     * 事务内失效登记到提交之后执行（有 spring-tx 时启用）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(TransactionSynchronizationManager::class)
    class TransactionalInvalidatorConfiguration {
        @Bean
        @ConditionalOnMissingBean
        fun cocacheNextTransactionalInvalidator(): TransactionalInvalidator = TransactionalInvalidator()
    }

    companion object {
        private const val TRANSACTION_SYNCHRONIZATION = "org.springframework.transaction.support.TransactionSynchronizationManager"

        @Bean
        @JvmStatic
        fun cocacheNextSpecRegistrar(): CacheSpecRegistrar = CacheSpecRegistrar()
    }
}
