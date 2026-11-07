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
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.FillObserver
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import me.ahoo.cocache.spring.cache.DegradingCacheErrorHandler
import me.ahoo.cocache.spring.tx.TransactionAwareSpringCacheManager
import me.ahoo.cocache.spring.tx.TransactionalFillObserver
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
import org.springframework.cache.interceptor.CacheAspectSupport
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.SimpleCacheErrorHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.ClassUtils
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
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
     * 有唯一的 [FillObserver] bean（有 spring-tx 时的 [TransactionalFillObserver]）时用它门控与记录写事务内的回源。
     * `cocache.metrics.local-hits=true` 时记录 L2 命中数（默认关闭，见 `CacheRuntime.Builder.recordLocalHits`）。
     * 关闭时等待在途 L1 步骤的上限取自 `cocache.redis.command-timeout`（[closeTimeoutOf]，K17）。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(CoherenceBackend::class)
    fun cocacheRuntime(
        backend: CoherenceBackend,
        clock: ObjectProvider<CacheClock>,
        loadObserver: ObjectProvider<LoadObserver>,
        fillObserver: ObjectProvider<FillObserver>,
        properties: CoCacheProperties,
        beanFactory: ListableBeanFactory,
        environment: Environment,
    ): CacheRuntime {
        val builder = CacheRuntime.builder().backend(backend)
            .recordLocalHits(environment.getProperty(LOCAL_HITS_PROPERTY, Boolean::class.java, false))
        clock.ifUnique?.let { builder.clock(it) }
        loadObserver.ifUnique?.let { builder.loadObserver(it) }
        fillObserver.ifUnique?.let { builder.fillObserver(it) }
        properties.refreshExecutor?.let {
            builder.refreshExecutor(executorBean(beanFactory, REFRESH_EXECUTOR_PROPERTY, it))
        }
        properties.executor?.let { builder.executor(executorBean(beanFactory, EXECUTOR_PROPERTY, it)) }
        builder.closeTimeout(closeTimeoutOf(properties.redis))
        return builder.build()
    }

    /**
     * 装配层（[CacheAssembler]）。创建之前检查绑定 `cocache` 时记下的策略各层（`cocache.caches.[name]`、`cocache.defaults`、`cocache.overrides`）
     * 下不认识的配置项（[UnknownCacheKeys]）：
     * 按 `cocache.caches-validation` 启动失败或告警，早于任何缓存的创建。
     * 名称校验在全部单例创建之后才读取 [CacheNameContributor]（延迟初始化的贡献者这时创建），不在这里提前创建它们。
     */
    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheAssembler(
        runtime: CacheRuntime,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        properties: CoCacheProperties,
        beanFactory: ConfigurableListableBeanFactory,
        fillModes: ObjectProvider<TransactionalFillModes>,
        unknownCacheKeys: UnknownCacheKeys,
        contributors: ObjectProvider<CacheNameContributor>,
    ): CacheAssembler {
        unknownCacheKeys.check(properties.cachesValidation)
        return CacheAssembler(
            runtime,
            customizers.orderedStream().toList(),
            properties,
            { CacheBeanDefinitions.declaredCacheNames(beanFactory) },
            fillModes.ifAvailable,
        ) { contributors.orderedStream().toList().flatMapTo(linkedSetOf()) { it.cacheNames() } }
    }

    /**
     * 缓存的装配入口（RFC 0005 §4.8）：接口式声明、spec bean 与库都经它创建缓存，叠加定制器与配置文件（[CacheAssembler]）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CacheAssembler::class)
    fun cocacheFactory(
        runtime: CacheRuntime,
        assembler: CacheAssembler
    ): CoCacheFactory = CoCacheFactory(runtime, assembler)

    /**
     * 未分层的缓存上不生效的 `cocache.caches.[name]` 告警（S20）。
     */
    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheUnlayeredConfigurationCheck(
        runtime: CacheRuntime,
        properties: CoCacheProperties,
    ): UnlayeredConfigurationCheck = UnlayeredConfigurationCheck(runtime, properties)

    /**
     * `ObjectMapper` 在第一个回退到 Jackson 的接口式缓存创建时才取得（应用的，否则一个新的 `JsonMapper`）：编解码已经确定的缓存
     * 与只有 spec 的应用不需要从多个 `ObjectMapper` 中选出一个（#883）。
     */
    @Bean
    @ConditionalOnBean(CoCacheFactory::class)
    fun cocacheAnnotatedCacheFactory(
        beanFactory: ListableBeanFactory,
        factory: CoCacheFactory,
        mappers: ObjectProvider<ObjectMapper>,
    ): AnnotatedCacheFactory = AnnotatedCacheFactory(beanFactory, factory) {
        mappers.getIfAvailable { JsonMapper.builder().findAndAddModules().build() }
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager::class)
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheCacheManager(
        runtime: CacheRuntime,
        beanFactory: ConfigurableListableBeanFactory,
        cacheAspects: ObjectProvider<CacheAspectSupport>,
    ): CacheManager {
        val manager = CoCacheSpringCacheManager(runtime)
        // 有 spring-tx 时经 CoCache 的事务装饰器：evict 提交与回滚都失效，put / clear 推迟到提交之后（RFC 0005 §4.9）
        if (!ClassUtils.isPresent(TRANSACTION_SYNCHRONIZATION, beanFactory.beanClassLoader)) {
            return manager
        }
        // 事务结束之后的失败交给缓存通知的 CacheErrorHandler（S18）。失败时才取得：此时缓存通知已经配置完成，
        // 也看得到 degrade-on-error 换上的处理器
        return TransactionAwareSpringCacheManager(manager) { cacheErrorHandler(cacheAspects) }
    }

    /**
     * `cocache.spring-cache.degrade-on-error=true`：缓存通知（[CacheAspectSupport]）改用 [DegradingCacheErrorHandler]，
     * 原来的处理器（`CachingConfigurer.errorHandler()` 给出的，或 Spring 默认的 [SimpleCacheErrorHandler]）处理其它缓存，
     * 以及 CoCache 缓存的失效与清空失败。
     *
     * 在全部单例创建之后替换：缓存通知的处理器由 `CachingConfigurer` 延迟解析，提前取得会提前创建它。
     * 已经是 [DegradingCacheErrorHandler] 的（应用自己配置了）不再包装。
     */
    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    @ConditionalOnProperty(prefix = SPRING_CACHE_PREFIX, name = ["degrade-on-error"], havingValue = "true")
    fun cocacheDegradingCacheErrorHandler(
        cacheAspects: ObjectProvider<CacheAspectSupport>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        cacheAspects.orderedStream().forEach { aspect ->
            val current = aspect.errorHandler
            if (current !is DegradingCacheErrorHandler) {
                aspect.errorHandler = DegradingCacheErrorHandler(current)
            }
        }
    }

    /**
     * 启动时检查指向 CoCache 缓存的 `@Cacheable` 是否声明了 `sync = true`（`cocache.spring-cache.require-sync`），
     * 指向不是派生副本 `Cache` 的缓存的操作一律启动失败。没有 `@EnableCaching`（没有 `CacheOperationSource`）时什么也不做。
     */
    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheSpringCacheSyncCheck(
        runtime: CacheRuntime,
        properties: CoCacheProperties,
        beanFactory: ConfigurableListableBeanFactory,
    ): SmartInitializingSingleton = SpringCacheSyncCheck(
        beanFactory,
        properties.springCache.requireSync,
        { runtime.cacheNames + CacheBeanDefinitions.declaredCacheNames(beanFactory) },
        { SpringCacheSyncCheck.underivedCacheNames(runtime, beanFactory) },
    )

    /**
     * 有权威副本（`Store`、`VersionedStore`、`SetStore`，含尚未创建的声明）时检查 Redis 的 `maxmemory-policy` 会不会驱逐唯一副本
     * （`cocache.redis.store-eviction-check`、`cocache.redis.assume-maxmemory-policy`）。
     * 检查的是运行时实际使用的后端（[CacheRuntime.backend]），不另外要求 `CoherenceBackend` bean：应用自建的运行时不必把后端暴露为 bean，
     * 建在 Redis 上的照样受检查（#874）。
     */
    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheStoreEvictionCheck(
        runtime: CacheRuntime,
        properties: CoCacheProperties,
        beanFactory: ConfigurableListableBeanFactory,
    ): SmartInitializingSingleton = StoreEvictionCheck(
        runtime.backend,
        properties.redis.storeEvictionCheck,
        properties.redis.assumeMaxmemoryPolicy,
    ) { StoreEvictionCheck.declaredStores(runtime, beanFactory) }

    /**
     * 事务内失效登记到提交之后执行（有 spring-tx 时启用）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(TransactionSynchronizationManager::class)
    class TransactionalInvalidatorConfiguration {
        @Bean
        @ConditionalOnMissingBean
        fun cocacheTransactionalInvalidator(): TransactionalInvalidator = TransactionalInvalidator()

        /**
         * 写事务内回源的两个机制每个缓存的生效值（`invalidate-fills-on-rollback`、`fill-in-transaction`，按字段分层，RFC 0005 §4.8）：
         * [CacheAssembler] 在分层时登记，[cocacheFillObserver] 按缓存名读取。声明在 [cocacheFillObserver] 之前：
         * 两者的条件都是没有其它 `FillObserver`，应用自己声明了回源钩子时两者都不创建，报告也不列出这两个字段。
         */
        @Bean
        @ConditionalOnMissingBean(FillObserver::class)
        fun cocacheTransactionalFillModes(): TransactionalFillModes = TransactionalFillModes()

        /**
         * 写事务内回源的门控与记录（RFC 0005 §4.9），由 `cocacheRuntime` 取用。两个机制对一个缓存都关闭时，这个缓存的回源不感知事务
         * （与 [FillObserver.NONE] 相同：不门控、不记录，事务中的 leader 也照常共享结果）。
         */
        @Bean
        @ConditionalOnMissingBean(FillObserver::class)
        fun cocacheFillObserver(modes: TransactionalFillModes): FillObserver =
            TransactionalFillObserver({ modes.invalidateFillsOnRollback(it) }, { modes.fillInTransaction(it) })
    }

    companion object {
        private val log = LogFactory.getLog(CoCacheAutoConfiguration::class.java)
        private const val TRANSACTION_SYNCHRONIZATION = "org.springframework.transaction.support.TransactionSynchronizationManager"
        private const val SPRING_CACHE_PREFIX = "${CoCacheProperties.PREFIX}.spring-cache"
        private val SIMPLE_ERROR_HANDLER = SimpleCacheErrorHandler()
        internal const val REFRESH_EXECUTOR_PROPERTY = "${CoCacheProperties.PREFIX}.refresh-executor"
        internal const val LOCAL_HITS_PROPERTY = "${CoCacheProperties.PREFIX}.metrics.local-hits"
        internal const val EXECUTOR_PROPERTY = "${CoCacheProperties.PREFIX}.executor"
        internal const val RUNTIME_BEAN = "cocacheRuntime"

        /**
         * 关闭时等待在途 L1 步骤的上限（RFC 0005 §4.4、K17）：`cocache.redis.command-timeout` 的两倍（在途的那条命令，
         * 加上随后不可取消的一步：释放令牌或补发广播），不小于运行时的默认值。命令超时高于默认值时，关闭因此不会在在途的命令结束之前返回。
         */
        internal fun closeTimeoutOf(redis: CoCacheProperties.Redis): Duration = maxOf(
            CacheRuntime.DEFAULT_CLOSE_TIMEOUT,
            ConfigValues.positiveDuration("${CoCacheProperties.PREFIX}.redis.command-timeout", redis.commandTimeout)
                .multipliedBy(2)
        )

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

        /**
         * 缓存通知的 [CacheErrorHandler]；没有唯一的缓存通知（没有 `@EnableCaching`）时是 Spring 默认的 [SimpleCacheErrorHandler]。
         */
        internal fun cacheErrorHandler(cacheAspects: ObjectProvider<CacheAspectSupport>): CacheErrorHandler =
            cacheAspects.getIfUnique()?.errorHandler ?: SIMPLE_ERROR_HANDLER

        @Bean
        @JvmStatic
        fun cocacheSpecRegistrar(): BeanFactoryPostProcessor = CacheSpecRegistrar()

        /**
         * 在 [CoCacheProperties] 的正式绑定中记下 `cocache.caches.[name]` 下不认识的配置项（[UnknownCacheKeys]）。静态：绑定器在绑定第一个
         * 配置类时取得全部 [org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor]，不能为此提前创建配置类。
         */
        @Bean
        @JvmStatic
        fun cocacheUnknownCacheKeys(): UnknownCacheKeys = UnknownCacheKeys()
    }
}
