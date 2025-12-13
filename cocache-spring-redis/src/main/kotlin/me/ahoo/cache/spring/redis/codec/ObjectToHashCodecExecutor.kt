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

import me.ahoo.cache.MissingGuard
import me.ahoo.cache.MissingGuard.Companion.isMissingGuard
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.util.CacheSecondClock
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * ObjectToHashCodecExecutor .
 *
 * @author ahoo wang
 */
class ObjectToHashCodecExecutor<V>(
    private val mapConverter: MapConverter<V>,
    override val redisTemplate: StringRedisTemplate
) : AbstractCodecExecutor<V, Map<String, String>>() {

    override fun CacheValue<V>.toRawValue(): Map<String, String> {
        if (isMissingGuard) {
            return mapOf(MissingGuard.STRING_VALUE to CacheSecondClock.INSTANCE.currentTime().toString())
        }
        return mapConverter.asMap(value)
    }

    override fun getRawValue(key: String): Map<String, String> {
        return redisTemplate.opsForHash<String, String>().entries(key)
    }

    override fun setValueWithTtlAt(key: String, cacheValue: CacheValue<V>) {
        setPipelined(key) { encodedKey, connection ->
            connection.hashCommands().hMSet(encodedKey, serialize(cacheValue.toRawValue()))
            connection.keyCommands().expire(encodedKey, cacheValue.expiredDuration.seconds)
        }
    }

    override fun setForeverValue(key: String, cacheValue: CacheValue<V>) {
        setPipelined(key) { encodedKey, connection ->
            connection.hashCommands().hMSet(encodedKey, serialize(cacheValue.toRawValue()))
        }
    }

    override fun decode(rawValue: Map<String, String>): V {
        return mapConverter.asValue(rawValue)
    }

    override fun isMissingGuard(rawValue: Map<String, String>): Boolean {
        return rawValue.isMissingGuard
    }

    interface MapConverter<V> {
        fun asValue(map: Map<String, String>): V
        fun asMap(value: V): Map<String, String>
    }
}
