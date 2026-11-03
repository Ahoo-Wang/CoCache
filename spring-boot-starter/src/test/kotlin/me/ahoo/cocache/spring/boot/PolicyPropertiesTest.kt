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
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.setCacheSpec
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
 * 配置文件对策略的语义：`refresh-window: 0` 关闭提前刷新；全局寿命不作用于集合缓存；`foreign-format: reclaim` 只用于有数据源的缓存。
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
        fun indexSpec(): SetCacheSpec<String, String> = setCacheSpec("index") { elementCodec = StringCodec }
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
    fun defaultTtlDoesNotExpireSetCaches() {
        runner.withPropertyValues(
            "cocache.defaults.ttl=60",
            "cocache.defaults.absent-ttl=5",
            "cocache.defaults.refresh-window=10",
            "cocache.defaults.local-max-age=90",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(CoCacheProperties::class.java)
            val setPolicy = properties.setPolicyFor("index", SetCacheSpec.DEFAULT_POLICY)
            setPolicy.ttl.assert().isNull()
            // 负缓存寿命与提前刷新对集合缓存没有意义，不继承；其它默认项照常生效
            setPolicy.absentTtl.assert().isEqualTo(SetCacheSpec.DEFAULT_POLICY.absentTtl)
            setPolicy.refreshWindow.assert().isNull()
            setPolicy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(90))
            properties.policyFor("user", CachePolicy.DEFAULT).ttl.assert().isEqualTo(Duration.ofSeconds(60))

            @Suppress("UNCHECKED_CAST")
            val index = context.getBean("index") as SetCache<String, String>
            index.add("k", listOf("a"))
            val read = context.getBean(CoherenceBackend::class.java).store("index").read("cocache:index:k", null, null)
            (read as RemoteRead.Hit).ttl.assert().isNull()
        }
    }

    @Test
    fun perCacheTtlStillAppliesToSetCaches() {
        runner.withPropertyValues("cocache.defaults.ttl=60", "cocache.caches.index.ttl=30").run { context ->
            val properties = context.getBean(CoCacheProperties::class.java)
            properties.setPolicyFor("index", SetCacheSpec.DEFAULT_POLICY).ttl.assert().isEqualTo(Duration.ofSeconds(30))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class VersionedConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun viewSpec(): CacheSpec<String, String> = cacheSpec("view") {
            codec = StringCodec
            versioned = true
        }

        @Bean
        fun loadedViewSpec(): CacheSpec<String, String> = cacheSpec("loaded-view") {
            codec = StringCodec
            versionedLoader = CacheLoader { Versioned("v-$it", 1) }
        }

        @Bean
        fun asyncViewSpec(): CacheSpec<String, String> = cacheSpec("async-view") {
            codec = StringCodec
            versionedAsyncLoader = AsyncCacheLoader { CompletableFuture.completedFuture(Versioned("a-$it", 1)) }
        }
    }

    /**
     * 带版本的缓存不支持提前刷新（6.8）：`cocache.defaults.refresh-window` 不作用于它们，其它默认项照常生效；
     * 只有显式的 `cocache.caches.[name].refresh-window` 作用于它，并因此启动失败。
     */
    @Test
    fun defaultRefreshWindowSkipsVersionedCaches() {
        val versioned = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withUserConfiguration(VersionedConfig::class.java)
        versioned.withPropertyValues("cocache.defaults.refresh-window=10", "cocache.defaults.ttl=60").run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(CoCacheProperties::class.java)
            val policy = properties.policyFor("view", CachePolicy.DEFAULT, versioned = true)
            policy.refreshWindow.assert().isNull()
            policy.ttl.assert().isEqualTo(Duration.ofSeconds(60))

            @Suppress("UNCHECKED_CAST")
            val view = context.getBean("view") as Cache<String, String>
            view.putIfNewer("k", "v", 1).assert().isEqualTo(VersionedPut.Written)
            @Suppress("UNCHECKED_CAST")
            val loaded = context.getBean("loadedView") as Cache<String, String>
            loaded.getVersioned("k").assert().isEqualTo(Versioned("v-k", 1))
            @Suppress("UNCHECKED_CAST")
            val async = context.getBean("asyncView") as Cache<String, String>
            async.getVersioned("k").assert().isEqualTo(Versioned("a-k", 1))
            requireNotNull(
                context.getBean(CacheRuntime::class.java).metrics("view")
            ).staleVersions.assert().isEqualTo(0L)
        }
        versioned.withPropertyValues("cocache.caches.view.refresh-window=10").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("refreshWindow")
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ForeignFormatConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader { "user-$it" }
        }

        @Bean
        fun profileSpec(): CacheSpec<String, String> = cacheSpec("profile") { codec = StringCodec }

        @Bean
        fun viewSpec(): CacheSpec<String, String> = cacheSpec("view") {
            codec = StringCodec
            versioned = true
        }

        @Bean
        fun indexSpec(): SetCacheSpec<String, String> = setCacheSpec("index") { elementCodec = StringCodec }
    }

    /**
     * `foreign-format: reclaim` 只能用于有数据源的缓存（没有数据源时 L1 是唯一副本，回收就是删除它）：
     * - `cocache.defaults.foreign-format: reclaim` 只作用于有数据源的缓存，没有数据源的缓存（含带版本的）与集合缓存跳过它，启动不失败；
     *   `fail` 照常作用于没有数据源的缓存。
     * - `cocache.caches.[name].foreign-format: reclaim` 落在没有数据源的缓存或集合缓存上时启动失败，消息带缓存名。
     */
    @Test
    fun reclaimOnlyAppliesToCachesWithASource() {
        val foreign = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withUserConfiguration(ForeignFormatConfig::class.java)
        foreign.withPropertyValues("cocache.defaults.foreign-format=reclaim").run { context ->
            assertThat(context).hasNotFailed()
            listOf("user", "profile", "view", "index").forEach { context.getBean(it) }
            val properties = context.getBean(CoCacheProperties::class.java)
            properties.policyFor(
                "user",
                CachePolicy.DEFAULT
            ).foreignFormat.assert().isEqualTo(ForeignFormatMode.RECLAIM)
            properties.policyFor("profile", CachePolicy.DEFAULT, sourced = false).foreignFormat.assert().isNull()
            properties.policyFor(
                "view",
                CachePolicy.DEFAULT,
                versioned = true,
                sourced = false
            ).foreignFormat.assert().isNull()
            properties.setPolicyFor("index", SetCacheSpec.DEFAULT_POLICY).foreignFormat.assert().isNull()
        }
        foreign.withPropertyValues("cocache.defaults.foreign-format=fail", "cocache.caches.user.foreign-format=reclaim")
            .run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(CoCacheProperties::class.java)
                properties.policyFor(
                    "user",
                    CachePolicy.DEFAULT
                ).foreignFormat.assert().isEqualTo(ForeignFormatMode.RECLAIM)
                properties.policyFor(
                    "profile",
                    CachePolicy.DEFAULT,
                    sourced = false
                ).foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
            }
        mapOf(
            "profile" to "Cache[profile]",
            "view" to "Cache[view]",
            "index" to "SetCache[index]"
        ).forEach { (name, prefix) ->
            foreign.withPropertyValues("cocache.caches.$name.foreign-format=reclaim").run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .hasMessageStartingWith("$prefix - foreignFormat = RECLAIM")
            }
        }
    }
}
