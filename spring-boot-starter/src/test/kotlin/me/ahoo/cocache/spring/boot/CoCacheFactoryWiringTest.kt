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
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * starter 的 [CoCacheFactory] bean（RFC 0005 §4.8）：库经它创建的缓存与 starter 自己的声明一样叠加定制器与配置文件；
 * 直接经 `runtime.create` 创建的缓存“未分层”，`cocache.caches.[name]` 指向它时告警（S20）。
 */
class CoCacheFactoryWiringTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.enabled=true")

    interface RevokedTokens : Store<String, String>

    @Configuration(proxyBeanMethods = false)
    class LibraryConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        /**
         * 库的接口式声明：经 starter 的 [CoCacheFactory] 创建。
         */
        @Bean
        fun revokedTokens(factory: CoCacheFactory): RevokedTokens =
            factory.create(RevokedTokens::class.java, storeSpec<String, String>("revoked") { codec = StringCodec })

        @Bean
        fun revokedCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
            override fun supports(cacheName: String): Boolean = cacheName == "revoked"

            override fun customize(spec: StoreSpec.Builder<*, *>) {
                spec.namespace = "revoked-tokens"
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class DirectConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        /**
         * 绕过 [CoCacheFactory]：配置文件对它不生效。
         */
        @Bean
        fun direct(runtime: CacheRuntime): Store<String, String> =
            runtime.create(storeSpec<String, String>("direct") { codec = StringCodec })
    }

    @Test
    fun aLibraryCacheIsLayeredLikeTheStartersOwn() {
        runner.withUserConfiguration(LibraryConfig::class.java)
            .withPropertyValues("cocache.caches.revoked.local-max-age=5s", "cocache.caches.revoked.key-prefix=app:")
            .run { context ->
                assertThat(context).hasNotFailed()
                val tokens = context.getBean(RevokedTokens::class.java)
                tokens.put("t", "revoked")
                tokens["t"].assert().isEqualTo("revoked")
                val runtime = context.getBean(CacheRuntime::class.java)
                runtime.policy("revoked")?.localMaxAge.assert().isEqualTo(Duration.ofSeconds(5))
                val admin = requireNotNull(runtime.admin("revoked"))
                admin.layered.assert().isTrue()
                admin.apiType.assert().isEqualTo(Store::class.java)
                admin.inspect("t").storageKey.assert().isEqualTo("app:revoked-tokens:t")
            }
    }

    @Test
    fun theFactoryBeanCanBeReplaced() {
        runner.withUserConfiguration(LibraryConfig::class.java, CustomFactoryConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(CoCacheFactory::class.java).assert().isSameAs(context.getBean("customFactory"))
            context.getBean(CacheRuntime::class.java).policy("revoked")?.localMaxAge
                .assert().isEqualTo(DefaultPolicies.STORE.localMaxAge)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomFactoryConfig {
        @Bean
        fun customFactory(runtime: CacheRuntime): CoCacheFactory = CoCacheFactory(runtime)
    }

    @Test
    fun aDirectCacheIsUnlayered() {
        runner.withUserConfiguration(DirectConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            requireNotNull(context.getBean(CacheRuntime::class.java).admin("direct")).layered.assert().isFalse()
        }
    }

    /**
     * 指向未分层缓存的 `cocache.caches.[name]` 不让启动失败（运维在事故中写下的覆盖不应让应用起不来），而是告警一次，
     * 端点列出被忽略的条目；启动之后才直接创建的缓存同样告警。
     */
    @Test
    fun configuringAnUnlayeredCacheWarns() {
        val logger = LoggerFactory.getLogger(UnlayeredConfigurationCheck::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            runner.withUserConfiguration(DirectConfig::class.java)
                .withConfiguration(AutoConfigurations.of(CoCacheEndpointAutoConfiguration::class.java))
                .withPropertyValues(
                    "cocache.caches.direct.local-max-age=5s",
                    "management.endpoints.web.exposure.include=cocache",
                )
                .run { context ->
                    assertThat(context).hasNotFailed()
                    val runtime = context.getBean(CacheRuntime::class.java)
                    runtime.policy("direct")?.localMaxAge.assert().isEqualTo(DefaultPolicies.STORE.localMaxAge)
                    val endpoint = context.getBean(CoCacheEndpoint::class.java)
                    requireNotNull(endpoint.cache("direct")).ignoredConfiguration
                        .assert().containsExactly("cocache.caches.direct")
                }
        } finally {
            logger.detachAppender(appender)
        }
        val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        warnings.assert().hasSize(1)
        warnings[0].assert().startsWith("Cache[direct] - [cocache.caches.direct] don't apply")
            .contains("Create it through CoCacheFactory")
    }

    /**
     * 两条路径都告警一次：创建检查之前已有的缓存（创建时核对），与之后直接创建的缓存（运行时的创建监听）；已分层的缓存不告警。
     */
    @Test
    fun theCheckSeesCachesCreatedBeforeAndAfterIt() {
        val properties = Binder(
            MapConfigurationPropertySource(
                mapOf(
                    "cocache.caches.early.local-max-age" to "5s",
                    "cocache.caches.LATE.local-max-age" to "5s",
                    "cocache.caches.layered.local-max-age" to "5s",
                )
            )
        ).bindOrCreate(CoCacheProperties.PREFIX, CoCacheProperties::class.java)
        val logger = LoggerFactory.getLogger(UnlayeredConfigurationCheck::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
                runtime.create(storeSpec<String, String>("early") { codec = StringCodec })
                UnlayeredConfigurationCheck(runtime, properties)
                runtime.create(storeSpec<String, String>("late") { codec = StringCodec })
                CoCacheFactory(runtime).create(storeSpec<String, String>("layered") { codec = StringCodec })
                runtime.create(storeSpec<String, String>("unconfigured") { codec = StringCodec })
            }
        } finally {
            logger.detachAppender(appender)
        }
        appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage.substringBefore(" don't apply") }
            .assert().containsExactly("Cache[early] - [cocache.caches.early]", "Cache[late] - [cocache.caches.LATE]")
    }
}
