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

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

class CoCacheObservabilityTest {
    private val autoConfigurations = AutoConfigurations.of(
        CoCacheAutoConfiguration::class.java,
        CoCacheMetricsAutoConfiguration::class.java,
        CoCacheEndpointAutoConfiguration::class.java,
    )
    private val runner = ApplicationContextRunner()
        .withConfiguration(autoConfigurations)
        .withPropertyValues("cocache.enabled=true")
        .withUserConfiguration(InMemoryConfig::class.java)

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
        fun indexSpec(): SetCacheSpec<String, String> = setCacheSpec("index") { elementCodec = StringCodec }
    }

    @Test
    fun canBeDisabled() {
        ApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(InMemoryConfig::class.java)
            .withPropertyValues("cocache.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(CoCacheMeterBinder::class.java)
                assertThat(context).doesNotHaveBean(CoCacheEndpoint::class.java)
            }
    }

    @Test
    fun enabled() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(CoCacheMeterBinder::class.java)
            assertThat(context).hasSingleBean(CoCacheEndpoint::class.java)
        }
    }

    @Test
    fun noRuntimeMeansNoBinderOrEndpoint() {
        ApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withPropertyValues("cocache.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(CoCacheMeterBinder::class.java)
                assertThat(context).doesNotHaveBean(CoCacheEndpoint::class.java)
                // 没有 binder 时后处理器什么也不做
                CacheRuntime.builder().backend(InMemoryBackend()).build().use { runtime ->
                    val cache = runtime.create(cacheSpec<String, String>("orphan") { codec = StringCodec })
                    context.autowireCapableBeanFactory.initializeBean(cache, "orphan").assert().isSameAs(cache)
                }
            }
    }

    @Test
    fun backOffWithoutMicrometerOrActuator() {
        runner.withClassLoader(FilteredClassLoader("io.micrometer.core")).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(CoCacheMeterBinder::class.java)
            assertThat(context).hasSingleBean(CoCacheEndpoint::class.java)
        }
        runner.withClassLoader(FilteredClassLoader(Endpoint::class.java)).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(CoCacheMeterBinder::class.java)
            assertThat(context).doesNotHaveBean(CoCacheEndpoint::class.java)
        }
    }

    @Test
    fun countersFollowReadsAndWrites() {
        runner.run { context ->
            val registry = SimpleMeterRegistry()
            context.getBean(CoCacheMeterBinder::class.java).bindTo(registry)
            @Suppress("UNCHECKED_CAST")
            val user = context.getBean("user") as Cache<String, String>

            registry.count("cocache.misses", "user").assert().isZero()
            registry.localSize("user").assert().isZero()

            user["1"].assert().isEqualTo("user-1")
            registry.count("cocache.misses", "user").assert().isEqualTo(1.0)
            val loads = registry.get("cocache.loads").tag(CoCacheMeterBinder.TAG_CACHE, "user").functionTimer()
            loads.count().assert().isEqualTo(1.0)
            loads.totalTime(TimeUnit.NANOSECONDS).assert().isGreaterThan(0.0)
            registry.localSize("user").assert().isEqualTo(1.0)

            // L2 命中不计数
            user["1"].assert().isEqualTo("user-1")
            registry.count("cocache.misses", "user").assert().isEqualTo(1.0)

            // 本节点 L2 被清空后，从 L1 读回
            context.getBean(CacheRuntime::class.java).admin("user")?.clearLocal()
            user["1"].assert().isEqualTo("user-1")
            registry.get("cocache.hits").tag(CoCacheMeterBinder.TAG_CACHE, "user")
                .tag(CoCacheMeterBinder.TAG_LEVEL, "l1").functionCounter().count().assert().isEqualTo(1.0)
            loads.count().assert().isEqualTo(1.0)

            CacheCounters.ALL.forEach {
                registry.find(it.meter).tag(CoCacheMeterBinder.TAG_CACHE, "index").functionCounter()
                    .assert().withFailMessage { "${it.meter} is not bound for index" }.isNotNull()
            }
        }
    }

    @Test
    fun bindsCachesCreatedLater() {
        runner.run { context ->
            val registry = SimpleMeterRegistry()
            val binder = context.getBean(CoCacheMeterBinder::class.java)
            binder.bindTo(registry)
            val runtime = context.getBean(CacheRuntime::class.java)
            val late = runtime.create(cacheSpec<String, String>("late") { codec = StringCodec })
            registry.find("cocache.misses").tag(CoCacheMeterBinder.TAG_CACHE, "late").meter().assert().isNull()

            // 延迟创建的缓存 bean 初始化后由后处理器补绑
            context.autowireCapableBeanFactory.initializeBean(late, "late")
            registry.count("cocache.misses", "late").assert().isZero()

            // 重复绑定是安全的
            binder.bindNewCaches()
            binder.afterSingletonsInstantiated()
            registry.find("cocache.misses").tag(CoCacheMeterBinder.TAG_CACHE, "late").meters()
                .assert().hasSize(1)
            val lateIndex = runtime.create(setCacheSpec<String, String>("late-index") { elementCodec = StringCodec })
            context.autowireCapableBeanFactory.initializeBean(lateIndex, "lateIndex")
            registry.count("cocache.misses", "late-index").assert().isZero()
            context.autowireCapableBeanFactory.initializeBean(Any(), "other").assert().isNotNull()
        }
    }

    @Test
    fun bindsToSpringBootMeterRegistry() {
        runner.withConfiguration(
            AutoConfigurations.of(
                MetricsAutoConfiguration::class.java,
                SimpleMetricsExportAutoConfiguration::class.java
            )
        ).run { context ->
            assertThat(context).hasNotFailed()
            val registry = context.getBean(MeterRegistry::class.java)
            registry.count("cocache.misses", "user").assert().isZero()
            registry.localSize("index").assert().isZero()
        }
    }

    @Test
    fun endpointOperations() {
        runner.run { context ->
            val endpoint = context.getBean(CoCacheEndpoint::class.java)

            @Suppress("UNCHECKED_CAST")
            val user = context.getBean("user") as Cache<String, String>
            user["1"]

            endpoint.caches().map { it.name }.assert().containsExactly("index", "user")
            val report = requireNotNull(endpoint.cache("user"))
            report.localSize.assert().isEqualTo(1L)
            report.loads.assert().isEqualTo(1L)
            report.loadNanos.assert().isGreaterThan(0L)
            report.metrics["misses"].assert().isEqualTo(1L)
            report.metrics.keys.assert().containsExactlyElementsOf(CacheCounters.ALL.map { it.key })
            endpoint.cache("missing").assert().isNull()

            endpoint.clearLocal("user")
            requireNotNull(endpoint.cache("user")).localSize.assert().isZero()
            user["1"].assert().isEqualTo("user-1")
            requireNotNull(endpoint.cache("user")).metrics["remoteHits"].assert().isEqualTo(1L)

            // 未知缓存名不报错
            endpoint.clearLocal("missing")
        }
    }

    private fun MeterRegistry.count(name: String, cache: String): Double {
        return get(name).tag(CoCacheMeterBinder.TAG_CACHE, cache).functionCounter().count()
    }

    private fun MeterRegistry.localSize(cache: String): Double {
        return get("cocache.local.size").tag(CoCacheMeterBinder.TAG_CACHE, cache).gauge().value()
    }
}
