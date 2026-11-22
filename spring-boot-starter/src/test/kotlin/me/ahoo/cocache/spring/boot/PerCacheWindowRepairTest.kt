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
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.RefreshWindow
import me.ahoo.cocache.spring.CacheNameContributor
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 缺陷复现（#996）：库锁定或固定了 30 s 的刷新窗口，`defaults` 的 `local-max-age` 为 20 s，`caches.[name]` 把它覆盖为 60 s（或 `unlock`
 * 之后关闭窗口）：最终配置合法，创建却在读到 `caches.[name]` 之前、定制器之前的中间校验就失败了，提示又恰好建议改 `caches.[name]`。
 * 现在中间那次构建只暂时关闭窗口，是否夹紧或失败按全部层之后的配置决定；有没有定制器结果都一样。
 */
class PerCacheWindowRepairTest {
    @Configuration(proxyBeanMethods = false)
    class Names {
        @Bean
        fun names(): CacheNameContributor = CacheNameContributor { setOf("held") }
    }

    /**
     * 对这个缓存什么也不改的定制器：它的存在让定制器之前的那次构建发生。
     */
    @Configuration(proxyBeanMethods = false)
    class Customized {
        @Bean
        fun customizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
            override fun customize(spec: CacheSpec.Builder<*, *>) = Unit
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun aPerCacheAgeRepairsALockedWindow(customized: Boolean) =
        verify(global = "defaults.cache", pinned = false, unlock = false, customized = customized)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun aPerCacheAgeRepairsAPinnedWindow(customized: Boolean) =
        verify(global = "defaults.cache", pinned = true, unlock = false, customized = customized)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun anExplicitUnlockTurnsTheWindowOff(customized: Boolean) =
        verify(global = "defaults.cache", pinned = false, unlock = true, customized = customized)

    @Test
    fun theSameRepairsWorkWithOverrides() {
        verify(global = "overrides.cache", pinned = false, unlock = true)
        verify(global = "overrides.cache", pinned = false, unlock = false)
    }

    @Test
    fun aPerCacheAgeWorksWithoutDefaults() = verify(global = null, pinned = false, unlock = false)

    /**
     * 真正无效的最终配置照旧失败：没有按缓存的修复时，锁定的窗口不能被关掉；按缓存写出的 20 s 自身就与窗口冲突。
     */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun anInvalidFinalConfigurationStillFails(customized: Boolean) {
        verify(global = "defaults.cache", pinned = false, unlock = false, customized = customized, repaired = false)
        verify(global = null, pinned = false, unlock = false, customized = customized, finalAge = "20s")
    }

    /**
     * 未锁定的窗口：按缓存的 `local-max-age` 消除了 defaults 引起的冲突时窗口保留（曾在中间被夹紧关闭）；没有修复时照旧夹紧关闭。
     */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun anUnlockedWindowIsClampedOnlyByTheFinalConfiguration(customized: Boolean) {
        runner(listOf("cocache.defaults.cache.local-max-age=20s", "cocache.caches.held.local-max-age=60s"), customized)
            .run { context ->
                context.getBean(CoCacheFactory::class.java).create(spec())
                val policy = requireNotNull(context.getBean(CacheRuntime::class.java).policy("held"))
                policy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(30))
                policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(60))
            }
        runner(listOf("cocache.defaults.cache.local-max-age=20s"), customized).run { context ->
            context.getBean(CoCacheFactory::class.java).create(spec())
            requireNotNull(context.getBean(CacheRuntime::class.java).policy("held")).refreshWindow.assert().isNull()
        }
    }

    /**
     * 冲突来自 `ttl` 减抖动（defaults 的 `ttl` 60 s，抖动取 30 s）：按缓存的 `ttl` 消除冲突时窗口保留；没有时照旧夹紧关闭。
     */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun aTtlConflictIsJudgedOnTheFinalConfiguration(customized: Boolean) {
        runner(listOf("cocache.defaults.cache.ttl=60s", "cocache.caches.held.ttl=10m"), customized).run { context ->
            context.getBean(CoCacheFactory::class.java).create(spec())
            requireNotNull(context.getBean(CacheRuntime::class.java).policy("held")).refreshWindow
                .assert().isEqualTo(Duration.ofSeconds(30))
        }
        runner(listOf("cocache.defaults.cache.ttl=60s"), customized).run { context ->
            context.getBean(CoCacheFactory::class.java).create(spec())
            requireNotNull(context.getBean(CacheRuntime::class.java).policy("held")).refreshWindow.assert().isNull()
        }
        // 永不过期：只有 local-max-age 一条约束，30 s 的窗口小于默认的 5 min，保留
        runner(listOf("cocache.defaults.cache.ttl=forever"), customized).run { context ->
            context.getBean(CoCacheFactory::class.java).create(spec())
            val policy = requireNotNull(context.getBean(CacheRuntime::class.java).policy("held"))
            policy.ttl.assert().isNull()
            policy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(30))
        }
    }

    /**
     * 定制器在窗口被暂时关闭时改了它（未锁定的窗口）：以定制器的值为准，不恢复被暂时关闭的声明值。
     */
    @Test
    fun aCustomizerThatChangesTheSuspendedWindowWins() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withPropertyValues("cocache.backend=memory", "cocache.defaults.cache.local-max-age=20s")
            .withUserConfiguration(Names::class.java, ShorterWindow::class.java)
            .run { context ->
                context.getBean(CoCacheFactory::class.java).create(spec())
                val policy = requireNotNull(context.getBean(CacheRuntime::class.java).policy("held"))
                policy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(10))
                policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(20))
            }
    }

    /**
     * 把窗口改为 10 s 的定制器。
     */
    @Configuration(proxyBeanMethods = false)
    class ShorterWindow {
        @Bean
        fun customizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
            override fun customize(spec: CacheSpec.Builder<*, *>) {
                spec.policy { refreshWindow = Duration.ofSeconds(10) }
            }
        }
    }

    private fun spec() = cacheSpec<String, String>("held") {
        codec = StringCodec
        loader = CacheLoader { "value" }
        policy { refreshWindow = Duration.ofSeconds(30) }
    }

    private fun runner(properties: List<String>, customized: Boolean): ApplicationContextRunner {
        val runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withPropertyValues("cocache.backend=memory", *properties.toTypedArray())
            .withUserConfiguration(Names::class.java)
        return if (customized) runner.withUserConfiguration(Customized::class.java) else runner
    }

    @Suppress("LongParameterList")
    private fun verify(
        global: String?,
        pinned: Boolean,
        unlock: Boolean,
        customized: Boolean = false,
        repaired: Boolean = true,
        finalAge: String = "60s",
    ) {
        val properties = mutableListOf<String>()
        if (global != null) {
            properties += "cocache.$global.local-max-age=20s"
        }
        if (unlock) {
            properties += "cocache.caches.held.unlock=refresh-window"
            properties += "cocache.caches.held.refresh-window=off"
        } else if (repaired) {
            properties += "cocache.caches.held.local-max-age=$finalAge"
        }
        runner(properties, customized).run { context ->
            context.startupFailure.assert().isNull()
            val locks = Locks.builder("window-library").apply {
                if (pinned) {
                    pin(PolicyField.REFRESH_WINDOW, RefreshWindow.Of(Duration.ofSeconds(30)))
                } else {
                    lock(PolicyField.REFRESH_WINDOW)
                }
            }.build()
            val factory = context.getBean(CoCacheFactory::class.java)
            if (!repaired || finalAge == "20s") {
                runCatching { factory.create(spec(), locks) }.exceptionOrNull().assert()
                    .isInstanceOf(IllegalArgumentException::class.java)
                return@run
            }
            factory.create(spec(), locks)
            val policy = requireNotNull(context.getBean(CacheRuntime::class.java).policy("held"))
            policy.refreshWindow.assert().isEqualTo(if (unlock) null else Duration.ofSeconds(30))
            policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(if (unlock) 20 else 60))
        }
    }
}
