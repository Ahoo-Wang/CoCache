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

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import me.ahoo.cocache.spring.tx.TransactionalInvalidator
import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
import java.util.concurrent.Executor

/**
 * CoCache 自动配置（`cocache.enabled`，默认启用）：后端、运行时、声明注册与 Spring Cache 适配。
 */
@AutoConfiguration(
    // 用类名而不是类引用：没有 Spring Data Redis 时（cocache.backend=memory）本类仍可加载
    afterName = ["org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"],
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

    /**
     * Redis 后端（`cocache.backend=redis`，默认）：需要 Spring Data Redis 的连接信息。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DataRedisConnectionDetails::class)
    @ConditionalOnProperty(
        prefix = CoCacheProperties.PREFIX,
        name = ["backend"],
        havingValue = "redis",
        matchIfMissing = true
    )
    class RedisBackendConfiguration {
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
    }

    /**
     * 内存后端（`cocache.backend=memory`）：不需要 Redis，只用于本地开发与测试。有 [CacheClock] bean（如测试的 `ManualClock`）时
     * 后端与运行时用同一个时钟，推进它即可让 L1 与 L2 的寿命一起到期。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = CoCacheProperties.PREFIX, name = ["backend"], havingValue = "memory")
    class MemoryBackendConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(CoherenceBackend::class)
        fun cocacheBackend(clock: ObjectProvider<CacheClock>): CoherenceBackend {
            log.warn(
                "CoCache uses the in-memory backend (${CoCacheProperties.PREFIX}.backend=memory): single process only, " +
                    "no cross-instance coherence. Use it for local development and tests, never in production."
            )
            return clock.ifUnique?.let { InMemoryBackend(it) } ?: InMemoryBackend()
        }
    }

    /**
     * 有唯一的 [CacheClock] bean 时运行时使用它（否则运行时自建 `TickingClock`）。
     * 设置了 `cocache.refresh-executor` 时按 bean 名取得提前刷新执行器（否则运行时自建并管理内置执行器）。
     * 有唯一的 [LoadObserver] bean（如 [CoCacheObservationAutoConfiguration] 注册的 Micrometer 观测器）时用它观测回源。
     * `cocache.metrics.local-hits=true` 时记录 L2 命中数（默认关闭，见 `CacheRuntime.Builder.recordLocalHits`）。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(CoherenceBackend::class)
    fun cocacheRuntime(
        backend: CoherenceBackend,
        clock: ObjectProvider<CacheClock>,
        loadObserver: ObjectProvider<LoadObserver>,
        properties: CoCacheProperties,
        beanFactory: ListableBeanFactory,
        environment: Environment,
    ): CacheRuntime {
        val builder = CacheRuntime.builder().backend(backend)
            .recordLocalHits(environment.getProperty(LOCAL_HITS_PROPERTY, Boolean::class.java, false))
        clock.ifUnique?.let { builder.clock(it) }
        loadObserver.ifUnique?.let { builder.loadObserver(it) }
        properties.refreshExecutor?.let {
            builder.refreshExecutor(executorBean(beanFactory, REFRESH_EXECUTOR_PROPERTY, it))
        }
        properties.asyncExecutor?.let { builder.asyncExecutor(executorBean(beanFactory, ASYNC_EXECUTOR_PROPERTY, it)) }
        properties.blockingExecutor?.let {
            builder.blockingExecutor(executorBean(beanFactory, BLOCKING_EXECUTOR_PROPERTY, it))
        }
        return builder.build()
    }

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
    fun cocacheCacheManager(
        runtime: CacheRuntime,
        properties: CoCacheProperties,
        beanFactory: ConfigurableListableBeanFactory,
    ): CacheManager {
        // 6.3 起 retrieve 经异步视图，不再使用执行器：旧配置项只告警
        properties.springCache.retrieveExecutor?.let {
            log.warn(
                "$RETRIEVE_EXECUTOR_PROPERTY=$it is deprecated since 6.3 and ignored: Spring Cache retrieve uses the async view " +
                    "and no executor. Remove it; see ${CoCacheProperties.PREFIX}.async-executor and ${CoCacheProperties.PREFIX}.blocking-executor."
            )
        }
        val manager = CoCacheSpringCacheManager(runtime)
        // 有 spring-tx 时把 put / evict / clear 推迟到事务提交之后：提交前失效会让并发回源读到旧数据（G2）
        if (!ClassUtils.isPresent(TRANSACTION_SYNCHRONIZATION, beanFactory.beanClassLoader)) {
            return manager
        }
        return TransactionAwareCacheManagerProxy(manager)
    }

    /**
     * 启动时检查指向 CoCache 缓存的 `@Cacheable` 是否声明了 `sync = true`（`cocache.spring-cache.require-sync`）。
     * 没有 `@EnableCaching`（没有 `CacheOperationSource`）时什么也不做。
     */
    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheSpringCacheSyncCheck(
        runtime: CacheRuntime,
        properties: CoCacheProperties,
        beanFactory: ConfigurableListableBeanFactory,
    ): SmartInitializingSingleton = SpringCacheSyncCheck(beanFactory, properties.springCache.requireSync) {
        runtime.cacheNames + CacheBeanDefinitions.declaredCacheNames(beanFactory)
    }

    /**
     * 有 `SetCache`（含尚未创建的声明）时检查 Redis 的 `maxmemory-policy` 会不会驱逐集合的唯一副本
     * （`cocache.redis.set-cache-eviction-check`、`cocache.redis.assume-maxmemory-policy`）。
     */
    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheSetCacheEvictionCheck(
        runtime: CacheRuntime,
        backend: CoherenceBackend,
        properties: CoCacheProperties,
        beanFactory: ConfigurableListableBeanFactory,
    ): SmartInitializingSingleton = SetCacheEvictionCheck(
        backend,
        properties.redis.setCacheEvictionCheck,
        properties.redis.assumeMaxmemoryPolicy,
    ) { SetCacheEvictionCheck.declaredSetCaches(runtime, beanFactory) }

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
        private val log = LogFactory.getLog(CoCacheAutoConfiguration::class.java)
        private const val TRANSACTION_SYNCHRONIZATION = "org.springframework.transaction.support.TransactionSynchronizationManager"
        internal const val REFRESH_EXECUTOR_PROPERTY = "${CoCacheProperties.PREFIX}.refresh-executor"
        internal const val LOCAL_HITS_PROPERTY = "${CoCacheProperties.PREFIX}.metrics.local-hits"
        internal const val RETRIEVE_EXECUTOR_PROPERTY = "${CoCacheProperties.PREFIX}.spring-cache.retrieve-executor"
        internal const val ASYNC_EXECUTOR_PROPERTY = "${CoCacheProperties.PREFIX}.async-executor"
        internal const val BLOCKING_EXECUTOR_PROPERTY = "${CoCacheProperties.PREFIX}.blocking-executor"
        internal const val RUNTIME_BEAN = "cocacheRuntime"

        /**
         * 按 bean 名（不按类型：避免多个组件意外共用同一个有状态 bean）取得执行器；
         * bean 不存在或不是 [Executor] 时以指明配置项的消息启动失败。
         *
         * 登记 `cocacheRuntime` 依赖该执行器：容器关闭时先关闭运行时（其中的刷新、续接与阻塞调用随之停止），再销毁执行器，
         * 否则执行器可能先被关闭，运行时关闭期间提交的任务被拒绝。
         */
        internal fun executorBean(beanFactory: ListableBeanFactory, property: String, beanName: String): Executor {
            check(beanFactory.containsBean(beanName)) {
                "$property=$beanName - no bean named '$beanName' is defined; set it to the name of an Executor bean or remove it."
            }
            check(beanFactory.isTypeMatch(beanName, Executor::class.java)) {
                "$property=$beanName - bean '$beanName' is a ${beanFactory.getType(beanName)?.name}, " +
                    "not a java.util.concurrent.Executor."
            }
            val executor = beanFactory.getBean(beanName, Executor::class.java)
            (beanFactory as? ConfigurableBeanFactory)?.registerDependentBean(beanName, RUNTIME_BEAN)
            return executor
        }

        @Bean
        @JvmStatic
        fun cocacheSpecRegistrar(): BeanFactoryPostProcessor = CacheSpecRegistrar()

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
