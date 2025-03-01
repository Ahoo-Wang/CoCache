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

package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.CacheManager
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.example.cache.UserCacheProxy
import me.ahoo.cache.util.ClientIdGenerator
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.cosid.machine.LocalHostAddressSupplier
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.listener.RedisMessageListenerContainer

internal class CoCacheAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withUserConfiguration(
                RedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                EnableCoCacheConfiguration::class.java
            )
            .run { context ->
                assertThat(context)
                    .hasSingleBean(ClientIdGenerator::class.java)
                    .hasSingleBean(RedisMessageListenerContainer::class.java)
                    .hasSingleBean(CacheEvictedEventBus::class.java)
                    .hasSingleBean(CacheManager::class.java)
                    .hasSingleBean(UserCacheProxy::class.java)
            }
    }

    @Test
    fun contextLoadsWithCloud() {
        contextRunner
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                RedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java
            )
            .run { context ->
                assertThat(context)
                    .hasSingleBean(ClientIdGenerator::class.java)
                    .hasSingleBean(RedisMessageListenerContainer::class.java)
                    .hasSingleBean(CacheEvictedEventBus::class.java)
                    .hasSingleBean(CacheManager::class.java)
                context.getBean(ClientIdGenerator::class.java).generate()
            }
    }
}
