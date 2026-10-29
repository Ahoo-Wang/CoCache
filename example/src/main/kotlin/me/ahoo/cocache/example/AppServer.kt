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

package me.ahoo.cocache.example

import me.ahoo.cocache.example.cache.UserCache
import me.ahoo.cocache.example.cache.UserExtendInfoCache
import me.ahoo.cocache.example.cache.UserExtendInfoJoinCache
import me.ahoo.cocache.spring.boot.EnableCoCache
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

/**
 * 示例应用：接口式声明（[UserCache]、[UserExtendInfoCache]）、组合视图（[UserExtendInfoJoinCache]）、spec bean（`productSpec`）、Spring Cache 注解，
 * 以及 WebFlux + 协程（[me.ahoo.cocache.example.controller.CoroutineController]）。
 */
@EnableCoCache(
    caches = [UserCache::class, UserExtendInfoCache::class],
    joinCaches = [UserExtendInfoJoinCache::class],
)
@EnableCaching
@SpringBootApplication
class AppServer

fun main(args: Array<String>) {
    runApplication<AppServer>(*args)
}
