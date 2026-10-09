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

import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * 以 Redis String 存储的编解码基类。
 */
abstract class StringCodecExecutor<V>(
    redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : AbstractCodecExecutor<V, String>(redisTemplate, missingGuardSentinel) {

    override fun RedisOperations<String, String>.readRaw(key: String) {
        opsForValue().get(key)
    }

    override fun toRaw(result: Any?): String? {
        return result as String?
    }

    override fun isMissingGuard(raw: String): Boolean {
        return raw == missingGuardSentinel
    }

    override fun encodeMissingGuard(): String {
        return missingGuardSentinel
    }

    override fun writeRaw(key: String, raw: String, ttlSeconds: Long?) {
        if (ttlSeconds == null) {
            redisTemplate.opsForValue().set(key, raw)
            return
        }
        redisTemplate.opsForValue().set(key, raw, Duration.ofSeconds(ttlSeconds))
    }
}

/**
 * 字符串值原样存储。
 *
 * 注意：与 [missingGuardSentinel] 相等的业务字符串在 Redis 中无法与负缓存区分，读回时视为负缓存。
 */
class StringToStringCodecExecutor @JvmOverloads constructor(
    redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : StringCodecExecutor<String>(redisTemplate, missingGuardSentinel) {
    override fun decode(raw: String): String = raw

    override fun encode(value: String): String = value
}
