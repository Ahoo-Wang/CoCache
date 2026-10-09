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

import me.ahoo.cache.api.CacheStore
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [CacheStore] 实现的兼容性规格。
 */
abstract class CacheStoreSpec<V> {
    protected abstract fun createCacheStore(): CacheStore<V>
    protected abstract fun createCacheEntry(): Pair<String, V>
    protected lateinit var cacheStore: CacheStore<V>

    @BeforeEach
    open fun setup() {
        cacheStore = createCacheStore()
    }

    @Test
    fun getAbsentKeyReturnsNull() {
        val (key, _) = createCacheEntry()
        cacheStore.getCache(key).assert().isNull()
    }

    @Test
    fun setForever() {
        val (key, value) = createCacheEntry()
        cacheStore.setCache(key, CacheValue.forever(value))
        val actual = requireNotNull(cacheStore.getCache(key))
        actual.value.assert().isEqualTo(value)
        actual.isForever.assert().isTrue()
        actual.isMissing.assert().isFalse()
    }

    /**
     * open：经存储 TTL 重建 ttlAt 的实现（如 Redis）可覆写为 ±1 秒容差断言。
     */
    @Test
    open fun setWithTtlAt() {
        val (key, value) = createCacheEntry()
        val cacheValue = CacheValue.of(value, TtlAt.at(10))
        cacheStore.setCache(key, cacheValue)
        val actual = requireNotNull(cacheStore.getCache(key))
        actual.value.assert().isEqualTo(value)
        actual.ttlAt.assert().isEqualTo(cacheValue.ttlAt)
    }

    @Test
    fun setExpiredEvictsExistingValue() {
        val (key, value) = createCacheEntry()
        cacheStore.setCache(key, CacheValue.forever(value))
        cacheStore.setCache(key, CacheValue.of(value, TtlAt.at(-5)))
        cacheStore.getCache(key).assert().isNull()
    }

    @Test
    fun setExpiresAtCurrentSecondEvictsExistingValue() {
        val (key, value) = createCacheEntry()
        cacheStore.setCache(key, CacheValue.forever(value))
        cacheStore.setCache(key, CacheValue.of(value, TtlAt.currentTime()))
        cacheStore.getCache(key).assert().isNull()
    }

    @Test
    fun setMissingForever() {
        val (key, _) = createCacheEntry()
        cacheStore.setCache(key, CacheValue.missing())
        val actual = requireNotNull(cacheStore.getCache(key))
        actual.isMissing.assert().isTrue()
        actual.isForever.assert().isTrue()
        actual.value.assert().isNull()
    }

    @Test
    open fun setMissingWithTtlAt() {
        val (key, _) = createCacheEntry()
        val missing = CacheValue.missing<V>(TtlAt.at(10))
        cacheStore.setCache(key, missing)
        val actual = requireNotNull(cacheStore.getCache(key))
        actual.isMissing.assert().isTrue()
        actual.ttlAt.assert().isEqualTo(missing.ttlAt)
    }

    @Test
    fun evict() {
        val (key, value) = createCacheEntry()
        cacheStore.setCache(key, CacheValue.forever(value))
        cacheStore.evict(key)
        cacheStore.getCache(key).assert().isNull()
    }

    @Test
    fun evictAbsentKey() {
        val (key, _) = createCacheEntry()
        cacheStore.evict(key)
        cacheStore.getCache(key).assert().isNull()
    }
}
