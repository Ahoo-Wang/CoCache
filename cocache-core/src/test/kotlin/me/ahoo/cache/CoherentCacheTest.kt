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
import me.ahoo.cache.consistency.InvalidateEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.source.NoOpCacheSource.noOp
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
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
    var compositeCache = builder<String, String>().keyConverter(ToStringKeyConverter(""))
        .cacheSource(cacheSource)
        .clientSideCaching(clientCaching)
        .distributedCaching(distributedCaching)
        .invalidateEventBus(invalidateEventBus)
        .build()

    @Disabled
    @Test
    fun get() {
        val CACHE_KEY = "get"
        val CACHE_VALUE = forever(CACHE_KEY)
        Assertions.assertNull(compositeCache[CACHE_KEY])
        clientCaching.setCache(CACHE_KEY, CACHE_VALUE)
        Assertions.assertEquals(CACHE_VALUE, clientCaching.getCache(CACHE_KEY))
        compositeCache.setCache(CACHE_KEY, CACHE_VALUE)
        Assertions.assertNull(clientCaching[CACHE_KEY])
    }

    @Test
    fun set() {
        val CACHE_KEY = "set"
        Assertions.assertNull(compositeCache[CACHE_KEY])
        val CACHE_VALUE = forever(CACHE_KEY)
        compositeCache.setCache(CACHE_KEY, CACHE_VALUE)
        Assertions.assertEquals(CACHE_VALUE, clientCaching.getCache(CACHE_KEY))
    }

    @Test
    fun evict() {
        val CACHE_KEY = "evict"
        val CACHE_VALUE = forever(CACHE_KEY)
        clientCaching.setCache(CACHE_KEY, CACHE_VALUE)
        Assertions.assertEquals(CACHE_VALUE, clientCaching.getCache(CACHE_KEY))
        compositeCache.evict(CACHE_KEY)
        Assertions.assertNull(clientCaching[CACHE_KEY])
    }

    fun testGet() {
//        Mono.just("fromClient")
//            .switchIfEmpty(Mono.just("fromDistributed").materialize().doOnNext {
//                if (it.isOnNext) {
//                    clientCaching.setCache("key", CacheValue.forever(it.get()))
//                }
//            }.then())
    }

}

