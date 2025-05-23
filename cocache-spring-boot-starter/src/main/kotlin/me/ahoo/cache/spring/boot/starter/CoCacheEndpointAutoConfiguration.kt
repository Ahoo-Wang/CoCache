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

import me.ahoo.cache.CacheFactory
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * CoCache AutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration(after = [CoCacheAutoConfiguration::class])
@ConditionalOnClass(Endpoint::class)
@ConditionalOnCoCacheEnabled
class CoCacheEndpointAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun cocacheEndpoint(cacheFactory: CacheFactory): CoCacheEndpoint {
        return CoCacheEndpoint(cacheFactory)
    }

    @Bean
    @ConditionalOnMissingBean
    fun coCacheClientEndpoint(cacheFactory: CacheFactory): CoCacheClientEndpoint {
        return CoCacheClientEndpoint(cacheFactory)
    }
}
