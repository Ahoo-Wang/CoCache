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

package me.ahoo.cache.join.proxy

import me.ahoo.cache.CacheManager
import me.ahoo.cache.annotation.JoinCacheMetadata
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinKeyExtractor
import me.ahoo.cache.join.SimpleJoinCache
import java.lang.reflect.Proxy

class DefaultJoinProxyFactory(private val cacheManager: CacheManager) : JoinProxyFactory {
    override fun <CACHE : JoinCache<*, *, *, *>> create(cacheMetadata: JoinCacheMetadata): CACHE {
        val firstCache = cacheManager.getCache<Any, Any>(cacheMetadata.firstCacheName)
        requireNotNull(firstCache)
        val joinCache = cacheManager.getCache<Any, Any>(cacheMetadata.joinCacheName)
        requireNotNull(joinCache)
        val joinKeyExtractor = JoinKeyExtractor<Any, Any> { TODO("Not yet implemented") }
        val delegate = SimpleJoinCache<Any, Any, Any, Any>(firstCache, joinCache, joinKeyExtractor)
        val invocationHandler = JoinCacheInvocationHandler(delegate)

        return Proxy.newProxyInstance(
            cacheMetadata.type.java.classLoader,
            arrayOf(
                cacheMetadata.type.java,
                JoinCache::class.java
            ),
            invocationHandler
        ) as CACHE
    }
}
