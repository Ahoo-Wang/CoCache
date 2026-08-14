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
import me.ahoo.cache.api.CacheValue
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.Type
import java.time.Duration

/**
 * ObjectToJsonCodecExecutor .
 *
 * @author ahoo wang
 */
class ObjectToJsonCodecExecutor<V>(
    private val valueType: Type,
    override val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    missingGuardSentinel: String = MissingGuard.STRING_VALUE,
) : AbstractCodecExecutor<V, String>(missingGuardSentinel) {
    private val valueJavaType = objectMapper.typeFactory.constructType(valueType)
    override fun CacheValue<V>.toRawValue(): String {
        if (isMissingGuard) {
            return missingGuardSentinel
        }
        return objectMapper.writeValueAsString(value)
    }

    override fun isMissingGuard(rawValue: String): Boolean {
        return rawValue == missingGuardSentinel
    }

    override fun getRawValue(key: String): String? {
        return redisTemplate.opsForValue()[key]
    }

    override fun decode(rawValue: String): V {
        return objectMapper.readValue(rawValue, valueJavaType)
    }

    override fun setForeverValue(key: String, cacheValue: CacheValue<V>) {
        redisTemplate.opsForValue()[key] = cacheValue.toRawValue()
    }

    override fun setValueWithTtlAt(key: String, cacheValue: CacheValue<V>) {
        // 与结构型 codec 一致：亚秒边界下钳为 1 秒，避免 Duration.ZERO 触发 SET EX 0 报错
        val ttl = maxOf(cacheValue.expiredDuration, Duration.ofSeconds(1))
        redisTemplate.opsForValue().set(key, cacheValue.toRawValue(), ttl)
    }
}
