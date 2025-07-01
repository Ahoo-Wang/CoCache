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

package me.ahoo.cache.test

import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.MissingGuard
import me.ahoo.cache.api.Cache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

abstract class CacheSpec<K, V> {
    protected abstract fun createCache(): Cache<K, V>
    protected abstract fun createCacheEntry(): Pair<K, V>
    protected lateinit var cache: Cache<K, V>
    protected open val missingGuard: V = DefaultCacheValue.missingGuardValue()

    @BeforeEach
    open fun setup() {
        cache = createCache()
    }

    @Test
    fun get() {
        val (key, value) = createCacheEntry()
        cache[key].assert().isNull()
    }

    @Test
    fun getWhenExpired() {
        val (key, value) = createCacheEntry()
        val ttlAt = ComputedTtlAt.at(-5)
        cache[key, ttlAt] = value
        cache[key].assert().isNull()
        cache.getTtlAt(key).assert().isNull()
    }

    @Test
    fun set() {
        val (key, value) = createCacheEntry()
        cache[key].assert().isNull()
        cache[key] = value
        cache[key].assert().isEqualTo(value)
    }

    @Test
    fun setWithTtl() {
        val (key, value) = createCacheEntry()
        cache[key].assert().isNull()
        val cacheValue = DefaultCacheValue.ttlAt(value, 5)
        cache.setCache(key, cacheValue)
        cache[key].assert().isEqualTo(value)
        cache.getTtlAt(key).assert().isEqualTo(cacheValue.ttlAt)
    }

    @Test
    fun setWithTtlAmplitude() {
        val (key, value) = createCacheEntry()
        cache[key].assert().isNull()
        val cacheValue = DefaultCacheValue.ttlAt(value, 5, 1)
        cache.setCache(key, cacheValue)
        cache[key].assert().isEqualTo(value)
        cache.getTtlAt(key).assert().isEqualTo(cacheValue.ttlAt)
    }

    @Test
    fun evict() {
        val (key, value) = createCacheEntry()
        cache[key] = value
        cache[key].assert().isEqualTo(value)
        cache.evict(key)
        cache[key].assert().isNull()
    }

    @Test
    fun setMissing() {
        val (key, value) = createCacheEntry()
        cache[key] = missingGuard
        cache[key].assert().isNull()
        cache.getTtlAt(key).assert().isNull()
    }

    @Test
    fun setMissingTtl() {
        val (key, value) = createCacheEntry()
        cache.setCache(key, DefaultCacheValue.missingGuard(missingGuard as MissingGuard, 5))
        cache[key].assert().isNull()
        cache.getTtlAt(key).assert().isNull()
    }
}
