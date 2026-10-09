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
import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.Cache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CoCacheEndpointTest : AbstractCoCacheEndpointTest() {
    private lateinit var endpoint: CoCacheEndpoint

    @BeforeEach
    override fun setup() {
        super.setup()
        endpoint = CoCacheEndpoint(cacheFactory)
    }

    @Test
    fun total() {
        endpoint.total().assert().hasSize(1)
    }

    @Test
    fun totalSkipsNonCoherentCaches() {
        val factory = mockk<CacheFactory> {
            every { caches } returns mapOf(
                CACHE_NAME to cacheFactory.caches.getValue(CACHE_NAME),
                "plain" to mockk<Cache<String, String>>()
            )
        }
        CoCacheEndpoint(factory).total().map { it.name }.assert().containsExactly(CACHE_NAME)
    }

    @Test
    fun stat() {
        val report = requireNotNull(endpoint.stat(CACHE_NAME))
        report.name.assert().isEqualTo(CACHE_NAME)
        report.clientId.assert().isEqualTo("clientId")
        report.clientSize.assert().isZero()
        report.ttlPolicy.assert().isEqualTo(TtlPolicy())
        report.keyConverter.assert().contains("keyPrefix")
        report.distributedCache.assert().isEqualTo(InMemoryDistributedCache::class.java.name)
        report.clientSideCache.assert().isEqualTo(MapClientSideCache::class.java.name)
        report.cacheSource.assert().isNotBlank()
        report.keyFilter.assert().isNotBlank()
    }

    @Test
    fun statWhenNotFound() {
        endpoint.stat(NOT_FOUND).assert().isNull()
    }

    @Test
    fun evict() {
        val cache = cacheFactory.getCache<Cache<String, String>>(CACHE_NAME)!!
        val key = "evict-key"
        cache[key] = "value"
        endpoint.evict(CACHE_NAME, key)
        cache[key].assert().isNull()
    }

    @Test
    fun evictWhenNotFound() {
        endpoint.evict(NOT_FOUND, "key").assert().isNotNull()
    }

    @Test
    fun get() {
        val cache = cacheFactory.getCache<Cache<String, String>>(CACHE_NAME)!!
        val key = "get-key"
        cache[key] = "value"
        endpoint.get(CACHE_NAME, key)?.value.assert().isEqualTo("value")
    }

    @Test
    fun getWhenNotFound() {
        endpoint.get(NOT_FOUND, "key").assert().isNull()
    }
}
