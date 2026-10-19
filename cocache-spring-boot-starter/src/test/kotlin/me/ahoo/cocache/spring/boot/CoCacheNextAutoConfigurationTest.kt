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

class CoCacheNextAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheNextAutoConfiguration::class.java))
        .withPropertyValues("cocache.next.enabled=true")

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
    fun disabledByDefault() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheNextAutoConfiguration::class.java))
            .withUserConfiguration(InMemoryConfig::class.java)
            .run { context ->
                assertThat(context).doesNotHaveBean(CacheRuntime::class.java)
                assertThat(context).doesNotHaveBean("user")
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
                "cocache.next.defaults.ttl=PT2M",
                "cocache.next.defaults.local-maximum-size=50",
                "cocache.next.caches.user.ttl=PT30S",
                "cocache.next.caches.user.ttl-jitter=PT0S",
                "cocache.next.caches.user.lease-wait=PT1S",
                "cocache.next.caches.user.load-max-concurrent=3",
                "cocache.next.caches.user.invalidate-failure=throw",
                "cocache.next.caches.user.read-failure=throw",
                "cocache.next.caches.index.ttl-forever=true",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean("user")
                context.getBean("profileCache")
                context.getBean("index")
                val properties = context.getBean(CoCacheNextProperties::class.java)
                val user = properties.policyFor("user", CachePolicy.DEFAULT)
                user.ttl.assert().isEqualTo(Duration.ofSeconds(30))
                user.ttlJitter.assert().isEqualTo(Duration.ZERO)
                user.lease.wait.assert().isEqualTo(Duration.ofSeconds(1))
                user.lease.ttl.assert().isEqualTo(CachePolicy.DEFAULT.lease.ttl)
                user.load.maxConcurrent.assert().isEqualTo(3)
                user.local.maximumSize.assert().isEqualTo(50)
                user.invalidateFailure.assert().isEqualTo(InvalidateFailureMode.THROW)
                user.readFailure.assert().isEqualTo(me.ahoo.cocache.core.policy.ReadFailureMode.THROW)
                properties.policyFor("profile", CachePolicy.DEFAULT).ttl.assert().isEqualTo(Duration.ofMinutes(2))
                properties.policyFor("index", CachePolicy.DEFAULT).ttl.assert().isNull()
            }
    }

    @Test
    fun everyPolicyPropertyIsApplied() {
        val properties = CoCacheNextProperties.PolicyProperties(
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
        CoCacheNextProperties.PolicyProperties(
            ttlForever = false
        ).applyTo(CachePolicy.DEFAULT).ttl.assert().isEqualTo(CachePolicy.DEFAULT.ttl)
        CoCacheNextProperties.Redis(clientName = "x").toOptions().clientName.assert().isEqualTo("x")
    }

    @Test
    fun customizersApplyBeforeProperties() {
        runner.withUserConfiguration(InMemoryConfig::class.java, CustomizerConfig::class.java)
            .withPropertyValues("cocache.next.caches.user.absent-ttl=PT9S")
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
            .withPropertyValues("cocache.next.caches.usr.ttl=PT1M")
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
                AutoConfigurations.of(DataRedisAutoConfiguration::class.java, CoCacheNextAutoConfiguration::class.java)
            )
            .withPropertyValues("cocache.next.enabled=true", "cocache.next.redis.client-name=cocache-starter-test")
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
}
