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

import me.ahoo.cache.CacheFactory
import me.ahoo.cache.annotation.JoinCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.join.JoinKeyExtractorFactory
import me.ahoo.cache.join.SimpleJoinCache
import me.ahoo.cache.proxy.CacheDelegated
import me.ahoo.cache.proxy.CacheInvocationHandler
import java.lang.reflect.Proxy
import kotlin.reflect.KType

class DefaultJoinCacheProxyFactory(
    private val cacheFactory: CacheFactory,
    private val joinKeyExtractorFactory: JoinKeyExtractorFactory,
) : JoinCacheProxyFactory {

    @Suppress("UNCHECKED_CAST")
    override fun <CACHE : JoinCache<*, *, *, *>> create(cacheMetadata: JoinCacheMetadata): CACHE {
        val firstCache =
            requireCache(
                cacheMetadata,
                cacheMetadata.firstCacheName,
                cacheMetadata.firstKeyType,
                cacheMetadata.firstValueType
            )
        val joinCache =
            requireCache(
                cacheMetadata,
                cacheMetadata.joinCacheName,
                cacheMetadata.joinKeyType,
                cacheMetadata.joinValueType
            )
        val joinKeyExtractor = joinKeyExtractorFactory.create<Any, Any>(cacheMetadata)
        val delegate = SimpleJoinCache(firstCache, joinCache, joinKeyExtractor)
        val proxyInterface = cacheMetadata.proxyInterface.java
        return Proxy.newProxyInstance(
            proxyInterface.classLoader,
            arrayOf(
                proxyInterface,
                JoinCache::class.java,
                CacheDelegated::class.java,
                JoinCacheMetadataCapable::class.java
            ),
            CacheInvocationHandler(proxyInterface, delegate, cacheMetadata)
        ) as CACHE
    }

    private fun requireCache(
        cacheMetadata: JoinCacheMetadata,
        cacheName: String,
        keyType: KType,
        valueType: KType
    ): Cache<Any, Any> {
        val cache: Cache<Any, Any>? = if (cacheName.isNotBlank()) {
            cacheFactory.getCache(cacheName)
        } else {
            cacheFactory.getCache(keyType, valueType)
        }
        return requireNotNull(cache) {
            "[${cacheMetadata.cacheName}] Cache not found for name[$cacheName] or type[Cache<$keyType, $valueType>]."
        }
    }
}
