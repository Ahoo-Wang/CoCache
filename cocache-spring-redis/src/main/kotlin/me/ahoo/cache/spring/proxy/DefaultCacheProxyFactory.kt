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

package me.ahoo.cache.spring.proxy

import com.fasterxml.jackson.databind.ObjectMapper
import me.ahoo.cache.Cache
import me.ahoo.cache.CacheConfig
import me.ahoo.cache.CacheManager
import me.ahoo.cache.CacheSource
import me.ahoo.cache.CoCache
import me.ahoo.cache.NamedCache
import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.proxy.CacheProxyFactory
import me.ahoo.cache.proxy.CoCacheInvocationHandler
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cache.util.ClientIdGenerator
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.core.ResolvableType
import org.springframework.data.redis.core.StringRedisTemplate
import java.lang.reflect.Proxy

class DefaultCacheProxyFactory(
    private val cacheManager: CacheManager,
    private val redisTemplate: StringRedisTemplate,
    private val clientIdGenerator: ClientIdGenerator,
    private val jsonSerializer: ObjectMapper
) : CacheProxyFactory, BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory
    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    @Suppress("UNCHECKED_CAST")
    override fun <CACHE : Cache<*, *>> create(cacheMetadata: CoCacheMetadata): CACHE {
        val clientId = clientIdGenerator.generate()
        val cacheSource = cacheMetadata.resolveCacheSource()
        val valueType = cacheMetadata.valueType.java as Class<Any>
        val codecExecutor = ObjectToJsonCodecExecutor(valueType, redisTemplate, jsonSerializer)
        val distributedCaching: DistributedCache<Any> = RedisDistributedCache(redisTemplate, codecExecutor)
        val delegate = cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = cacheMetadata.cacheName,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(cacheMetadata.resolveCacheKeyPrefix()),
                distributedCaching = distributedCaching,
                cacheSource = cacheSource,
            ),
        )
        val invocationHandler = CoCacheInvocationHandler(delegate)
        return Proxy.newProxyInstance(
            cacheMetadata.type.java.classLoader,
            arrayOf(cacheMetadata.type.java, NamedCache::class.java),
            invocationHandler
        ) as CACHE
    }

    private fun CoCacheMetadata.resolveCacheSource(): CacheSource<String, Any> {
        val cacheSourceType = ResolvableType.forClassWithGenerics(
            CacheSource::class.java,
            String::class.java,
            valueType.java
        )
        val cacheSourceProvider = beanFactory.getBeanProvider<CacheSource<String, Any>>(cacheSourceType)
        return cacheSourceProvider.getIfAvailable {
            CacheSource.noOp()
        }
    }

    private fun CoCacheMetadata.resolveCacheKeyPrefix(): String {
        return prefix.ifBlank {
            "${CoCache.COCACHE}:$cacheName"
        }
    }
}
