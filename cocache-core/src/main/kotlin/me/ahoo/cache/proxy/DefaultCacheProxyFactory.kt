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

import me.ahoo.cache.CacheConfig
import me.ahoo.cache.CacheManager
import me.ahoo.cache.ComputedCache
import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.NamedCache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.client.ClientSideCache
import me.ahoo.cache.client.ClientSideCacheFactory
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.util.ClientIdGenerator
import java.lang.reflect.Proxy

class DefaultCacheProxyFactory(
    private val cacheManager: CacheManager,
    private val clientIdGenerator: ClientIdGenerator,
    private val clientSideCacheFactory: ClientSideCacheFactory,
    private val distributedCacheFactory: DistributedCacheFactory,
    private val cacheSourceResolver: CacheSourceResolver
) : CacheProxyFactory {

    @Suppress("UNCHECKED_CAST")
    override fun <CACHE : Cache<*, *>> create(cacheMetadata: CoCacheMetadata): CACHE {
        val clientId = clientIdGenerator.generate()
        val cacheSource = cacheSourceResolver.resolve<Any>(cacheMetadata)
        val clientSideCaching: ClientSideCache<Any> = clientSideCacheFactory.create(cacheMetadata)
        val distributedCaching: DistributedCache<Any> = distributedCacheFactory.create(cacheMetadata)
        val delegate = cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = cacheMetadata.cacheName,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(cacheMetadata.resolveCacheKeyPrefix()),
                clientSideCaching = clientSideCaching,
                distributedCaching = distributedCaching,
                cacheSource = cacheSource,
            ),
        )
        val invocationHandler = CoCacheInvocationHandler(delegate)
        return Proxy.newProxyInstance(
            cacheMetadata.type.java.classLoader,
            arrayOf(cacheMetadata.type.java, ComputedCache::class.java, NamedCache::class.java),
            invocationHandler
        ) as CACHE
    }

    private fun CoCacheMetadata.resolveCacheKeyPrefix(): String {
        return prefix.ifBlank {
            "${CoCache.COCACHE}:$cacheName"
        }
    }
}
