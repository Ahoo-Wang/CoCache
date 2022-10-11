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
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * SetToSetCodecExecutor .
 *
 * @author ahoo wang
 */
class SetToSetCodecExecutor(private val redisTemplate: StringRedisTemplate) : CodecExecutor<Set<String>> {
    override fun executeAndDecode(key: String, ttlAt: Long): CacheValue<Set<String>> {
        val value = redisTemplate.opsForSet().members(key) ?: return missingGuard()
        return if (CacheValue.isMissingGuard(value)) {
            missingGuard()
        } else {
            CacheValue(value, ttlAt)
        }
    }

    override fun executeAndEncode(key: String, cacheValue: CacheValue<Set<String>>) {
        if (cacheValue.isMissingGuard) {
            redisTemplate.opsForSet().add(key, CacheValue.MISSING_GUARD_STRING_VALUE)
            return
        }
        redisTemplate.delete(key)
        redisTemplate.opsForSet().add(key, *cacheValue.value.toTypedArray())
    }
}
