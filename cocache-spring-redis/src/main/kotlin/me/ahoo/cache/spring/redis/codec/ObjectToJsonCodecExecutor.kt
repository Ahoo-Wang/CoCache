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

import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.Type

/**
 * 对象序列化为 JSON 字符串存储。
 */
class ObjectToJsonCodecExecutor<V> @JvmOverloads constructor(
    valueType: Type,
    redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : StringCodecExecutor<V>(redisTemplate, missingGuardSentinel) {
    private val valueJavaType: JavaType = objectMapper.typeFactory.constructType(valueType)

    override fun decode(raw: String): V {
        return objectMapper.readValue(raw, valueJavaType)
    }

    override fun encode(value: V): String {
        return objectMapper.writeValueAsString(value)
    }
}
