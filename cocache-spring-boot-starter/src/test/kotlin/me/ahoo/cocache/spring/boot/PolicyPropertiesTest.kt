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

import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 配置文件对策略的语义：`refresh-window: 0` 关闭提前刷新；全局寿命不作用于集合缓存。
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
}
