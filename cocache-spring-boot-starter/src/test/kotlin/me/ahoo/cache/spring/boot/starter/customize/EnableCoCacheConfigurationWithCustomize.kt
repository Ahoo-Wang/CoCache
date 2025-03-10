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

package me.ahoo.cache.spring.boot.starter.customize

import io.mockk.mockk
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.api.annotation.JoinCacheable
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinKeyExtractor
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.ComputedClientSideCache
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.example.cache.UserCache
import me.ahoo.cache.example.cache.UserExtendInfoCache
import me.ahoo.cache.example.model.User
import me.ahoo.cache.example.model.UserExtendInfo
import me.ahoo.cache.spring.EnableCoCache
import me.ahoo.cache.spring.join.SpringJoinKeyExtractorFactory.Companion.JOIN_KEY_EXTRACTOR_SUFFIX
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
@EnableCoCache(
    caches = [
        UserCache::class,
        UserExpCache::class,
        UserPlaceholderCache::class,
        UserKeyCache::class,
        UserExtendInfoCache::class,
        MockUserExtendInfoJoinCache::class,
        MockUserExtendInfoJoinCacheUseNameSuffix::class
    ]
)
class EnableCoCacheConfigurationWithCustomize {

    @Bean
    fun customizeUserClientSideCache(): ComputedClientSideCache<User> {
        return mockk()
    }

    @Bean
    fun customizeUserCacheSource(): CacheSource<String, User> {
        return mockk()
    }

    @Bean
    fun customizeDistributedCache(): DistributedCache<User> {
        return mockk()
    }

    @Bean
    fun joinKeyExtractor(): JoinKeyExtractor<UserExtendInfo, String> {
        return mockk()
    }

    @Bean("MockUserExtendInfoJoinCacheUseNameSuffix${JOIN_KEY_EXTRACTOR_SUFFIX}")
    fun joinKeyExtractorUseNameSuffix(): JoinKeyExtractor<Any, String> {
        return mockk()
    }
}

@CoCache(keyExpression = "#{#root}")
interface UserExpCache : Cache<String, User>

@CoCache(keyPrefix = "\${spring.application.name}:user")
interface UserPlaceholderCache : Cache<String, User>

interface UserKeyCache : Cache<UserId, User>

data class UserId(val id: String)

interface UserExtendInfoCache : Cache<String, UserExtendInfo>

data class UserExtendInfo(val id: String, val userId: String)

@JoinCacheable(
    firstCacheName = "UserExtendInfoCache",
    joinCacheName = "UserCache"
)
interface MockUserExtendInfoJoinCache : JoinCache<String, UserExtendInfo, String, User>

@JoinCacheable(
    firstCacheName = "UserExtendInfoCache",
    joinCacheName = "UserCache"
)
interface MockUserExtendInfoJoinCacheUseNameSuffix : JoinCache<String, UserExtendInfo, String, User>
