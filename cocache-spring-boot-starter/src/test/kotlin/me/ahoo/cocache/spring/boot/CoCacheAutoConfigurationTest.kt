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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ResolvableType
import java.time.Duration

class CoCacheAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.enabled=true")

    @Configuration(proxyBeanMethods = false)
    class InMemoryConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader { "user-$it" }
        }

        @Bean
        fun profile(): CacheSpec<String, String> = cacheSpec("profile") { codec = StringCodec }

        @Bean
        fun indexSpec(): SetCacheSpec<String, String> = setCacheSpec("index") { elementCodec = StringCodec }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomizerConfig {
        @Bean
        fun customizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
            override fun supports(cacheName: String): Boolean = cacheName != "profile"

            override fun customize(spec: CacheSpec.Builder<*, *>) {
                spec.keyPrefix = "custom:"
                spec.policy = spec.policy.toBuilder().absentTtl(Duration.ofSeconds(7)).build()
            }

            override fun customize(spec: SetCacheSpec.Builder<*, *>) {
                spec.keyPrefix = "custom-set:"
            }
        }

        @Bean
        fun defaultCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {}
    }

    @Configuration(proxyBeanMethods = false)
    class RawSpecConfig {
        @Bean
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Suppress("UNCHECKED_CAST")
        @Bean
        fun rawSpec(): CacheSpec<*, *> = cacheSpec<String, String>("raw") { codec = StringCodec }
    }

    @Configuration(proxyBeanMethods = false)
    class ConflictConfig {
        @Bean
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") { codec = StringCodec }

        @Bean
        fun user(): Any = Any()
    }

    @Test
    fun canBeDisabled() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues("cocache.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(CacheRuntime::class.java)
                assertThat(context).doesNotHaveBean("user")
            }
    }

    @Test
    fun enabledByDefault() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withUserConfiguration(InMemoryConfig::class.java)
            .run { context ->
                assertThat(context).hasSingleBean(CacheRuntime::class.java)
                assertThat(context).hasBean("user")
            }
    }

    @Test
    fun registersTypedCacheBeansForSpecs() {
        runner.withUserConfiguration(InMemoryConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val type = ResolvableType.forClassWithGenerics(Cache::class.java, String::class.java, String::class.java)
            context.getBeanNamesForType(type).toSet().assert().containsExactlyInAnyOrder("user", "profileCache")
            @Suppress("UNCHECKED_CAST")
            val user = context.getBean("user") as Cache<String, String>
            user["1"].assert().isEqualTo("user-1")
            @Suppress("UNCHECKED_CAST")
            val index = context.getBean("index") as SetCache<String, String>
            index.add("k", listOf("a"))
            index["k"].assert().containsExactly("a")
            context.getBeanNamesForType(
                ResolvableType.forClassWithGenerics(SetCache::class.java, String::class.java, String::class.java)
            )
                .toList().assert().containsExactly("index")
            context.getBean(
                CacheRuntime::class.java
            ).cacheNames.assert().containsExactlyInAnyOrder("user", "profile", "index")
            assertThat(context).hasSingleBean(CacheManager::class.java)
            (
                context.getBean(
                    CacheManager::class.java
                ) is org.springframework.cache.transaction.TransactionAwareCacheManagerProxy
                ).assert().isTrue()
            context.getBean(CacheManager::class.java).getCache("user")?.get("2")?.get().assert().isEqualTo("user-2")
        }
    }

    @Test
    fun propertiesOverrideDeclarationsInOrder() {
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues(
                "cocache.defaults.ttl=PT2M",
                "cocache.defaults.local-maximum-size=50",
                "cocache.caches.user.ttl=PT30S",
                "cocache.caches.user.ttl-jitter=PT0S",
                "cocache.caches.user.lease-wait=PT1S",
                "cocache.caches.user.load-max-concurrent=3",
                "cocache.caches.user.load-async-timeout=7",
                "cocache.caches.user.invalidate-failure=throw",
                "cocache.caches.user.read-failure=throw",
                "cocache.caches.index.ttl-forever=true",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean("user")
                context.getBean("profileCache")
                context.getBean("index")
                val properties = context.getBean(CoCacheProperties::class.java)
                val user = properties.policyFor("user", CachePolicy.DEFAULT)
                user.ttl.assert().isEqualTo(Duration.ofSeconds(30))
                user.ttlJitter.assert().isEqualTo(Duration.ZERO)
                user.lease.wait.assert().isEqualTo(Duration.ofSeconds(1))
                user.lease.ttl.assert().isEqualTo(CachePolicy.DEFAULT.lease.ttl)
                user.load.maxConcurrent.assert().isEqualTo(3)
                user.load.asyncTimeout.assert().isEqualTo(Duration.ofSeconds(7))
                user.local.maximumSize.assert().isEqualTo(50)
                user.invalidateFailure.assert().isEqualTo(InvalidateFailureMode.THROW)
                user.readFailure.assert().isEqualTo(me.ahoo.cocache.core.policy.ReadFailureMode.THROW)
                properties.policyFor("profile", CachePolicy.DEFAULT).ttl.assert().isEqualTo(Duration.ofMinutes(2))
                properties.policyFor("index", CachePolicy.DEFAULT).ttl.assert().isNull()
            }
    }

    @Test
    fun everyPolicyPropertyIsApplied() {
        val properties = policyProperties(
            ttl = Duration.ofMinutes(3),
            ttlJitter = Duration.ofSeconds(1),
            absentTtl = Duration.ofSeconds(2),
            localMaxAge = Duration.ofMinutes(1),
            degradedLocalMaxAge = Duration.ofSeconds(3),
            refreshWindow = Duration.ofSeconds(30),
            leaseTtl = Duration.ofSeconds(4),
            loadAcquireTimeout = Duration.ofMillis(5),
        )
        val policy = properties.applyTo(CachePolicy.DEFAULT)
        policy.ttl.assert().isEqualTo(Duration.ofMinutes(3))
        policy.ttlJitter.assert().isEqualTo(Duration.ofSeconds(1))
        policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(2))
        policy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(1))
        policy.degradedLocalMaxAge.assert().isEqualTo(Duration.ofSeconds(3))
        policy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(30))
        policy.lease.ttl.assert().isEqualTo(Duration.ofSeconds(4))
        policy.lease.wait.assert().isEqualTo(CachePolicy.DEFAULT.lease.wait)
        policy.load.acquireTimeout.assert().isEqualTo(Duration.ofMillis(5))
        policy.load.maxConcurrent.assert().isEqualTo(0)
        policyProperties(
            ttlForever = false
        ).applyTo(CachePolicy.DEFAULT).ttl.assert().isEqualTo(CachePolicy.DEFAULT.ttl)
        val options = CoCacheProperties.Redis(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            8,
            "x",
            me.ahoo.cocache.redis.RedisChannelMode.SHARDED,
            CoCacheProperties.EvictionCheck.WARN
        ).toOptions()
        options.clientName.assert().isEqualTo("x")
        options.channelMode.assert().isEqualTo(me.ahoo.cocache.redis.RedisChannelMode.SHARDED)
    }

    @Test
    fun customizersApplyBeforeProperties() {
        runner.withUserConfiguration(InMemoryConfig::class.java, CustomizerConfig::class.java)
            .withPropertyValues("cocache.caches.user.absent-ttl=PT9S")
            .run { context ->
                assertThat(context).hasNotFailed()
                @Suppress("UNCHECKED_CAST")
                val user = context.getBean("user") as Cache<String, String>
                user.put("k", "v")
                val backend = context.getBean(CoherenceBackend::class.java)
                (
                    backend.store(
                        "user"
                    ).read("custom:user:k", null, null) is me.ahoo.cocache.api.spi.RemoteRead.Hit
                    ).assert().isTrue()
                @Suppress("UNCHECKED_CAST")
                val index = context.getBean("index") as SetCache<String, String>
                index.add("k", listOf("a"))
                (
                    backend.store(
                        "index"
                    ).read("custom-set:index:k", null, null) is me.ahoo.cocache.api.spi.RemoteRead.Hit
                    ).assert().isTrue()
                @Suppress("UNCHECKED_CAST")
                val profile = context.getBean("profileCache") as Cache<String, String>
                profile.put("k", "v")
                (
                    backend.store(
                        "profile"
                    ).read("cocache:profile:k", null, null) is me.ahoo.cocache.api.spi.RemoteRead.Hit
                    ).assert().isTrue()
            }
    }

    @Test
    fun undeclaredCacheInPropertiesFailsStartup() {
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues("cocache.caches.usr.ttl=PT1M")
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("undeclared caches [usr]")
            }
    }

    @Test
    fun specWithoutGenericsFailsStartup() {
        runner.withUserConfiguration(RawSpecConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("must declare its generic types")
        }
    }

    @Test
    fun conflictingBeanNameFailsStartup() {
        runner.withUserConfiguration(ConflictConfig::class.java).run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun providesTransactionalInvalidator() {
        runner.withUserConfiguration(InMemoryConfig::class.java).run { context ->
            assertThat(context).hasSingleBean(me.ahoo.cocache.spring.tx.TransactionalInvalidator::class.java)
        }
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withClassLoader(
                org.springframework.boot.test.context.FilteredClassLoader(
                    org.springframework.transaction.support.TransactionSynchronizationManager::class.java
                )
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(me.ahoo.cocache.spring.tx.TransactionalInvalidator::class.java)
            }
    }

    @Test
    fun cacheManagerIsPlainWithoutSpringTx() {
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withClassLoader(
                org.springframework.boot.test.context.FilteredClassLoader(
                    org.springframework.transaction.support.TransactionSynchronizationManager::class.java
                )
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                (context.getBean(CacheManager::class.java) is CoCacheSpringCacheManager).assert().isTrue()
            }
    }

    @Test
    fun cacheBeanNaming() {
        CacheSpecRegistrar.cacheBeanName("userSpec").assert().isEqualTo("user")
        CacheSpecRegistrar.cacheBeanName("user").assert().isEqualTo("userCache")
        CacheSpecRegistrar.cacheBeanName("Spec").assert().isEqualTo("SpecCache")
    }

    @Test
    fun noBackendMeansNoRuntime() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(CacheRuntime::class.java)
            assertThat(context).doesNotHaveBean(CacheManager::class.java)
        }
    }

    @Test
    fun redisBackendFromSpringBootConnectionDetails() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(DataRedisAutoConfiguration::class.java, CoCacheAutoConfiguration::class.java)
            )
            .withPropertyValues("cocache.enabled=true", "cocache.redis.client-name=cocache-starter-test")
            .withUserConfiguration(RedisSpecConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                (context.getBean(CoherenceBackend::class.java) is RedisBackend).assert().isTrue()
                @Suppress("UNCHECKED_CAST")
                val cache = context.getBean("starterUser") as Cache<String, String>
                cache["1"].assert().isEqualTo("u-1")
                cache.invalidate("1")
            }
    }

    @Configuration(proxyBeanMethods = false)
    class RedisSpecConfig {
        @Bean
        fun starterUserSpec(): CacheSpec<String, String> = cacheSpec("starter-user-" + java.util.UUID.randomUUID()) {
            codec = StringCodec
            loader = CacheLoader { "u-$it" }
        }
    }

    @me.ahoo.cocache.api.annotation.CoCache(name = "LazyUser")
    interface LazyUserCache : Cache<String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [LazyUserCache::class])
    class LazyCacheConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun eagerSpec(): CacheSpec<String, String> = cacheSpec("eager") { codec = StringCodec }
    }

    /**
     * 把接口式声明的缓存 bean 改为延迟初始化（如 `spring.main.lazy-initialization=true`）。
     */
    private val lazyCaches = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withUserConfiguration(LazyCacheConfig::class.java)
        .withInitializer { context ->
            context.addBeanFactoryPostProcessor { factory -> factory.getBeanDefinition("LazyUser").isLazyInit = true }
        }

    @Test
    fun configurationPropertiesBindWithUnitsAndDefaults() {
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues(
                "cocache.defaults.ttl=120",
                "cocache.defaults.lease-wait=500ms",
                "cocache.defaults.local-max-age=PT2M",
                "cocache.caches.user.refresh-window=30",
                "cocache.caches.user.ttl-jitter=0",
                "cocache.redis.command-timeout=1500",
                "cocache.redis.channel-mode=sharded",
                "cocache.redis.set-cache-eviction-check=fail",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(CoCacheProperties::class.java)
                properties.enabled.assert().isTrue()
                properties.defaults.ttl.assert().isEqualTo(Duration.ofSeconds(120))
                properties.defaults.leaseWait.assert().isEqualTo(Duration.ofMillis(500))
                properties.defaults.localMaxAge.assert().isEqualTo(Duration.ofMinutes(2))
                requireNotNull(
                    properties.cacheProperties("user")
                ).refreshWindow.assert().isEqualTo(Duration.ofSeconds(30))
                properties.redis.commandTimeout.assert().isEqualTo(Duration.ofMillis(1500))
                properties.redis.channelMode.assert().isEqualTo(me.ahoo.cocache.redis.RedisChannelMode.SHARDED)
                properties.redis.setCacheEvictionCheck.assert().isEqualTo(CoCacheProperties.EvictionCheck.FAIL)
                properties.redis.toOptions().channelMode.assert().isEqualTo(
                    me.ahoo.cocache.redis.RedisChannelMode.SHARDED
                )
            }
        runner.withUserConfiguration(InMemoryConfig::class.java).run { context ->
            val properties = context.getBean(CoCacheProperties::class.java)
            properties.redis.commandTimeout.assert().isEqualTo(Duration.ofSeconds(2))
            properties.redis.heartbeat.assert().isEqualTo(Duration.ofSeconds(5))
            properties.redis.invalidateBatchSize.assert().isEqualTo(512)
            properties.redis.channelMode.assert().isEqualTo(me.ahoo.cocache.redis.RedisChannelMode.PUBSUB)
            properties.defaults.assert().isEqualTo(policyProperties())
            properties.caches.assert().isEmpty()
        }
    }

    @Test
    fun cacheNamesInPropertiesAreCaseInsensitive() {
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues("cocache.caches.USER.ttl=45", "cocache.caches.Profile.ttl=46")
            .run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(CoCacheProperties::class.java)
                properties.policyFor("user", CachePolicy.DEFAULT).ttl.assert().isEqualTo(Duration.ofSeconds(45))
                properties.policyFor("profile", CachePolicy.DEFAULT).ttl.assert().isEqualTo(Duration.ofSeconds(46))
            }
        // 精确匹配优先
        CoCacheProperties(
            true,
            policyProperties(),
            mapOf(
                "user" to policyProperties(ttl = Duration.ofSeconds(1)),
                "USER" to policyProperties(ttl = Duration.ofSeconds(2))
            ),
            CoCacheProperties.Redis(
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                512,
                null,
                me.ahoo.cocache.redis.RedisChannelMode.PUBSUB,
                CoCacheProperties.EvictionCheck.WARN
            ),
            CoCacheProperties.Backend.REDIS,
            CoCacheProperties.SpringCache(false, null),
            null
        ).policyFor("USER", CachePolicy.DEFAULT).ttl.assert().isEqualTo(Duration.ofSeconds(2))
    }

    @Test
    fun lazyCachesCountAsDeclared() {
        lazyCaches.withPropertyValues("cocache.caches.lazyuser.ttl=30").run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(CacheRuntime::class.java).cacheNames.assert().containsExactly("eager")
            context.getBean(LazyUserCache::class.java).name.assert().isEqualTo("LazyUser")
        }
        lazyCaches.withPropertyValues("cocache.caches.lazy-usr.ttl=30").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasMessageContaining("undeclared caches [lazy-usr]")
                .hasMessageContaining("LazyUser")
        }
    }

    @Test
    fun removedPropertiesFailStartupWithReplacement() {
        val cases = mapOf(
            "cocache.redis.strict-failure=true" to "cocache.defaults.read-failure=throw",
            "cocache.load.max-concurrent=8" to "cocache.defaults.load-max-concurrent",
            "cocache.load.acquire-timeout=1s" to "cocache.defaults.load-acquire-timeout",
            "cocache.load.other=1" to "cocache.defaults.load-*",
            "cocache.redis.eviction-channel=sharded" to "cocache.redis.channel-mode",
            "cocache.redis.missing-guard-sentinel=x" to "no replacement",
            "cocache.metrics.client-side-hits=true" to "cocache.metrics.local-hits",
            "cocache.next.enabled=true" to "cocache.next.* became cocache.*",
        )
        cases.forEach { (property, replacement) ->
            runner.withUserConfiguration(InMemoryConfig::class.java).withPropertyValues(property).run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining(property.substringBefore('='))
                    .hasStackTraceContaining(replacement)
            }
        }
        // 环境变量形式同样被识别
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    org.springframework.core.env.SystemEnvironmentPropertySource(
                        "test-systemEnvironment",
                        mapOf<String, Any>("COCACHE_REDIS_STRICTFAILURE" to "true")
                    )
                )
            }
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining("cocache.redis.strictfailure")
            }
        RemovedProperties.adviceFor(
            org.springframework.boot.context.properties.source.ConfigurationPropertyName.of(
                "cocache.redis.channel-mode"
            )
        ).assert().isNull()
    }

    @Test
    fun autoConfigurationRunsBeforeSpringCacheAutoConfiguration() {
        val metadata = java.util.Properties()
        javaClass.classLoader.getResources("META-INF/spring-autoconfigure-metadata.properties").asSequence()
            .forEach { url -> url.openStream().use { metadata.load(it) } }
        metadata.getProperty("${CoCacheAutoConfiguration::class.java.name}.AutoConfigureBefore").assert()
            .isEqualTo("org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration")
    }

    @Test
    fun configurationMetadataListsAllProperties() {
        // 测试源码集的 kapt 也会生成一份元数据，合并所有副本再检查
        val text = javaClass.classLoader.getResources("META-INF/spring-configuration-metadata.json").asSequence()
            .joinToString("\n") { it.readText() }
        listOf(
            "\"cocache.enabled\"",
            "\"cocache.defaults.ttl\"",
            "\"cocache.defaults.read-failure\"",
            "\"cocache.redis.channel-mode\"",
            "\"cocache.redis.command-timeout\"",
            "\"cocache.redis.strict-failure\"",
            "\"cocache.backend\"",
            "\"cocache.defaults.key-prefix\"",
            "\"cocache.spring-cache.require-sync\"",
            "\"cocache.spring-cache.retrieve-executor\"",
            "\"cocache.refresh-executor\"",
            "\"spring-bean-reference\"",
            "\"management.health.cocache.enabled\"",
            "\"level\": \"error\"",
        ).forEach { text.assert().contains(it) }
    }

    @Test
    fun specCacheWithoutBackendExplainsTheCause() {
        runner.withUserConfiguration(NoBackendSpecConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasStackTraceContaining(
                "Cannot create cache[orphan] - no CoherenceBackend is available"
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    class NoBackendSpecConfig {
        @Bean
        fun orphanSpec(): CacheSpec<String, String> = cacheSpec("orphan") { codec = StringCodec }
    }

    @Suppress("LongParameterList")
    private fun policyProperties(
        ttl: Duration? = null,
        ttlForever: Boolean? = null,
        ttlJitter: Duration? = null,
        absentTtl: Duration? = null,
        localMaxAge: Duration? = null,
        degradedLocalMaxAge: Duration? = null,
        refreshWindow: Duration? = null,
        leaseTtl: Duration? = null,
        loadAcquireTimeout: Duration? = null,
        loadAsyncTimeout: Duration? = null,
    ) = CoCacheProperties.PolicyProperties(
        ttl,
        ttlForever,
        ttlJitter,
        absentTtl,
        localMaxAge,
        degradedLocalMaxAge,
        refreshWindow,
        leaseTtl,
        null,
        null,
        loadAcquireTimeout,
        null,
        null,
        null,
        null,
        loadAsyncTimeout
    )

    /**
     * 只覆盖设置了的 load 字段：其余沿用下层的值（此前重建 `LoadPolicy` 时 `asyncTimeout` 被重置为默认值）。
     */
    @Test
    fun loadPropertiesKeepUnsetFieldsOfTheLowerLayer() {
        val base = CachePolicy.DEFAULT.toBuilder()
            .load(me.ahoo.cocache.core.policy.LoadPolicy(maxConcurrent = 2, asyncTimeout = Duration.ofSeconds(3)))
            .build()
        val acquire = policyProperties(loadAcquireTimeout = Duration.ofMillis(5)).applyTo(base).load
        acquire.acquireTimeout.assert().isEqualTo(Duration.ofMillis(5))
        acquire.maxConcurrent.assert().isEqualTo(2)
        acquire.asyncTimeout.assert().isEqualTo(Duration.ofSeconds(3))
        val async = policyProperties(loadAsyncTimeout = Duration.ofSeconds(9)).applyTo(base).load
        async.asyncTimeout.assert().isEqualTo(Duration.ofSeconds(9))
        async.maxConcurrent.assert().isEqualTo(2)
        async.acquireTimeout.assert().isEqualTo(base.load.acquireTimeout)
    }

    @Test
    fun removedPropertiesCheckSkipsNonEnumerableSources() {
        val environment = org.springframework.core.env.StandardEnvironment()
        environment.propertySources.addFirst(
            object : org.springframework.core.env.PropertySource<Any>("opaque", Any()) {
                override fun getProperty(name: String): Any? = null
            }
        )
        RemovedProperties.check(environment)
    }

    @Test
    fun missingComponentWithoutEnvironment() {
        runCatching {
            CacheBeanDefinitions.requireComponent(
                org.springframework.beans.factory.support.DefaultListableBeanFactory(),
                CacheAssembler::class.java,
                "x"
            )
        }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot create cache[x] - no CoherenceBackend is available")
    }
}
