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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.DefaultMissingGuard
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

    @Suppress("TooGenericExceptionCaught")
    override fun executeAndDecode(key: String, ttlAt: Long): CacheValue<V>? {
        // ttlAt 是绝对到期时间戳：不得经 missingGuard(ttl) 构造（其按相对时长 now+ttl 计算），
        // 否则负缓存读回的到期时间约为两倍纪元秒，客户端负缓存实际永不过期。
        val rawValue = getRawValue(key)
        if (rawValue == null || isMissingGuard(rawValue)) {
            return missingGuardCacheValue(ttlAt)
        }
        val value = try {
            decode(rawValue)
        } catch (e: Exception) {
            // Self-heal: any decode failure means the stored payload is corrupted
            // or incompatible. Catch broadly so every codec gets the same guarantee.
            log.warn(e) { "Corrupted payload at key[$key] - evict and treat as cache miss." }
            redisTemplate.delete(key)
            return null
        }
        return DefaultCacheValue(
            value,
            ttlAt,
        )
    }

    protected abstract fun getRawValue(key: String): RAW_VALUE?
    protected abstract fun isMissingGuard(rawValue: RAW_VALUE): Boolean
    protected abstract fun decode(rawValue: RAW_VALUE): V

    /**
     * 以绝对到期时间戳构造负缓存值。不得改为 [DefaultCacheValue.missingGuard]——
     * 其参数按相对时长（now+ttl）计算，传入绝对时间戳会使到期时间翻倍纪元秒。
     */
    protected fun missingGuardCacheValue(ttlAt: Long): CacheValue<V> {
        @Suppress("UNCHECKED_CAST")
        return DefaultCacheValue(DefaultMissingGuard, ttlAt) as CacheValue<V>
    }

    /**
     * null 归一化：非 missing-guard 的 null 统一转为负缓存哨兵写入，
     * 使所有 codec 与内存实现语义对齐（null = 负缓存）。
     */
    override fun executeAndEncode(key: String, cacheValue: CacheValue<V>) {
        val normalizedValue = if (cacheValue.value == null && cacheValue.isMissingGuard.not()) {
            missingGuardCacheValue(cacheValue.ttlAt)
        } else {
            cacheValue
        }
        if (normalizedValue.isForever) {
            setForeverValue(key, normalizedValue)
        } else {
            setValueWithTtlAt(key, normalizedValue)
        }
    }

    protected abstract fun setForeverValue(key: String, cacheValue: CacheValue<V>)
    protected abstract fun setValueWithTtlAt(key: String, cacheValue: CacheValue<V>)

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
