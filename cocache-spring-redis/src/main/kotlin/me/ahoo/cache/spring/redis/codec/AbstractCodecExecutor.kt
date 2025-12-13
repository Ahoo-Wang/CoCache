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

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.CacheValue
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.core.StringRedisTemplate

abstract class AbstractCodecExecutor<V, RAW_VALUE> : CodecExecutor<V> {
    abstract val redisTemplate: StringRedisTemplate

    private fun serialize(key: String): ByteArray {
        return redisTemplate.stringSerializer.serialize(key)
    }

    fun serialize(hashes: Map<String, String>): Map<ByteArray, ByteArray> {
        val ret = mutableMapOf<ByteArray, ByteArray>()

        for (entry in hashes.entries) {
            ret[serialize(entry.key)] = serialize(entry.value)
        }

        return ret
    }

    fun serialize(value: Set<String>?): Array<ByteArray> {
        if (value == null) {
            return emptyArray()
        }
        return value.map { serialize(it) }.toTypedArray()
    }

    protected fun setPipelined(key: String, block: (encodedKey: ByteArray, connection: RedisConnection) -> Unit) {
        redisTemplate.executePipelined { connection ->
            val encodedKey = serialize(key)
            connection.keyCommands().del(encodedKey)
            block(encodedKey, connection)
            null
        }
    }

    abstract fun CacheValue<V>.toRawValue(): RAW_VALUE

    override fun executeAndDecode(key: String, ttlAt: Long): CacheValue<V> {
        val rawValue = getRawValue(key) ?: return DefaultCacheValue.missingGuard(ttlAt)
        return if (isMissingGuard(rawValue)) {
            DefaultCacheValue.missingGuard(ttlAt)
        } else {
            val value = decode(rawValue)
            DefaultCacheValue(
                value,
                ttlAt,
            )
        }
    }

    protected abstract fun getRawValue(key: String): RAW_VALUE?
    protected abstract fun isMissingGuard(rawValue: RAW_VALUE): Boolean
    protected abstract fun decode(rawValue: RAW_VALUE): V

    override fun executeAndEncode(key: String, cacheValue: CacheValue<V>) {
        when {
            cacheValue.isForever -> setForeverValue(key, cacheValue)
            else -> setValueWithTtlAt(key, cacheValue)
        }
    }

    protected abstract fun setForeverValue(key: String, cacheValue: CacheValue<V>)
    protected abstract fun setValueWithTtlAt(key: String, cacheValue: CacheValue<V>)
}
