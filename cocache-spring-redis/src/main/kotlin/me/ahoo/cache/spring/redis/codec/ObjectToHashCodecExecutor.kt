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
package me.ahoo.cache.spring.redis.codec

import me.ahoo.cache.CacheValue
import me.ahoo.cache.MissingGuard
import me.ahoo.cache.util.CacheSecondClock
import org.springframework.data.redis.connection.StringRedisConnection
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * ObjectToHashCodecExecutor .
 *
 * @author ahoo wang
 */
class ObjectToHashCodecExecutor<V>(
    private val mapConverter: MapConverter<V>,
    private val redisTemplate: StringRedisTemplate,
) : AbstractCodecExecutor<V, Map<String, String>>() {

    override fun getRawValue(key: String): Map<String, String>? {
        return redisTemplate.opsForHash<String, String>().entries(key)
    }

    override fun setMissingGuard(key: String) {
        redisTemplate.opsForHash<String, String>()
            .put(key, MissingGuard.STRING_VALUE, CacheSecondClock.INSTANCE.currentTime().toString())
    }

    override fun setValueWithTtlAt(key: String, cacheValue: CacheValue<V>) {
        redisTemplate.executePipelined { connection ->
            connection as StringRedisConnection
            connection.del(key)
            connection.hMSet(key, mapConverter.asMap(cacheValue.value))
            connection.expire(key, cacheValue.expiredDuration.seconds)
            null
        }
    }

    override fun setForeverValue(key: String, value: V) {
        redisTemplate.executePipelined { connection ->
            connection as StringRedisConnection
            connection.del(key)
            connection.hMSet(key, mapConverter.asMap(value))
            null
        }
    }

    override fun decode(rawValue: Map<String, String>): V {
        return mapConverter.asValue(rawValue)
    }

    override fun isMissingGuard(rawValue: Map<String, String>): Boolean {
        return CacheValue.isMissingGuard(rawValue)
    }

    interface MapConverter<V> {
        fun asValue(map: Map<String, String>): V
        fun asMap(value: V): Map<String, String>
    }
}
