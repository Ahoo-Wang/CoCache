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
package me.ahoo.cache.spring.redis

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.consistency.CacheEvictedEvent
import me.ahoo.cache.spring.redis.codec.CodecExecutor
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * RedisDistributedCacheFailureTest .
 *
 * @author ahoo wang
 */
internal class RedisDistributedCacheFailureTest {
    private val failure = RedisConnectionFailureException("redis down")

    private fun newFailingTemplate(): StringRedisTemplate {
        val template = mockk<StringRedisTemplate>()
        every { template.getExpire(any<String>()) } throws failure
        every { template.delete(any<String>()) } throws failure
        return template
    }

    private fun newFailingCodec(): CodecExecutor<String> {
        val codec = mockk<CodecExecutor<String>>()
        every { codec.executeAndEncode(any(), any()) } throws failure
        return codec
    }

    @Test
    fun getCacheDegradesToMissOnFailure() {
        val cache = RedisDistributedCache(newFailingTemplate(), newFailingCodec())
        cache.getCache("key").assert().isNull()
    }

    @Test
    fun getCacheDegradesToMissWhenCodecFails() {
        val template = mockk<StringRedisTemplate>()
        every { template.getExpire(any<String>()) } returns RedisDistributedCache.FOREVER
        val codec = mockk<CodecExecutor<String>>()
        every { codec.executeAndDecode(any(), any()) } throws failure

        val cache = RedisDistributedCache(template, codec)

        cache.getCache("key").assert().isNull()
    }

    @Test
    fun setCacheSwallowsFailure() {
        val cache = RedisDistributedCache(mockk<StringRedisTemplate>(), newFailingCodec())
        cache.setCache("key", DefaultCacheValue.forever("value"))
    }

    @Test
    fun evictSwallowsFailure() {
        val cache = RedisDistributedCache(newFailingTemplate(), mockk<CodecExecutor<String>>())
        cache.evict("key")
    }

    @Test
    fun getCacheRethrowsInStrictMode() {
        val cache = RedisDistributedCache(newFailingTemplate(), newFailingCodec(), strictFailure = true)
        assertThrows<RedisConnectionFailureException> {
            cache.getCache("key")
        }
    }

    @Test
    fun setCacheRethrowsInStrictMode() {
        val cache = RedisDistributedCache(mockk<StringRedisTemplate>(), newFailingCodec(), strictFailure = true)
        assertThrows<RedisConnectionFailureException> {
            cache.setCache("key", DefaultCacheValue.forever("value"))
        }
    }

    @Test
    fun evictRethrowsInStrictMode() {
        val cache = RedisDistributedCache(newFailingTemplate(), mockk<CodecExecutor<String>>(), strictFailure = true)
        assertThrows<RedisConnectionFailureException> {
            cache.evict("key")
        }
    }
}

internal class RedisCacheEvictedEventBusFailureTest {
    @Test
    fun publishSwallowsRedisFailure() {
        val redisTemplate = mockk<StringRedisTemplate>()
        every { redisTemplate.convertAndSend(any<String>(), any<Any>()) } throws RedisConnectionFailureException("down")
        val bus = RedisCacheEvictedEventBus(redisTemplate, mockk(relaxed = true))

        bus.publish(CacheEvictedEvent("cache", "key", "clientId"))
    }
}
