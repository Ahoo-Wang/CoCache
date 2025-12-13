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
 * MapToHashCodecExecutor .
 *
 * @author ahoo wang
 */
class MapToHashCodecExecutor(override val redisTemplate: StringRedisTemplate) :
    AbstractCodecExecutor<Map<String, String>, Map<String, String>>() {

    override fun CacheValue<Map<String, String>>.toRawValue(): Map<String, String> {
        if (isMissingGuard) {
            return mapOf(MissingGuard.STRING_VALUE to CacheSecondClock.INSTANCE.currentTime().toString())
        }
        return value
    }

    override fun getRawValue(key: String): Map<String, String>? {
        return redisTemplate.opsForHash<String, String>().entries(key)
    }

    override fun isMissingGuard(rawValue: Map<String, String>): Boolean {
        return rawValue.isMissingGuard
    }

    override fun decode(rawValue: Map<String, String>): Map<String, String> {
        return rawValue
    }

    override fun setForeverValue(key: String, cacheValue: CacheValue<Map<String, String>>) {
        setPipelined(key) { encodedKey, connection ->
            connection.hashCommands().hMSet(encodedKey, serialize(cacheValue.toRawValue()))
        }
    }

    override fun setValueWithTtlAt(key: String, cacheValue: CacheValue<Map<String, String>>) {
        setPipelined(key) { encodedKey, connection ->
            connection.hashCommands().hMSet(encodedKey, serialize(cacheValue.toRawValue()))
            connection.keyCommands().expire(encodedKey, cacheValue.expiredDuration.seconds)
        }
    }
}
