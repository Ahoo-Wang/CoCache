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

import me.ahoo.cache.CacheValue
import me.ahoo.cache.TtlAt
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.spring.redis.codec.CodecExecutor
import me.ahoo.cache.util.CacheSecondClock
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

/**
 * Redis Distributed Cache.
 *
 * @author ahoo wang
 */
class RedisDistributedCache<V>(
    private val redisTemplate: StringRedisTemplate,
    private val codecExecutor: CodecExecutor<V>,
) : DistributedCache<V> {
    override fun getCache(key: String): CacheValue<V>? {
        val ttlAt = getExpireAt(key) ?: return null
        return codecExecutor.executeAndDecode(key, ttlAt)
    }

    override fun getExpireAt(key: String): Long? {
        val ttl = redisTemplate.getExpire(key)
        if (NOT_EXIST == ttl) {
            return null
        }
        return if (FOREVER == ttl) {
            TtlAt.FOREVER
        } else {
            CacheSecondClock.INSTANCE.currentTime() + ttl
        }
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        codecExecutor.executeAndEncode(key, value)
        if (!value.isForever) {
            redisTemplate.expireAt(key, Instant.ofEpochSecond(value.ttlAt))
        }
    }

    override fun evict(key: String) {
        redisTemplate.delete(key)
    }

    override fun close() = Unit

    companion object {
        const val FOREVER = -1L
        const val NOT_EXIST = -2L
    }
}
