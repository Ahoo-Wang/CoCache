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
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript

/**
 * `Set<String>` 存为 Redis Set。负缓存形态为单元素 `{sentinel}`；空 Set 写入即淘汰。
 */
class SetToSetCodecExecutor @JvmOverloads constructor(
    redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : AbstractCodecExecutor<Set<String>, Set<String>>(redisTemplate, missingGuardSentinel) {
    companion object {
        /**
         * DEL + SADD + 可选 EXPIRE 原子执行。ARGV 为成员列表，末位为 TTL 秒数（0 表示永不过期）。
         */
        private val WRITE_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            """
            redis.call('DEL', KEYS[1])
            for i = 1, #ARGV - 1 do
              redis.call('SADD', KEYS[1], ARGV[i])
            end
            local ttl = tonumber(ARGV[#ARGV])
            if ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }

    override fun RedisOperations<String, String>.readRaw(key: String) {
        opsForSet().members(key)
    }

    override fun toRaw(result: Any?): Set<String>? {
        @Suppress("UNCHECKED_CAST")
        return (result as Set<String>?)?.takeIf { it.isNotEmpty() }
    }

    override fun isMissingGuard(raw: Set<String>): Boolean {
        return raw.size == 1 && raw.first() == missingGuardSentinel
    }

    override fun decode(raw: Set<String>): Set<String> = raw

    override fun encodeMissingGuard(): Set<String> = setOf(missingGuardSentinel)

    override fun encode(value: Set<String>): Set<String> = value

    override fun writeRaw(key: String, raw: Set<String>, ttlSeconds: Long?) {
        if (raw.isEmpty()) {
            redisTemplate.delete(key)
            return
        }
        val args = ArrayList<String>(raw.size + 1)
        args.addAll(raw)
        args.add((ttlSeconds ?: 0).toString())
        redisTemplate.execute(WRITE_SCRIPT, listOf(key), *args.toTypedArray())
    }
}
