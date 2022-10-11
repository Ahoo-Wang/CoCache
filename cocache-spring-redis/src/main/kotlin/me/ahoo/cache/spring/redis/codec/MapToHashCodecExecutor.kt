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
import me.ahoo.cache.CacheValue.Companion.missingGuard
import me.ahoo.cache.util.CacheSecondClock
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * MapToHashCodecExecutor .
 *
 * @author ahoo wang
 */
class MapToHashCodecExecutor(private val redisTemplate: StringRedisTemplate) : CodecExecutor<Map<String, String>> {
    override fun executeAndDecode(key: String, ttlAt: Long): CacheValue<Map<String, String>> {
        val value = redisTemplate.opsForHash<String, String>().entries(key)
        return if (CacheValue.isMissingGuard(value)) {
            missingGuard()
        } else {
            CacheValue(value, ttlAt)
        }
    }

    override fun executeAndEncode(key: String, cacheValue: CacheValue<Map<String, String>>) {
        if (cacheValue.isMissingGuard) {
            redisTemplate.opsForHash<Any, Any>()
                .put(key, CacheValue.MISSING_GUARD_STRING_VALUE, CacheSecondClock.INSTANCE.currentTime().toString())
            return
        }
        redisTemplate.delete(key)
        redisTemplate.opsForHash<Any, Any>().putAll(key, cacheValue.value)
    }
}