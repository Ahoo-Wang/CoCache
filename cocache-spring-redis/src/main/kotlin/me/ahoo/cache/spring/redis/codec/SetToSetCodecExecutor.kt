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
import org.springframework.data.redis.core.script.RedisScript

/**
 * `Set<String>` 存为 Redis Set。负缓存形态为单元素 `{sentinel}`；空 Set 写入即淘汰。
 */
class SetToSetCodecExecutor @JvmOverloads constructor(
    redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : AbstractCodecExecutor<Set<String>, Set<String>>(redisTemplate, missingGuardSentinel) {
    companion object {
        private val WRITE_SCRIPT: RedisScript<Long> = collectionWriteScript("SADD", step = 1)
        private val READ_SCRIPT: RedisScript<List<*>> = readScript("SMEMBERS")
    }

    override val readScript: RedisScript<List<*>> = READ_SCRIPT

    override fun toRaw(elements: List<*>): Set<String>? {
        if (elements.isEmpty()) {
            return null
        }
        return elements.mapTo(LinkedHashSet()) { it as String }
    }

    override fun isMissingGuard(raw: Set<String>): Boolean {
        return raw.size == 1 && raw.first() == missingGuardSentinel
    }

    override fun decode(raw: Set<String>): Set<String> = raw

    override fun encodeMissingGuard(): Set<String> = setOf(missingGuardSentinel)

    override fun encode(value: Set<String>): Set<String> = value

    override fun writeRaw(key: String, raw: Set<String>, ttlSeconds: Long?) {
        executeCollectionWrite(WRITE_SCRIPT, key, raw.toList(), ttlSeconds)
    }
}
