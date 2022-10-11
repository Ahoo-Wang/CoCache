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

import me.ahoo.cache.CacheValue;
import me.ahoo.cache.TtlAt;
import me.ahoo.cache.distributed.DistributedCache;
import me.ahoo.cache.spring.redis.codec.CodecExecutor;
import me.ahoo.cache.spring.redis.codec.InvalidateMessages;
import me.ahoo.cache.util.CacheSecondClock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;

/**
 * Redis Distributed Cache.
 *
 * @author ahoo wang
 */
@Slf4j
public class RedisDistributedCache<V> implements DistributedCache<V> {
    
    public static final Long FOREVER = -1L;
    public static final Long NOT_EXIST = -2L;
    private final String clientId;
    private final StringRedisTemplate redisTemplate;
    private final CodecExecutor<V> codecExecutor;
    
    public RedisDistributedCache(String clientId,
                                 StringRedisTemplate redisTemplate,
                                 CodecExecutor<V> codecExecutor) {
        this.clientId = clientId;
        this.redisTemplate = redisTemplate;
        this.codecExecutor = codecExecutor;
    }
    
    @Override
    public String getClientId() {
        return clientId;
    }
    
    public StringRedisTemplate getRedisTemplate() {
        return redisTemplate;
    }
    
    @Override
    public CacheValue<V> getCache(@Nonnull String key) {
        Long ttlAt = getExpireAt(key);
        if (null == ttlAt) {
            return null;
        }
        
        return codecExecutor.executeAndDecode(key, ttlAt);
    }
    
    @Nullable
    @Override
    public Long getExpireAt(String key) {
        Long ttl = redisTemplate.getExpire(key);
        if (NOT_EXIST.equals(ttl)) {
            return null;
        }
        if (FOREVER.equals(ttl)) {
            return TtlAt.FOREVER;
        }
        return CacheSecondClock.INSTANCE.currentTime() + ttl;
    }
    
    @Override
    public void setCache(@Nonnull String key, @Nonnull CacheValue<V> value) {
        codecExecutor.executeAndEncode(key, value);
        if (!value.isForever()) {
            redisTemplate.expireAt(key, Instant.ofEpochSecond(value.getTtlAt()));
        }
        publishInvalidateMessage(key);
    }
    
    
    @Override
    public void evict(@Nonnull String key) {
        redisTemplate.delete(key);
        publishInvalidateMessage(key);
    }
    
    protected void publishInvalidateMessage(String key) {
        redisTemplate.convertAndSend(key, InvalidateMessages.ofClientId(getClientId()));
    }
    
    @Override
    public void close() {
    }
}
