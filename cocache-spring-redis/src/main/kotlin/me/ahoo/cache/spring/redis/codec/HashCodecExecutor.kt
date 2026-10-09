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

import me.ahoo.cache.api.TtlAt
import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript

/**
 * 以 Redis Hash 存储的编解码基类。负缓存形态为单字段 `{sentinel: 写入时间}`；空 Map 写入即淘汰。
 */
abstract class HashCodecExecutor<V>(
    redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : AbstractCodecExecutor<V, Map<String, String>>(redisTemplate, missingGuardSentinel) {
    companion object {
        /**
         * DEL + HSET + 可选 EXPIRE 原子执行。ARGV 为扁平 field/value 对，末位为 TTL 秒数（0 表示永不过期）。
         * 逐对 HSET 而非 unpack，避免大 Map 超出 Lua 栈限制。
         */
        private val WRITE_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            """
            redis.call('DEL', KEYS[1])
            for i = 1, #ARGV - 1, 2 do
              redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            local ttl = tonumber(ARGV[#ARGV])
            if ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }

    override fun RedisOperations<String, String>.readRaw(key: String) {
        opsForHash<String, String>().entries(key)
    }

    override fun toRaw(result: Any?): Map<String, String>? {
        @Suppress("UNCHECKED_CAST")
        return (result as Map<String, String>?)?.takeIf { it.isNotEmpty() }
    }

    override fun isMissingGuard(raw: Map<String, String>): Boolean {
        return raw.size == 1 && raw.keys.first() == missingGuardSentinel
    }

    override fun encodeMissingGuard(): Map<String, String> {
        return mapOf(missingGuardSentinel to TtlAt.currentTime().toString())
    }

    override fun writeRaw(key: String, raw: Map<String, String>, ttlSeconds: Long?) {
        if (raw.isEmpty()) {
            redisTemplate.delete(key)
            return
        }
        val args = ArrayList<String>(raw.size * 2 + 1)
        raw.forEach { (field, value) ->
            args.add(field)
            args.add(value)
        }
        args.add((ttlSeconds ?: 0).toString())
        redisTemplate.execute(WRITE_SCRIPT, listOf(key), *args.toTypedArray())
    }
}

/**
 * `Map<String, String>` 原样存为 Hash。
 */
class MapToHashCodecExecutor @JvmOverloads constructor(
    redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : HashCodecExecutor<Map<String, String>>(redisTemplate, missingGuardSentinel) {
    override fun decode(raw: Map<String, String>): Map<String, String> = raw

    override fun encode(value: Map<String, String>): Map<String, String> = value
}

/**
 * 对象经 [MapConverter] 转换后存为 Hash。
 */
class ObjectToHashCodecExecutor<V> @JvmOverloads constructor(
    private val mapConverter: MapConverter<V>,
    redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : HashCodecExecutor<V>(redisTemplate, missingGuardSentinel) {
    override fun decode(raw: Map<String, String>): V = mapConverter.asValue(raw)

    override fun encode(value: V): Map<String, String> = mapConverter.asMap(value)

    interface MapConverter<V> {
        fun asValue(map: Map<String, String>): V
        fun asMap(value: V): Map<String, String>
    }
}
