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
import me.ahoo.cache.util.CacheSecondClock;

import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * MapToHashCodecExecutor .
 *
 * @author ahoo wang
 */
public class MapToHashCodecExecutor implements CodecExecutor<Map<String, String>> {
    
    private final StringRedisTemplate redisTemplate;
    
    public MapToHashCodecExecutor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    @Override
    public CacheValue<Map<String, String>> executeAndDecode(@Nonnull String key, @Nonnull long ttlAt) {
        Map<String, String> value = redisTemplate.<String, String>opsForHash().entries(key);
        
        if (CacheValue.isMissingGuard(value)) {
            return CacheValue.missingGuard();
        }
        return CacheValue.of(value, ttlAt);
    }
    
    @Override
    public void executeAndEncode(@Nonnull String key, @Nonnull CacheValue<Map<String, String>> cacheValue) {
        if (cacheValue.isMissingGuard()) {
            redisTemplate.opsForHash().put(key, CacheValue.MISSING_GUARD_STRING_VALUE, String.valueOf(CacheSecondClock.INSTANCE.currentTime()));
            return;
        }
        redisTemplate.delete(key);
        redisTemplate.opsForHash().putAll(key, cacheValue.getValue());
    }
}
