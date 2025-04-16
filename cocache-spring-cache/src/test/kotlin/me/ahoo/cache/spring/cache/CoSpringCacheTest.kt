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

package me.ahoo.cache.spring.cache

import me.ahoo.cache.api.Cache
import me.ahoo.cache.client.MapClientSideCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class CoSpringCacheTest {
    @Suppress("UNCHECKED_CAST")
    private val coSpringCache = CoSpringCache("test", MapClientSideCache<Any?>() as Cache<Any, Any?>)

    @Test
    fun getName() {
        assertEquals("test", coSpringCache.cacheName)
    }

    @Test
    fun getNativeCache() {
        assertEquals(coSpringCache.delegate, coSpringCache.nativeCache)
    }

    @Test
    fun get() {
        assertEquals(null, coSpringCache.get("test"))
    }

    @Test
    fun testGet() {
        assertEquals(null, coSpringCache.get("test", String::class.java))
    }

    @Test
    fun testGet1() {
        assertEquals(
            "test",
            coSpringCache.get("test", {
                "test"
            })
        )
    }

    @Test
    fun put() {
        coSpringCache.put("putTest", "test")
        assertEquals("test", coSpringCache.get("putTest")!!.get())
    }

    @Test
    fun evict() {
        coSpringCache.put("evictTest", "test")
        coSpringCache.evict("evictTest")
        assertEquals(null, coSpringCache.get("evictTest"))
    }

    @Test
    fun clear() {
        coSpringCache.put("clearTest", "test")
        coSpringCache.clear()
        assertEquals(null, coSpringCache.get("clearTest"))
    }

    @Test
    fun retrieve() {
        coSpringCache.put("retrieveTest", "test")
        assertEquals("test", coSpringCache.retrieve("retrieveTest")!!.get())
    }

    @Test
    fun testRetrieve() {
        coSpringCache.put("testRetrieve", "test")
        assertEquals(
            "test",
            coSpringCache.retrieve("testRetrieve", {
                CompletableFuture.completedFuture("test")
            })!!.get()
        )
    }

    @Test
    fun getCacheName() {
        assertEquals("test", coSpringCache.cacheName)
    }

    @Test
    fun getDelegate() {
        assertNotNull(coSpringCache.delegate)
    }
}
