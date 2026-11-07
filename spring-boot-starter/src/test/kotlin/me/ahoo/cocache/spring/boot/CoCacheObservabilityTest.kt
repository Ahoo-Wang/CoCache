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
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CoCacheObservabilityTest {
    companion object {
        private const val LOCAL_HITS = "localHits"
    }

    private val autoConfigurations = AutoConfigurations.of(
        CoCacheAutoConfiguration::class.java,
        CoCacheMetricsAutoConfiguration::class.java,
        CoCacheEndpointAutoConfiguration::class.java,
    )
    private val runner = ApplicationContextRunner()
        .withConfiguration(autoConfigurations)
        .withPropertyValues("cocache.enabled=true", "management.endpoints.web.exposure.include=cocache")
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
        fun indexSpec(): SetStoreSpec<String, String> = setStoreSpec("index") { elementCodec = StringCodec }
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
    fun endpointRequiresExposureAndAccess() {
        ApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(InMemoryConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(CoCacheMeterBinder::class.java)
                assertThat(context).doesNotHaveBean(CoCacheEndpoint::class.java)
            }
        runner.withPropertyValues("management.endpoint.cocache.access=NONE").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(CoCacheEndpoint::class.java)
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
                    val cache = runtime.create(
                        cacheSpec<String, String>("orphan") {
                            codec = StringCodec
                            externallySourced()
                        }
                    )
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

            // L2 命中默认不计数，也不注册 level=l2
            user["1"].assert().isEqualTo("user-1")
            registry.count("cocache.misses", "user").assert().isEqualTo(1.0)
            registry.find("cocache.hits").tag(CoCacheMeterBinder.TAG_CACHE, "user")
                .tag(CoCacheMeterBinder.TAG_LEVEL, "l2").meter().assert().isNull()

            // 本节点 L2 被清空后，从 L1 读回
            context.getBean(CacheRuntime::class.java).admin("user")?.clearLocal()
            user["1"].assert().isEqualTo("user-1")
            registry.get("cocache.hits").tag(CoCacheMeterBinder.TAG_CACHE, "user")
                .tag(CoCacheMeterBinder.TAG_LEVEL, "l1").functionCounter().count().assert().isEqualTo(1.0)
            loads.count().assert().isEqualTo(1.0)

            CacheCounters.ALL.filter { it.key != LOCAL_HITS }.forEach {
                registry.find(it.meter).tag(CoCacheMeterBinder.TAG_CACHE, "index").functionCounter()
                    .assert().withFailMessage { "${it.meter} is not bound for index" }.isNotNull()
            }
        }
    }

    @Test
    fun localHitsAreCountedWhenEnabled() {
        runner.withPropertyValues("cocache.metrics.local-hits=true").run { context ->
            val registry = SimpleMeterRegistry()
            context.getBean(CoCacheMeterBinder::class.java).bindTo(registry)
            val endpoint = context.getBean(CoCacheEndpoint::class.java)

            @Suppress("UNCHECKED_CAST")
            val user = context.getBean("user") as Cache<String, String>
            user["1"].assert().isEqualTo("user-1")
            user["1"].assert().isEqualTo("user-1")
            user["1"].assert().isEqualTo("user-1")

            val l2Hits = registry.get("cocache.hits").tag(CoCacheMeterBinder.TAG_CACHE, "user")
                .tag(CoCacheMeterBinder.TAG_LEVEL, "l2").functionCounter()
            l2Hits.count().assert().isEqualTo(2.0)
            registry.count("cocache.misses", "user").assert().isEqualTo(1.0)
            val report = requireNotNull(endpoint.cache("user"))
            report.metrics[LOCAL_HITS].assert().isEqualTo(2L)
            report.metrics.keys.assert().containsExactlyElementsOf(CacheCounters.ALL.map { it.key })
        }
    }

    @Test
    fun bindsCachesCreatedLater() {
        runner.run { context ->
            val registry = SimpleMeterRegistry()
            val binder = context.getBean(CoCacheMeterBinder::class.java)
            binder.bindTo(registry)
            val runtime = context.getBean(CacheRuntime::class.java)
            val late = runtime.create(
                cacheSpec<String, String>("late") {
                    codec = StringCodec
                    externallySourced()
                }
            )
            // 运行时创建每个缓存之后回调绑定（直接经 runtime.create 创建的也是）
            registry.count("cocache.misses", "late").assert().isZero()
            late.assert().isNotNull()

            // 重复绑定是安全的
            binder.bindNewCaches()
            binder.afterSingletonsInstantiated()
            registry.find("cocache.misses").tag(CoCacheMeterBinder.TAG_CACHE, "late").meters()
                .assert().hasSize(1)
            runtime.create(setStoreSpec<String, String>("late-index") { elementCodec = StringCodec })
            registry.count("cocache.misses", "late-index").assert().isZero()
        }
    }

    @me.ahoo.cocache.api.annotation.CoCache(
        name = "lazy-metrics",
        source = me.ahoo.cocache.api.annotation.CoCache.Source.EXTERNAL
    )
    interface LazyMetricsCache : Cache<String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [LazyMetricsCache::class])
    class LazyMetricsConfig {
        @Bean
        fun stringCodec(): me.ahoo.cocache.api.Codec<String> = StringCodec
    }

    /**
     * 延迟初始化的缓存 bean（`spring.main.lazy-initialization`，这里是接口式声明）第一次取用时才创建，经运行时的创建监听补绑指标，
     * 不需要 bean 后处理器。
     */
    @Test
    fun bindsLazilyCreatedCacheBeans() {
        runner.withUserConfiguration(LazyMetricsConfig::class.java)
            .withInitializer { context ->
                context.addBeanFactoryPostProcessor(
                    org.springframework.boot.LazyInitializationBeanFactoryPostProcessor()
                )
            }
            .run { context ->
                assertThat(context).hasNotFailed()
                val registry = SimpleMeterRegistry()
                context.getBean(CoCacheMeterBinder::class.java).bindTo(registry)
                registry.find("cocache.misses").tag(CoCacheMeterBinder.TAG_CACHE, "lazy-metrics").meters()
                    .assert().isEmpty()
                val cache = context.getBean(LazyMetricsCache::class.java)
                cache["1"].assert().isNull()
                registry.count("cocache.misses", "lazy-metrics").assert().isEqualTo(1.0)
            }
    }

    /**
     * 库经 `CoCacheFactory` 在启动之后创建的缓存（不是 bean）立即注册指标；直接经 `runtime.create` 创建的缓存在端点上标注“未分层”。
     */
    @Test
    fun bindsCachesCreatedThroughTheFactoryAfterStartup() {
        runner.run { context ->
            val registry = SimpleMeterRegistry()
            context.getBean(CoCacheMeterBinder::class.java).bindTo(registry)
            val factory = context.getBean(CoCacheFactory::class.java)
            factory.create(storeSpec<String, String>("revoked") { codec = StringCodec })
            registry.count("cocache.misses", "revoked").assert().isZero()

            val runtime = context.getBean(CacheRuntime::class.java)
            runtime.create(
                storeSpec<String, String>("direct") {
                    keyPrefix = "ops:"
                    namespace = "tokens"
                    codec = StringCodec
                }
            )
            val endpoint = context.getBean(CoCacheEndpoint::class.java)
            requireNotNull(endpoint.cache("revoked")).layered.assert().isTrue()
            val direct = requireNotNull(endpoint.cache("direct"))
            direct.layered.assert().isFalse()
            direct.type.assert().isEqualTo("Store")
            direct.keyPrefix.assert().isEqualTo("ops:")
            direct.namespace.assert().isEqualTo("tokens")
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
            report.type.assert().isEqualTo("Cache")
            report.keyPrefix.assert().isEqualTo("cocache:")
            report.namespace.assert().isEqualTo("user")
            report.layered.assert().isTrue()
            requireNotNull(endpoint.cache("index")).type.assert().isEqualTo("SetStore")
            report.localSize.assert().isEqualTo(1L)
            report.loads.assert().isEqualTo(1L)
            report.loadNanos.assert().isGreaterThan(0L)
            report.metrics["misses"].assert().isEqualTo(1L)
            report.metrics.keys.assert().containsExactlyElementsOf(CacheCounters.ALL.map { it.key } - LOCAL_HITS)
            endpoint.cache("missing").assert().isNull()

            endpoint.clearLocal("user")
            requireNotNull(endpoint.cache("user")).localSize.assert().isZero()
            user["1"].assert().isEqualTo("user-1")
            requireNotNull(endpoint.cache("user")).metrics["remoteHits"].assert().isEqualTo(1L)

            // 未知缓存名不报错
            endpoint.clearLocal("missing")
        }
    }

    /**
     * 订阅失败而回滚的创建从不对外可见（#830）：等待订阅确认期间补绑指标拿不到这个名字，同名重试成功之后绑定的是新的引擎，
     * 计数随新引擎变化。
     */
    @Test
    fun metricsBindTheEngineThatWasCreatedNotOneThatRolledBack() {
        val delegate = InMemoryBackend()
        val subscribing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val flaky = object : CoherenceBackend by delegate {
            override val channel = object : InvalidationChannel {
                override fun subscribe(keyspace: Keyspace, listener: InvalidationListener): Subscription {
                    if (attempts.incrementAndGet() == 1) {
                        subscribing.countDown()
                        release.awaitOrFail("subscribe released")
                        throw IllegalStateException("subscribe failed")
                    }
                    return delegate.channel.subscribe(keyspace, listener)
                }

                override suspend fun publish(keyspace: Keyspace, frame: ByteArray) = delegate.channel.publish(
                    keyspace,
                    frame
                )
            }
        }
        CacheRuntime.builder().backend(flaky).build().use { runtime ->
            val binder = CoCacheMeterBinder(runtime)
            val registry = SimpleMeterRegistry()
            binder.bindTo(registry)
            val spec = cacheSpec<String, String>("retry") {
                codec = StringCodec
                loader = CacheLoader { "v-$it" }
            }
            val first = fork { runtime.create(spec) }
            subscribing.awaitOrFail("subscribing")
            binder.bindNewCaches()
            release.countDown()
            first.joinFailure().assert().isInstanceOf(IllegalStateException::class.java)

            @Suppress("UNCHECKED_CAST")
            val cache = runtime.create(spec) as Cache<String, String>
            cache["1"].assert().isEqualTo("v-1")
            registry.count("cocache.misses", "retry").assert().isEqualTo(1.0)
        }
    }

    private fun MeterRegistry.count(name: String, cache: String): Double {
        return get(name).tag(CoCacheMeterBinder.TAG_CACHE, cache).functionCounter().count()
    }

    private fun MeterRegistry.localSize(cache: String): Double {
        return get("cocache.local.size").tag(CoCacheMeterBinder.TAG_CACHE, cache).gauge().value()
    }
}
