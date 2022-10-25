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

import me.ahoo.cache.CacheValue
import me.ahoo.cache.TtlAt
import me.ahoo.cache.client.ClientSideCache
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

abstract class ClientSideCacheSpec<V> : CacheSpec<String, V>() {

    abstract override fun createCache(): ClientSideCache<V>

    @Test
    fun setMissing() {
        val (key, value) = createCacheEntry()
        cache[key] = CacheValue.missingGuardValue()
        Assertions.assertNull(cache[key])
        Assertions.assertEquals(TtlAt.FOREVER, cache.getExpireAt(key))
    }

    @Test
    fun clear() {
        val (key, value) = createCacheEntry()
        cache[key] = value
        (cache as ClientSideCache<V>).clear()
        assertThat(cache[key], nullValue())
    }
}
