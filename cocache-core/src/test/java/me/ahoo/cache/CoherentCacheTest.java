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

package me.ahoo.cache;

import me.ahoo.cache.client.ClientSideCache;
import me.ahoo.cache.client.MapClientSideCache;
import me.ahoo.cache.converter.ToStringKeyConverter;
import me.ahoo.cache.distributed.mock.MockDistributedCache;
import me.ahoo.cache.eventbus.GuavaInvalidateEventBus;
import me.ahoo.cache.eventbus.InvalidateEventBus;
import me.ahoo.cache.source.NoOpCacheSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * Composite Cache Test .
 *
 * @author ahoo wang
 */
class CoherentCacheTest {
    ClientSideCache<String> clientCaching = new MapClientSideCache<>();
    InvalidateEventBus invalidateEventBus = new GuavaInvalidateEventBus(UUID.randomUUID().toString());
    MockDistributedCache<String> distributedCaching = new MockDistributedCache<>(invalidateEventBus);
    CacheSource<String, String> cacheSource = new NoOpCacheSource<>();
    
    CoherentCache<String, String> compositeCache = CoherentCache.<String, String>builder().
        keyConverter(new ToStringKeyConverter<>(""))
        .cacheSource(cacheSource)
        .clientSideCaching(clientCaching)
        .distributedCaching(distributedCaching)
        .invalidateEventBus(invalidateEventBus)
        .build();
    
    @Disabled
    @Test
    void get() {
        
        final String CACHE_KEY = "get";
        final CacheValue<String> CACHE_VALUE = CacheValue.forever(CACHE_KEY);
        Assertions.assertNull(compositeCache.get(CACHE_KEY));
        
        clientCaching.setCache(CACHE_KEY, CACHE_VALUE);
        Assertions.assertEquals(CACHE_VALUE, clientCaching.getCache(CACHE_KEY));
        
        compositeCache.setCache(CACHE_KEY, CACHE_VALUE);
        Assertions.assertNull(clientCaching.get(CACHE_KEY));
    }
    
    @Test
    void set() {
        
        final String CACHE_KEY = "set";
        Assertions.assertNull(compositeCache.get(CACHE_KEY));
        final CacheValue<String> CACHE_VALUE = CacheValue.forever(CACHE_KEY);
        compositeCache.setCache(CACHE_KEY, CACHE_VALUE);
        Assertions.assertEquals(CACHE_VALUE, clientCaching.getCache(CACHE_KEY));
    }
    
    @Test
    void evict() {
        final String CACHE_KEY = "evict";
        final CacheValue<String> CACHE_VALUE = CacheValue.forever(CACHE_KEY);
        clientCaching.setCache(CACHE_KEY, CACHE_VALUE);
        Assertions.assertEquals(CACHE_VALUE, clientCaching.getCache(CACHE_KEY));
        compositeCache.evict(CACHE_KEY);
        Assertions.assertNull(clientCaching.get(CACHE_KEY));
    }
}
