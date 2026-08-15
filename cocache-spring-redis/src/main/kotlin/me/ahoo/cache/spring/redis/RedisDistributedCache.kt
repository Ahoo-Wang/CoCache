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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.spring.redis.codec.CodecExecutor
import me.ahoo.cache.util.CacheSecondClock
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Redis Distributed Cache.
 *
 * 默认故障降级：读失败按缓存未命中处理（上层回源），写/evict 失败仅告警。
 *
 * @param strictFailure true = 重抛 [DataAccessException]（旧行为）；false（默认）= 降级。
 * @author ahoo wang
 */
class RedisDistributedCache<V>(
    private val redisTemplate: StringRedisTemplate,
    private val codecExecutor: CodecExecutor<V>,
    override val ttl: Long = CoCache.DEFAULT_TTL,
    override val ttlAmplitude: Long = CoCache.DEFAULT_TTL_AMPLITUDE,
    private val strictFailure: Boolean = false,
) : DistributedCache<V> {
    override fun getCache(key: String): CacheValue<V>? {
        return try {
            val ttlAt = ttlAt(key) ?: return null
            codecExecutor.executeAndDecode(key, ttlAt)
        } catch (e: DataAccessException) {
            handleFailure("getCache", key, e)
            null
        }
    }

    private fun ttlAt(key: String): Long? {
        val ttl = redisTemplate.getExpire(key)
        if (NOT_EXIST == ttl) {
            return null
        }
        return if (FOREVER == ttl) {
            ComputedTtlAt.FOREVER
        } else {
            CacheSecondClock.INSTANCE.currentTime() + ttl
        }
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        try {
            if (value.isExpired) {
                evict(key)
                return
            }
            codecExecutor.executeAndEncode(key, value)
        } catch (e: DataAccessException) {
            handleFailure("setCache", key, e)
        }
    }

    override fun evict(key: String) {
        try {
            redisTemplate.delete(key)
        } catch (e: DataAccessException) {
            handleFailure("evict", key, e)
        }
    }

    override fun close() = Unit

    private fun handleFailure(operation: String, key: String, e: DataAccessException) {
        if (strictFailure) {
            throw e
        }
        log.warn(e) { "Cache operation[$operation] key[$key] failed - degrading (strictFailure=false)." }
    }

    companion object {
        private val log = KotlinLogging.logger {}
        const val FOREVER = -1L
        const val NOT_EXIST = -2L
    }
}
