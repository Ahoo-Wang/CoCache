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

package me.ahoo.cache.distributed.mock;

import me.ahoo.cache.CacheValue;
import me.ahoo.cache.distributed.DistributedCache;
import me.ahoo.cache.eventbus.InvalidateEvent;
import me.ahoo.cache.eventbus.InvalidateEventBus;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock Distributed Caching .
 *
 * @author ahoo wang
 */
public class MockDistributedCache<V> implements DistributedCache<V> {
    private final ConcurrentHashMap<String, CacheValue<V>> cacheMap;
    private final InvalidateEventBus invalidateEventBus;
    
    public MockDistributedCache(InvalidateEventBus invalidateEventBus) {
        cacheMap = new ConcurrentHashMap<>();
        this.invalidateEventBus = invalidateEventBus;
    }
    
    @Override
    public V get(@Nonnull String key) {
        CacheValue<V> cacheValue = getCache(key);
        if (null == cacheValue) {
            return null;
        }
        if (cacheValue.isExpired()) {
            evict(key);
            return null;
        }
        return cacheValue.getValue();
    }
    
    @Nullable
    @Override
    public CacheValue<V> getCache(String key) {
        return cacheMap.get(key);
    }
    
    @Override
    public void setCache(@Nonnull String key, @Nonnull CacheValue<V> value) {
        cacheMap.put(key, value);
        invalidateEventBus.publish(InvalidateEvent.of(key, getClientId()));
    }
    
    @Override
    public void evict(@Nonnull String key) {
        cacheMap.remove(key);
        invalidateEventBus.publish(InvalidateEvent.of(key, getClientId()));
    }
    
    @Override
    public void close() throws Exception {
        cacheMap.clear();
    }
    
    @Override
    public String getClientId() {
        return invalidateEventBus.getClientId();
    }
    
}

