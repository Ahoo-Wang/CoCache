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
import me.ahoo.cache.MissingGuard
import me.ahoo.cache.api.CacheValue
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript

abstract class AbstractCodecExecutor<V, RAW_VALUE>(
    /**
     * 负缓存哨兵值。默认 [MissingGuard.STRING_VALUE]；自定义值与默认值互不识别，
     * 启用自定义哨兵需全集群同时切换（滚动升级期间旧实例会把新哨兵当真实值）。
     *
     * 限定范围：仅影响 Redis 静止字节的写入与读侧识别；进程内
     * [me.ahoo.cache.MissingGuard.Companion.isMissingGuard]（core 常量判定）不受影响——
     * 值为 `"_nil_"` 形状的 [me.ahoo.cache.api.CacheValue] 在 core 各层仍被视为负缓存。
     */
    protected val missingGuardSentinel: String = MissingGuard.STRING_VALUE,
) : CodecExecutor<V> {
    abstract val redisTemplate: StringRedisTemplate

    /**
     * 原子写入 Hash（DEL + HSET + 可选 EXPIRE）。空 Map 仅淘汰该 key；[ttlSeconds] <= 0 跳过 EXPIRE（永不过期）。
     */
    protected fun executeAtomicHashWrite(key: String, hashes: Map<String, String>, ttlSeconds: Long) {
        if (hashes.isEmpty()) {
            redisTemplate.delete(key)
            return
        }
        val args = ArrayList<String>(hashes.size * 2 + 1)
        hashes.forEach { (field, value) ->
            args.add(field)
            args.add(value)
        }
        args.add(ttlSeconds.coerceAtLeast(0).toString())
        redisTemplate.execute(SET_HASH_SCRIPT, listOf(key), *args.toTypedArray())
    }

    /**
     * 原子写入 Set（DEL + SADD + 可选 EXPIRE）。空 Set 仅淘汰该 key；[ttlSeconds] <= 0 跳过 EXPIRE（永不过期）。
     */
    protected fun executeAtomicSetWrite(key: String, members: Set<String>, ttlSeconds: Long) {
        if (members.isEmpty()) {
            redisTemplate.delete(key)
            return
        }
        val args = ArrayList<String>(members.size + 1)
        args.addAll(members)
        args.add(ttlSeconds.coerceAtLeast(0).toString())
        redisTemplate.execute(SET_SET_SCRIPT, listOf(key), *args.toTypedArray())
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

        /**
         * DEL + HSET + 可选 EXPIRE 原子执行（ARGV 为扁平 field/value 对，末位为 ttl 秒数，0 表示永不过期）。
         * 逐对 HSET 而非 unpack，避免大 Map 超出 Lua 栈限制。
         */
        private val SET_HASH_SCRIPT: RedisScript<Long> = DefaultRedisScript(
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

        /**
         * DEL + SADD + 可选 EXPIRE 原子执行（ARGV 为成员列表，末位为 ttl 秒数，0 表示永不过期）。
         */
        private val SET_SET_SCRIPT: RedisScript<Long> = DefaultRedisScript(
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
}
