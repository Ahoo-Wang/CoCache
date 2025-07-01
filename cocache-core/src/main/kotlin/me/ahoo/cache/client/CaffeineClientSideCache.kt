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
package me.ahoo.cache.client

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CaffeineCache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.api.annotation.GuavaCache

/**
 * Caffeine Client Side Cache .
 *
 * @author ahoo wang
 */
class CaffeineClientSideCache<V>(
    private val caffeineCache: Cache<String, CacheValue<V>> = Caffeine.newBuilder().build(),
    override val ttl: Long = CoCache.DEFAULT_TTL,
    override val ttlAmplitude: Long = CoCache.DEFAULT_TTL_AMPLITUDE
) : ComputedClientSideCache<V> {

    override fun getCache(key: String): CacheValue<V>? {
        return caffeineCache.getIfPresent(key)
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        if (value.isExpired) {
            return
        }
        caffeineCache.put(key, value)
    }

    override val size: Long
        get() = caffeineCache.estimatedSize()

    override fun evict(key: String) {
        caffeineCache.invalidate(key)
    }

    override fun clear() {
        caffeineCache.invalidateAll()
    }

    companion object {
        fun <V> CaffeineCache.toClientSideCache(
            ttl: Long = CoCache.DEFAULT_TTL,
            ttlAmplitude: Long = CoCache.DEFAULT_TTL_AMPLITUDE
        ): CaffeineClientSideCache<V> {
            val cacheBuilder = Caffeine.newBuilder()
            if (initialCapacity != GuavaCache.UNSET_INT) {
                cacheBuilder.initialCapacity(initialCapacity)
            }
            if (maximumSize != GuavaCache.UNSET_LONG) {
                cacheBuilder.maximumSize(maximumSize)
            }
            if (expireAfterWrite != GuavaCache.UNSET_LONG) {
                cacheBuilder.expireAfterWrite(expireAfterWrite, expireUnit)
            }
            if (expireAfterAccess != GuavaCache.UNSET_LONG) {
                cacheBuilder.expireAfterAccess(expireAfterAccess, expireUnit)
            }
            return CaffeineClientSideCache(cacheBuilder.build(), ttl, ttlAmplitude)
        }
    }
}
