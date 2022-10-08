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

package me.ahoo.cache.example.config;

import me.ahoo.cache.CacheSource;
import me.ahoo.cache.CoherentCache;
import me.ahoo.cache.client.ClientSideCache;
import me.ahoo.cache.client.GuavaClientSideCache;
import me.ahoo.cache.client.MapClientSideCache;
import me.ahoo.cache.converter.ExpKeyConverter;
import me.ahoo.cache.converter.ToStringKeyConverter;
import me.ahoo.cache.distributed.DistributedCache;
import me.ahoo.cache.distributed.mock.MockDistributedCache;
import me.ahoo.cache.consistency.GuavaInvalidateEventBus;
import me.ahoo.cache.consistency.InvalidateEventBus;
import me.ahoo.cache.example.model.User;
import me.ahoo.cache.source.NoOpCacheSource;
import me.ahoo.cache.spring.redis.RedisCoherentCacheBuilder;
import me.ahoo.cache.spring.redis.RedisDistributedCache;
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor;
import me.ahoo.cosid.IdGenerator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * AppConfig.
 *
 * @author ahoo wang
 */
@Configuration
public class AppConfig {
    
    @Bean("userCache")
    public CoherentCache<Long, User> userCache(StringRedisTemplate redisTemplate,
                                               RedisMessageListenerContainer redisMessageListenerContainer,
                                               ObjectMapper objectMapper,
                                               IdGenerator idGenerator) {
        String clientId = idGenerator.generateAsString();
        ObjectToJsonCodecExecutor<User> codecExecutor = new ObjectToJsonCodecExecutor<>(User.class, redisTemplate, objectMapper);
        DistributedCache<User> distributedCaching = new RedisDistributedCache<>(clientId, redisTemplate, codecExecutor);
        return new RedisCoherentCacheBuilder<Long, User>()
            .keyConverter(new ExpKeyConverter<>(User.CACHE_KEY_PREFIX, "#{#root}"))
            .cacheSource(new NoOpCacheSource<>())
            .clientSideCaching(new GuavaClientSideCache<>())
            .distributedCaching(distributedCaching)
            .listenerContainer(redisMessageListenerContainer)
            .build();
    }
    
    @Bean("mockCache")
    public CoherentCache<String, String> mockCache(IdGenerator idGenerator) {
        ClientSideCache<String> clientCaching = new MapClientSideCache<>();
        InvalidateEventBus invalidateEventBus = new GuavaInvalidateEventBus(idGenerator.generateAsString());
        MockDistributedCache<String> distributedCaching = new MockDistributedCache<>(invalidateEventBus);
        CacheSource<String, String> cacheSource = new NoOpCacheSource<>();
        return CoherentCache.<String, String>builder()
            .keyConverter(new ToStringKeyConverter<>(""))
            .cacheSource(cacheSource)
            .clientSideCaching(clientCaching)
            .distributedCaching(distributedCaching)
            .invalidateEventBus(invalidateEventBus)
            .build();
    }
}
