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

import com.fasterxml.jackson.databind.ObjectMapper
import me.ahoo.cache.MissingGuard
import me.ahoo.cache.MissingGuard.Companion.isMissingGuard
import me.ahoo.cache.api.CacheValue
import org.springframework.data.redis.core.StringRedisTemplate
import java.lang.reflect.Type

/**
 * ObjectToJsonCodecExecutor .
 *
 * @author ahoo wang
 */
class ObjectToJsonCodecExecutor<V>(
    private val valueType: Type,
    override val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) : AbstractCodecExecutor<V, String>() {
    private val valueJavaType = objectMapper.typeFactory.constructType(valueType)
    override fun CacheValue<V>.toRawValue(): String {
        if (isMissingGuard) {
            return MissingGuard.STRING_VALUE
        }
        return objectMapper.writeValueAsString(value)
    }

    override fun isMissingGuard(rawValue: String): Boolean {
        return rawValue.isMissingGuard
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
        redisTemplate.opsForValue().set(key, cacheValue.toRawValue(), cacheValue.expiredDuration)
    }
}
