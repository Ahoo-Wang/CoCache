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
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ResolvableType
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

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
        fun profile(): CacheSpec<String, String> = cacheSpec("profile") {
            codec = StringCodec
            externallySourced()
        }

        @Bean
        fun indexSpec(): SetStoreSpec<String, String> = setStoreSpec("index") { elementCodec = StringCodec }
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

            override fun customize(spec: SetStoreSpec.Builder<*, *>) {
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
        fun rawSpec(): CacheSpec<*, *> = cacheSpec<String, String>("raw") {
            codec = StringCodec
            externallySourced()
        }
    }

    /**
     * `FactoryBean` 提供的五种 spec：泛型取自工厂的产品类型（`FactoryBean<StoreSpec<String, String>>`）。
     */
    @Configuration(proxyBeanMethods = false)
    class FactoryBeanSpecConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun pageSpec(): FactoryBean<CacheSpec<String, String>> = specFactory(
            cacheSpec("page") {
                codec = StringCodec
                loader = CacheLoader { "page-$it" }
            }
        )

        @Bean
        fun viewSpec(): FactoryBean<VersionedCacheSpec<String, String>> = specFactory(
            versionedCacheSpec("view") {
                codec = StringCodec
                versionedLoader = CacheLoader { Versioned("view-$it", 1) }
            }
        )

        @Bean
        fun recordsSpec(): FactoryBean<StoreSpec<String, String>> = specFactory(
            storeSpec("records") { codec = StringCodec }
        )

        @Bean
        fun ledgerSpec(): FactoryBean<VersionedStoreSpec<String, String>> = specFactory(
            versionedStoreSpec("ledger") { codec = StringCodec }
        )

        @Bean
        fun tagsSpec(): FactoryBean<SetStoreSpec<String, String>> = specFactory(
            setStoreSpec("tags") { elementCodec = StringCodec }
        )
    }

    @Configuration(proxyBeanMethods = false)
    class RawFactoryBeanSpecConfig {
        @Bean
        fun backend(): CoherenceBackend = InMemoryBackend()

        /**
         * 产品类型可知（`StoreSpec`）但没有具体的类型参数：与直接声明的 `StoreSpec<*, *>` 一样启动失败。
         * 原始的 `FactoryBean<*>` 不创建工厂就无从得知产品，不算 spec 声明（与此前相同）。
         */
        @Bean
        fun rawSpec(): FactoryBean<StoreSpec<*, *>> = specFactory(
            storeSpec<String, String>("raw") { codec = StringCodec }
        )
    }

    @Configuration(proxyBeanMethods = false)
    class ConflictConfig {
        @Bean
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            externallySourced()
        }

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
            val index = context.getBean("index") as SetStore<String, String>
            index.add("k", listOf("a"))
            index["k"].assert().containsExactly("a")
            context.getBeanNamesForType(
                ResolvableType.forClassWithGenerics(SetStore::class.java, String::class.java, String::class.java)
            )
                .toList().assert().containsExactly("index")
            context.getBean(
                CacheRuntime::class.java
            ).cacheNames.assert().containsExactlyInAnyOrder("user", "profile", "index")
            assertThat(context).hasSingleBean(CacheManager::class.java)
            (
                context.getBean(
                    CacheManager::class.java
                ) is me.ahoo.cocache.spring.tx.TransactionAwareSpringCacheManager
                ).assert().isTrue()
            context.getBean(CacheManager::class.java).getCache("user")?.get("2")?.get().assert().isEqualTo("user-2")
        }
    }

    /**
     * 配置文件逐字段叠加在声明之上：`cocache.defaults.cache.*` 只填声明没有写出的字段，`cocache.caches.[name].*` 设定字段。
     */
    @Test
    fun propertiesLayerOverDeclarationsByField() {
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues(
                "cocache.defaults.cache.ttl=2m",
                "cocache.defaults.cache.local-maximum-size=50",
                "cocache.caches.user.ttl=PT30S",
                "cocache.caches.user.ttl-jitter=0s",
                "cocache.caches.user.lease-wait=1s",
                "cocache.caches.user.load-max-concurrent=3",
                "cocache.caches.user.load-timeout=7s",
                "cocache.caches.user.invalidate-failure=throw",
                "cocache.caches.user.read-failure=throw",
                "cocache.caches.index.ttl=forever",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean("user")
                context.getBean("profileCache")
                context.getBean("index")
                val runtime = context.getBean(CacheRuntime::class.java)
                val user = requireNotNull(runtime.policy("user"))
                user.ttl.assert().isEqualTo(Duration.ofSeconds(30))
                user.ttlJitter.assert().isEqualTo(Duration.ZERO)
                user.lease.wait.assert().isEqualTo(Duration.ofSeconds(1))
                user.lease.ttl.assert().isEqualTo(DefaultPolicies.CACHE.lease.ttl)
                user.load.maxConcurrent.assert().isEqualTo(3)
                user.load.timeout.assert().isEqualTo(Duration.ofSeconds(7))
                user.local.maximumSize.assert().isEqualTo(50)
                user.invalidateFailure.assert().isEqualTo(InvalidateFailureMode.THROW)
                user.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
                requireNotNull(runtime.policy("profile")).ttl.assert().isEqualTo(Duration.ofMinutes(2))
                // cache 一节不作用于权威副本
                requireNotNull(runtime.policy("index")).ttl.assert().isNull()
                requireNotNull(runtime.policy("index")).local.maximumSize
                    .assert().isEqualTo(DefaultPolicies.SET_STORE.local.maximumSize)
            }
    }

    /**
     * 每个配置键都解析为对应字段的取值并写进生效策略（`VersionedCache` 适用全部策略字段）。
     */
    @Test
    fun everyPolicyPropertyIsApplied() {
        val path = "cocache.caches.view"
        val properties = bind(
            "$path.ttl" to "3m",
            "$path.ttl-jitter" to "1s",
            "$path.absent-ttl" to "2s",
            "$path.local-max-age" to "1m",
            "$path.degraded-local-max-age" to "3s",
            "$path.tombstone-ttl" to "1h",
            "$path.refresh-window" to "30s",
            "$path.lease-ttl" to "4s",
            "$path.lease-wait" to "250ms",
            "$path.load-max-concurrent" to "6",
            "$path.load-acquire-timeout" to "5ms",
            "$path.load-timeout" to "9s",
            "$path.read-timeout" to "800ms",
            "$path.local-maximum-size" to "77",
            "$path.invalidate-failure" to "throw",
            "$path.read-failure" to "throw",
        )
        val entries = requireNotNull(properties.cacheProperties("view")).second.entries(path)
        entries.map { it.field }.assert().containsExactlyElementsOf(PolicyField.POLICY_FIELDS)
        entries.first().key.assert().isEqualTo("$path.ttl")
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            val spec = versionedCacheSpec<String, String>("view") {
                codec = StringCodec
                versionedLoader = CacheLoader { null }
            }
            val policy = CacheAssembler(runtime, emptyList(), properties).apply(spec, Locks.NONE).spec.policy
            policy.ttl.assert().isEqualTo(Duration.ofMinutes(3))
            policy.ttlJitter.assert().isEqualTo(Duration.ofSeconds(1))
            policy.absentTtl.assert().isEqualTo(Duration.ofSeconds(2))
            policy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(1))
            policy.degradedLocalMaxAge.assert().isEqualTo(Duration.ofSeconds(3))
            policy.tombstoneTtl.assert().isEqualTo(Duration.ofHours(1))
            policy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(30))
            policy.lease.ttl.assert().isEqualTo(Duration.ofSeconds(4))
            policy.lease.wait.assert().isEqualTo(Duration.ofMillis(250))
            policy.load.maxConcurrent.assert().isEqualTo(6)
            policy.load.acquireTimeout.assert().isEqualTo(Duration.ofMillis(5))
            policy.load.timeout.assert().isEqualTo(Duration.ofSeconds(9))
            policy.readTimeout.assert().isEqualTo(Duration.ofMillis(800))
            policy.local.maximumSize.assert().isEqualTo(77)
            policy.invalidateFailure.assert().isEqualTo(InvalidateFailureMode.THROW)
            policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        }
        val options = bind(
            "cocache.redis.command-timeout" to "1s",
            "cocache.redis.heartbeat" to "2s",
            "cocache.redis.invalidate-batch-size" to "8",
            "cocache.redis.client-name" to "x",
            "cocache.redis.channel-mode" to "sharded",
        ).redis.toOptions()
        options.commandTimeout.assert().isEqualTo(Duration.ofSeconds(1))
        options.heartbeat.assert().isEqualTo(Duration.ofSeconds(2))
        options.invalidateBatchSize.assert().isEqualTo(8)
        options.clientName.assert().isEqualTo("x")
        options.channelMode.assert().isEqualTo(me.ahoo.cocache.redis.RedisChannelMode.SHARDED)
    }

    /**
     * 每一节在绑定时解析一次（#908 §2.3）：分层时每个缓存取同一份条目，`caches.[name]` 按名称不区分大小写查找；
     * `command-timeout` 只解析一次，后端选项与关闭超时都用它。
     */
    @Test
    fun sectionsAreParsedOnceAtBinding() {
        val properties = bind(
            "cocache.defaults.cache.ttl" to "60s",
            "cocache.overrides.store.local-max-age" to "10s",
            "cocache.caches.User.ttl" to "30s",
            "cocache.redis.command-timeout" to "3s",
        )
        val defaults = properties.defaultsSection(store = false)
        defaults.path.assert().isEqualTo("cocache.defaults.cache")
        defaults.entries.map { it.key }.assert().containsExactly("cocache.defaults.cache.ttl")
        properties.defaultsSection(store = false).entries.assert().isSameAs(defaults.entries)
        properties.defaultsSection(store = true).entries.assert().isEmpty()
        properties.overridesSection(store = true).entries.map { it.key }
            .assert().containsExactly("cocache.overrides.store.local-max-age")
        properties.overridesSection(store = false).path.assert().isEqualTo("cocache.overrides.cache")
        val user = requireNotNull(properties.cacheSection("user"))
        user.path.assert().isEqualTo("cocache.caches.User")
        properties.cacheSection("User").assert().isSameAs(user)
        properties.cacheSection("order").assert().isNull()
        properties.redis.commandTimeoutDuration.assert().isEqualTo(Duration.ofSeconds(3))
        properties.redis.toOptions().commandTimeout.assert().isEqualTo(Duration.ofSeconds(3))
        CoCacheAutoConfiguration.closeTimeoutOf(properties.redis).assert().isEqualTo(Duration.ofSeconds(6))
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
                    Suspending.await {
                        backend.store(
                            Keyspace("custom:", "user")
                        ).read("custom:user:k", null, null, null)
                    } is me.ahoo.cocache.api.spi.L1Read.Hit
                    )
                    .assert().isTrue()
                @Suppress("UNCHECKED_CAST")
                val index = context.getBean("index") as SetStore<String, String>
                index.add("k", listOf("a"))
                (
                    Suspending.await {
                        backend.store(
                            Keyspace("custom-set:", "index")
                        ).read("custom-set:index:k", null, null, null)
                    } is me.ahoo.cocache.api.spi.L1Read.Hit
                    )
                    .assert().isTrue()
                @Suppress("UNCHECKED_CAST")
                val profile = context.getBean("profileCache") as Cache<String, String>
                profile.put("k", "v")
                (
                    Suspending.await {
                        backend.store(
                            Keyspace("cocache:", "profile")
                        ).read("cocache:profile:k", null, null, null)
                    } is me.ahoo.cocache.api.spi.L1Read.Hit
                    )
                    .assert().isTrue()
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
    fun registersCachesForFactoryBeanSpecs() {
        runner.withUserConfiguration(FactoryBeanSpecConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            fun namesOf(type: Class<*>): List<String> {
                return context.getBeanNamesForType(
                    ResolvableType.forClassWithGenerics(type, String::class.java, String::class.java)
                ).toList()
            }
            namesOf(Cache::class.java).assert().containsExactly("page")
            namesOf(VersionedCache::class.java).assert().containsExactly("view")
            namesOf(Store::class.java).assert().containsExactly("records")
            namesOf(VersionedStore::class.java).assert().containsExactly("ledger")
            namesOf(SetStore::class.java).assert().containsExactly("tags")
            @Suppress("UNCHECKED_CAST")
            (context.getBean("page") as Cache<String, String>)["a"].assert().isEqualTo("page-a")
            @Suppress("UNCHECKED_CAST")
            (context.getBean("view") as VersionedCache<String, String>).getVersioned("a").assert()
                .isEqualTo(Versioned("view-a", 1))
            @Suppress("UNCHECKED_CAST")
            val records = context.getBean("records") as Store<String, String>
            records.put("k", "v")
            records["k"].assert().isEqualTo("v")
            @Suppress("UNCHECKED_CAST")
            val ledger = context.getBean("ledger") as VersionedStore<String, String>
            ledger.putIfNewer("k", "v", 2)
            ledger.getVersioned("k").assert().isEqualTo(Versioned("v", 2))
            @Suppress("UNCHECKED_CAST")
            val tags = context.getBean("tags") as SetStore<String, String>
            tags.add("k", listOf("a"))
            tags["k"].assert().containsExactly("a")
            context.getBean(CacheRuntime::class.java).cacheNames.assert()
                .containsExactlyInAnyOrder("page", "view", "records", "ledger", "tags")
        }
    }

    @Test
    fun rawFactoryBeanSpecFailsStartup() {
        runner.withUserConfiguration(RawFactoryBeanSpecConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Cache spec bean[rawSpec] must declare its generic types")
        }
    }

    /**
     * 注册时只读 bean 定义：`FactoryBean` 声明的工厂不被创建，产品也不被取出。
     */
    @Test
    fun registeringAFactoryBeanSpecCreatesNeitherTheFactoryNorTheSpec() {
        val created = AtomicInteger()
        val beanFactory = DefaultListableBeanFactory()
        val specType = ResolvableType.forClassWithGenerics(
            StoreSpec::class.java,
            String::class.java,
            String::class.java
        )
        beanFactory.registerBeanDefinition(
            "recordsSpec",
            RootBeanDefinition(FactoryBean::class.java).apply {
                setTargetType(ResolvableType.forClassWithGenerics(FactoryBean::class.java, specType))
                instanceSupplier = Supplier {
                    created.incrementAndGet()
                    specFactory(storeSpec<String, String>("records") { codec = StringCodec })
                }
            }
        )
        CacheSpecRegistrar().postProcessBeanFactory(beanFactory)
        val registered = beanFactory.getMergedBeanDefinition("records")
        CacheBeanDefinitions.specBeanOf(registered).assert().isEqualTo("recordsSpec")
        registered.resolvableType.resolve().assert().isEqualTo(Store::class.java)
        registered.resolvableType.generics.map { it.resolve() }.assert().containsExactly(
            String::class.java,
            String::class.java
        )
        created.get().assert().isZero()
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

    @me.ahoo.cocache.api.annotation.CoCache(
        name = "LazyUser",
        source = me.ahoo.cocache.api.annotation.CoCache.Source.EXTERNAL
    )
    interface LazyUserCache : Cache<String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [LazyUserCache::class])
    class LazyCacheConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun eagerSpec(): CacheSpec<String, String> = cacheSpec("eager") {
            codec = StringCodec
            externallySourced()
        }
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
    fun configurationPropertiesBindWithUnits() {
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues(
                "cocache.key-prefix=app:",
                "cocache.defaults.cache.ttl=120s",
                "cocache.defaults.cache.lease-wait=500ms",
                "cocache.defaults.cache.local-max-age=PT2M",
                "cocache.defaults.store.local-max-age=20s",
                "cocache.overrides.cache.absent-ttl=30s",
                "cocache.caches.user.refresh-window=30s",
                "cocache.caches.user.ttl-jitter=0s",
                "cocache.caches-validation=warn",
                "cocache.redis.command-timeout=1500ms",
                "cocache.redis.channel-mode=sharded",
                "cocache.redis.store-eviction-check=fail",
                "cocache.redis.assume-maxmemory-policy=volatile-lru",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(CoCacheProperties::class.java)
                properties.enabled.assert().isTrue()
                properties.keyPrefix.assert().isEqualTo("app:")
                properties.defaults.cache.ttl.assert().isEqualTo("120s")
                properties.defaults.cache.leaseWait.assert().isEqualTo("500ms")
                properties.defaults.cache.localMaxAge.assert().isEqualTo("PT2M")
                properties.defaults.store.localMaxAge.assert().isEqualTo("20s")
                properties.overrides.cache.absentTtl.assert().isEqualTo("30s")
                properties.cachesValidation.assert().isEqualTo(CoCacheProperties.Validation.WARN)
                requireNotNull(properties.cacheProperties("user")).second.refreshWindow.assert().isEqualTo("30s")
                properties.redis.commandTimeout.assert().isEqualTo("1500ms")
                properties.redis.toOptions().commandTimeout.assert().isEqualTo(Duration.ofMillis(1500))
                properties.redis.channelMode.assert().isEqualTo(me.ahoo.cocache.redis.RedisChannelMode.SHARDED)
                properties.redis.storeEvictionCheck.assert().isEqualTo(CoCacheProperties.EvictionCheck.FAIL)
                properties.redis.assumeMaxmemoryPolicy.assert().isEqualTo("volatile-lru")
                properties.redis.toOptions().channelMode.assert().isEqualTo(
                    me.ahoo.cocache.redis.RedisChannelMode.SHARDED
                )
                val user = requireNotNull(context.getBean(CacheRuntime::class.java).policy("user"))
                user.ttl.assert().isEqualTo(Duration.ofSeconds(120))
                user.lease.wait.assert().isEqualTo(Duration.ofMillis(500))
                user.localMaxAge.assert().isEqualTo(Duration.ofMinutes(2))
                user.absentTtl.assert().isEqualTo(Duration.ofSeconds(30))
                user.refreshWindow.assert().isEqualTo(Duration.ofSeconds(30))
                user.ttlJitter.assert().isEqualTo(Duration.ZERO)
                requireNotNull(context.getBean(CacheRuntime::class.java).policy("index")).localMaxAge
                    .assert().isEqualTo(Duration.ofSeconds(20))
            }
    }

    @Test
    fun configurationPropertiesDefaults() {
        runner.withUserConfiguration(InMemoryConfig::class.java).run { context ->
            val properties = context.getBean(CoCacheProperties::class.java)
            properties.keyPrefix.assert().isNull()
            properties.redis.toOptions().commandTimeout.assert().isEqualTo(Duration.ofSeconds(2))
            properties.redis.toOptions().heartbeat.assert().isEqualTo(Duration.ofSeconds(5))
            properties.redis.invalidateBatchSize.assert().isEqualTo(512)
            properties.redis.channelMode.assert().isEqualTo(me.ahoo.cocache.redis.RedisChannelMode.PUBSUB)
            properties.cachesValidation.assert().isEqualTo(CoCacheProperties.Validation.FAIL)
            properties.defaults.entries("cocache.defaults").assert().isEmpty()
            properties.overrides.entries("cocache.overrides").assert().isEmpty()
            properties.defaults.cache.unlock.assert().isEmpty()
            properties.caches.assert().isEmpty()
        }
    }

    @Test
    fun cacheNamesInPropertiesAreCaseInsensitive() {
        runner.withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues("cocache.caches.USER.ttl=45s", "cocache.caches.Profile.ttl=46s")
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean("user")
                context.getBean("profileCache")
                val runtime = context.getBean(CacheRuntime::class.java)
                requireNotNull(runtime.policy("user")).ttl.assert().isEqualTo(Duration.ofSeconds(45))
                requireNotNull(runtime.policy("profile")).ttl.assert().isEqualTo(Duration.ofSeconds(46))
            }
        // 精确匹配优先，返回配置中的名称
        val properties = bind("cocache.caches[user].ttl" to "1s", "cocache.caches[USER].ttl" to "2s")
        requireNotNull(properties.cacheProperties("USER")).let { (name, entry) ->
            name.assert().isEqualTo("USER")
            entry.ttl.assert().isEqualTo("2s")
        }
        requireNotNull(properties.cacheProperties("User")).first.assert().isIn("user", "USER")
        properties.cacheProperties("other").assert().isNull()
    }

    @Test
    fun lazyCachesCountAsDeclared() {
        lazyCaches.withPropertyValues("cocache.caches.lazyuser.ttl=30s").run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(CacheRuntime::class.java).cacheNames.assert().containsExactly("eager")
            context.getBean(LazyUserCache::class.java).name.assert().isEqualTo("LazyUser")
        }
        lazyCaches.withPropertyValues("cocache.caches.lazy-usr.ttl=30s").run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasMessageContaining("undeclared caches [lazy-usr]")
                .hasMessageContaining("LazyUser")
        }
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
            "\"cocache.key-prefix\"",
            "\"cocache.defaults.cache.ttl\"",
            "\"cocache.defaults.store.local-max-age\"",
            "\"cocache.overrides.cache.read-failure\"",
            "\"cocache.overrides.store.absent-ttl\"",
            "\"cocache.caches-validation\"",
            "\"cocache.redis.channel-mode\"",
            "\"cocache.redis.command-timeout\"",
            "\"cocache.backend\"",
            "\"cocache.spring-cache.require-sync\"",
            "\"cocache.refresh-executor\"",
            "\"spring-bean-reference\"",
            "\"management.health.cocache.enabled\"",
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
        fun orphanSpec(): CacheSpec<String, String> = cacheSpec("orphan") {
            codec = StringCodec
            externallySourced()
        }
    }

    private fun bind(vararg entries: Pair<String, String>): CoCacheProperties =
        Binder(MapConfigurationPropertySource(mapOf(*entries)))
            .bindOrCreate(CoCacheProperties.PREFIX, CoCacheProperties::class.java)

    /**
     * 只覆盖设置了的 load 字段：其余沿用下层的值（字段级合并，`load-*` 三个键各是一个字段）。
     */
    @Test
    fun loadPropertiesKeepUnsetFieldsOfTheLowerLayer() {
        val spec = cacheSpec<String, String>("user") {
            codec = StringCodec
            loader = CacheLoader { it }
            policy { load = LoadPolicy(maxConcurrent = 2, timeout = Duration.ofSeconds(3)) }
        }
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
            fun loadWith(key: String, value: String): LoadPolicy =
                CacheAssembler(runtime, emptyList(), bind(key to value)).apply(spec, Locks.NONE).spec.policy.load
            val acquire = loadWith("cocache.caches.user.load-acquire-timeout", "5ms")
            acquire.acquireTimeout.assert().isEqualTo(Duration.ofMillis(5))
            acquire.maxConcurrent.assert().isEqualTo(2)
            acquire.timeout.assert().isEqualTo(Duration.ofSeconds(3))
            val timeout = loadWith("cocache.caches.user.load-timeout", "9s")
            timeout.timeout.assert().isEqualTo(Duration.ofSeconds(9))
            timeout.maxConcurrent.assert().isEqualTo(2)
            timeout.acquireTimeout.assert().isEqualTo(spec.policy.load.acquireTimeout)
        }
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

    companion object {
        /**
         * 提供既有 spec 的 `FactoryBean`：bean 方法的返回类型声明产品的泛型，`getObjectType` 只是原始类型。
         */
        fun <T : Any> specFactory(spec: T): FactoryBean<T> = object : FactoryBean<T> {
            override fun getObject(): T = spec

            override fun getObjectType(): Class<*> = spec.javaClass
        }
    }
}
