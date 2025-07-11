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

package me.ahoo.cache.spring.redis

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.spring.AbstractCacheFactory
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cache.spring.redis.serialization.JsonSerializer
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType
import org.springframework.data.redis.core.StringRedisTemplate
import kotlin.reflect.javaType

class RedisDistributedCacheFactory(
    beanFactory: BeanFactory,
    private val redisTemplate: StringRedisTemplate
) : DistributedCacheFactory, AbstractCacheFactory(beanFactory) {
    companion object {
        const val DISTRIBUTED_CACHE_SUFFIX = ".DistributedCache"
    }

    override val suffix: String = DISTRIBUTED_CACHE_SUFFIX

    @OptIn(ExperimentalStdlibApi::class)
    override fun getBeanType(cacheMetadata: CoCacheMetadata): ResolvableType {
        return ResolvableType.forClassWithGenerics(
            DistributedCache::class.java,
            ResolvableType.forType(cacheMetadata.valueType.javaType)
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun fallback(cacheMetadata: CoCacheMetadata): Any {
        val codecExecutor = ObjectToJsonCodecExecutor<Any>(
            valueType = cacheMetadata.valueType.javaType,
            redisTemplate = redisTemplate,
            objectMapper = JsonSerializer
        )
        return RedisDistributedCache(
            redisTemplate,
            codecExecutor,
            ttl = cacheMetadata.ttl,
            ttlAmplitude = cacheMetadata.ttlAmplitude
        )
    }

    override fun <V> create(cacheMetadata: CoCacheMetadata): DistributedCache<V> {
        @Suppress("UNCHECKED_CAST")
        return createBean(cacheMetadata) as DistributedCache<V>
    }
}
