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

import me.ahoo.cache.ComputedCache
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.DefaultCacheValue.Companion.missingGuard
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinKeyExtractor
import me.ahoo.cache.api.join.JoinValue
import kotlin.math.min

/**
 * Simple Join Cache .
 *
 * @author ahoo wang
 */
class SimpleJoinCache<K1, V1, K2, V2>(
    private val firstCache: Cache<K1, V1>,
    private val joinCache: Cache<K2, V2>,
    override val joinKeyExtractor: JoinKeyExtractor<V1, K2>
) : JoinCache<K1, V1, K2, V2>, ComputedCache<K1, JoinValue<V1, K2, V2>> {

    @Suppress("ReturnCount")
    override fun getCache(key: K1): CacheValue<JoinValue<V1, K2, V2>>? {
        val firstCacheValue = firstCache.getCache(key) ?: return null
        if (firstCacheValue.isMissingGuard) {
            return missingGuard()
        }
        val joinKey = joinKeyExtractor.extract(firstCacheValue.value)
        val secondCacheValue = joinCache.getCache(joinKey)
        val joinValue = DefaultJoinValue(firstCacheValue.value, joinKey, secondCacheValue?.value)
        val ttlAt = getJoinTtlAt(firstCacheValue.ttlAt, secondCacheValue?.ttlAt)
        return DefaultCacheValue(value = joinValue, ttlAt = ttlAt)
    }

    private fun getJoinTtlAt(
        firstTtlAt: Long,
        secondTtlAt: Long?
    ): Long {
        if (secondTtlAt == null) {
            return firstTtlAt
        }
        return min(firstTtlAt, secondTtlAt)
    }

    @Suppress("UNCHECKED_CAST")
    override fun setCache(key: K1, value: CacheValue<JoinValue<V1, K2, V2>>) {
        if (value.isMissingGuard) {
            firstCache.setCache(key, missingGuard())
            return
        }
        val firstCacheValue = DefaultCacheValue(value = value.value.firstValue, ttlAt = value.ttlAt)
        firstCache.setCache(key, firstCacheValue)
        val secondValue = value.value.secondValue ?: return
        val secondCacheValue = DefaultCacheValue(value = secondValue, ttlAt = value.ttlAt) as CacheValue<V2>
        joinCache.setCache(value.value.joinKey, secondCacheValue)
    }

    override fun evict(key: K1) {
        val firstValue = firstCache[key] ?: return
        firstCache.evict(key)
        val joinKey = joinKeyExtractor.extract(firstValue)
        joinCache.evict(joinKey)
    }
}
