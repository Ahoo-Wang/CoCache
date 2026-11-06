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
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.PolicyReport
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ByteArrayResource
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 配置文件对策略的语义：`refresh-window: 0s` 与 `off` 关闭提前刷新；`cocache.defaults.cache.*` 不作用于权威副本
 * （`Store`、`VersionedStore`、`SetStore`），`cocache.defaults.store.*` 作用于它们，但 `defaults.store.ttl` 不允许。
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

        @Bean
        fun userSpec(): CacheSpec<String, String> = cacheSpec("user") {
            codec = StringCodec
            loader = CacheLoader { "user-$it" }
            policy {
                ttl = Duration.ofMinutes(10)
                refreshWindow = Duration.ofMinutes(1)
            }
        }
    }

    @Test
    fun zeroOrOffRefreshWindowTurnsRefreshOff() {
        // `false`：YAML 中不带引号的 `off` 绑定到字符串属性时的样子
        listOf("0s", "off", "OFF", "false").forEach { value ->
            runner.withPropertyValues("cocache.caches.user.refresh-window=$value").run { context ->
                assertThat(context).hasNotFailed()
                context.getBean("user")
                requireNotNull(context.getBean(CacheRuntime::class.java).policy("user")).refreshWindow.assert().isNull()
            }
        }
    }

    /**
     * YAML 把不带引号的 `off` 读作布尔 `false`（Spring Boot 保留 SnakeYAML 的布尔解析）：它照样关闭提前刷新，而不是启动失败。
     */
    @Test
    fun bareYamlOffTurnsRefreshOff() {
        val yaml = """
            cocache:
              caches:
                user:
                  refresh-window: off
        """.trimIndent()
        runner.withInitializer { context ->
            YamlPropertySourceLoader().load("application.yaml", ByteArrayResource(yaml.toByteArray()))
                .forEach { context.environment.propertySources.addFirst(it) }
        }.run { context ->
            assertThat(context).hasNotFailed()
            context.getBean("user")
            requireNotNull(context.getBean(CacheRuntime::class.java).policy("user")).refreshWindow.assert().isNull()
        }
    }

    @Test
    fun cacheDefaultsDoNotReachStores() {
        runner.withPropertyValues(
            "cocache.defaults.cache.ttl=60s",
            "cocache.defaults.cache.absent-ttl=1s",
            "cocache.defaults.cache.local-max-age=2m",
            "cocache.defaults.store.ttl=60s",
            "cocache.defaults.store.absent-ttl=5s",
            "cocache.defaults.store.refresh-window=10s",
            "cocache.defaults.store.local-max-age=90s",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val runtime = context.getBean(CacheRuntime::class.java)
            context.getBean("index")
            val setPolicy = requireNotNull(runtime.policy("index"))
            // 权威副本的寿命即语义：defaults.store.ttl 跳过，ttl 仍是类型默认值（永不过期）
            setPolicy.ttl.assert().isNull()
            // absent-ttl 适用于权威副本（未命中进入 L2）；提前刷新不适用，跳过
            setPolicy.absentTtl.assert().isEqualTo(Duration.ofSeconds(5))
            setPolicy.refreshWindow.assert().isNull()
            setPolicy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(90))
            val skipped = requireNotNull(requireNotNull(runtime.admin("index")).policyReport).skipped
            skipped.map { it.key }.assert().containsExactlyInAnyOrder(
                "cocache.defaults.store.ttl",
                "cocache.defaults.store.refresh-window"
            )
            skipped.first { it.key == "cocache.defaults.store.ttl" }.reason.assert().contains("not allowed")
            skipped.first { it.key == "cocache.defaults.store.refresh-window" }.reason.assert()
                .isEqualTo("not applicable to a SetStore")
            // 派生副本只看 cache 一节；声明写出的 ttl 不被 defaults 改变
            context.getBean("user")
            val user = requireNotNull(runtime.policy("user"))
            user.ttl.assert().isEqualTo(Duration.ofMinutes(10))
            user.absentTtl.assert().isEqualTo(Duration.ofSeconds(1))
            user.localMaxAge.assert().isEqualTo(Duration.ofMinutes(2))

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
        runner.withPropertyValues("cocache.defaults.store.ttl=60s", "cocache.caches.index.ttl=30s").run { context ->
            assertThat(context).hasNotFailed()
            context.getBean("index")
            requireNotNull(
                context.getBean(CacheRuntime::class.java).policy("index")
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
     * `VersionedStore` 的墓碑必须有限：全局层的 `tombstone-ttl: forever` 对它跳过并报告（与其它全局层冲突相同，不让启动失败），
     * 对 `VersionedCache` 照常生效；`cocache.caches.[name]` 中为 `VersionedStore` 写 `forever` 仍让启动失败。
     */
    @Test
    fun globalForeverTombstoneTtlIsSkippedForVersionedStores() {
        val versioned = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withUserConfiguration(VersionedConfig::class.java)
        versioned.withPropertyValues(
            "cocache.defaults.store.tombstone-ttl=forever",
            "cocache.defaults.cache.tombstone-ttl=forever",
        ).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean("ledger")
            context.getBean("view")
            val runtime = context.getBean(CacheRuntime::class.java)
            requireNotNull(runtime.policy("ledger")).tombstoneTtl.assert()
                .isEqualTo(DefaultPolicies.VERSIONED_STORE.tombstoneTtl)
            requireNotNull(requireNotNull(runtime.admin("ledger")).policyReport).skipped.assert().contains(
                PolicyReport.Skipped(
                    "cocache.defaults.store.tombstone-ttl",
                    "cocache.defaults.store",
                    "a VersionedStore needs a finite tombstone-ttl"
                )
            )
            requireNotNull(requireNotNull(runtime.admin("view")).policyReport).entries
                .first { it.field == "tombstone-ttl" }.assert()
                .isEqualTo(PolicyReport.Entry("tombstone-ttl", "forever", PolicyReport.Source.DEFAULTS))
        }
        versioned.withPropertyValues("cocache.caches.ledger.tombstone-ttl=forever").run { context ->
            assertThat(context).hasFailed()
        }
        // 有限的全局墓碑寿命照常作用于 VersionedStore
        versioned.withPropertyValues("cocache.defaults.store.tombstone-ttl=2h").run { context ->
            assertThat(context).hasNotFailed()
            context.getBean("ledger")
            val runtime = context.getBean(CacheRuntime::class.java)
            requireNotNull(runtime.policy("ledger")).tombstoneTtl.assert().isEqualTo(Duration.ofHours(2))
            requireNotNull(requireNotNull(runtime.admin("ledger")).policyReport).skipped.assert().isEmpty()
        }
    }

    /**
     * `VersionedCache` 照常提前刷新（RFC 0005 §4.7）：`cocache.defaults.cache.refresh-window` 与其它默认项一样作用于它们，
     * `cocache.caches.[name].refresh-window` 同样。`VersionedStore` 是权威副本：`cache` 一节不作用于它。
     */
    @Test
    fun defaultRefreshWindowAppliesToVersionedCaches() {
        val versioned = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withUserConfiguration(VersionedConfig::class.java)
        versioned.withPropertyValues(
            "cocache.defaults.cache.refresh-window=10s",
            "cocache.defaults.cache.ttl=60s"
        ).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean("view")
            val policy = requireNotNull(context.getBean(CacheRuntime::class.java).policy("view"))
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
            requireNotNull(runtime.policy("ledger")).tombstoneTtl.assert()
                .isEqualTo(DefaultPolicies.VERSIONED_STORE.tombstoneTtl)
            @Suppress("UNCHECKED_CAST")
            val ledger = context.getBean("ledger") as VersionedStore<String, String>
            ledger.putIfNewer("k", "v", 1).assert().isEqualTo(VersionedPut.Written)
            requireNotNull(
                context.getBean(CacheRuntime::class.java).metrics("view")
            ).staleVersions.assert().isEqualTo(0L)
        }
        versioned.withPropertyValues("cocache.caches.view.refresh-window=10s").run { context ->
            assertThat(context).hasNotFailed()
            context.getBean("view")
            requireNotNull(
                context.getBean(CacheRuntime::class.java).policy("view")
            ).refreshWindow.assert().isEqualTo(Duration.ofSeconds(10))
        }
    }
}
