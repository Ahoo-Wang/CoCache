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

import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [Cache] 语义的兼容性规格。
 */
abstract class CacheSpec<K, V> {
    protected abstract fun createCache(): Cache<K, V>
    protected abstract fun createCacheEntry(): Pair<K, V>
    protected lateinit var cache: Cache<K, V>

    @BeforeEach
    open fun setup() {
        cache = createCache()
    }

    @Test
    fun getAbsent() {
        val (key, _) = createCacheEntry()
        cache[key].assert().isNull()
        cache.getTtlAt(key).assert().isNull()
    }

    @Test
    fun set() {
        val (key, value) = createCacheEntry()
        cache[key] = value
        cache[key].assert().isEqualTo(value)
        cache.getTtlAt(key).assert().isNotNull()
    }

    @Test
    fun setWithTtlAt() {
        val (key, value) = createCacheEntry()
        val ttlAt = TtlAt.at(10)
        cache[key, ttlAt] = value
        cache[key].assert().isEqualTo(value)
        cache.getTtlAt(key).assert().isEqualTo(ttlAt)
    }

    @Test
    fun setExpiredEvictsExistingValue() {
        val (key, value) = createCacheEntry()
        cache[key] = value
        cache[key, TtlAt.at(-5)] = value
        cache[key].assert().isNull()
        cache.getTtlAt(key).assert().isNull()
    }

    @Test
    fun setMissing() {
        val (key, _) = createCacheEntry()
        cache.setCache(key, CacheValue.missing(TtlAt.at(10)))
        cache[key].assert().isNull()
        cache.getTtlAt(key).assert().isNull()
        requireNotNull(cache.getCache(key)).isMissing.assert().isTrue()
    }

    @Test
    fun evict() {
        val (key, value) = createCacheEntry()
        cache[key] = value
        cache.evict(key)
        cache[key].assert().isNull()
    }
}
