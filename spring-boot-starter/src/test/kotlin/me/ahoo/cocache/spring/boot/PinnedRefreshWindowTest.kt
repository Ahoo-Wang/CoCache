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
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.RefreshWindow
import me.ahoo.cocache.spring.CacheNameContributor
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/** defaults 的中间校验应按固定后的窗口判断，不能拿已被 pin 替换的旧窗口误报冲突（#967）。 */
class PinnedRefreshWindowTest {
    @Configuration(proxyBeanMethods = false)
    class Names {
        @Bean
        fun names(): CacheNameContributor = CacheNameContributor { setOf("pinned") }
    }

    @Test
    fun aSmallerPinnedWindowMustFitTheGlobalDefaultAge() = verify("defaults.cache", WINDOW)

    @Test
    fun aPinnedOffWindowMustNotConflictWithTheGlobalDefaultAge() = verify("defaults.cache", RefreshWindow.Off)

    @Test
    fun theSamePinnedWindowFitsAnOverrideAge() = verify("overrides.cache", WINDOW)

    @Test
    fun theSamePinnedWindowFitsAPerCacheAge() = verify("caches.pinned", WINDOW)

    @Test
    fun pinningWorksWithoutTheGlobalDefault() = verify(null, WINDOW)

    /**
     * 对照：固定值本身与全局层冲突时照常失败（不静默关掉固定的窗口），消息给出固定值，而不是被替换掉的声明值。
     */
    @Test
    fun aConflictingPinnedWindowStillFailsWithItsOwnValue() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withPropertyValues("cocache.backend=memory", "cocache.defaults.cache.local-max-age=20s")
            .withUserConfiguration(Names::class.java)
            .run { context ->
                val spec = cacheSpec<String, String>("pinned") {
                    codec = StringCodec
                    loader = CacheLoader { "value" }
                    policy { refreshWindow = Duration.ofSeconds(10) }
                }
                val held = Locks.builder("window-library")
                    .pin(PolicyField.REFRESH_WINDOW, RefreshWindow.Of(Duration.ofSeconds(30)))
                    .build()
                val expected = "refresh-window[PT30S] is pinned by window-library, so it can't be turned off"
                runCatching { context.getBean(CoCacheFactory::class.java).create(spec, held) }
                    .exceptionOrNull().assert()
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining(expected)
                    .hasMessageContaining("local-max-age[PT20S]")
                context.getBean(CacheRuntime::class.java).cacheNames.assert().doesNotContain("pinned")
            }
    }

    private fun verify(section: String?, window: RefreshWindow) {
        var runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withPropertyValues("cocache.backend=memory")
            .withUserConfiguration(Names::class.java)
        if (section != null) {
            runner = runner.withPropertyValues("cocache.$section.local-max-age=20s")
        }
        runner.run { context ->
            context.startupFailure.assert().isNull()
            val factory = context.getBean(CoCacheFactory::class.java)
            val spec = cacheSpec<String, String>("pinned") {
                codec = StringCodec
                loader = CacheLoader { "value" }
                // 声明本身有效：30 s 窗口小于类型默认的 5 min localMaxAge。
                policy { refreshWindow = Duration.ofSeconds(30) }
            }
            val held = Locks.builder("window-library").pin(PolicyField.REFRESH_WINDOW, window).build()
            factory.create(spec, held)
            val policy = requireNotNull(context.getBean(CacheRuntime::class.java).policy("pinned"))
            policy.refreshWindow.assert().isEqualTo(window.toDuration())
            val expectedAge = if (section == null) Duration.ofMinutes(5) else Duration.ofSeconds(20)
            policy.localMaxAge.assert().isEqualTo(expectedAge)
        }
    }

    private companion object {
        val WINDOW = RefreshWindow.Of(Duration.ofSeconds(10))
    }
}
