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

import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.test.DefaultCoherentCacheSpec
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Coherent Cache Test .
 *
 * @author ahoo wang
 */
internal class DefaultCoherentCacheTest : DefaultCoherentCacheSpec<String, String>() {

    override fun createKeyConverter(): KeyConverter<String> = ToStringKeyConverter("")

    override fun createClientSideCache(): ClientSideCache<String> = MapClientSideCache()

    override fun createDistributedCache(): DistributedCache<String> = MockDistributedCache()

    override fun createCacheEvictedEventBus(): CacheEvictedEventBus = GuavaCacheEvictedEventBus()
    override fun createCacheName(): String {
        return "CoherentCacheTest"
    }

    override fun createCacheEntry(): Pair<String, String> {
        return UUID.randomUUID().toString() to UUID.randomUUID().toString()
    }

    @Test
    fun closeClosesDistributedCache() {
        val distributedCache = mockk<DistributedCache<String>>(relaxUnitFun = true)
        val cache = DefaultCoherentCache<String, String>(
            config = CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = clientId,
                keyConverter = ToStringKeyConverter(""),
                clientSideCache = MapClientSideCache(),
                distributedCache = distributedCache,
                cacheSource = CacheSource.noOp()
            ),
            cacheEvictedEventBus = GuavaCacheEvictedEventBus()
        )
        cache.close()
        cache.close()
        verify(exactly = 1) { distributedCache.close() }
    }
}
