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

package me.ahoo.cache.consistency

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.converter.KeyConverter
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.filter.KeyFilter
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.cache.test.DefaultCoherentCacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.*

internal class DefaultCoherentCacheTest : DefaultCoherentCacheSpec<String, String>() {

    override fun createKeyConverter(): KeyConverter<String> = ToStringKeyConverter("test:")

    override fun createClientSideCache(): ClientSideCache<String> = MapClientSideCache()

    override fun createDistributedCache(): DistributedCache<String> = InMemoryDistributedCache()

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = LocalCacheEvictedEventBus()

    override fun createCacheName(): String = "DefaultCoherentCacheTest"

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }

    @Test
    fun keyFilterShortCircuitsToMissing() {
        val cache = DefaultCoherentCache(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                distributedCache = distributedCache,
                clientSideCache = clientSideCache,
                keyFilter = KeyFilter { true },
                cacheSource = { CacheValue.forever("unexpected") }
            ),
            NoOpCacheEvictedEventBus
        )
        requireNotNull(cache.getCache("key")).isMissing.assert().isTrue()
        distributedCache.getCache(keyConverter.toStringKey("key")).assert().isNull()
    }

    @Test
    fun closeClosesDistributedCacheOnce() {
        val mockDistributedCache = mockk<DistributedCache<String>>(relaxUnitFun = true)
        val cache = DefaultCoherentCache(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                distributedCache = mockDistributedCache,
            ),
            NoOpCacheEvictedEventBus
        )
        cache.close()
        cache.close()
        verify(exactly = 1) { mockDistributedCache.close() }
    }

    @Test
    fun closeSwallowsFailures() {
        val mockDistributedCache = mockk<DistributedCache<String>> {
            every { close() } throws IllegalStateException("close boom")
        }
        val eventBus = mockk<CacheEvictedEventBus> {
            every { unregister(any()) } throws IllegalStateException("unregister boom")
        }
        val cache = DefaultCoherentCache(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = keyConverter,
                distributedCache = mockDistributedCache,
            ),
            eventBus
        )
        cache.close()
        verify(exactly = 1) { mockDistributedCache.close() }
    }

    @Test
    fun exposesConfiguration() {
        coherentCache.cacheName.assert().isEqualTo(cacheName)
        coherentCache.clientId.assert().isEqualTo(clientId)
        coherentCache.configuration.clientSideCache.assert().isSameAs(clientSideCache)
        coherentCache.toString().assert().contains(cacheName)
    }
}
