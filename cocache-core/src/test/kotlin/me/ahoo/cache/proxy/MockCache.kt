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

package me.ahoo.cache.proxy

import me.ahoo.cache.CoherentCache
import me.ahoo.cache.ComputedCache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.api.annotation.MissingGuardCache
import me.ahoo.cache.distributed.DistributedClientId

@CoCache
@MissingGuardCache(ttlSeconds = 120)
interface MockCache :
    ComputedCache<String, String>,
    CacheDelegated<CoherentCache<String, String>>,
    DistributedClientId,
    CacheMetadataCapable

@CoCache(keyPrefix = "prefix:", keyExpression = "#{#root}")
interface MockCacheWithKeyExpression : ComputedCache<String, String> {
    fun defaultMethod(): String {
        return "defaultMethod"
    }
}
