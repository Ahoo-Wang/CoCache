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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ResolvableType
import java.util.concurrent.atomic.AtomicInteger

/**
 * `spring.main.lazy-initialization=true` 下的 starter（D7、D8）。`ApplicationContextRunner` 不处理 `spring.main.*`，
 * 这里与 `SpringApplication` 一样登记 [LazyInitializationBeanFactoryPostProcessor]：除了 Spring Boot 排除的类型
 * （`SmartInitializingSingleton`、基础设施 bean 与 `LazyInitializationExcludeFilter` 列出的类型），每个 bean 都延迟到第一次使用。
 */
class LazyInitializationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withInitializer { it.addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor()) }

    @Configuration(proxyBeanMethods = false)
    class BackendConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()
    }

    /**
     * D7：未分层配置的检查没有注入点，延迟之后从不创建，启动之后直接经 `runtime.create` 创建的缓存不再告警。
     */
    @Test
    fun theUnlayeredConfigurationCheckIsNotLazy() {
        val logger = LoggerFactory.getLogger(UnlayeredConfigurationCheck::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            runner.withUserConfiguration(BackendConfig::class.java)
                // 启动时 late 尚未声明：校验只告警
                .withPropertyValues("cocache.caches.late.local-max-age=5s", "cocache.caches-validation=warn")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    context.getBean(CacheRuntime::class.java)
                        .create(storeSpec<String, String>("late") { codec = StringCodec })
                }
        } finally {
            logger.detachAppender(appender)
        }
        appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            .assert().anyMatch { it.startsWith("Cache[late] - [cocache.caches.late] don't apply") }
    }

    @CoCache(name = "record")
    interface RecordStore : Store<String, String>

    /**
     * Spring Cache 按名称创建的声明只有派生副本 `Cache`（接口式或 spec），按名称取第一个声明，不创建缓存。
     */
    @Test
    fun derivedCacheBeansListOnlyDerivedDeclarations() {
        val beanFactory = DefaultListableBeanFactory().apply {
            registerBeanDefinition(
                "externalBean",
                CacheBeanDefinitions.annotatedCacheDefinition(ExternalCache::class.java)
            )
            registerBeanDefinition("recordBean", CacheBeanDefinitions.annotatedCacheDefinition(RecordStore::class.java))
            registerSingleton(
                "usersSpec",
                cacheSpec<String, String>("users") {
                    codec = StringCodec
                    externallySourced()
                }
            )
            registerSingleton("ledgerSpec", storeSpec<String, String>("ledger") { codec = StringCodec })
            registerSingleton(
                "externalSpec",
                cacheSpec<String, String>("external") {
                    codec = StringCodec
                    externallySourced()
                }
            )
            listOf("usersSpec", "ledgerSpec", "externalSpec").forEach { spec ->
                registerBeanDefinition(
                    "${spec}Cache",
                    CacheBeanDefinitions.specCacheDefinition(ResolvableType.forClass(Cache::class.java), spec)
                )
            }
        }
        DeclaredCaches.of(beanFactory).derivedCacheBeans
            .assert().containsExactlyEntriesOf(mapOf("external" to "externalBean", "users" to "usersSpecCache"))
    }

    /**
     * 外部来源的派生副本：只经 `@Cacheable` 使用，没有注入点。
     */
    @CoCache(name = "external", source = CoCache.Source.EXTERNAL)
    interface ExternalCache : Cache<String, String>

    open class ExternalService {
        // open：CGLIB 代理转发给目标，否则读到的是代理实例自己未初始化的字段
        open val loads = AtomicInteger()

        @Cacheable(cacheNames = ["external"], sync = true)
        open fun load(id: String): String = "loaded-$id-${loads.incrementAndGet()}"
    }

    @EnableCaching
    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [ExternalCache::class])
    class SpringCacheConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun codec(): Codec<String> = StringCodec

        @Bean
        fun service(): ExternalService = ExternalService()
    }

    /**
     * D8：延迟初始化下，只经 `@Cacheable` 使用的缓存在第一次调用时才创建，而不是以“找不到缓存”失败；
     * 不是 CoCache 声明的名称照常查不到。
     */
    @Test
    fun aCacheUsedOnlyBySpringCacheIsCreatedOnFirstUse() {
        runner.withUserConfiguration(SpringCacheConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val runtime = context.getBean(CacheRuntime::class.java)
            runtime.cacheNames.assert().doesNotContain("external")
            val service = context.getBean(ExternalService::class.java)
            service.load("a").assert().isEqualTo("loaded-a-1")
            service.load("a").assert().isEqualTo("loaded-a-1")
            service.loads.get().assert().isEqualTo(1)
            runtime.cacheNames.assert().contains("external")
            val manager = context.getBean(CacheManager::class.java)
            manager.getCache("external").assert().isNotNull()
            manager.getCache("unknown").assert().isNull()
        }
    }
}
