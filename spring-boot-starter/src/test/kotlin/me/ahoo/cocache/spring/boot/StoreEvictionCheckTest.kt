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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.redis.RedisChannelMode
import me.ahoo.cocache.redis.StoreEvictionRisk
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

class StoreEvictionCheckTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()

    @AfterEach
    fun destroy() = runtime.close()

    private fun redisWith(policies: Map<String, String>?): RedisBackend {
        val redis = redisBackendMock()
        every { redis.maxmemoryPolicies() } returns policies
        return redis
    }

    private fun runtimeOn(backend: CoherenceBackend): CacheRuntime {
        val runtime = mockk<CacheRuntime>(relaxed = true)
        every { runtime.backend } returns backend
        return runtime
    }

    private fun check(
        backend: CoherenceBackend,
        mode: CoCacheProperties.EvictionCheck,
        assumed: String? = null,
        setCaches: Map<String, Duration?> = mapOf("index" to null),
    ) = StoreEvictionCheck(runtimeOn(backend), mode, assumed) { setCaches }

    private fun withSetStore() = runtime.create(setStoreSpec<String, String>("index") { elementCodec = StringCodec })

    private fun warnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(StoreEvictionCheck::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    private fun failure(block: () -> Unit): Throwable? = runCatching(block).exceptionOrNull()

    /**
     * `allkeys-*` 可能驱逐任何 key，权威缓存的唯一副本无法恢复：无论 `store-eviction-check` 是什么都启动失败（S22）。
     */
    @Test
    fun allKeysPolicyFailsInEveryMode() {
        val backend = redisWith(mapOf("10.0.0.1:6379" to "allkeys-lru", "10.0.0.2:6379" to "noeviction"))
        CoCacheProperties.EvictionCheck.entries.forEach { mode ->
            failure { check(backend, mode).afterSingletonsInstantiated() }.assert()
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("[index]")
                .hasMessageContaining("allkeys-lru")
                .hasMessageContaining("10.0.0.1:6379")
                .hasMessageNotContaining("10.0.0.2")
        }
        // ttl 有限的权威缓存同样
        failure {
            check(
                redisWith(mapOf("redis" to "allkeys-lfu")),
                CoCacheProperties.EvictionCheck.OFF,
                setCaches = mapOf("recent" to Duration.ofHours(1))
            ).afterSingletonsInstantiated()
        }.assert().isInstanceOf(IllegalStateException::class.java).hasMessageContaining("allkeys-lfu")
    }

    @Test
    fun safePoliciesPass() {
        warnings {
            check(
                redisWith(mapOf("redis" to "noeviction")),
                CoCacheProperties.EvictionCheck.FAIL
            ).afterSingletonsInstantiated()
            // 没有 ttl 的集合不会被 volatile-* 驱逐
            check(
                redisWith(mapOf("redis" to "volatile-lru")),
                CoCacheProperties.EvictionCheck.FAIL
            ).afterSingletonsInstantiated()
            // ttl 有限的集合在 noeviction 下同样安全
            check(
                redisWith(mapOf("redis" to "noeviction")),
                CoCacheProperties.EvictionCheck.FAIL,
                setCaches = mapOf("index" to Duration.ofDays(1))
            ).afterSingletonsInstantiated()
        }.assert().isEmpty()
    }

    /**
     * `store-eviction-check` 只管 `volatile-*` 且有 ttl 有限的权威缓存（它们可能在到期前被驱逐）：只列出 ttl 有限的缓存，
     * `warn`（默认）告警，`fail` 启动失败，`off` 不检查。
     */
    @Test
    fun volatilePolicyWithAFiniteTtlFollowsTheMode() {
        val backend = redisWith(mapOf("10.0.0.1:6379" to "volatile-ttl"))
        val setCaches = mapOf("index" to null, "recent" to Duration.ofHours(1))
        warnings {
            check(backend, CoCacheProperties.EvictionCheck.WARN, setCaches = setCaches).afterSingletonsInstantiated()
        }.single().assert().contains("[recent]").contains("volatile-ttl").doesNotContain("index")
        failure {
            check(backend, CoCacheProperties.EvictionCheck.FAIL, setCaches = setCaches).afterSingletonsInstantiated()
        }.assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("[recent]")
            .hasMessageContaining("store-eviction-check=off")
        warnings {
            check(backend, CoCacheProperties.EvictionCheck.OFF, setCaches = setCaches).afterSingletonsInstantiated()
        }.assert().isEmpty()
    }

    /**
     * 读不到策略（Redis 用户没有 INFO 权限）又没有配置假定值：无论 `store-eviction-check` 是什么都启动失败（S22）；
     * 配置了假定值则按它判断，读得到时以实际的值为准。
     */
    @Test
    fun unreadablePolicyFailsUnlessAssumed() {
        val unreadable = redisWith(null)
        CoCacheProperties.EvictionCheck.entries.forEach { mode ->
            failure { check(unreadable, mode).afterSingletonsInstantiated() }.assert()
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("[index]")
                .hasMessageContaining("INFO memory")
                .hasMessageContaining("+info")
                .hasMessageContaining("assume-maxmemory-policy")
        }
        warnings {
            check(unreadable, CoCacheProperties.EvictionCheck.FAIL, "noeviction").afterSingletonsInstantiated()
        }.assert().isEmpty()
        failure {
            check(unreadable, CoCacheProperties.EvictionCheck.OFF, "allkeys-lru").afterSingletonsInstantiated()
        }.assert().isInstanceOf(IllegalStateException::class.java).hasMessageContaining("assumed=allkeys-lru")
        // 假定的 volatile-* 照常按 mode 判断
        warnings {
            check(
                unreadable,
                CoCacheProperties.EvictionCheck.WARN,
                "volatile-lru",
                setCaches = mapOf("recent" to Duration.ofHours(1))
            ).afterSingletonsInstantiated()
        }.single().assert().contains("[recent]").contains("assumed=volatile-lru")
        // 读得到时以实际的值为准
        warnings {
            check(redisWith(mapOf("redis" to "noeviction")), CoCacheProperties.EvictionCheck.FAIL, "allkeys-lru")
                .afterSingletonsInstantiated()
        }.assert().isEmpty()
    }

    @Test
    fun rejectsAnUnknownAssumedPolicy() {
        listOf("noevction", "", "lru").forEach { policy ->
            runCatching { check(redisWith(null), CoCacheProperties.EvictionCheck.WARN, policy) }.exceptionOrNull()
                .assert().isInstanceOf(
                    IllegalArgumentException::class.java
                ).hasMessageContaining("assume-maxmemory-policy[$policy]")
        }
        check(redisWith(null), CoCacheProperties.EvictionCheck.WARN, "volatile-lfu")
    }

    @Test
    fun skipsWithoutStoresOrNonRedisBackend() {
        val backend = redisWith(mapOf("redis" to "allkeys-lru"))
        // 没有权威缓存：不读取策略
        CoCacheProperties.EvictionCheck.entries.forEach { mode ->
            check(backend, mode, setCaches = emptyMap()).afterSingletonsInstantiated()
        }
        verify(exactly = 0) { backend.maxmemoryPolicies() }
        // 后端不是 Redis：不求权威缓存（不解析尚未创建的声明），不检查创建，也不调度重读
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { memory ->
            val check = StoreEvictionCheck(memory, CoCacheProperties.EvictionCheck.FAIL, null, Duration.ofMillis(1)) {
                error("the declarations are resolved on a non-Redis backend")
            }
            check.afterSingletonsInstantiated()
            check.verdict.assert().isEqualTo(StoreEvictionRisk.NotApplicable)
            check.check(storeSpec<String, String>("late") { codec = StringCodec })
            warnings { memory.create(storeSpec<String, String>("direct") { codec = StringCodec }) }.assert().isEmpty()
            check.verdict.assert().isEqualTo(StoreEvictionRisk.NotApplicable)
            check.destroy()
        }
    }

    /**
     * 已创建的权威缓存（`Store`、`VersionedStore`、`SetStore`）取运行时中生效的策略，包括不经 starter、直接由运行时创建的；
     * 派生缓存不在其中。
     */
    @Test
    fun createdStoresUseTheirEffectivePolicy() {
        withSetStore()
        runtime.create(
            setStoreSpec<String, String>("recent") {
                elementCodec = StringCodec
                policy = DefaultPolicies.SET_STORE.toBuilder().ttl(Duration.ofHours(1)).build()
            }
        )
        runtime.create(storeSpec<String, String>("record") { codec = StringCodec })
        runtime.create(versionedStoreSpec<String, String>("ledger") { codec = StringCodec })
        runtime.create(
            cacheSpec<String, String>("user") {
                codec = StringCodec
                externallySourced()
            }
        )
        val beanFactory = org.springframework.beans.factory.support.DefaultListableBeanFactory()
        StoreEvictionCheck.declaredStores(runtime, beanFactory).assert()
            .isEqualTo(mapOf("index" to null, "ledger" to null, "record" to null, "recent" to Duration.ofHours(1)))
    }

    @CoCache(name = "LazyIndex", ttl = 600)
    interface LazyIndexCache : SetStore<String, String>

    @CoCache(name = "EagerIndex")
    interface EagerIndexCache : SetStore<String, String>

    @CoCache(name = "LazyRecord", ttl = 600)
    interface LazyRecordStore : Store<String, String>

    @CoCache(name = "Profile", source = CoCache.Source.EXTERNAL)
    interface ProfileCache : Cache<String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(
        caches = [LazyIndexCache::class, EagerIndexCache::class, LazyRecordStore::class, ProfileCache::class]
    )
    class LazyDeclarations {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun tagSpec(): SetStoreSpec<String, String> = setStoreSpec("tag") { elementCodec = StringCodec }

        @Bean
        fun eagerSpec(): SetStoreSpec<String, String> = setStoreSpec("eager") { elementCodec = StringCodec }

        @Bean
        fun ledgerSpec(): VersionedStoreSpec<String, String> = versionedStoreSpec("ledger") { codec = StringCodec }

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            externallySourced()
        }
    }

    /**
     * 延迟初始化的权威缓存（如 `spring.main.lazy-initialization=true` 下的接口式声明）在启动检查时尚未创建，仍按声明检查：
     * 生效的 ttl 与创建时同样叠加注解、定制器与配置文件，且检查不会创建它们。
     */
    @Test
    fun uncreatedDeclarationsAreCheckedWithTheirResolvedTtl() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withUserConfiguration(LazyDeclarations::class.java)
            .withBean(CacheSpecCustomizer::class.java, {
                object : CacheSpecCustomizer {
                    override fun supports(cacheName: String) = cacheName == "tag"

                    override fun customize(spec: SetStoreSpec.Builder<*, *>) {
                        spec.policy = DefaultPolicies.SET_STORE.toBuilder().ttl(Duration.ofMinutes(5)).build()
                    }
                }
            })
            .withPropertyValues("cocache.caches.LazyIndex.ttl=120s")
            .withInitializer { context ->
                context.addBeanFactoryPostProcessor { factory ->
                    factory.getBeanDefinition("LazyIndex").isLazyInit = true
                    factory.getBeanDefinition("LazyRecord").isLazyInit = true
                }
            }
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)
                runtime.cacheNames.assert().containsExactlyInAnyOrder(
                    "EagerIndex",
                    "Profile",
                    "eager",
                    "ledger",
                    "tag",
                    "user"
                )
                // 已创建的接口式声明（EagerIndex）取运行时中的策略；派生缓存的声明（Profile、user）不在其中
                val expected = mapOf(
                    "EagerIndex" to null,
                    "LazyIndex" to Duration.ofSeconds(120),
                    "LazyRecord" to Duration.ofSeconds(600),
                    "eager" to null,
                    "ledger" to null,
                    "tag" to Duration.ofMinutes(5)
                )
                StoreEvictionCheck.declaredStores(runtime, context.beanFactory).assert().isEqualTo(expected)
                // 只读取声明，不创建缓存
                runtime.cacheNames.assert().doesNotContain("LazyIndex", "LazyRecord")
                // spec 声明尚未创建时同样按创建时的规则解析（另一个空的运行时：所有声明都还没有创建）
                CacheRuntime.builder().backend(InMemoryBackend()).build().use { empty ->
                    StoreEvictionCheck.declaredStores(empty, context.beanFactory).assert().isEqualTo(expected)
                }
            }
    }

    /**
     * 应用自建的 [CacheRuntime]（自动配置的运行时退让）：驱逐检查按这个运行时实际使用的后端判断（#874），不另外要求 `CoherenceBackend` bean。
     */
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))

    @Configuration(proxyBeanMethods = false)
    class RuntimeOnly {
        @Bean(destroyMethod = "close")
        fun runtime(): CacheRuntime = CacheRuntime.builder().backend(InMemoryBackend()).build()
    }

    @Configuration(proxyBeanMethods = false)
    class RuntimeWithBackend {
        @Bean
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean(destroyMethod = "close")
        fun runtime(backend: CoherenceBackend): CacheRuntime = CacheRuntime.builder().backend(backend).build()
    }

    /**
     * 运行时建在 Redis 后端上（同样不暴露为 bean），`maxmemory-policy` 为 `allkeys-lru`；权威缓存是延迟初始化的声明，启动时不创建。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [LazyRecordStore::class])
    class RuntimeOnRedis {
        @Bean(destroyMethod = "close")
        fun runtime(): CacheRuntime {
            val redis = redisBackendMock(relaxed = true)
            every { redis.maxmemoryPolicies() } returns mapOf("redis" to "allkeys-lru")
            return CacheRuntime.builder().backend(redis).build()
        }
    }

    /**
     * 缺陷复现（#874）：运行时的后端不是 bean 时，检查曾因缺少 `CoherenceBackend` bean 而使启动失败。
     */
    @Test
    fun aRuntimeWhoseBackendIsNotABeanStarts() {
        runner.withUserConfiguration(RuntimeOnly::class.java).run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(CacheRuntime::class.java)
            assertThat(context).doesNotHaveBean(CoherenceBackend::class.java)
            assertThat(context).doesNotHaveBean("cocacheRuntime")
        }
    }

    /**
     * 对照：后端另外声明为 bean 时照常启动。
     */
    @Test
    fun aRuntimeWhoseBackendIsAlsoABeanStarts() {
        runner.withUserConfiguration(RuntimeWithBackend::class.java).run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(CacheRuntime::class.java)
            assertThat(context).doesNotHaveBean("cocacheRuntime")
        }
    }

    /**
     * 自建运行时的后端是 Redis 时，权威缓存仍然受检查：`allkeys-*` 启动失败。
     */
    @Test
    fun aRuntimeOnRedisIsStillChecked() {
        runner.withUserConfiguration(RuntimeOnRedis::class.java)
            .withInitializer { context ->
                context.addBeanFactoryPostProcessor { factory -> factory.getBeanDefinition("LazyRecord").isLazyInit = true }
            }
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert()
                    .hasStackTraceContaining("[LazyRecord]")
                    .hasStackTraceContaining("allkeys-lru")
            }
    }

    /**
     * 存储在内存中、`unwrap` 到一个 `maxmemory-policy` 可调的 Redis 后端的桩（同测试中包装 Redis 的后端）。
     */
    class EvictingBackend(private val memory: InMemoryBackend = InMemoryBackend()) : CoherenceBackend by memory {
        @Volatile
        var policies: Map<String, String>? = mapOf("redis" to "noeviction")

        private val redis: RedisBackend = redisBackendMock().also { redis ->
            every { redis.maxmemoryPolicies() } answers { policies }
            // 关闭超时、健康检查与指标经 unwrap 读 Redis 后端
            every { redis.commandTimeout } returns Duration.ofSeconds(2)
            every { redis.channelMode } returns RedisChannelMode.PUBSUB
            every { redis.heartbeatHealthy } returns true
            every { redis.heartbeatFailure } returns null
            every { redis.commandConnectionFailure } returns null
        }

        override fun <T : Any> unwrap(type: Class<T>): T? =
            if (type == RedisBackend::class.java) type.cast(redis) else memory.unwrap(type)

        override fun close() = memory.close()
    }

    @Configuration(proxyBeanMethods = false)
    class EvictingConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = EvictingBackend()
    }

    private fun expiringStore(name: String) = storeSpec<String, String>(name) {
        codec = StringCodec
        policy = DefaultPolicies.STORE.toBuilder().ttl(Duration.ofHours(1)).build()
    }

    /**
     * X2：启动之后经 `CoCacheFactory` 创建的权威缓存在创建路径上按同一规则检查，致命时创建失败（不留下缓存）；派生缓存不受影响；
     * 直接经 `runtime.create` 创建的只能在创建之后告警；`volatile-*` 在 `warn` 下只告警。
     */
    @Test
    fun aStoreCreatedAfterStartupIsCheckedOnTheCreationPath() {
        runner.withUserConfiguration(EvictingConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val backend = context.getBean(CoherenceBackend::class.java) as EvictingBackend
            val factory = context.getBean(me.ahoo.cocache.core.CoCacheFactory::class.java)
            val runtime = context.getBean(CacheRuntime::class.java)
            backend.policies = mapOf("redis" to "allkeys-lru")
            failure { factory.create(storeSpec<String, String>("late") { codec = StringCodec }) }.assert()
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("[late]")
                .hasMessageContaining("allkeys-lru")
            runtime.cacheNames.assert().doesNotContain("late")
            factory.create(
                cacheSpec<String, String>("derived") {
                    codec = StringCodec
                    externallySourced()
                }
            )
            val direct = warnings { runtime.create(storeSpec<String, String>("direct") { codec = StringCodec }) }
            direct.single().assert().contains("[direct]").contains("CacheRuntime.create")
            runtime.cacheNames.assert().contains("derived", "direct")

            backend.policies = mapOf("redis" to "volatile-lru")
            warnings { factory.create(expiringStore("expiring")) }.single().assert()
                .contains("[expiring]").contains("store-eviction-check=off")
            val check = context.getBean(StoreEvictionCheck::class.java)
            (check.verdict as StoreEvictionRisk.Risk).kind.assert().isEqualTo(
                StoreEvictionRisk.Kind.EVICTS_EXPIRING_KEYS
            )
        }
    }

    /**
     * 缺陷复现：创建一个无风险的权威缓存曾用它自己的结论覆盖健康快照，已有的风险（`expiring`）从快照中消失。快照总是对全部权威缓存的
     * 结论；创建只按这一个缓存决定失败或告警。
     */
    @Test
    fun aSafeCreationKeepsTheRiskOfTheOtherStores() {
        runner.withUserConfiguration(EvictingConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val backend = context.getBean(CoherenceBackend::class.java) as EvictingBackend
            val factory = context.getBean(me.ahoo.cocache.core.CoCacheFactory::class.java)
            val runtime = context.getBean(CacheRuntime::class.java)
            val check = context.getBean(StoreEvictionCheck::class.java)
            backend.policies = mapOf("redis" to "volatile-lru")
            warnings { factory.create(expiringStore("expiring")) }.single().assert().contains("[expiring]")
            warnings {
                factory.create(storeSpec<String, String>("plain") { codec = StringCodec })
                runtime.create(storeSpec<String, String>("direct") { codec = StringCodec })
            }.assert().isEmpty()
            val risk = check.verdict as StoreEvictionRisk.Risk
            risk.kind.assert().isEqualTo(StoreEvictionRisk.Kind.EVICTS_EXPIRING_KEYS)
            risk.caches.assert().containsExactly("expiring")
        }
    }

    /**
     * 缺陷复现：库自建的 `CoCacheFactory(runtime)` 创建的缓存也是“分层的”，却不经过 starter 的创建路径检查；创建监听曾按 `layered`
     * 跳过它，启动之后创建的权威缓存既不失败也不告警。现在没有在创建路径上检查过的都告警。
     */
    @Test
    fun aStoreCreatedThroughALibraryFactoryIsWarned() {
        runner.withUserConfiguration(EvictingConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val backend = context.getBean(CoherenceBackend::class.java) as EvictingBackend
            val runtime = context.getBean(CacheRuntime::class.java)
            backend.policies = mapOf("redis" to "allkeys-lru")
            val library = me.ahoo.cocache.core.CoCacheFactory(runtime)
            warnings { library.create(storeSpec<String, String>("library") { codec = StringCodec }) }
                .single().assert().contains("[library]").contains("a library's own CoCacheFactory")
            runtime.admin("library")?.layered.assert().isEqualTo(true)
        }
    }

    /**
     * 包装过的 Redis 后端（如测试的 `HookedBackend`）经 `unwrap` 照样受检查，不再被当作“不是 Redis”而跳过。
     */
    @Test
    fun aWrappedRedisBackendIsChecked() {
        val backend = EvictingBackend().apply { policies = mapOf("redis" to "allkeys-lru") }
        CacheRuntime.builder().backend(me.ahoo.cocache.test.HookedBackend(backend)).build().use { runtime ->
            failure {
                StoreEvictionCheck(runtime, CoCacheProperties.EvictionCheck.WARN, null) { mapOf("index" to null) }
                    .afterSingletonsInstantiated()
            }.assert().hasMessageContaining("allkeys-lru")
        }
    }

    /**
     * 周期重读：重新读取策略，检查已创建的权威缓存；风险变化时告警一次，结论留给健康检查（只读快照，不影响状态）。
     */
    @Test
    fun aPeriodicRecheckReadsThePoliciesAgain() {
        val backend = EvictingBackend()
        CacheRuntime.builder().backend(backend).build().use { runtime ->
            runtime.create(storeSpec<String, String>("index") { codec = StringCodec })
            val check = StoreEvictionCheck(runtime, CoCacheProperties.EvictionCheck.WARN, null, Duration.ofHours(1)) {
                StoreEvictionRisk.authoritativeStores(runtime)
            }
            check.afterSingletonsInstantiated()
            check.verdict.assert().isEqualTo(StoreEvictionRisk.Safe(mapOf("redis" to "noeviction")))
            val health = CoCacheHealthIndicator(runtime) { check }
            @Suppress("UNCHECKED_CAST")
            (
                requireNotNull(
                    health.health()
                ).details["storeEviction"] as Map<String, Any>
                )["safe"].assert().isEqualTo(true)

            backend.policies = mapOf("redis" to "allkeys-lru")
            warnings {
                check.recheck()
                check.recheck()
            }.single().assert().contains("[index]").contains("allkeys-lru")
            requireNotNull(check.checkedAt)
            val down = requireNotNull(health.health())
            down.status.assert().isEqualTo(org.springframework.boot.health.contributor.Status.UP)
            @Suppress("UNCHECKED_CAST")
            val detail = down.details["storeEviction"] as Map<String, Any>
            detail["safe"].assert().isEqualTo(false)
            detail["risk"].assert().isEqualTo("evicts-any-key")
            detail["caches"].assert().isEqualTo(sortedSetOf("index"))
            check.destroy()

            // 按间隔调度
            val scheduled = StoreEvictionCheck(
                runtime,
                CoCacheProperties.EvictionCheck.WARN,
                null,
                Duration.ofMillis(10)
            ) {
                emptyMap()
            }
            scheduled.afterSingletonsInstantiated()
            scheduled.verdict.assert().isEqualTo(StoreEvictionRisk.NotApplicable)
            me.ahoo.cocache.test.Concurrency.awaitCondition("rechecked") { scheduled.verdict is StoreEvictionRisk.Risk }
            scheduled.destroy()
        }
    }
}
