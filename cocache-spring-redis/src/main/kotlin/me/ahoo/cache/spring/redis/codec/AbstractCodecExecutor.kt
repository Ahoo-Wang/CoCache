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
import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.SessionCallback
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 编解码执行器基类：统一读写协议，子类只负责某种 Redis 数据结构的原始读写与值编解码。
 *
 * - 读：pipeline 一次往返执行“读原始值 + TTL”。原始值缺失或 TTL 显示 key 不存在均为未命中（`null`），
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
    }

    /**
     * 在 pipeline 中排入读取原始值的命令（返回值被 pipeline 忽略）。
     */
    protected abstract fun RedisOperations<String, String>.readRaw(key: String)

    /**
     * 将 pipeline 结果转为原始值；key 不存在（含空集合）时返回 `null`。
     */
    protected abstract fun toRaw(result: Any?): RAW?

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
        val results = redisTemplate.executePipelined(
            object : SessionCallback<Any?> {
                override fun <K : Any, HV : Any> execute(operations: RedisOperations<K, HV>): Any? {
                    @Suppress("UNCHECKED_CAST")
                    val stringOperations = operations as RedisOperations<String, String>
                    stringOperations.readRaw(key)
                    stringOperations.getExpire(key)
                    return null
                }
            }
        )
        val raw = toRaw(results[0]) ?: return null
        val ttl = results[1] as Long? ?: return null
        if (ttl == TTL_NOT_EXIST) {
            // 读到值后 key 被删除/过期：按未命中处理
            return null
        }
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
