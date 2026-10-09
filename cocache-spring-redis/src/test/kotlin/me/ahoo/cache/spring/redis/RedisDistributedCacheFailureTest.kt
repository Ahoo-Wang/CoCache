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
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.spring.redis.codec.CodecExecutor
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate

internal class RedisDistributedCacheFailureTest {
    private val failure = RedisConnectionFailureException("redis down")

    private val failingTemplate = mockk<StringRedisTemplate> {
        every { delete(any<String>()) } throws failure
    }

    private val failingCodec = mockk<CodecExecutor<String>> {
        every { executeAndDecode(any()) } throws failure
        every { executeAndEncode(any(), any()) } throws failure
    }

    @Test
    fun degradesByDefault() {
        val cache = RedisDistributedCache(failingTemplate, failingCodec)
        cache.getCache("key").assert().isNull()
        cache.setCache("key", CacheValue.forever("value"))
        cache.evict("key")
    }

    @Test
    fun rethrowsInStrictMode() {
        val cache = RedisDistributedCache(failingTemplate, failingCodec, strictFailure = true)
        assertThrows<RedisConnectionFailureException> { cache.getCache("key") }
        assertThrows<RedisConnectionFailureException> { cache.setCache("key", CacheValue.forever("value")) }
        assertThrows<RedisConnectionFailureException> { cache.evict("key") }
    }
}
