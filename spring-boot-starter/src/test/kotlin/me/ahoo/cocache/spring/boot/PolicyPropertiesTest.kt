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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 配置文件对策略的语义：`refresh-window: 0` 关闭提前刷新；全局寿命与提前刷新不作用于权威副本（`Store`、`VersionedStore`、`SetStore`）。
 */
class PolicyPropertiesTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withUserConfiguration(SetConfig::class.java)

    @Configuration(proxyBeanMethods = false)
    class SetConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun indexSpec(): SetStoreSpec<String, String> = setStoreSpec("index") { elementCodec = StringCodec }
    }

    @Test
    fun zeroRefreshWindowTurnsRefreshOff() {
        runner.withPropertyValues("cocache.caches.index.refresh-window=0").run { context ->
            val properties = context.getBean(CoCacheProperties::class.java)
            val declared = CachePolicy(ttl = Duration.ofMinutes(10), refreshWindow = Duration.ofMinutes(1))
            properties.policyFor("index", declared).refreshWindow.assert().isNull()
        }
    }

    @Test
    fun defaultTtlDoesNotExpireStores() {
        runner.withPropertyValues(
            "cocache.defaults.ttl=60",
            "cocache.defaults.absent-ttl=5",
            "cocache.defaults.refresh-window=10",
            "cocache.defaults.local-max-age=90",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(CoCacheProperties::class.java)
            val setPolicy = properties.authoritativePolicyFor("index", SetStoreSpec.DEFAULT_POLICY)
            setPolicy.ttl.assert().isNull()
            // 提前刷新对权威副本没有意义，不继承；其它默认项（含 absent-ttl：权威副本的未命中进入 L2）照常生效
            setPolicy.absentTtl.assert().isEqualTo(Duration.ofSeconds(5))
            setPolicy.refreshWindow.assert().isNull()
            setPolicy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(90))
            properties.policyFor("user", CachePolicy.DEFAULT).ttl.assert().isEqualTo(Duration.ofSeconds(60))

            @Suppress("UNCHECKED_CAST")
            val index = context.getBean("index") as SetStore<String, String>
            index.add("k", listOf("a"))
            val store = context.getBean(CoherenceBackend::class.java).store(Keyspace("cocache:", "index"))
            val read = Suspending.await { store.read("cocache:index:k", null, null, null) }
            (read as L1Read.Hit).ttl.assert().isNull()
        }
    }

    @Test
    fun perCacheTtlStillAppliesToStores() {
        runner.withPropertyValues("cocache.defaults.ttl=60", "cocache.caches.index.ttl=30").run { context ->
            val properties = context.getBean(CoCacheProperties::class.java)
            properties.authoritativePolicyFor(
                "index",
                SetStoreSpec.DEFAULT_POLICY
            ).ttl.assert().isEqualTo(Duration.ofSeconds(30))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class VersionedConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun viewSpec(): VersionedCacheSpec<String, String> = versionedCacheSpec("view") {
            codec = StringCodec
            versionedLoader = CacheLoader { null }
        }

        @Bean
        fun loadedViewSpec(): VersionedCacheSpec<String, String> = versionedCacheSpec("loaded-view") {
            codec = StringCodec
            versionedLoader = CacheLoader { Versioned("v-$it", 1) }
        }

        @Bean
        fun asyncViewSpec(): VersionedCacheSpec<String, String> = versionedCacheSpec("async-view") {
            codec = StringCodec
            versionedAsyncLoader = AsyncCacheLoader { CompletableFuture.completedFuture(Versioned("a-$it", 1)) }
        }

        @Bean
        fun ledgerSpec(): VersionedStoreSpec<String, String> = versionedStoreSpec("ledger") { codec = StringCodec }
    }

    /**
     * `VersionedCache` 照常提前刷新（RFC 0005 §4.7）：`cocache.defaults.refresh-window` 与其它默认项一样作用于它们，
     * `cocache.caches.[name].refresh-window` 同样。`VersionedStore` 是权威副本：全局寿命与提前刷新不作用于它。
     */
    @Test
    fun defaultRefreshWindowAppliesToVersionedCaches() {
        val versioned = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withUserConfiguration(VersionedConfig::class.java)
        versioned.withPropertyValues("cocache.defaults.refresh-window=10", "cocache.defaults.ttl=60").run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(CoCacheProperties::class.java)
            val policy = properties.policyFor("view", CachePolicy.DEFAULT)
            policy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(10))
            policy.ttl.assert().isEqualTo(Duration.ofSeconds(60))

            @Suppress("UNCHECKED_CAST")
            val view = context.getBean("view") as VersionedCache<String, String>
            view.putIfNewer("k", "v", 1).assert().isEqualTo(VersionedPut.Written)
            @Suppress("UNCHECKED_CAST")
            val loaded = context.getBean("loadedView") as VersionedCache<String, String>
            loaded.getVersioned("k").assert().isEqualTo(Versioned("v-k", 1))
            @Suppress("UNCHECKED_CAST")
            val async = context.getBean("asyncView") as VersionedCache<String, String>
            async.getVersioned("k").assert().isEqualTo(Versioned("a-k", 1))
            val runtime = context.getBean(CacheRuntime::class.java)
            requireNotNull(runtime.policy("ledger")).refreshWindow.assert().isNull()
            requireNotNull(runtime.policy("ledger")).ttl.assert().isNull()
            @Suppress("UNCHECKED_CAST")
            val ledger = context.getBean("ledger") as VersionedStore<String, String>
            ledger.putIfNewer("k", "v", 1).assert().isEqualTo(VersionedPut.Written)
            requireNotNull(
                context.getBean(CacheRuntime::class.java).metrics("view")
            ).staleVersions.assert().isEqualTo(0L)
        }
        versioned.withPropertyValues("cocache.caches.view.refresh-window=10").run { context ->
            assertThat(context).hasNotFailed()
            requireNotNull(context.getBean(CacheRuntime::class.java).admin("view")).name.assert().isEqualTo("view")
        }
    }
}
