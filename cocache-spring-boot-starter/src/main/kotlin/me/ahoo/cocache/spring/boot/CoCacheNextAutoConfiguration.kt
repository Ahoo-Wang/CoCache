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

import me.ahoo.cache.spring.boot.starter.CoCacheAutoConfiguration
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Bean

/**
 * CoCache 6.0 自动配置（`cocache.next.enabled=true` 时启用）。
 *
 * 排在 5.x 自动配置之前：6.0 的 [CacheManager] 先注册，5.x 的因 `@ConditionalOnMissingBean` 退让。
 */
@AutoConfiguration(after = [DataRedisAutoConfiguration::class], before = [CoCacheAutoConfiguration::class])
@ConditionalOnProperty(prefix = CoCacheNextProperties.PREFIX, name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(CoCacheNextProperties::class)
class CoCacheNextAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CoherenceBackend::class)
    @ConditionalOnBean(DataRedisConnectionDetails::class)
    fun cocacheNextBackend(details: DataRedisConnectionDetails, properties: CoCacheNextProperties): CoherenceBackend {
        return RedisBackends.create(details, properties.redis.toOptions())
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(CoherenceBackend::class)
    fun cocacheNextRuntime(backend: CoherenceBackend): CacheRuntime = CacheRuntime.builder().backend(backend).build()

    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheNextAssembler(
        runtime: CacheRuntime,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        properties: CoCacheNextProperties,
    ): CacheAssembler = CacheAssembler(runtime, customizers.orderedStream().toList(), properties)

    @Bean
    @ConditionalOnMissingBean(CacheManager::class)
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheNextCacheManager(runtime: CacheRuntime): CacheManager = CoCacheSpringCacheManager(runtime)

    companion object {
        @Bean
        @JvmStatic
        fun cocacheNextSpecRegistrar(): CacheSpecRegistrar = CacheSpecRegistrar()
    }
}
