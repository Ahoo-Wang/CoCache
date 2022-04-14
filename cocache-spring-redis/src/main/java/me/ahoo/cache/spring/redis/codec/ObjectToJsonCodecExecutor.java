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

package me.ahoo.cache.spring.redis.codec;

import me.ahoo.cache.CacheValue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Nonnull;

/**
 * ObjectToJsonCodecExecutor .
 *
 * @author ahoo wang
 */
public class ObjectToJsonCodecExecutor<V> implements CodecExecutor<V> {
    private final Class<V> valueType;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    
    public ObjectToJsonCodecExecutor(Class<V> valueType, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.valueType = valueType;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }
    
    @Override
    public CacheValue<V> executeAndDecode(@Nonnull String key, long ttlAt) {
        String value = redisTemplate.opsForValue().get(key);
        
        if (null == value) {
            return null;
        }
        
        if (CacheValue.isMissingGuard(value)) {
            return CacheValue.missingGuard();
        }
        
        try {
            V typedValue = objectMapper.readValue(value, valueType);
            return CacheValue.of(typedValue, ttlAt);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }

    }
    
    @Override
    public void executeAndEncode(@Nonnull String key, @Nonnull CacheValue<V> cacheValue) {
        if (cacheValue.isMissingGuard()) {
            redisTemplate.opsForValue().set(key, CacheValue.MISSING_GUARD_STRING_VALUE);
            return;
        }
        try {
            String jsonValue = objectMapper.writeValueAsString(cacheValue.getValue());
            redisTemplate.opsForValue().set(key, jsonValue);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
