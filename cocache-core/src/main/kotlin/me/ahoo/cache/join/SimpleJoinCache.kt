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

package me.ahoo.cache.join

import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.MissingValue
import me.ahoo.cache.api.PresentValue
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinKeyExtractor
import me.ahoo.cache.api.join.JoinValue
import kotlin.math.min

/**
 * 组合两个独立缓存的 [JoinCache]。不持有组件缓存的生命周期。
 *
 * @author ahoo wang
 */
class SimpleJoinCache<K1, V1, K2, V2>(
    val firstCache: Cache<K1, V1>,
    val joinCache: Cache<K2, V2>,
    override val joinKeyExtractor: JoinKeyExtractor<V1, K2>
) : JoinCache<K1, V1, K2, V2> {

    override fun getCache(key: K1): CacheValue<JoinValue<V1, K2, V2>>? {
        val first = firstCache.getCache(key)?.takeUnless { it.isExpired } ?: return null
        val firstValue = when (first) {
            is MissingValue -> return CacheValue.missing(first.ttlAt)
            is PresentValue -> first.value
        }
        val joinKey = joinKeyExtractor.extract(firstValue)
        val second = joinCache.getCache(joinKey)?.takeUnless { it.isExpired }
        val ttlAt = second?.let { min(first.ttlAt, it.ttlAt) } ?: first.ttlAt
        return CacheValue.of(JoinValue(firstValue, joinKey, second?.value), ttlAt)
    }

    override fun setCache(key: K1, value: CacheValue<JoinValue<V1, K2, V2>>) {
        val joinValue = when (value) {
            is MissingValue -> {
                firstCache.setCache(key, CacheValue.missing(value.ttlAt))
                return
            }

            is PresentValue -> value.value
        }
        firstCache.setCache(key, CacheValue.of(joinValue.firstValue, value.ttlAt))
        joinValue.secondValue?.let {
            joinCache.setCache(joinValue.joinKey, CacheValue.of(it, value.ttlAt))
        }
    }

    /**
     * 各组件缓存按自身 TTL 策略写入。
     */
    override fun set(key: K1, value: JoinValue<V1, K2, V2>) {
        firstCache[key] = value.firstValue
        value.secondValue?.let {
            joinCache[value.joinKey] = it
        }
    }

    /**
     * 只淘汰主缓存：关联缓存有独立的生命周期，由其写入方负责失效。
     */
    override fun evict(key: K1) {
        firstCache.evict(key)
    }

    override fun evict(firstKey: K1, joinKey: K2) {
        firstCache.evict(firstKey)
        joinCache.evict(joinKey)
    }
}
