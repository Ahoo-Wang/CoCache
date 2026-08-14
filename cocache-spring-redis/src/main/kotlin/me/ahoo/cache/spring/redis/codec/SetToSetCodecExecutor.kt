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

/**
 * SetToSetCodecExecutor .
 *
 * @author ahoo wang
 */
class SetToSetCodecExecutor(
    override val redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = MissingGuard.STRING_VALUE,
) : AbstractCodecExecutor<Set<String>, Set<String>>(missingGuardSentinel) {

    private val missingGuard: Set<String> = setOf(missingGuardSentinel)

    override fun CacheValue<Set<String>>.toRawValue(): Set<String> {
        if (isMissingGuard) {
            return missingGuard
        }
        return value
    }

    override fun isMissingGuard(rawValue: Set<String>): Boolean {
        return rawValue.size == 1 && rawValue.first() == missingGuardSentinel
    }

    override fun getRawValue(key: String): Set<String>? {
        return redisTemplate.opsForSet().members(key)
    }

    override fun decode(rawValue: Set<String>): Set<String> {
        return rawValue
    }

    override fun setForeverValue(key: String, cacheValue: CacheValue<Set<String>>) {
        executeAtomicSetWrite(key, cacheValue.toRawValue(), ttlSeconds = 0)
    }

    override fun setValueWithTtlAt(key: String, cacheValue: CacheValue<Set<String>>) {
        executeAtomicSetWrite(key, cacheValue.toRawValue(), ttlSeconds = cacheValue.expiredDuration.seconds)
    }
}
