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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

/**
 * 以 Redis Hash 存储的编解码基类。负缓存形态为单字段 `{sentinel: 写入时间}`；空 Map 写入即淘汰。
 */
abstract class HashCodecExecutor<V>(
    redisTemplate: StringRedisTemplate,
    missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : AbstractCodecExecutor<V, Map<String, String>>(redisTemplate, missingGuardSentinel) {
    companion object {
        private val WRITE_SCRIPT: RedisScript<Long> = collectionWriteScript("HSET", step = 2)
        private val READ_SCRIPT: RedisScript<List<*>> = readScript("HGETALL")
    }

    override val readScript: RedisScript<List<*>> = READ_SCRIPT

    /**
     * HGETALL 展开为 field/value 交替的列表。
     */
    override fun toRaw(elements: List<*>): Map<String, String>? {
        if (elements.isEmpty()) {
            return null
        }
        return elements.chunked(2).associate { (field, value) -> field as String to value as String }
    }

    override fun isMissingGuard(raw: Map<String, String>): Boolean {
        return raw.size == 1 && raw.keys.first() == missingGuardSentinel
    }

    override fun encodeMissingGuard(): Map<String, String> {
        return mapOf(missingGuardSentinel to TtlAt.currentTime().toString())
    }

    override fun writeRaw(key: String, raw: Map<String, String>, ttlSeconds: Long?) {
        val elements = ArrayList<String>(raw.size * 2)
        raw.forEach { (field, value) ->
            elements.add(field)
            elements.add(value)
        }
        executeCollectionWrite(WRITE_SCRIPT, key, elements, ttlSeconds)
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
