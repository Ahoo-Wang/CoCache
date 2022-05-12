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
import me.ahoo.cache.TtlAt;
import me.ahoo.cosid.test.MockIdGenerator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * String To String Codec Executor Test .
 *
 * @author ahoo wang
 */
class StringToStringCodecExecutorTest {
    StringRedisTemplate stringRedisTemplate;
    StringToStringCodecExecutor codecExecutor;
    LettuceConnectionFactory lettuceConnectionFactory;
    
    @BeforeEach
    private void setup() {
        RedisStandaloneConfiguration redisStandaloneConfiguration = new RedisStandaloneConfiguration();
        lettuceConnectionFactory = new LettuceConnectionFactory(redisStandaloneConfiguration);
        lettuceConnectionFactory.afterPropertiesSet();
        stringRedisTemplate = new StringRedisTemplate(lettuceConnectionFactory);
        codecExecutor = new StringToStringCodecExecutor(stringRedisTemplate);
    }
    
    @AfterEach
    void destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy();
        }
    }
    
    @Test
    void executeAndEncode() {
        final String CACHE_KEY = "executeAndDecode:" + MockIdGenerator.INSTANCE.generateAsString();
        final CacheValue<String> CACHE_VALUE = CacheValue.forever(CACHE_KEY);
        codecExecutor.executeAndEncode(CACHE_KEY, CACHE_VALUE);
        
        CacheValue<String> actual = codecExecutor.executeAndDecode(CACHE_KEY, TtlAt.FOREVER);
        Assertions.assertEquals(CACHE_VALUE, actual);
    }
    
    @Test
    void executeAndEncodeMissing() {
        final String CACHE_KEY = "executeAndDecodeWhenMissing:" + MockIdGenerator.INSTANCE.generateAsString();
        final CacheValue<String> CACHE_VALUE = CacheValue.missingGuard();
        codecExecutor.executeAndEncode(CACHE_KEY, CACHE_VALUE);
        
        CacheValue<String> actual = codecExecutor.executeAndDecode(CACHE_KEY, TtlAt.FOREVER);
        Assertions.assertEquals(CACHE_VALUE, actual);
    }
}
