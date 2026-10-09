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
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.MissingValue
import me.ahoo.cache.api.PresentValue
import me.ahoo.cache.api.TtlAt
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript

/**
 * 编解码执行器基类：统一读写协议，子类只负责某种 Redis 数据结构的原始读写与值编解码。
 *
 * - 读：Lua 脚本一次往返原子地读取 TTL 与原始值。原始值缺失或 key 不存在均为未命中（`null`），
 *   绝不推断为负缓存——负缓存只来自存储中真实的哨兵记录。
 * - 写：负缓存写为 [missingGuardSentinel]；剩余 TTL 钳为至少 1 秒（亚秒边界下 0 会被当作永不过期或被拒绝）。
 *
 * @param missingGuardSentinel 负缓存在 Redis 中的静止形态。修改它需全集群同时切换，且不得与任何合法业务值的编码相等。
 */
abstract class AbstractCodecExecutor<V, RAW : Any>(
    protected val redisTemplate: StringRedisTemplate,
    protected val missingGuardSentinel: String = DEFAULT_MISSING_GUARD_SENTINEL,
) : CodecExecutor<V> {
    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * 默认负缓存哨兵。
         */
        const val DEFAULT_MISSING_GUARD_SENTINEL = "_nil_"

        private const val TTL_FOREVER = -1L
        private const val TTL_NOT_EXIST = -2L

        /**
         * 生成原子读脚本：一次往返返回 `{ttl, ...原始值}`；key 不存在时只返回 `{-2}`。
         * 走共享连接（EVALSHA），不同于 pipeline 需要专用连接。
         *
         * @param command 读取原始值的命令（GET / HGETALL / SMEMBERS），集合结果被展开在 ttl 之后
         */
        fun readScript(command: String): RedisScript<List<*>> {
            return DefaultRedisScript(
                """
                local ttl = redis.call('TTL', KEYS[1])
                if ttl == -2 then return {ttl} end
                local raw = redis.call('$command', KEYS[1])
                if type(raw) == 'table' then
                  table.insert(raw, 1, ttl)
                  return raw
                end
                return {ttl, raw}
                """.trimIndent(),
                List::class.java,
            )
        }

        /**
         * 生成集合原子写脚本：ARGV 为扁平元素，末位为 TTL 秒数（0 表示永不过期）。
         * 逐个调用 [command] 而非 unpack，避免大集合超出 Lua 栈限制。
         *
         * @param step 每次写入消耗的元素个数（SADD 为 1，HSET field/value 为 2）
         */
        fun collectionWriteScript(command: String, step: Int): RedisScript<Long> {
            val elements = (1..step).joinToString(", ") { if (it == 1) "ARGV[i]" else "ARGV[i + ${it - 1}]" }
            return DefaultRedisScript(
                """
                redis.call('DEL', KEYS[1])
                for i = 1, #ARGV - 1, $step do
                  redis.call('$command', KEYS[1], $elements)
                end
                local ttl = tonumber(ARGV[#ARGV])
                if ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end
                return 1
                """.trimIndent(),
                Long::class.java,
            )
        }
    }

    /**
     * 原子读脚本，由 [readScript] 生成。
     */
    protected abstract val readScript: RedisScript<List<*>>

    /**
     * 将读脚本在 ttl 之后返回的元素转为原始值；值缺失（含空集合）时返回 `null`。
     */
    protected abstract fun toRaw(elements: List<*>): RAW?

    protected abstract fun isMissingGuard(raw: RAW): Boolean

    protected abstract fun decode(raw: RAW): V

    protected abstract fun encodeMissingGuard(): RAW

    protected abstract fun encode(value: V): RAW

    /**
     * @param ttlSeconds 剩余 TTL（至少 1 秒），`null` 表示永不过期
     */
    protected abstract fun writeRaw(key: String, raw: RAW, ttlSeconds: Long?)

    @Suppress("TooGenericExceptionCaught")
    final override fun executeAndDecode(key: String): CacheValue<V>? {
        val results = redisTemplate.execute(readScript, listOf(key)) ?: return null
        val ttl = results.firstOrNull() as Long? ?: return null
        if (ttl == TTL_NOT_EXIST) {
            return null
        }
        val raw = toRaw(results.subList(1, results.size)) ?: return null
        val ttlAt = if (ttl == TTL_FOREVER) TtlAt.FOREVER else TtlAt.currentTime() + ttl
        if (isMissingGuard(raw)) {
            return CacheValue.missing(ttlAt)
        }
        val value = try {
            decode(raw)
        } catch (e: Exception) {
            // 自愈：载荷损坏或不兼容，淘汰后按未命中处理（回源重建）
            log.warn(e) { "Corrupted payload at key[$key] - evict and treat as cache miss." }
            redisTemplate.delete(key)
            return null
        }
        return CacheValue.of(value, ttlAt)
    }

    /**
     * 原子写入集合结构：DEL + 逐元素写入 + 可选 EXPIRE（单个 Lua 脚本）。空集合仅淘汰该 key。
     *
     * @param script 由 [collectionWriteScript] 生成
     * @param elements 扁平参数（Set 为成员，Hash 为 field/value 交替）
     */
    protected fun executeCollectionWrite(
        script: RedisScript<Long>,
        key: String,
        elements: List<String>,
        ttlSeconds: Long?
    ) {
        if (elements.isEmpty()) {
            redisTemplate.delete(key)
            return
        }
        val args = ArrayList<String>(elements.size + 1)
        args.addAll(elements)
        args.add((ttlSeconds ?: 0).toString())
        redisTemplate.execute(script, listOf(key), *args.toTypedArray())
    }

    final override fun executeAndEncode(key: String, cacheValue: CacheValue<V>) {
        if (cacheValue.isExpired) {
            redisTemplate.delete(key)
            return
        }
        val raw = when (cacheValue) {
            is MissingValue -> encodeMissingGuard()
            is PresentValue -> encode(cacheValue.value)
        }
        val ttlSeconds = if (cacheValue.isForever) null else cacheValue.expiredDuration.seconds.coerceAtLeast(1)
        writeRaw(key, raw, ttlSeconds)
    }
}
