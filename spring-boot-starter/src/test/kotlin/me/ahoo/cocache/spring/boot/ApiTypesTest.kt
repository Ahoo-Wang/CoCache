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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ResolvableType
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 五种用户 API 类型的接入（RFC 0005 §3.1）：接口式声明按它继承的类型翻译为对应的 spec，spec bean 注册为对应类型的缓存 bean；
 * 数据来源的规则（§4.1）在启动时校验。
 */
class ApiTypesTest {
    /**
     * 有数据源的派生副本：按泛型匹配 `CacheLoader<String, String>`。
     */
    @CoCache(name = "derived")
    interface DerivedCache : Cache<String, String>

    /**
     * 外部来源的派生副本：值由调用方提供。
     */
    @CoCache(name = "external", source = CoCache.Source.EXTERNAL)
    interface ExternalCache : Cache<String, String>

    /**
     * 带版本的派生副本：按泛型匹配 `CacheLoader<String, Versioned<String>>`。
     */
    @CoCache(name = "view")
    interface ViewCache : VersionedCache<String, String>

    @CoCache(name = "record")
    interface RecordStore : Store<String, String>

    @CoCache(name = "ledger")
    interface LedgerStore : VersionedStore<String, String>

    @CoCache(name = "members")
    interface MemberStore : SetStore<String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(
        caches = [
            DerivedCache::class,
            ExternalCache::class,
            ViewCache::class,
            RecordStore::class,
            LedgerStore::class,
            MemberStore::class,
        ]
    )
    class InterfaceConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun codec(): Codec<String> = StringCodec

        @Bean
        fun loader(): CacheLoader<String, String> = CacheLoader { "loaded-$it" }

        @Bean
        fun versionedLoader(): CacheLoader<String, Versioned<String>> = CacheLoader { Versioned("v-$it", 1) }
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))

    /**
     * 每种类型的接口式声明都创建为它继承的那种缓存，代理按那个类型的方法集转发（RFC 0005 §4.2）。
     */
    @Test
    fun everyApiTypeIsCreatedAsTheTypeItExtends() {
        runner.withUserConfiguration(InterfaceConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val runtime = context.getBean(CacheRuntime::class.java)
            listOf(
                DerivedCache::class.java to Cache::class.java,
                ExternalCache::class.java to Cache::class.java,
                ViewCache::class.java to VersionedCache::class.java,
                RecordStore::class.java to Store::class.java,
                LedgerStore::class.java to VersionedStore::class.java,
                MemberStore::class.java to SetStore::class.java,
            ).forEach { (declared, apiType) ->
                val bean = context.getBean(declared)
                val target = requireNotNull(CacheProxies.targetOf(bean))
                target.assert().isSameAs(runtime.cache(AnnotatedCacheFactory.cacheNameOf(declared)))
                apiType.isInstance(
                    target
                ).assert().withFailMessage { "$declared is created as ${apiType.simpleName}" }.isTrue()
            }

            val derived = context.getBean(DerivedCache::class.java)
            derived["a"].assert().isEqualTo("loaded-a")
            derived.invalidate("a")

            val external = context.getBean(ExternalCache::class.java)
            external["a"].assert().isNull()
            external.get("a") { "called-$it" }.assert().isEqualTo("called-a")
            external.put("b", "put-b")
            external["b"].assert().isEqualTo("put-b")

            val view = context.getBean(ViewCache::class.java)
            view.getVersioned("a").assert().isEqualTo(Versioned("v-a", 1))
            view.putIfNewer("a", "x", 2).assert().isEqualTo(VersionedPut.Written)
            view.putIfNewer("a", "old", 1).assert().isEqualTo(VersionedPut.Stale(2))
            view.evict("a")
            view.putIfNewer("a", "older", 1).assert().isEqualTo(VersionedPut.Stale(2))
            view.purge("a")

            val record = context.getBean(RecordStore::class.java)
            record["k"].assert().isNull()
            val written = record.putIfMatch("k", null, "v1") as ConditionalPut.Written
            (record.getIfChanged("k", written.digest) === ConditionalGet.Unchanged).assert().isTrue()
            record.deleteIfMatch("k", written.digest).assert().isEqualTo(ConditionalDelete.Deleted)
            record.put("k", "v2")
            record.delete("k")
            record["k"].assert().isNull()

            val ledger = context.getBean(LedgerStore::class.java)
            ledger.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
            ledger.getVersioned("k").assert().isEqualTo(Versioned("v1", 1))
            ledger.deleteIfNewer("k", 2).assert().isEqualTo(VersionedPut.Written)
            ledger["k"].assert().isNull()
            ledger.purge("k")

            val members = context.getBean(MemberStore::class.java)
            members["k"].assert().isEmpty()
            members.add("k", listOf("a", "b"))
            members["k"].assert().containsExactlyInAnyOrder("a", "b")
            members.clear("k")
            members["k"].assert().isEmpty()
        }
    }

    @CoCache(name = "orphan")
    interface OrphanCache : Cache<Long, String>

    @CoCache(name = "externalWithLoader", loader = "loader", source = CoCache.Source.EXTERNAL)
    interface ExternalWithLoaderCache : Cache<String, String>

    @CoCache(name = "orphanView")
    interface OrphanView : VersionedCache<Long, String>

    @CoCache(name = "loadedStore", loader = "loader")
    interface LoaderStore : Store<String, String>

    @CoCache(name = "externalLedger", source = CoCache.Source.EXTERNAL)
    interface ExternalLedger : VersionedStore<String, String>

    @CoCache(name = "externalMembers", source = CoCache.Source.EXTERNAL)
    interface ExternalMembers : SetStore<String, String>

    interface ReadOnlyCache : ReadableCache<String, String>

    private fun <C : ReadableCache<*, *>> createFailure(type: Class<C>): Throwable? {
        var failure: Throwable? = null
        runner.withUserConfiguration(InterfaceConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            failure = runCatching { context.getBean(AnnotatedCacheFactory::class.java).create(type) }.exceptionOrNull()
        }
        return failure
    }

    /**
     * 派生缓存必须声明数据来源（RFC 0005 §4.1，V1）：既没有匹配到数据源、也没有声明外部来源时启动失败，而不是静默地成为“没有数据源”。
     */
    @Test
    fun aCacheWithoutASourceFailsStartup() {
        createFailure(OrphanCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("has no data source")
            .hasMessageContaining("source = EXTERNAL")
        createFailure(ExternalWithLoaderCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cannot name a loader")
        createFailure(OrphanView::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("has no versioned data source")
            .hasMessageContaining("VersionedStore")
    }

    /**
     * 权威类型没有数据源：`loader` 与 `source = EXTERNAL` 都启动失败。
     */
    @Test
    fun authoritativeTypesRejectASource() {
        createFailure(LoaderStore::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not supported on Store")
        createFailure(ExternalLedger::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("only supported on Cache interfaces")
        createFailure(ExternalMembers::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("only supported on Cache interfaces")
    }

    /**
     * 声明接口必须恰好继承一种用户 API 类型：只继承 `ReadableCache` 时失败（同时继承两种在编译期就因 `async()` 的返回类型冲突而失败）。
     */
    @Test
    fun anInterfaceMustExtendExactlyOneApiType() {
        createFailure(ReadOnlyCache::class.java).assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must extend exactly one of")
    }

    /**
     * 可重建的副本误声明成权威类型（RFC 0005 §4.1 的误分类表）：存在匹配的 `CacheLoader` bean 时告警，照常创建。
     */
    @Test
    fun aStoreWithAMatchingLoaderWarns() {
        val logger = LoggerFactory.getLogger(AnnotatedCacheFactory::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            runner.withUserConfiguration(InterfaceConfig::class.java).run { context ->
                assertThat(context).hasNotFailed()
            }
        } finally {
            logger.detachAppender(appender)
        }
        val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        warnings.filter { it.contains(RecordStore::class.java.name) }.single().assert()
            .contains("[loader]").contains("extend Cache instead")
        // 集合与带版本的权威类型的值类型与这些数据源不同：不告警
        warnings.none { it.contains(MemberStore::class.java.name) }.assert().isTrue()
    }

    /**
     * 定制器的五个重载：每种 spec 只经它自己的重载定制。
     */
    class RecordingCustomizer : CacheSpecCustomizer {
        val customized = CopyOnWriteArrayList<String>()

        override fun customize(spec: CacheSpec.Builder<*, *>) {
            customized += "Cache:${spec.name}"
        }

        override fun customize(spec: VersionedCacheSpec.Builder<*, *>) {
            customized += "VersionedCache:${spec.name}"
        }

        override fun customize(spec: StoreSpec.Builder<*, *>) {
            customized += "Store:${spec.name}"
        }

        override fun customize(spec: VersionedStoreSpec.Builder<*, *>) {
            customized += "VersionedStore:${spec.name}"
        }

        override fun customize(spec: SetStoreSpec.Builder<*, *>) {
            customized += "SetStore:${spec.name}"
        }
    }

    @Configuration(proxyBeanMethods = false)
    class SpecConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun customizer(): RecordingCustomizer = RecordingCustomizer()

        @Bean
        fun profileSpec(): CacheSpec<String, String> = cacheSpec("profile") {
            codec = StringCodec
            externallySourced()
        }

        @Bean
        fun stateSpec(): VersionedCacheSpec<String, String> = versionedCacheSpec("state") {
            codec = StringCodec
            versionedLoader = CacheLoader { Versioned("s-$it", 1) }
        }

        @Bean
        fun policySpec(): StoreSpec<String, String> = storeSpec("policy") { codec = StringCodec }

        @Bean
        fun eventSpec(): VersionedStoreSpec<String, String> = versionedStoreSpec("event") { codec = StringCodec }

        @Bean
        fun indexSpec(): SetStoreSpec<String, String> = setStoreSpec("index") { elementCodec = StringCodec }
    }

    /**
     * 每种 spec bean 注册为对应类型（带 spec 的泛型）的缓存 bean，经定制器的对应重载。
     */
    @Test
    fun specBeansOfEveryTypeAreRegisteredWithTheirType() {
        runner.withUserConfiguration(SpecConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            mapOf(
                "profile" to Cache::class.java,
                "state" to VersionedCache::class.java,
                "policy" to Store::class.java,
                "event" to VersionedStore::class.java,
                "index" to SetStore::class.java,
            ).forEach { (beanName, apiType) ->
                val type = ResolvableType.forClassWithGenerics(apiType, String::class.java, String::class.java)
                context.getBeanNamesForType(
                    type
                ).assert().withFailMessage { "$beanName is a $type" }.containsExactly(beanName)
                apiType.isInstance(context.getBean(beanName)).assert().isTrue()
            }
            context.getBean(RecordingCustomizer::class.java).customized.assert().containsExactlyInAnyOrder(
                "Cache:profile",
                "VersionedCache:state",
                "Store:policy",
                "VersionedStore:event",
                "SetStore:index",
            )
            @Suppress("UNCHECKED_CAST")
            val state = context.getBean("state") as VersionedCache<String, String>
            state.getVersioned("k").assert().isEqualTo(Versioned("s-k", 1))
        }
    }

    /**
     * 新的计数（RFC 0005 §8）：`cocache.reads.unknown` 与 `cocache.fill.unversioned.absent` 绑定到每个缓存。
     */
    @Test
    fun newMetersAreBound() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(CoCacheAutoConfiguration::class.java, CoCacheMetricsAutoConfiguration::class.java)
            )
            .withUserConfiguration(SpecConfig::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val registry = SimpleMeterRegistry()
                context.getBean(CoCacheMeterBinder::class.java).bindTo(registry)
                listOf("cocache.reads.unknown", "cocache.fill.unversioned.absent").forEach { name ->
                    listOf("profile", "state", "policy", "event", "index").forEach { cache ->
                        registry.get(name).tag(CoCacheMeterBinder.TAG_CACHE, cache).functionCounter().count()
                            .assert().isZero()
                    }
                }
            }
    }
}
