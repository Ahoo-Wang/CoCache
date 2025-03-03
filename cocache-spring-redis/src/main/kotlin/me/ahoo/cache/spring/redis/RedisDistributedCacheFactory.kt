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
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cache.spring.redis.serialization.JsonSerializer
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType
import org.springframework.data.redis.core.StringRedisTemplate

class RedisDistributedCacheFactory(
    private val beanFactory: BeanFactory,
    private val redisTemplate: StringRedisTemplate
) : DistributedCacheFactory {
    @Suppress("UNCHECKED_CAST")
    override fun <V> create(cacheMetadata: CoCacheMetadata): DistributedCache<V> {
        val valueType = cacheMetadata.valueType.java as Class<V>
        val distributedCacheType = ResolvableType.forClassWithGenerics(
            DistributedCache::class.java,
            valueType
        )
        val distributedCacheProvider = beanFactory.getBeanProvider<DistributedCache<V>>(distributedCacheType)
        return distributedCacheProvider.getIfAvailable {
            val codecExecutor = ObjectToJsonCodecExecutor(valueType, redisTemplate, JsonSerializer)
            RedisDistributedCache(redisTemplate, codecExecutor)
        }
    }
}
