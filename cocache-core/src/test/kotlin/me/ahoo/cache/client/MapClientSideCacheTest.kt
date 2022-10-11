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
        val key = "get"
        Assertions.assertNull(clientSideCaching[key])
    }

    @Test
    fun set() {
        val key = "set"
        val value = "set"
        clientSideCaching[key] = value
        Assertions.assertEquals(value, clientSideCaching[key])
    }

    @Test
    fun setMissing() {
        val key = "setMissing"
        clientSideCaching[key] = missingGuardValue()
        Assertions.assertNull(clientSideCaching[key])
        Assertions.assertEquals(TtlAt.FOREVER, clientSideCaching.getExpireAt(key))
    }

    @Test
    fun evict() {
        val key = "evict"
        val value = "evict"
        clientSideCaching[key] = value
        clientSideCaching.evict(key)
        Assertions.assertNull(clientSideCaching[key])
    }
}
