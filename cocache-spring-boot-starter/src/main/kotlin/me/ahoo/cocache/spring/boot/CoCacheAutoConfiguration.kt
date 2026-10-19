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

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import me.ahoo.cocache.spring.tx.TransactionalInvalidator
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.ClassUtils
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/**
 * CoCache 自动配置（`cocache.enabled`，默认启用）：后端、运行时、声明注册与 Spring Cache 适配。
 */
@AutoConfiguration(
    after = [DataRedisAutoConfiguration::class],
    // 先于 Spring Boot 的 CacheAutoConfiguration：后者的 @ConditionalOnMissingBean(CacheManager) 应看到 CoCache 的 CacheManager
    beforeName = ["org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration"]
)
@ConditionalOnProperty(
    prefix = CoCacheProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@EnableConfigurationProperties(CoCacheProperties::class)
class CoCacheAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CoherenceBackend::class)
    @ConditionalOnBean(DataRedisConnectionDetails::class)
    fun cocacheBackend(
        details: DataRedisConnectionDetails,
        properties: CoCacheProperties,
        redisProperties: ObjectProvider<DataRedisProperties>,
    ): CoherenceBackend {
        // `rediss://` URL 即使没有设置 spring.data.redis.ssl.enabled 也启用 TLS（与 Spring Boot 自己的 Lettuce 配置一致）
        val urlUsesSsl = RedisBackends.urlUsesSsl(redisProperties.ifAvailable?.url)
        return RedisBackends.create(details, properties.redis.toOptions(), urlUsesSsl)
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(CoherenceBackend::class)
    fun cocacheRuntime(backend: CoherenceBackend): CacheRuntime = CacheRuntime.builder().backend(backend).build()

    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheAssembler(
        runtime: CacheRuntime,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        properties: CoCacheProperties,
        beanFactory: ConfigurableListableBeanFactory,
    ): CacheAssembler = CacheAssembler(runtime, customizers.orderedStream().toList(), properties) {
        CacheBeanDefinitions.declaredCacheNames(beanFactory)
    }

    @Bean
    @ConditionalOnBean(CacheAssembler::class)
    fun cocacheAnnotatedCacheFactory(
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
    fun cocacheCacheManager(runtime: CacheRuntime, beanFactory: ConfigurableBeanFactory): CacheManager {
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
        fun cocacheTransactionalInvalidator(): TransactionalInvalidator = TransactionalInvalidator()
    }

    companion object {
        private const val TRANSACTION_SYNCHRONIZATION = "org.springframework.transaction.support.TransactionSynchronizationManager"

        @Bean
        @JvmStatic
        fun cocacheSpecRegistrar(): CacheSpecRegistrar = CacheSpecRegistrar()

        /**
         * 尽早（容器后处理阶段，任何缓存创建之前）拒绝 5.x 已删除的配置项。
         */
        @Bean
        @JvmStatic
        fun cocacheRemovedPropertiesCheck(
            environment: Environment
        ): BeanFactoryPostProcessor = BeanFactoryPostProcessor {
            RemovedProperties.check(environment)
        }
    }
}
