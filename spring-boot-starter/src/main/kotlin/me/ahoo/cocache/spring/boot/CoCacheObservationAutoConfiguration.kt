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

import io.micrometer.observation.ObservationRegistry
import me.ahoo.cocache.core.LoadObserver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * classpath 中有 Micrometer Observation、容器中有 `ObservationRegistry`（如 Spring Boot 的 `ObservationAutoConfiguration`）
 * 且 `cocache.observation.enabled` 未关闭时注册 [MicrometerLoadObserver]，由 `cocacheRuntime` 取用。
 *
 * 独立于 [CoCacheAutoConfiguration]：没有 Micrometer 时整个类被条件跳过，不会加载 Micrometer 的类。
 * 运行时经 `ObjectProvider` 取用观测器，先于运行时注册只是为了让顺序直观。
 */
@AutoConfiguration(
    before = [CoCacheAutoConfiguration::class],
    afterName = ["org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"]
)
@ConditionalOnClass(ObservationRegistry::class)
@ConditionalOnProperty(
    prefix = CoCacheProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@ConditionalOnBooleanProperty(name = [CoCacheObservationAutoConfiguration.ENABLED_PROPERTY], matchIfMissing = true)
class CoCacheObservationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LoadObserver::class)
    @ConditionalOnBean(ObservationRegistry::class)
    fun cocacheLoadObserver(registry: ObservationRegistry): LoadObserver = MicrometerLoadObserver(registry)

    companion object {
        const val ENABLED_PROPERTY = "${CoCacheProperties.PREFIX}.observation.enabled"
    }
}
