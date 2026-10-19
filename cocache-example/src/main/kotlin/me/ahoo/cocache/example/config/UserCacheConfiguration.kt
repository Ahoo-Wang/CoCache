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

package me.ahoo.cocache.example.config

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.example.model.User
import me.ahoo.cocache.example.model.UserExtendInfo
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class UserCacheConfiguration {
    /**
     * `UserCache` 的数据源：按泛型 `CacheLoader<String, User>` 唯一匹配。示例直接构造，真实应用在此查询数据库。
     */
    @Bean
    fun userLoader(): CacheLoader<String, User> = CacheLoader { id -> User(id, "user-$id") }

    @Bean
    fun userExtendInfoLoader(): CacheLoader<String, UserExtendInfo> = CacheLoader { id ->
        UserExtendInfo(
            id,
            "user-of-$id"
        )
    }

    /**
     * spec bean：不写接口，直接注入 `Cache<String, String>`（bean 名 `product`）。
     */
    @Bean
    fun productSpec(): CacheSpec<String, String> = cacheSpec("product") {
        codec = StringCodec
        loader = CacheLoader { id -> "product-$id" }
        policy { ttl = Duration.ofMinutes(10) }
    }

    /**
     * 取代 5.x 的 `{cacheName}.ClientSideCache` 等 bean 名约定：按缓存名修改声明。
     */
    @Bean
    fun userCacheCustomizer(): CacheSpecCustomizer = object : CacheSpecCustomizer {
        override fun supports(cacheName: String): Boolean = cacheName == "UserCache"

        override fun customize(spec: CacheSpec.Builder<*, *>) {
            spec.policy = spec.policy.toBuilder().absentTtl(Duration.ofSeconds(30)).build()
        }
    }
}
