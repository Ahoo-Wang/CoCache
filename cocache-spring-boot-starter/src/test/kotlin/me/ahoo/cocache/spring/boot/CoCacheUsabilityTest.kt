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
import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.annotation.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 开发体验：内存后端、全局 key 前缀、Spring Cache 的 `sync` 检查。
 */
class CoCacheUsabilityTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(DataRedisAutoConfiguration::class.java, CoCacheAutoConfiguration::class.java)
        )

    @Configuration(proxyBeanMethods = false)
    class SpecConfig {
        @Bean
        fun loads(): AtomicInteger = AtomicInteger()

        @Bean
        fun userSpec(loads: AtomicInteger): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader {
                loads.incrementAndGet()
                "user-$it"
            }
            policy = CachePolicy(ttl = Duration.ofMinutes(10), ttlJitter = Duration.ZERO)
        }

        @Bean
        fun profileSpec(): CacheSpec<String, String> = cacheSpec("profile") {
            codec = StringCodec
            keyPrefix = "declared:"
        }

        @Bean
        fun indexSpec(): SetCacheSpec<String, String> = setCacheSpec("index") { elementCodec = StringCodec }
    }

    @Configuration(proxyBeanMethods = false)
    class ClockConfig {
        @Bean
        fun clock(): ManualClock = ManualClock()
    }

    @Configuration(proxyBeanMethods = false)
    class CustomBackendConfig {
        @Bean(destroyMethod = "close")
        fun customBackend(): CoherenceBackend = InMemoryBackend()
    }

    @Test
    fun memoryBackendStartsWithoutRedis() {
        captureLogs(CoCacheAutoConfiguration::class.java) { logs ->
            runner.withUserConfiguration(SpecConfig::class.java)
                // 不可达的 Redis：内存模式下从不连接
                .withPropertyValues("cocache.backend=memory", "spring.data.redis.port=1")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    (context.getBean(CoherenceBackend::class.java) is InMemoryBackend).assert().isTrue()
                    @Suppress("UNCHECKED_CAST")
                    val user = context.getBean("user") as Cache<String, String>
                    user["1"].assert().isEqualTo("user-1")
                }
            logs.any { it.level == Level.WARN && it.formattedMessage.contains("single process only") }
                .assert().withFailMessage { "memory backend warning not logged: $logs" }.isTrue()
        }
    }

    @Test
    fun memoryBackendWithoutSpringDataRedis() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("org.springframework.boot.data.redis"))
            .withUserConfiguration(SpecConfig::class.java)
            .withPropertyValues("cocache.backend=MEMORY")
            .run { context ->
                assertThat(context).hasNotFailed()
                (context.getBean(CoherenceBackend::class.java) is InMemoryBackend).assert().isTrue()
                context.getBean(CacheRuntime::class.java).cacheNames.assert().contains("user")
            }
    }

    @Test
    fun redisModeWithoutSpringDataRedisHasNoBackend() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("org.springframework.boot.data.redis"))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(CoherenceBackend::class.java)
                assertThat(context).doesNotHaveBean(CacheRuntime::class.java)
            }
    }

    @Test
    fun declaredBackendWinsOverMemoryMode() {
        runner.withUserConfiguration(CustomBackendConfig::class.java)
            .withPropertyValues("cocache.backend=memory")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(CoherenceBackend::class.java)
                assertThat(context).hasBean("customBackend")
            }
    }

    @Test
    fun manualClockDrivesRuntimeAndMemoryBackend() {
        runner.withUserConfiguration(SpecConfig::class.java, ClockConfig::class.java)
            .withPropertyValues("cocache.backend=memory")
            .run { context ->
                assertThat(context).hasNotFailed()
                val clock = context.getBean(ManualClock::class.java)
                val runtime = context.getBean(CacheRuntime::class.java)
                runtime.clock.assert().isSameAs(clock)
                val loads = context.getBean(AtomicInteger::class.java)

                @Suppress("UNCHECKED_CAST")
                val user = context.getBean("user") as Cache<String, String>
                user["1"].assert().isEqualTo("user-1")
                loads.get().assert().isEqualTo(1)

                // L2 到期（localMaxAge 5m）后从 L1 读回，不回源
                clock.advance(Duration.ofMinutes(6))
                user["1"].assert().isEqualTo("user-1")
                loads.get().assert().isEqualTo(1)

                // L1 也到期（ttl 10m）后回源：L1 用的是同一个时钟
                clock.advance(Duration.ofMinutes(10))
                user["1"].assert().isEqualTo("user-1")
                loads.get().assert().isEqualTo(2)
            }
    }

    @Test
    fun clockBeanAlsoUsedWithDeclaredBackend() {
        runner.withUserConfiguration(CustomBackendConfig::class.java, ClockConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(
                    CacheRuntime::class.java
                ).clock.assert().isSameAs(context.getBean(CacheClock::class.java))
            }
    }

    @Test
    fun redisIsTheDefaultBackend() {
        runner.withPropertyValues("cocache.redis.client-name=cocache-usability-test").run { context ->
            assertThat(context).hasNotFailed()
            (context.getBean(CoherenceBackend::class.java) is RedisBackend).assert().isTrue()
            context.getBean(CoCacheProperties::class.java).backend.assert().isEqualTo(CoCacheProperties.Backend.REDIS)
        }
    }

    private fun storageKeyOf(runtime: CacheRuntime, cacheName: String): String =
        requireNotNull(runtime.admin(cacheName)).inspect("k").storageKey

    @Test
    fun keyPrefixDefaultsAndPerCacheOverride() {
        runner.withUserConfiguration(SpecConfig::class.java, CustomBackendConfig::class.java)
            .withPropertyValues(
                "spring.application.name=orders",
                "cocache.defaults.key-prefix=\${spring.application.name}:",
                "cocache.caches.index.key-prefix=idx:",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)
                context.getBean("user")
                context.getBean("profile")
                context.getBean("index")
                storageKeyOf(runtime, "user").assert().isEqualTo("orders:user:k")
                // 配置文件覆盖声明中显式写出的前缀（与策略字段同一规则）
                storageKeyOf(runtime, "profile").assert().isEqualTo("orders:profile:k")
                storageKeyOf(runtime, "index").assert().isEqualTo("idx:index:k")
                @Suppress("UNCHECKED_CAST")
                val index = context.getBean("index") as SetCache<String, String>
                index.add("k", listOf("a"))
                requireNotNull(runtime.admin("index")).inspect("k").remote.value.assert().isEqualTo("[a]")
            }
    }

    @Test
    fun keyPrefixFallsBackToDeclarationAndOverridesCustomizer() {
        runner.withUserConfiguration(SpecConfig::class.java, CustomBackendConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)
                context.getBean("user")
                context.getBean("profile")
                storageKeyOf(runtime, "user").assert().isEqualTo("cocache:user:k")
                storageKeyOf(runtime, "profile").assert().isEqualTo("declared:profile:k")
            }
        runner.withUserConfiguration(
            SpecConfig::class.java,
            CustomBackendConfig::class.java,
            CustomizerConfig::class.java
        )
            .withPropertyValues("cocache.caches.user.key-prefix=props:")
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)
                context.getBean("user")
                context.getBean("profile")
                storageKeyOf(runtime, "user").assert().isEqualTo("props:user:k")
                storageKeyOf(runtime, "profile").assert().isEqualTo("customized:profile:k")
            }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomizerConfig {
        @Bean
        fun prefixCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
            override fun customize(spec: CacheSpec.Builder<*, *>) {
                spec.keyPrefix = "customized:"
            }
        }
    }

    /**
     * `@Cacheable` 的 `sync` 检查。
     */
    open class UserService {
        @Cacheable("user")
        open fun unsynced(id: String): String = "u-$id"

        /**
         * 同名重载合并为一条违规。
         */
        @Cacheable(cacheNames = ["user", "profile"])
        open fun unsynced(id: Long): String = "u-$id"

        @Cacheable(cacheNames = ["user"], sync = true)
        open fun synced(id: String): String = "u-$id"

        @Cacheable("not-cocache")
        open fun otherCache(id: String): String = "o-$id"
    }

    @CacheConfig(cacheNames = ["profile"])
    open class ProfileService {
        @Cacheable
        open fun byClassLevelName(id: String): String = "p-$id"
    }

    interface Lookup {
        fun find(id: String): String
    }

    /**
     * 实现接口的 bean 由 JDK 代理包装（`@EnableCaching` 默认 proxyTargetClass = false）。
     */
    class LookupImpl : Lookup {
        @Cacheable("user")
        override fun find(id: String): String = "l-$id"
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    class CachingConfig {
        @Bean
        fun userService(): UserService = UserService()

        @Bean
        fun profileService(): ProfileService = ProfileService()

        @Bean
        fun lookup(): Lookup = LookupImpl()

        @Bean
        fun abstractTemplate(): org.springframework.beans.factory.config.BeanFactoryPostProcessor =
            org.springframework.beans.factory.config.BeanFactoryPostProcessor { factory ->
                val registry = factory as org.springframework.beans.factory.support.BeanDefinitionRegistry
                // 类型无法预测的 FactoryBean（不初始化）：跳过
                val opaque = org.springframework.beans.factory.support.RootBeanDefinition(OpaqueFactoryBean::class.java)
                opaque.isLazyInit = true
                registry.registerBeanDefinition("opaqueFactory", opaque)
                registry.registerBeanDefinition(
                    "abstractService",
                    org.springframework.beans.factory.support.RootBeanDefinition(
                        UserService::class.java
                    ).apply { isAbstract = true }
                )
            }
    }

    class OpaqueFactoryBean : org.springframework.beans.factory.FactoryBean<Any> {
        override fun getObject(): Any = Any()

        override fun getObjectType(): Class<*>? = null
    }

    @Test
    fun warnsAboutUnsyncedCacheable() {
        captureLogs(SpringCacheSyncCheck::class.java) { logs ->
            runner.withUserConfiguration(
                SpecConfig::class.java,
                CustomBackendConfig::class.java,
                CachingConfig::class.java
            )
                .run { context ->
                    assertThat(context).hasNotFailed()
                    val violations = context.getBean(SpringCacheSyncCheck::class.java).violations
                    violations.map { it.method }.assert().containsExactlyInAnyOrder(
                        "${UserService::class.java.name}#unsynced",
                        "${ProfileService::class.java.name}#byClassLevelName",
                        "${LookupImpl::class.java.name}#find",
                    )
                    violations.first {
                        it.method.endsWith(
                            "#byClassLevelName"
                        )
                    }.cacheNames.assert().containsExactly("profile")
                    val unsynced = violations.first { it.method.endsWith("#unsynced") }
                    unsynced.cacheNames.assert().containsExactlyInAnyOrder("user", "profile")
                }
            logs.filter { it.level == Level.WARN }.map { it.formattedMessage }
                .any { it.contains("UserService#unsynced") && it.contains("[user") && it.contains("sync = true") }
                .assert().withFailMessage { "sync warning not logged: $logs" }.isTrue()
        }
    }

    @Test
    fun requireSyncFailsStartup() {
        runner.withUserConfiguration(SpecConfig::class.java, CustomBackendConfig::class.java, CachingConfig::class.java)
            .withPropertyValues("cocache.spring-cache.require-sync=true")
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining("@Cacheable without sync = true")
                    .hasStackTraceContaining("UserService#unsynced")
            }
    }

    @Test
    fun syncCheckIsNoOpWithoutCaching() {
        runner.withUserConfiguration(SpecConfig::class.java, CustomBackendConfig::class.java)
            .withPropertyValues("cocache.spring-cache.require-sync=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(SpringCacheSyncCheck::class.java).violations.assert().isEmpty()
            }
    }

    @Test
    fun syncCheckWithoutCoCacheCaches() {
        runner.withUserConfiguration(CustomBackendConfig::class.java, CachingConfig::class.java)
            .withPropertyValues("cocache.spring-cache.require-sync=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(SpringCacheSyncCheck::class.java).violations.assert().isEmpty()
            }
    }

    @Test
    fun syncCheckPassesWhenAllSynced() {
        runner.withUserConfiguration(SpecConfig::class.java, CustomBackendConfig::class.java, SyncedConfig::class.java)
            .withPropertyValues("cocache.spring-cache.require-sync=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(SpringCacheSyncCheck::class.java).violations.assert().isEmpty()
            }
    }

    open class SyncedService {
        @Cacheable(cacheNames = ["user"], sync = true)
        open fun synced(id: String): String = "u-$id"
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    class SyncedConfig {
        @Bean
        fun syncedService(): SyncedService = SyncedService()
    }

    private fun captureLogs(type: Class<*>, block: (List<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(type) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block(appender.list)
        } finally {
            logger.detachAppender(appender)
        }
    }
}
