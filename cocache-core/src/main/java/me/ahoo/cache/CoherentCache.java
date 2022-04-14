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
import me.ahoo.cache.converter.KeyConverter;
import me.ahoo.cache.distributed.DistributedCache;
import me.ahoo.cache.eventbus.InvalidateEventBus;
import me.ahoo.cache.filter.NoOpKeyFilter;
import me.ahoo.cache.source.NoOpCacheSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coherent cache .
 *
 * @author ahoo wang
 */
public class CoherentCache<K, V> implements Cache<K, V> {
    private final KeyConverter<K> keyConverter;
    private final CacheSource<K, V> cacheSource;
    private final ClientSideCache<V> clientSideCaching;
    private final DistributedCache<V> distributedCaching;
    /*
    TODO Replace with real KeyFilter.
     */
    private final KeyFilter keyFilter = NoOpKeyFilter.INSTANCE;
    
    protected CoherentCache(KeyConverter<K> keyConverter, CacheSource<K, V> cacheSource, ClientSideCache<V> clientCaching, DistributedCache<V> distributedCaching) {
        this.keyConverter = keyConverter;
        this.cacheSource = cacheSource;
        this.clientSideCaching = clientCaching;
        this.distributedCaching = distributedCaching;
    }
    
    public static <K, V> CoCachingBuilder<K, V> builder() {
        return new CoCachingBuilder<K, V>();
    }
    
    private CacheValue<V> getL2Cache(String cacheKey) {
        //region L2
        CacheValue<V> cacheValue = clientSideCaching.getCache(cacheKey);
        if (null != cacheValue) {
            return cacheValue;
        }
        //endregion
        if (keyFilter.notExist(cacheKey)) {
            return CacheValue.missingGuard();
        }
        //region L1
        cacheValue = distributedCaching.getCache(cacheKey);
        if (null != cacheValue) {
            clientSideCaching.setCache(cacheKey, cacheValue);
            return cacheValue;
        }
        //endregion
        
        return null;
    }
    
    @Nullable
    @Override
    public CacheValue<V> getCache(K key) {
        String cacheKey = keyConverter.asString(key);
        
        CacheValue<V> cacheValue = getL2Cache(cacheKey);
        if (null != cacheValue) {
            if (cacheValue.isMissingGuard()) {
                return null;
            }
            return cacheValue;
        }
        
        /*
         *** Fix 缓存击穿 ***
         * 0. Db 存在该记录
         * 1. 并发获取缓存时导致的多次回源问题
         **** 应用级锁控制并发回源 ***
         */
        synchronized (this) {
            cacheValue = getL2Cache(cacheKey);
            if (null != cacheValue) {
                if (cacheValue.isMissingGuard()) {
                    return null;
                }
                return cacheValue;
            }
            //region L0:Cache Source
            /*
             * This is a heavy-duty operation.
             */
            CacheValue<V> sourceCache = cacheSource.load(key);
            if (null != sourceCache) {
                setCache(cacheKey, sourceCache);
                return sourceCache;
            }
            //endregion
            /*
             *** Fix 缓存穿透 ***
             * 0. Db 不存在该记录
             * 1. 穿透到 Db 回源
             **** 缓存空值 ***
             */
            setCache(cacheKey, CacheValue.missingGuard());
            return null;
        }
    }
    
    private void setCache(String cacheKey, CacheValue<V> cacheValue) {
        clientSideCaching.setCache(cacheKey, cacheValue);
        distributedCaching.setCache(cacheKey, cacheValue);
    }
    
    @Override
    public void setCache(@Nonnull K key, @Nonnull CacheValue<V> value) {
        String cacheKey = keyConverter.asString(key);
        setCache(cacheKey, value);
    }
    
    @Override
    public void evict(@Nonnull K key) {
        String cacheKey = keyConverter.asString(key);
        clientSideCaching.evict(cacheKey);
        distributedCaching.evict(cacheKey);
    }
    
    public static class CoCachingBuilder<K, V> {
        
        private KeyConverter<K> keyConverter;
        private CacheSource<K, V> cacheSource = new NoOpCacheSource<>();
        private ClientSideCache<V> clientSideCaching = new MapClientSideCache<>();
        private DistributedCache<V> distributedCaching;
        private InvalidateEventBus invalidateEventBus;
        
        CoCachingBuilder() {
        }
        
        public CoCachingBuilder<K, V> keyConverter(KeyConverter<K> keyConverter) {
            this.keyConverter = keyConverter;
            return this;
        }
        
        public CoCachingBuilder<K, V> cacheSource(CacheSource<K, V> cacheSource) {
            this.cacheSource = cacheSource;
            return this;
        }
        
        public CoCachingBuilder<K, V> clientSideCaching(ClientSideCache<V> clientSideCaching) {
            this.clientSideCaching = clientSideCaching;
            return this;
        }
        
        public CoCachingBuilder<K, V> distributedCaching(DistributedCache<V> distributedCaching) {
            this.distributedCaching = distributedCaching;
            return this;
        }
        
        public CoCachingBuilder<K, V> invalidateEventBus(InvalidateEventBus invalidateEventBus) {
            this.invalidateEventBus = invalidateEventBus;
            return this;
        }
        
        public CoherentCache<K, V> build() {
            this.invalidateEventBus.register(clientSideCaching);
            return new CoherentCache<>(keyConverter, cacheSource, clientSideCaching, distributedCaching);
        }
    }
}
