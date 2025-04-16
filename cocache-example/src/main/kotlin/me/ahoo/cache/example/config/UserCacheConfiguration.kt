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
package me.ahoo.cache.example.config

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.example.model.User
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class UserCacheConfiguration {
    @Bean
    fun customizeUserClientSideCache(
        @Qualifier("UserCache.CacheMetadata")
        cacheMetadata: CoCacheMetadata
    ): ClientSideCache<User> {
        return MapClientSideCache(ttl = cacheMetadata.ttl, ttlAmplitude = cacheMetadata.ttlAmplitude)
    }

    @Bean
    fun customizeUserCacheSource(): CacheSource<String, User> {
        return CacheSource.noOp()
    }
}
