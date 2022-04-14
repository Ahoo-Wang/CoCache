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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheBuilderSpec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Guava Client Side Cache .
 *
 * @author ahoo wang
 */
public class GuavaClientSideCache<V> implements ClientSideCache<V> {
    private final com.google.common.cache.Cache<String, CacheValue<V>> guavaCache;
    
    public GuavaClientSideCache() {
        this(CacheBuilder.newBuilder().build());
    }
    
    public GuavaClientSideCache(String spec) {
        this(CacheBuilder.from(spec).build());
    }
    
    public GuavaClientSideCache(CacheBuilderSpec spec) {
        this(CacheBuilder.from(spec).build());
    }
    
    public GuavaClientSideCache(Cache<String, CacheValue<V>> guavaCache) {
        this.guavaCache = guavaCache;
    }
    
    @Nullable
    @Override
    public CacheValue<V> getCache(String key) {
        return guavaCache.getIfPresent(key);
    }
    
    @Override
    public void setCache(@Nonnull String key, @Nonnull CacheValue<V> value) {
        guavaCache.put(key, value);
    }
    
    @Override
    public void evict(@Nonnull String key) {
        guavaCache.invalidate(key);
    }
    
    @Override
    public void clear() {
        guavaCache.invalidateAll();
    }
}
