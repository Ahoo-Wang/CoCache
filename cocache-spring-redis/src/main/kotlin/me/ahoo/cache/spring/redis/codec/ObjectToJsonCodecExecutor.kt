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

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import me.ahoo.cache.CacheValue
import me.ahoo.cache.CacheValue.Companion.missingGuard
import me.ahoo.cache.MissingGuard
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * ObjectToJsonCodecExecutor .
 *
 * @author ahoo wang
 */
class ObjectToJsonCodecExecutor<V>(
    private val valueType: Class<V>,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) : CodecExecutor<V> {
    override fun executeAndDecode(key: String, ttlAt: Long): CacheValue<V> {
        val value = redisTemplate.opsForValue()[key] ?: return missingGuard()
        return if (CacheValue.isMissingGuard(value)) {
            missingGuard()
        } else {
            val typedValue = objectMapper.readValue(value, valueType)
            CacheValue(typedValue, ttlAt)
        }
    }

    override fun executeAndEncode(key: String, cacheValue: CacheValue<V>) {
        if (cacheValue.isMissingGuard) {
            redisTemplate.opsForValue()[key] = MissingGuard.STRING_VALUE
            return
        }
        redisTemplate.opsForValue()[key] = objectMapper.writeValueAsString(cacheValue.value)
    }
}
