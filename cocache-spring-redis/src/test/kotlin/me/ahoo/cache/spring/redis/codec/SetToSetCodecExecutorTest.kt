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

import com.google.common.collect.Sets
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
 * SetToSetCodecExecutorTest .
 *
 * @author ahoo wang
 */
internal class SetToSetCodecExecutorTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    lateinit var codecExecutor: SetToSetCodecExecutor

    @BeforeEach
    private fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        codecExecutor = SetToSetCodecExecutor(stringRedisTemplate)
    }

    @AfterEach
    fun destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy()
        }
    }

    @Test
    fun executeAndDecode() {
        val key = "executeAndDecode:" + MockIdGenerator.INSTANCE.generateAsString()
        val value = forever<Set<String>>(Sets.newHashSet("1", "2"))
        codecExecutor.executeAndEncode(key, value)
        Assertions.assertEquals(value, codecExecutor.executeAndDecode(key, TtlAt.FOREVER))
    }

    @Test
    fun executeAndDecodeWhenMissing() {
        val key = "executeAndDecodeWhenMissing:" + MockIdGenerator.INSTANCE.generateAsString()
        val value = missingGuard<CacheValue<Set<String>>>()
        codecExecutor.executeAndEncode(key, value)
        Assertions.assertEquals(value, codecExecutor.executeAndDecode(key, TtlAt.FOREVER))
    }
}
