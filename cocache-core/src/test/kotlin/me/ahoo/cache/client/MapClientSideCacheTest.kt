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

import me.ahoo.cache.CacheValue.Companion.missingGuardValue
import me.ahoo.cache.TtlAt
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * MapClientSideCachingTest .
 *
 * @author ahoo wang
 */
internal class MapClientSideCacheTest {
    var clientSideCaching = MapClientSideCache<String>()
    @Test
    fun get() {
        val CACHE_KEY = "get"
        Assertions.assertNull(clientSideCaching[CACHE_KEY])
    }

    @Test
    fun set() {
        val CACHE_KEY = "set"
        val CACHE_VALUE = "set"
        clientSideCaching[CACHE_KEY] = CACHE_VALUE
        Assertions.assertEquals(CACHE_VALUE, clientSideCaching[CACHE_KEY])
    }

    @Test
    fun setMissing() {
        val CACHE_KEY = "setMissing"
        clientSideCaching[CACHE_KEY] = missingGuardValue()
        Assertions.assertNull(clientSideCaching[CACHE_KEY])
        Assertions.assertEquals(TtlAt.FOREVER, clientSideCaching.getExpireAt(CACHE_KEY))
    }

    @Test
    fun evict() {
        val CACHE_KEY = "evict"
        val CACHE_VALUE = "evict"
        clientSideCaching[CACHE_KEY] = CACHE_VALUE
        clientSideCaching.evict(CACHE_KEY)
        Assertions.assertNull(clientSideCaching[CACHE_KEY])
    }
}
