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
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.spring.redis.codec.CodecExecutor
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Redis 分布式缓存。
 *
 * 默认故障降级：读失败按未命中处理（上层回源），写/淘汰失败仅告警。
 *
 * @param strictFailure `true` 时重抛 [DataAccessException]
 * @author ahoo wang
 */
class RedisDistributedCache<V> @JvmOverloads constructor(
    private val redisTemplate: StringRedisTemplate,
    private val codecExecutor: CodecExecutor<V>,
    private val strictFailure: Boolean = false,
) : DistributedCache<V> {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun getCache(key: String): CacheValue<V>? {
        return try {
            codecExecutor.executeAndDecode(key)
        } catch (e: DataAccessException) {
            handleFailure("getCache", key, e)
            null
        }
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        try {
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

    private fun handleFailure(operation: String, key: String, e: DataAccessException) {
        if (strictFailure) {
            throw e
        }
        log.warn(e) { "Cache operation[$operation] key[$key] failed - degrading (strictFailure=false)." }
    }
}
