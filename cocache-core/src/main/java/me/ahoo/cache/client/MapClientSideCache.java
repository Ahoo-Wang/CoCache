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

package me.ahoo.cache.client;

import me.ahoo.cache.CacheValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Map Client Cache .
 *
 * @author ahoo wang
 */
public class MapClientSideCache<V> implements ClientSideCache<V> {
    private final Map<String, CacheValue<V>> cacheMap;
    
    public MapClientSideCache() {
        this(new ConcurrentHashMap<>());
    }
    
    public MapClientSideCache(Map<String, CacheValue<V>> cacheMap) {
        this.cacheMap = cacheMap;
    }
    
    @Nullable
    @Override
    public CacheValue<V> getCache(String key) {
        return cacheMap.get(key);
    }
    
    @Override
    public void setCache(@Nonnull String key, @Nonnull CacheValue<V> value) {
        if (CacheValue.missingGuardValue().equals(value.getValue())) {
            cacheMap.put(key, CacheValue.missingGuard());
            return;
        }
        cacheMap.put(key, value);
    }
    
    @Override
    public void evict(@Nonnull String key) {
        cacheMap.remove(key);
    }
    
    @Override
    public void clear() {
        cacheMap.clear();
    }
}
