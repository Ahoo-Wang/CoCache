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

package me.ahoo.cache.spring.redis;

import me.ahoo.cache.CacheSource;
import me.ahoo.cache.CoherentCache;
import me.ahoo.cache.client.ClientSideCache;
import me.ahoo.cache.converter.KeyConverter;
import me.ahoo.cache.distributed.DistributedCache;
import me.ahoo.cache.eventbus.GuavaInvalidateEventBus;

import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Coherent Cache Builder .
 *
 * @author ahoo wang
 */
public class RedisCoherentCacheBuilder<K, V> {
    private KeyConverter<K> keyConverter;
    private CacheSource<K, V> cacheSource;
    private ClientSideCache<V> clientSideCaching;
    private DistributedCache<V> distributedCaching;
    private RedisMessageListenerContainer listenerContainer;
    
    public RedisCoherentCacheBuilder() {
    }
    
    public RedisCoherentCacheBuilder<K, V> keyConverter(KeyConverter<K> keyConverter) {
        this.keyConverter = keyConverter;
        return this;
    }
    
    public RedisCoherentCacheBuilder<K, V> cacheSource(CacheSource<K, V> cacheSource) {
        this.cacheSource = cacheSource;
        return this;
    }
    
    public RedisCoherentCacheBuilder<K, V> clientSideCaching(ClientSideCache<V> clientSideCaching) {
        this.clientSideCaching = clientSideCaching;
        return this;
    }
    
    public RedisCoherentCacheBuilder<K, V> distributedCaching(DistributedCache<V> distributedCaching) {
        this.distributedCaching = distributedCaching;
        return this;
    }
    
    public RedisCoherentCacheBuilder<K, V> listenerContainer(RedisMessageListenerContainer listenerContainer) {
        this.listenerContainer = listenerContainer;
        return this;
    }
    
    public CoherentCache<K, V> build() {
        RedisInvalidateEventBus invalidateEventBus =
            new RedisInvalidateEventBus(keyConverter.getKeyPrefix(), new GuavaInvalidateEventBus(distributedCaching.getClientId()), listenerContainer);

        return CoherentCache.<K, V>builder()
            .keyConverter(keyConverter)
            .cacheSource(cacheSource)
            .clientSideCaching(clientSideCaching)
            .distributedCaching(distributedCaching)
            .invalidateEventBus(invalidateEventBus)
            .build();
    }
}
