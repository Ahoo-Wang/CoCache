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

import io.micrometer.core.instrument.binder.MeterBinder
import me.ahoo.cocache.api.CoCache
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.core.CacheRuntime
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * classpath 中有 Micrometer 且 `cocache.next.enabled=true` 时注册 [CoCacheNextMeterBinder]，
 * 由 Spring Boot 绑定到所有 MeterRegistry。
 */
@AutoConfiguration(after = [CoCacheNextAutoConfiguration::class])
@ConditionalOnClass(MeterBinder::class)
@ConditionalOnProperty(prefix = CoCacheNextProperties.PREFIX, name = ["enabled"], havingValue = "true")
class CoCacheNextMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheNextMeterBinder(runtime: CacheRuntime): CoCacheNextMeterBinder = CoCacheNextMeterBinder(runtime)

    companion object {
        /**
         * 延迟创建的缓存 bean（如懒加载）初始化后补绑指标。
         */
        @Bean
        @JvmStatic
        fun cocacheNextMeterBindingPostProcessor(
            binder: ObjectProvider<CoCacheNextMeterBinder>
        ): BeanPostProcessor = MeterBindingPostProcessor(binder)
    }

    internal class MeterBindingPostProcessor(private val binder: ObjectProvider<CoCacheNextMeterBinder>) :
        BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is CoCache<*, *> || bean is SetCache<*, *>) {
                binder.ifAvailable?.bindNewCaches()
            }
            return bean
        }
    }
}
