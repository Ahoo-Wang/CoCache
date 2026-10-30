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
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.redis.RedisBackend
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

class SetCacheEvictionCheckTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()

    @AfterEach
    fun destroy() = runtime.close()

    private fun redisWith(policies: Map<String, String>?): RedisBackend = mockk {
        every { maxmemoryPolicies() } returns policies
    }

    private fun check(
        backend: CoherenceBackend,
        mode: CoCacheProperties.EvictionCheck,
        assumed: String? = null,
        setCaches: Map<String, Duration?> = mapOf("index" to null),
    ) = SetCacheEvictionCheck(backend, mode, assumed) { setCaches }

    private fun withSetCache() = runtime.create(setCacheSpec<String, String>("index") { elementCodec = StringCodec })

    private fun warnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(SetCacheEvictionCheck::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun warnsWhenAPrimaryEvictsAnyKey() {
        val backend = redisWith(mapOf("10.0.0.1:6379" to "allkeys-lru", "10.0.0.2:6379" to "noeviction"))
        val logs = warnings {
            check(backend, CoCacheProperties.EvictionCheck.WARN).afterSingletonsInstantiated()
        }
        logs.single().assert().contains(
            "[index]"
        ).contains("allkeys-lru").contains("10.0.0.1:6379").doesNotContain("10.0.0.2")
    }

    @Test
    fun failModeStopsStartup() {
        val backend = redisWith(mapOf("redis" to "allkeys-lfu"))
        runCatching {
            check(backend, CoCacheProperties.EvictionCheck.FAIL).afterSingletonsInstantiated()
        }
            .exceptionOrNull().assert().isInstanceOf(
                IllegalStateException::class.java
            ).hasMessageContaining("allkeys-lfu")
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
     * volatile-* 驱逐设置了过期时间的 key：只列出 ttl 有限的集合缓存，`fail` 下也只告警（这些集合本来就会过期）。
     */
    @Test
    fun volatilePolicyWarnsAboutSetCachesWithAFiniteTtl() {
        val backend = redisWith(mapOf("10.0.0.1:6379" to "volatile-ttl"))
        val setCaches = mapOf("index" to null, "recent" to Duration.ofHours(1))
        val logs = warnings {
            check(backend, CoCacheProperties.EvictionCheck.FAIL, setCaches = setCaches).afterSingletonsInstantiated()
        }
        logs.single().assert().contains("[recent]").contains("volatile-ttl").doesNotContain("index")
    }

    /**
     * 读不到策略（Redis 用户没有 INFO 权限）：不再静默跳过。没有配置假定值时按 mode 告警或启动失败；配置了则按它判断。
     */
    @Test
    fun unreadablePolicyIsReportedUnlessAssumed() {
        val unreadable = redisWith(null)
        warnings {
            check(unreadable, CoCacheProperties.EvictionCheck.WARN).afterSingletonsInstantiated()
        }.single().assert().contains("[index]").contains("+info").contains("assume-maxmemory-policy")
        runCatching { check(unreadable, CoCacheProperties.EvictionCheck.FAIL).afterSingletonsInstantiated() }
            .exceptionOrNull().assert().isInstanceOf(
                IllegalStateException::class.java
            ).hasMessageContaining("INFO memory")
        warnings {
            check(unreadable, CoCacheProperties.EvictionCheck.FAIL, "noeviction").afterSingletonsInstantiated()
        }.assert().isEmpty()
        runCatching {
            check(
                unreadable,
                CoCacheProperties.EvictionCheck.FAIL,
                "allkeys-lru"
            ).afterSingletonsInstantiated()
        }
            .exceptionOrNull().assert().isInstanceOf(
                IllegalStateException::class.java
            ).hasMessageContaining("assumed=allkeys-lru")
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
    fun skipsWithoutSetCachesOffModeOrNonRedisBackend() {
        val backend = redisWith(mapOf("redis" to "allkeys-lru"))
        // 没有集合缓存：不读取策略
        check(backend, CoCacheProperties.EvictionCheck.FAIL, setCaches = emptyMap()).afterSingletonsInstantiated()
        verify(exactly = 0) { backend.maxmemoryPolicies() }
        check(backend, CoCacheProperties.EvictionCheck.OFF).afterSingletonsInstantiated()
        verify(exactly = 0) { backend.maxmemoryPolicies() }
        val memory: CoherenceBackend = InMemoryBackend()
        check(memory, CoCacheProperties.EvictionCheck.FAIL).afterSingletonsInstantiated()
    }

    /**
     * 已创建的集合缓存取运行时中生效的策略，包括不经 starter、直接由运行时创建的；普通缓存不在其中。
     */
    @Test
    fun createdSetCachesUseTheirEffectivePolicy() {
        withSetCache()
        runtime.create(
            setCacheSpec<String, String>("recent") {
                elementCodec = StringCodec
                policy = CachePolicy(ttl = Duration.ofHours(1))
            }
        )
        runtime.create(cacheSpec<String, String>("user") { codec = StringCodec })
        val beanFactory = org.springframework.beans.factory.support.DefaultListableBeanFactory()
        SetCacheEvictionCheck.declaredSetCaches(runtime, beanFactory).assert()
            .isEqualTo(mapOf("index" to null, "recent" to Duration.ofHours(1)))
    }

    @CoCache(name = "LazyIndex", ttl = 600)
    interface LazyIndexCache : SetCache<String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [LazyIndexCache::class])
    class LazyDeclarations {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun tagSpec(): SetCacheSpec<String, String> = setCacheSpec("tag") { elementCodec = StringCodec }

        @Bean
        fun eagerSpec(): SetCacheSpec<String, String> = setCacheSpec("eager") { elementCodec = StringCodec }

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") { codec = StringCodec }
    }

    /**
     * 延迟初始化的集合缓存（如 `spring.main.lazy-initialization=true` 下的接口式声明）在启动检查时尚未创建，仍按声明检查：
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

                    override fun customize(spec: SetCacheSpec.Builder<*, *>) {
                        spec.policy = CachePolicy(ttl = Duration.ofMinutes(5))
                    }
                }
            })
            .withPropertyValues("cocache.caches.LazyIndex.ttl=120")
            .withInitializer { context ->
                context.addBeanFactoryPostProcessor { factory -> factory.getBeanDefinition("LazyIndex").isLazyInit = true }
            }
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)
                runtime.cacheNames.assert().containsExactlyInAnyOrder("eager", "tag", "user")
                val expected = mapOf(
                    "LazyIndex" to Duration.ofSeconds(120),
                    "eager" to null,
                    "tag" to Duration.ofMinutes(5)
                )
                SetCacheEvictionCheck.declaredSetCaches(runtime, context.beanFactory).assert().isEqualTo(expected)
                // 只读取声明，不创建缓存
                runtime.cacheNames.assert().doesNotContain("LazyIndex")
                // spec 声明尚未创建时同样按创建时的规则解析（另一个空的运行时：所有声明都还没有创建）
                CacheRuntime.builder().backend(InMemoryBackend()).build().use { empty ->
                    SetCacheEvictionCheck.declaredSetCaches(empty, context.beanFactory).assert().isEqualTo(expected)
                }
            }
    }
}
