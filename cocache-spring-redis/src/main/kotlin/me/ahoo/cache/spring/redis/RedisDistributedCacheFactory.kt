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
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.spring.AbstractCacheFactory
import me.ahoo.cache.spring.redis.codec.AbstractCodecExecutor
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import org.springframework.beans.factory.BeanFactory
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import kotlin.reflect.jvm.javaType

/**
 * 解析 `{cacheName}.DistributedCache` bean，缺省创建 JSON 编码的 [RedisDistributedCache]。
 *
 * @param missingGuardSentinel 负缓存哨兵，见 [AbstractCodecExecutor]
 * @param strictFailure 透传给 [RedisDistributedCache]
 */
class RedisDistributedCacheFactory(
    beanFactory: BeanFactory,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: StringRedisTemplate,
    val missingGuardSentinel: String = AbstractCodecExecutor.DEFAULT_MISSING_GUARD_SENTINEL,
    val strictFailure: Boolean = false,
) : DistributedCacheFactory, AbstractCacheFactory(beanFactory) {
    companion object {
        const val DISTRIBUTED_CACHE_SUFFIX = ".DistributedCache"
    }

    override val suffix: String = DISTRIBUTED_CACHE_SUFFIX

    override fun fallback(cacheMetadata: CoCacheMetadata): Any {
        val codecExecutor = ObjectToJsonCodecExecutor<Any>(
            valueType = cacheMetadata.valueType.javaType,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            missingGuardSentinel = missingGuardSentinel
        )
        return RedisDistributedCache(redisTemplate, codecExecutor, strictFailure)
    }

    override fun <V> create(cacheMetadata: CoCacheMetadata): DistributedCache<V> {
        @Suppress("UNCHECKED_CAST")
        return resolve(cacheMetadata) as DistributedCache<V>
    }
}
