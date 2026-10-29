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
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * classpath 中有 actuator 且 CoCache 启用（`cocache.enabled`，默认启用）时注册 [CoCacheEndpoint]。
 */
@AutoConfiguration(after = [CoCacheAutoConfiguration::class])
@ConditionalOnClass(Endpoint::class)
@ConditionalOnProperty(
    prefix = CoCacheProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class CoCacheEndpointAutoConfiguration {

    /**
     * 只在端点可用（`management.endpoint.cocache.access` 允许且已暴露）时创建。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CacheRuntime::class)
    @ConditionalOnAvailableEndpoint(CoCacheEndpoint::class)
    fun cocacheEndpoint(runtime: CacheRuntime): CoCacheEndpoint = CoCacheEndpoint(runtime)
}
