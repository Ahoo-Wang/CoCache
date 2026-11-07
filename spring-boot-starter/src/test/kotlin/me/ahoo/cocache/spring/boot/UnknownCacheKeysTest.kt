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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * [UnknownCacheKeys] 的判定本身（启动时的行为见 `ThreeLayerConfigTest.unknownPerCacheKeysFailOrWarn`）：它接在正式绑定的处理器链上，
 * 只记录，不改变绑定。
 */
class UnknownCacheKeysTest {
    private fun bind(
        advisor: UnknownCacheKeys,
        handler: BindHandler,
        vararg entries: Pair<String, String>
    ): CoCacheProperties =
        Binder(MapConfigurationPropertySource(mapOf(*entries)))
            .bindOrCreate(CoCacheProperties.PREFIX, Bindable.of(CoCacheProperties::class.java), advisor.apply(handler))

    @Test
    fun recordsOnlyThePerCacheKeysThatBindToNothing() {
        val advisor = UnknownCacheKeys()
        val properties = bind(
            advisor,
            BindHandler.DEFAULT,
            "cocache.caches.user.ttl" to "1m",
            "cocache.caches.user.ttl-forever" to "true",
            "cocache.caches.order.max-concurrent" to "4",
            "cocache.defaults.cache.ttl-forever" to "true",
        )
        properties.caches.getValue("user").ttl.assert().isEqualTo("1m")
        advisor.keys.assert().containsExactly("cocache.caches.order.max-concurrent", "cocache.caches.user.ttl-forever")

        bind(advisor, BindHandler.DEFAULT, "cocache.caches.user.ttl" to "1m")
        advisor.keys.assert().withFailMessage { "each binding of cocache replaces the record" }.isEmpty()
    }

    /**
     * 外层处理器的回调照常执行：这里外层是 Spring Boot 的 [NoUnboundElementsBindHandler]（`ignoreUnknownFields = false` 时的处理器），
     * 它在 `onFinish` 中照样发现不认识的键并让绑定失败。
     */
    @Test
    fun theOuterHandlersStillRun() {
        val advisor = UnknownCacheKeys()
        val failure = runCatching {
            bind(advisor, NoUnboundElementsBindHandler(BindHandler.DEFAULT), "cocache.redis.heartbeat-period" to "5s")
        }.exceptionOrNull()
        failure.assert().isNotNull()
        advisor.keys.assert().isEmpty()
    }

    /**
     * 同前缀的其它配置类（只读取 `cocache.enabled` 的投影）。
     */
    class EnabledProjection {
        var enabled: Boolean = false
    }

    /**
     * 缺陷复现（#867）：advisor 作用于所有配置类的绑定，只有 [CoCacheProperties] 的绑定才记录。其它类型绑定同一前缀时
     * 不认识 `cocache.caches.*`，曾覆盖记录，把合法的键报为不认识。
     */
    @Test
    fun otherTargetsBoundToTheSamePrefixDoNotReplaceTheRecord() {
        val advisor = UnknownCacheKeys()
        val entries = arrayOf(
            "cocache.enabled" to "true",
            "cocache.caches.user.ttl" to "1m",
            "cocache.caches.user.ttl-forever" to "true",
        )
        bind(advisor, BindHandler.DEFAULT, *entries)
        advisor.keys.assert().containsExactly("cocache.caches.user.ttl-forever")

        Binder(MapConfigurationPropertySource(mapOf(*entries)))
            .bindOrCreate(
                CoCacheProperties.PREFIX,
                Bindable.of(EnabledProjection::class.java),
                advisor.apply(BindHandler.DEFAULT)
            )
            .enabled.assert().isTrue()
        advisor.keys.assert().withFailMessage { "binding another type to cocache keeps the record of CoCacheProperties" }
            .containsExactly("cocache.caches.user.ttl-forever")
    }

    @Configuration(proxyBeanMethods = false)
    class CacheConfig {
        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader { "v-$it" }
        }
    }

    /**
     * 应用的配置投影在 [CoCacheProperties] 之后、装配层校验之前绑定（自定义运行时依赖它）。
     */
    @Configuration(proxyBeanMethods = false)
    class ProjectionConfig {
        @Bean
        @ConfigurationProperties(prefix = "cocache")
        fun enabledProjection(properties: CoCacheProperties): EnabledProjection {
            check(properties.caches.getValue("user").ttl == "2s")
            return EnabledProjection()
        }

        @Bean(destroyMethod = "close")
        fun runtime(
            backend: CoherenceBackend,
            properties: CoCacheProperties,
            projection: EnabledProjection
        ): CacheRuntime {
            check(properties.enabled && projection.enabled)
            return CacheRuntime.builder().backend(backend).build()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ControlConfig {
        @Bean(destroyMethod = "close")
        fun runtime(backend: CoherenceBackend, properties: CoCacheProperties): CacheRuntime {
            check(properties.enabled)
            return CacheRuntime.builder().backend(backend).build()
        }
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withUserConfiguration(CacheConfig::class.java)
        .withPropertyValues("cocache.enabled=true", "cocache.backend=memory", "cocache.caches.user.ttl=2s")

    /**
     * 缺陷复现（#867）：同前缀的应用配置投影不让 `cocache.caches.user.ttl` 变成不认识的键，启动成功且 `ttl` 生效。
     */
    @Test
    fun aProjectionOfTheSamePrefixKeepsKnownPerCacheKeys() {
        runner.withUserConfiguration(ProjectionConfig::class.java).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean("user")
            requireNotNull(
                context.getBean(CacheRuntime::class.java).policy("user")
            ).ttl.assert().isEqualTo(Duration.ofSeconds(2))
        }
    }

    /**
     * 对照：没有投影时同样的配置启动成功；有投影时真正不认识的键仍然启动失败。
     */
    @Test
    fun withoutAProjectionTheSameConfigurationStarts() {
        runner.withUserConfiguration(ControlConfig::class.java).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean("user")
            requireNotNull(
                context.getBean(CacheRuntime::class.java).policy("user")
            ).ttl.assert().isEqualTo(Duration.ofSeconds(2))
        }
        runner.withUserConfiguration(ProjectionConfig::class.java)
            .withPropertyValues("cocache.caches.user.ttl-forever=true")
            .run { context ->
                context.startupFailure.assert().hasStackTraceContaining(
                    "unknown keys [cocache.caches.user.ttl-forever]"
                )
            }
    }
}
