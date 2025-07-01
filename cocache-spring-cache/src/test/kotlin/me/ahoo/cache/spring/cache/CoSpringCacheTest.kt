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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class CoSpringCacheTest {
    @Suppress("UNCHECKED_CAST")
    private val coSpringCache = CoSpringCache("test", MapClientSideCache<Any?>() as Cache<Any, Any?>)

    @Test
    fun getName() {
        coSpringCache.cacheName.assert().isEqualTo("test")
    }

    @Test
    fun getNativeCache() {
        coSpringCache.nativeCache.assert().isEqualTo(coSpringCache.delegate)
    }

    @Test
    fun get() {
        coSpringCache.get("test").assert().isNull()
    }

    @Test
    fun testGet() {
        coSpringCache.get("test", String::class.java).assert().isNull()
    }

    @Test
    fun testGet1() {
        coSpringCache.get("test", {
            "test"
        }).assert().isEqualTo("test")
    }

    @Test
    fun put() {
        coSpringCache.put("putTest", "test")
        coSpringCache.get("putTest")!!.get().assert().isEqualTo("test")
    }

    @Test
    fun evict() {
        coSpringCache.put("evictTest", "test")
        coSpringCache.evict("evictTest")
        coSpringCache.get("evictTest").assert().isNull()
    }

    @Test
    fun clear() {
        coSpringCache.put("clearTest", "test")
        coSpringCache.clear()
        coSpringCache.get("clearTest").assert().isNull()
    }

    @Test
    fun retrieve() {
        coSpringCache.put("retrieveTest", "test")
        coSpringCache.retrieve("retrieveTest")!!.get().assert().isEqualTo("test")
    }

    @Test
    fun testRetrieve() {
        coSpringCache.put("testRetrieve", "test")
        coSpringCache.retrieve("testRetrieve", {
            CompletableFuture.completedFuture("test")
        }).get().assert().isEqualTo("test")
    }

    @Test
    fun getCacheName() {
        coSpringCache.cacheName.assert().isEqualTo("test")
    }

    @Test
    fun getDelegate() {
        coSpringCache.delegate.assert().isNotNull
    }
}
