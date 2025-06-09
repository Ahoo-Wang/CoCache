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

import me.ahoo.cache.CacheFactory
import me.ahoo.cache.api.Cache
import me.ahoo.cache.client.MapClientSideCache
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import kotlin.reflect.KType

class CoCacheManagerTest {
    object MockCacheFactory : CacheFactory {
        override val caches: Map<String, Cache<*, *>>
            get() = emptyMap()

        @Suppress("UNCHECKED_CAST")
        override fun <CACHE : Cache<*, *>> getCache(
            cacheName: String,
            cacheType: Class<*>
        ): CACHE? {
            return MapClientSideCache<Any>() as CACHE
        }

        override fun <CACHE : Cache<*, *>> getCache(
            keyType: KType,
            valueType: KType
        ): CACHE? {
            return null
        }
    }

    private val coCacheManager = CoCacheManager(MockCacheFactory)

    @Test
    fun loadCaches() {
        assertThat(coCacheManager.cacheNames, Matchers.emptyIterable<String>())
    }

    @Test
    fun getMissingCache() {
        val cache = coCacheManager.getCache("test")
        assertThat(cache!!.name, equalTo("test"))
    }
}
