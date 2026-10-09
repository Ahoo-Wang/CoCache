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

package me.ahoo.cache.spring.boot.starter

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.CacheFactory
import me.ahoo.cache.api.Cache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import org.junit.jupiter.api.BeforeEach

open class AbstractCoCacheEndpointTest {
    companion object {
        const val CACHE_NAME = "cacheName"
        const val NOT_FOUND = "NotFound"
    }

    lateinit var cacheFactory: CacheFactory

    @BeforeEach
    open fun setup() {
        val clientSideCaching = MapClientSideCache<String>()
        val distributedCaching = InMemoryDistributedCache<String>()
        val mockCache = DefaultCoherentCache<String, String>(
            CoherentCacheConfiguration(
                CACHE_NAME,
                "clientId",
                ToStringKeyConverter("keyPrefix"),
                distributedCaching,
                clientSideCaching
            ),
            NoOpCacheEvictedEventBus
        )

        cacheFactory = mockk {
            every {
                caches
            } returns mapOf(
                CACHE_NAME to mockCache
            )
            every {
                getCache<Cache<String, String>>(CACHE_NAME)
            } returns mockCache
            every {
                getCache<Cache<String, String>>(CACHE_NAME)
            } returns mockCache
            every {
                getCache<CoherentCache<String, String>>(CACHE_NAME, CoherentCache::class.java)
            } returns mockCache
            every {
                getCache<CoherentCache<String, String>>(NOT_FOUND, any())
            } returns null
        }
    }
}
