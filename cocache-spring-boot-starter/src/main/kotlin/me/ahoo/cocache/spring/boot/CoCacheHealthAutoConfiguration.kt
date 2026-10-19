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

import me.ahoo.cocache.core.CacheRuntime
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean

/**
 * classpath 中有 Spring Boot 健康检查（`spring-boot-health`，actuator 自带）且 CoCache 启用时注册 [CoCacheHealthIndicator]
 * （`management.health.cocache.enabled`，默认启用）。
 */
@AutoConfiguration(
    after = [CoCacheAutoConfiguration::class],
    beforeName = ["org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration"]
)
@ConditionalOnClass(HealthIndicator::class, ConditionalOnEnabledHealthIndicator::class)
@ConditionalOnProperty(
    prefix = CoCacheProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class CoCacheHealthAutoConfiguration {

    /**
     * bean 名决定健康检查名：`cocacheHealthIndicator` → `cocache`。
     */
    @Bean
    @ConditionalOnMissingBean(name = ["cocacheHealthIndicator"])
    @ConditionalOnBean(CacheRuntime::class)
    @ConditionalOnEnabledHealthIndicator("cocache")
    fun cocacheHealthIndicator(runtime: CacheRuntime): CoCacheHealthIndicator = CoCacheHealthIndicator(runtime)
}
