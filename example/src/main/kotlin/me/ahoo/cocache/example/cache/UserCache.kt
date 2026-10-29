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

package me.ahoo.cocache.example.cache

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.annotation.CaffeineCache
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.example.model.User

/**
 * 接口式声明：时长单位为秒，数据源按 `CacheLoader<String, User>` 泛型匹配（见 [me.ahoo.cocache.example.config.UserCacheConfiguration]）。
 */
@CoCache(keyPrefix = "user:", ttl = 120)
@CaffeineCache(maximumSize = 1_000_000, expireAfterAccess = 120)
interface UserCache : Cache<String, User>
