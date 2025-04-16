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

package me.ahoo.cache.spring.cache

import me.ahoo.cache.CacheFactory
import me.ahoo.cache.api.Cache
import org.springframework.cache.support.AbstractCacheManager
import org.springframework.cache.Cache as SpringCache

class CoCacheManager(private val cacheFactory: CacheFactory) : AbstractCacheManager() {
    @Suppress("UNCHECKED_CAST")
    override fun loadCaches(): Collection<SpringCache> {
        return cacheFactory.caches.map { (cacheName, cache) ->
            CoSpringCache(cacheName, cache as Cache<Any, Any?>)
        }
    }

    override fun getMissingCache(name: String): SpringCache? {
        val cache = cacheFactory.getCache<Cache<Any, Any?>>(name) ?: return null
        return CoSpringCache(name, cache)
    }
}
