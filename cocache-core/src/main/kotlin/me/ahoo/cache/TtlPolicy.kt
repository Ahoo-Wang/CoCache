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

package me.ahoo.cache

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.annotation.CoCache

/**
 * 缓存的 TTL 策略（单位：秒），由编排层持有，存储层只看到计算后的绝对到期时间。
 *
 * @param ttl 命中值 TTL，可为 [TtlAt.FOREVER]
 * @param ttlAmplitude 命中值 TTL 的随机抖动幅度
 * @param missingTtl 负缓存 TTL（不抖动）
 * @param clientSideTtl L2 副本的最长存活时间，`0` 表示不限制
 */
data class TtlPolicy(
    val ttl: Long = CoCache.DEFAULT_TTL,
    val ttlAmplitude: Long = CoCache.DEFAULT_TTL_AMPLITUDE,
    val missingTtl: Long = CoCache.DEFAULT_MISSING_TTL,
    val clientSideTtl: Long = CoCache.DEFAULT_CLIENT_SIDE_TTL
) {
    init {
        require(ttl > 0) { "ttl[$ttl] must be positive." }
        require(ttlAmplitude >= 0) { "ttlAmplitude[$ttlAmplitude] must not be negative." }
        require(missingTtl > 0) { "missingTtl[$missingTtl] must be positive." }
        require(clientSideTtl >= 0) { "clientSideTtl[$clientSideTtl] must not be negative." }
    }

    /**
     * 保留 5.1 的构造函数签名，保证二进制兼容。
     */
    @Deprecated("Binary compatibility only.", level = DeprecationLevel.HIDDEN)
    constructor(ttl: Long, ttlAmplitude: Long, missingTtl: Long) :
        this(ttl, ttlAmplitude, missingTtl, CoCache.DEFAULT_CLIENT_SIDE_TTL)

    /**
     * 按策略构造缓存条目：`null` 值为负缓存。
     */
    fun <V> toCacheValue(value: V?): CacheValue<V> {
        if (value == null) {
            return missing()
        }
        return CacheValue.of(value, valueTtlAt())
    }

    /**
     * 命中值的绝对到期时间（含抖动）。
     */
    fun valueTtlAt(): Long {
        return TtlAt.at(ttl, ttlAmplitude)
    }

    fun <V> missing(): CacheValue<V> {
        return CacheValue.missing(TtlAt.at(missingTtl))
    }

    /**
     * 写入 L2 的副本：到期时间不晚于 `now + clientSideTtl`。不限制或原到期时间更早时原样返回。
     */
    fun <V> clientSide(cacheValue: CacheValue<V>): CacheValue<V> {
        if (clientSideTtl == 0L) {
            return cacheValue
        }
        val cappedTtlAt = TtlAt.at(clientSideTtl)
        if (cacheValue.ttlAt <= cappedTtlAt) {
            return cacheValue
        }
        return CacheValue.of(cacheValue.value, cappedTtlAt)
    }
}
