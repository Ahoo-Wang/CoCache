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

import me.ahoo.cache.DefaultCacheValue.Companion.forever
import me.ahoo.cache.DefaultCacheValue.Companion.missingGuard
import me.ahoo.cache.DefaultCacheValue.Companion.missingGuardValue
import me.ahoo.cache.api.Cache

interface ComputedCache<K, V> : Cache<K, V> {
    /**
     * Get the real cache value.
     *
     * @param key cache key
     * @return real cache value
     */
    @Suppress("ReturnCount")
    override fun get(key: K): V? {
        val cacheValue = getCache(key) ?: return null
        if (cacheValue.isMissingGuard || cacheValue.isExpired) {
            return null
        }
        return cacheValue.value
    }

    /**
     * get cache expire at time.
     *
     * @param key cache key
     * @return when return null:cache not exist.
     */
    @Suppress("ReturnCount")
    override fun getTtlAt(key: K): Long? {
        val cacheValue = getCache(key) ?: return null
        if (cacheValue.isMissingGuard) {
            return null
        }
        return cacheValue.ttlAt
    }

    override operator fun set(key: K, ttlAt: Long, value: V) {
        setCache(key, DefaultCacheValue(value, ttlAt))
    }

    override operator fun set(key: K, value: V) {
        val cacheValue = if (missingGuardValue<Any>() == value) missingGuard() else forever(value)
        setCache(key, cacheValue)
    }
}
