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
package me.ahoo.cache

import me.ahoo.cache.CacheValue.Companion.forever
import me.ahoo.cache.CoherentCache.Companion.builder
import me.ahoo.cache.client.ClientSideCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.GuavaInvalidateEventBus
import me.ahoo.cache.consistency.InvalidateEvent
import me.ahoo.cache.consistency.InvalidateEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.source.NoOpCacheSource.noOp
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Composite Cache Test .
 *
 * @author ahoo wang
 */
internal class CoherentCacheTest {
    var clientCaching: ClientSideCache<String> = MapClientSideCache()
    var invalidateEventBus: InvalidateEventBus = GuavaInvalidateEventBus(UUID.randomUUID().toString())
    var distributedCaching = MockDistributedCache<String>(invalidateEventBus)
    var cacheSource = noOp<String, String>()
    var coherentCache = builder<String, String>().keyConverter(ToStringKeyConverter(""))
        .cacheSource(cacheSource)
        .clientSideCaching(clientCaching)
        .distributedCaching(distributedCaching)
        .invalidateEventBus(invalidateEventBus)
        .build()

    @Test
    fun get() {
        val key = "get"
        val value = forever(key)
        Assertions.assertNull(coherentCache[key])
        coherentCache.setCache(key, value)
        Assertions.assertEquals(value, clientCaching.getCache(key))
    }

    @Test
    fun set() {
        val key = "set"
        Assertions.assertNull(coherentCache[key])
        val value = forever(key)
        coherentCache.setCache(key, value)
        Assertions.assertEquals(value, coherentCache.getCache(key))
    }

    @Test
    fun evict() {
        val key = "evict"
        val value = forever(key)
        coherentCache.setCache(key, value)
        Assertions.assertEquals(value, coherentCache.getCache(key))
        coherentCache.evict(key)
        Assertions.assertNull(coherentCache[key])
    }

    @Test
    fun onInvalidate() {
        val key = "onInvalidate"
        val value = forever(key)
        coherentCache.setCache(key, value)
        val event = InvalidateEvent(key, "")
        coherentCache.onInvalidate(event)
        Assertions.assertNull(clientCaching[key])
        Assertions.assertNotNull(distributedCaching[key])
        Assertions.assertNotNull(coherentCache[key])
    }
}
