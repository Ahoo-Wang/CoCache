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

import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.util.CacheSecondClock
import me.ahoo.test.asserts.assert
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

abstract class CodecExecutorSpec<V> {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    lateinit var codecExecutor: CodecExecutor<V>
    abstract fun createCodecExecutor(): CodecExecutor<V>
    abstract fun createCacheValue(): V

    @BeforeEach
    open fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        stringRedisTemplate.afterPropertiesSet()
        codecExecutor = createCodecExecutor()
    }

    @AfterEach
    open fun destroy() {
        if (null != lettuceConnectionFactory) {
            lettuceConnectionFactory.destroy()
        }
    }

    @Test
    fun executeAndEncode() {
        val key = "executeAndDecode:" + UUID.randomUUID().toString()
        val value = DefaultCacheValue.forever(createCacheValue())
        codecExecutor.executeAndEncode(key, value)
        val actual = codecExecutor.executeAndDecode(key, ComputedTtlAt.FOREVER)
        actual.assert().isEqualTo(value)
    }

    @Test
    fun executeAndEncodeWithTtlAt() {
        val key = "executeAndDecode:" + UUID.randomUUID().toString()
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 10
        val value = DefaultCacheValue(createCacheValue(), ttlAt)
        codecExecutor.executeAndEncode(key, value)
        val actual = codecExecutor.executeAndDecode(key, ttlAt)
        actual.assert().isEqualTo(value)
        actual.ttlAt.assert().isCloseTo(value.ttlAt, Offset.offset(1))
    }

    @Test
    fun executeAndEncodeMissing() {
        val key = "executeAndDecodeWhenMissing:" + UUID.randomUUID().toString()
        val value = DefaultCacheValue.missingGuard<CacheValue<V>>()
        codecExecutor.executeAndEncode(key, value)
        val actual = codecExecutor.executeAndDecode(key, ComputedTtlAt.FOREVER)
        actual.assert().isEqualTo(value)
    }

    @Test
    fun executeAndEncodeMissingWithTtlAt() {
        val key = "executeAndDecodeWhenMissingTtl:" + UUID.randomUUID().toString()
        val value = DefaultCacheValue.missingGuard<CacheValue<V>>(100)
        codecExecutor.executeAndEncode(key, value)
        val actual = codecExecutor.executeAndDecode(key, 100)
        actual.assert().isEqualTo(value)
        actual.ttlAt.assert().isCloseTo(value.ttlAt, Offset.offset(1))
    }
}
