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

import me.ahoo.cache.MissingGuard
import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.spring.AbstractCacheFactory
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import kotlin.reflect.javaType

class RedisDistributedCacheFactory(
    beanFactory: BeanFactory,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: StringRedisTemplate,
    /**
     * 负缓存哨兵值，默认 [MissingGuard.STRING_VALUE]。约束见
     * [AbstractCodecExecutor.missingGuardSentinel]：自定义值与默认值互不识别，
     * 需全集群同时切换，且不得与任何合法业务序列化值相等。
     */
    val missingGuardSentinel: String = MissingGuard.STRING_VALUE,
    /**
     * true = Redis 故障重抛（旧行为）；false（默认）= 降级。透传给 [RedisDistributedCache]。
     */
    val strictFailure: Boolean = false,
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
            objectMapper = objectMapper,
            missingGuardSentinel = missingGuardSentinel
        )
        return RedisDistributedCache(
            redisTemplate,
            codecExecutor,
            ttl = cacheMetadata.ttl,
            ttlAmplitude = cacheMetadata.ttlAmplitude,
            strictFailure = strictFailure
        )
    }

    override fun <V> create(cacheMetadata: CoCacheMetadata): DistributedCache<V> {
        @Suppress("UNCHECKED_CAST")
        return createBean(cacheMetadata) as DistributedCache<V>
    }
}
