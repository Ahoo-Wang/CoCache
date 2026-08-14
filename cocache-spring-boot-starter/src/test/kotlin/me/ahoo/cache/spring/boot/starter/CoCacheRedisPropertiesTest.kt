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

import io.mockk.mockk
import me.ahoo.cache.MissingGuard
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.spring.redis.RedisDistributedCacheFactory
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper

internal class CoCacheRedisPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withBean(StringRedisTemplate::class.java, { mockk<StringRedisTemplate>(relaxed = true) })
        .withBean(RedisConnectionFactory::class.java, { mockk<RedisConnectionFactory>(relaxed = true) })
        .withBean(ObjectMapper::class.java, { mockk<ObjectMapper>(relaxed = true) })

    @Test
    fun defaultsPropagateToFactory() {
        contextRunner.run { context ->
            context.getBean(CoCacheProperties::class.java).let { properties ->
                properties.redis.strictFailure.assert().isFalse()
                properties.redis.missingGuardSentinel.assert().isEqualTo(MissingGuard.STRING_VALUE)
            }
            val factory = context.getBean(DistributedCacheFactory::class.java) as RedisDistributedCacheFactory
            factory.strictFailure.assert().isFalse()
            factory.missingGuardSentinel.assert().isEqualTo(MissingGuard.STRING_VALUE)
        }
    }

    @Test
    fun customPropertiesPropagateToFactory() {
        contextRunner
            .withPropertyValues(
                "cocache.redis.strict-failure=true",
                "cocache.redis.missing-guard-sentinel=custom-nil",
            )
            .run { context ->
                context.getBean(CoCacheProperties::class.java).let { properties ->
                    properties.redis.strictFailure.assert().isTrue()
                    properties.redis.missingGuardSentinel.assert().isEqualTo("custom-nil")
                }
                // 断言完整透传链：属性绑定 → AutoConfiguration → 工厂构造参数。
                // 删除 distributedCacheFactory 中的任一透传实参都会使此处失败（回退默认值）。
                val factory = context.getBean(DistributedCacheFactory::class.java) as RedisDistributedCacheFactory
                factory.strictFailure.assert().isTrue()
                factory.missingGuardSentinel.assert().isEqualTo("custom-nil")
            }
    }
}
