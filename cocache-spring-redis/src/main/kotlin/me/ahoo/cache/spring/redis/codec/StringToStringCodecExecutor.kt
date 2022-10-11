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

import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Nonnull;

/**
 * String To String Codec Executor .
 *
 * @author ahoo wang
 */
public class StringToStringCodecExecutor implements CodecExecutor<String> {
    private final StringRedisTemplate redisTemplate;
    
    public StringToStringCodecExecutor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    @Override
    public CacheValue<String> executeAndDecode(@Nonnull String key, long ttlAt) {
        String value = redisTemplate.opsForValue().get(key);
        
        if (null == value) {
            return null;
        }
        
        if (CacheValue.isMissingGuard(value)) {
            return CacheValue.missingGuard();
        }
        
        return CacheValue.of(value, ttlAt);
    }
    
    @Override
    public void executeAndEncode(@Nonnull String key, @Nonnull CacheValue<String> cacheValue) {
        if (cacheValue.isMissingGuard()) {
            redisTemplate.opsForValue().set(key, CacheValue.MISSING_GUARD_STRING_VALUE);
            return;
        }
        redisTemplate.opsForValue().set(key, cacheValue.getValue());
    }
    
    
}
