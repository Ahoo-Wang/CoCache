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

import me.ahoo.cache.Cache
import me.ahoo.cache.util.SystemSecondClock
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

abstract class CacheSpec<K, V> {
    protected abstract fun createCache(): Cache<K, V>
    protected abstract fun createCacheEntry(): Pair<K, V>
    protected lateinit var cache: Cache<K, V>

    @BeforeEach
    open fun setup() {
        cache = createCache()
    }

    @Test
    fun get() {
        val (key, value) = createCacheEntry()
        assertThat(cache[key], nullValue())
    }

    @Test
    fun getWhenExpired() {
        val (key, value) = createCacheEntry()
        val ttlAt = SystemSecondClock.currentTime() - 5
        cache[key, ttlAt] = value
        assertThat(cache[key], nullValue())
        assertThat(cache.getExpireAt(key), nullValue())
    }

    @Test
    fun set() {
        val (key, value) = createCacheEntry()
        assertThat(cache[key], nullValue())
        cache[key] = value
        assertThat(cache[key], equalTo(value))
    }

    @Test
    fun evict() {
        val (key, value) = createCacheEntry()
        cache[key] = value
        assertThat(cache[key], equalTo(value))
        cache.evict(key)
        assertThat(cache[key], nullValue())
    }
}
