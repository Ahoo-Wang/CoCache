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
package me.ahoo.cache.spring.redis.codec

import me.ahoo.cache.CacheValue
import me.ahoo.cache.CacheValue.Companion.forever
import me.ahoo.cache.CacheValue.Companion.missingGuard
import me.ahoo.cache.TtlAt
import me.ahoo.cosid.test.MockIdGenerator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * String To String Codec Executor Test .
 *
 * @author ahoo wang
 */
internal class StringToStringCodecExecutorTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var codecExecutor: StringToStringCodecExecutor
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory

    @BeforeEach
    private fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        codecExecutor = StringToStringCodecExecutor(stringRedisTemplate)
    }

    @AfterEach
    fun destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy()
        }
    }

    @Test
    fun executeAndEncode() {
        val key = "executeAndDecode:" + MockIdGenerator.INSTANCE.generateAsString()
        val value = forever(key)
        codecExecutor.executeAndEncode(key, value)
        val actual = codecExecutor.executeAndDecode(key, TtlAt.FOREVER)
        Assertions.assertEquals(value, actual)
    }

    @Test
    fun executeAndEncodeMissing() {
        val key = "executeAndDecodeWhenMissing:" + MockIdGenerator.INSTANCE.generateAsString()
        val value = missingGuard<CacheValue<String>>()
        codecExecutor.executeAndEncode(key, value)
        val actual = codecExecutor.executeAndDecode(key, TtlAt.FOREVER)
        Assertions.assertEquals(value, actual)
    }
}
