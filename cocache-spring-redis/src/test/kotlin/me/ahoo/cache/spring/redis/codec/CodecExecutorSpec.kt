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
import me.ahoo.cache.DefaultMissingGuard
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

    /** 自定义哨兵执行器：驱动 [customSentinelGuardRoundTrip] 在每个 codec 上回归哨兵属性化。 */
    abstract fun createCustomSentinelCodecExecutor(): CodecExecutor<V>
    abstract fun createCacheValue(): V

    companion object {
        const val CUSTOM_SENTINEL = "\u0000cocache-test:nil"
    }

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
        val actual = requireNotNull(codecExecutor.executeAndDecode(key, ttlAt))
        // ttlAt is reconstructed from Redis EXPIRE and may drift by up to 1
        // second across the write/read boundary, so assert value equality and a
        // tolerant ttlAt instead of full-object equality (which is flaky).
        actual.value.assert().isEqualTo(value.value)
        actual.isMissingGuard.assert().isEqualTo(value.isMissingGuard)
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
        // ttlAt is an ABSOLUTE deadline (production callers pass currentTime + remainingTtl),
        // so construct the guard with the same absolute value the decode side will receive.
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 100

        @Suppress("UNCHECKED_CAST")
        val value = DefaultCacheValue(DefaultMissingGuard, ttlAt) as CacheValue<V>
        codecExecutor.executeAndEncode(key, value)
        val actual = requireNotNull(codecExecutor.executeAndDecode(key, ttlAt))
        // The sentinel must round-trip exactly, and the decoded ttlAt must equal the
        // passed absolute deadline (a regression re-treats it as a relative duration).
        actual.value.assert().isEqualTo(value.value)
        actual.isMissingGuard.assert().isEqualTo(value.isMissingGuard)
        actual.ttlAt.assert().isEqualTo(ttlAt)
    }

    @Test
    fun executeAndEncodeNullValueAsMissingGuard() {
        val key = "null-normalize:" + UUID.randomUUID().toString()
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 100

        @Suppress("UNCHECKED_CAST")
        val nullValue = null as V
        codecExecutor.executeAndEncode(key, DefaultCacheValue(nullValue, ttlAt))

        val actual = requireNotNull(codecExecutor.executeAndDecode(key, ttlAt))

        actual.isMissingGuard.assert().isTrue()
        actual.ttlAt.assert().isEqualTo(ttlAt)
    }

    @Test
    fun customSentinelGuardRoundTrip() {
        val executor = createCustomSentinelCodecExecutor()
        val key = "custom-sentinel-rt:" + UUID.randomUUID().toString()
        val ttlAt = CacheSecondClock.INSTANCE.currentTime() + 100

        @Suppress("UNCHECKED_CAST")
        val guardValue = DefaultCacheValue(DefaultMissingGuard, ttlAt) as CacheValue<V>
        executor.executeAndEncode(key, guardValue)

        val actual = requireNotNull(executor.executeAndDecode(key, ttlAt))

        // 若某 codec 的哨兵判定退回常量（而非属性），自定义哨兵写读将无法互相识别，此断言失败
        actual.isMissingGuard.assert().isTrue()
    }
}
