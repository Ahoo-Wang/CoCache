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

package me.ahoo.cache.client

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.annotation.GuavaCache
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.client.GuavaClientSideCache.Companion.toClientSideCache
import kotlin.reflect.full.findAnnotation

object DefaultClientSideCacheFactory : ClientSideCacheFactory {
    override fun <V> create(cacheMetadata: CoCacheMetadata): ClientSideCache<V> {
        val guavaCache = cacheMetadata.type.findAnnotation<GuavaCache>() ?: return MapClientSideCache()
        return guavaCache.toClientSideCache()
    }
}
