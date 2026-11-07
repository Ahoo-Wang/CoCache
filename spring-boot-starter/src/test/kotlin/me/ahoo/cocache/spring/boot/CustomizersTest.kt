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

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import java.time.Duration
import java.util.UUID

/**
 * 运行时与后端的定制器（X6）：按 Spring 的顺序依次调用，定制器设置的值覆盖 starter 的接线，其余接线照常保留。
 */
class CustomizersTest {
    @Configuration(proxyBeanMethods = false)
    class RuntimeCustomizers {
        @Bean
        fun clock(): CacheClock = CLOCK

        @Bean
        @Order(2)
        fun later(): CacheRuntimeBuilderCustomizer = CacheRuntimeBuilderCustomizer { builder ->
            builder.closeTimeout(Duration.ofSeconds(40))
        }

        @Bean
        @Order(1)
        fun earlier(): CacheRuntimeBuilderCustomizer = CacheRuntimeBuilderCustomizer {
            it.closeTimeout(Duration.ofSeconds(30)).nodeId(NODE)
        }
    }

    @Test
    fun runtimeCustomizersRunInOrderAfterTheStartersWiring() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withPropertyValues("cocache.backend=memory", "cocache.redis.command-timeout=30s")
            .withUserConfiguration(RuntimeCustomizers::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)
                // 后执行的定制器覆盖先执行的，也覆盖 starter 由 command-timeout 推出的 60 s
                runtime.closeTimeout.assert().isEqualTo(Duration.ofSeconds(40))
                runtime.nodeId.assert().isEqualTo(NODE)
                // starter 的其余接线照常保留
                runtime.clock.assert().isSameAs(CLOCK)
            }
    }

    @Configuration(proxyBeanMethods = false)
    class BackendCustomizers {
        @Bean
        @Order(2)
        fun suffix(): RedisBackendOptionsCustomizer = RedisBackendOptionsCustomizer {
            // 收到前一个定制器的结果
            it.clientName.assert().isEqualTo(CLIENT_NAME)
            it.copy(
                clientName = "$CLIENT_NAME-2",
                heartbeat = Duration.ofSeconds(7),
                commandTimeout = Duration.ofSeconds(10)
            )
        }

        @Bean
        @Order(1)
        fun clientName(): RedisBackendOptionsCustomizer = RedisBackendOptionsCustomizer {
            // 收到 cocache.redis.* 的值
            it.commandTimeout.assert().isEqualTo(Duration.ofSeconds(3))
            it.copy(clientName = CLIENT_NAME)
        }
    }

    @Test
    fun backendOptionsCustomizersRunInOrderAfterTheProperties() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(DataRedisAutoConfiguration::class.java, CoCacheAutoConfiguration::class.java)
            )
            .withPropertyValues("cocache.redis.command-timeout=3s", "cocache.redis.client-name=from-properties")
            .withUserConfiguration(BackendCustomizers::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val backend = context.getBean(CoherenceBackend::class.java) as RedisBackend
                backend.clientName.assert().isEqualTo("$CLIENT_NAME-2")
                backend.commandTimeout.assert().isEqualTo(Duration.ofSeconds(10))
                // 关闭超时按后端实际的命令超时（定制器改过的 10 s）推算，而不是配置项的 3 s：等得到在途的命令结束（K17）
                context.getBean(CacheRuntime::class.java).closeTimeout.assert().isEqualTo(Duration.ofSeconds(20))
            }
    }

    companion object {
        val CLOCK = ManualClock()
        val NODE = NodeId.random()
        val CLIENT_NAME = "cocache-customizer-" + UUID.randomUUID()
    }
}
