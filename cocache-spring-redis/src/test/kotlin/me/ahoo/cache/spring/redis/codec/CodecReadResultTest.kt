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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.api.TtlAt
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript

/**
 * 读脚本结果 `{ttl, ...raw}` 的解码契约：key 不存在、值缺失都必须是未命中（回源），绝不推断为负缓存。
 */
internal class CodecReadResultTest {
    private fun codecReturning(result: List<Any?>?): StringToStringCodecExecutor {
        val redisTemplate = mockk<StringRedisTemplate> {
            every { execute(any<RedisScript<List<*>>>(), any<List<String>>()) } returns result
        }
        return StringToStringCodecExecutor(redisTemplate)
    }

    @Test
    fun absentKeyIsMiss() {
        codecReturning(listOf(-2L)).executeAndDecode("key").assert().isNull()
    }

    @Test
    fun missingValueIsMiss() {
        codecReturning(listOf(10L, null)).executeAndDecode("key").assert().isNull()
    }

    @Test
    fun emptyResultIsMiss() {
        codecReturning(emptyList()).executeAndDecode("key").assert().isNull()
    }

    @Test
    fun hashWithoutFieldsIsMiss() {
        // TTL 读到之后、HGETALL 之前 key 被删除：空集合必须按未命中处理
        val redisTemplate = mockk<StringRedisTemplate> {
            every { execute(any<RedisScript<List<*>>>(), any<List<String>>()) } returns listOf(10L)
        }
        MapToHashCodecExecutor(redisTemplate).executeAndDecode("key").assert().isNull()
        SetToSetCodecExecutor(redisTemplate).executeAndDecode("key").assert().isNull()
    }

    @Test
    fun nullResultIsMiss() {
        codecReturning(null).executeAndDecode("key").assert().isNull()
    }

    @Test
    fun foreverValue() {
        val actual = requireNotNull(codecReturning(listOf(-1L, "v")).executeAndDecode("key"))
        actual.value.assert().isEqualTo("v")
        actual.isForever.assert().isTrue()
    }

    @Test
    fun sentinelIsMissingWithTtlAt() {
        val actual = requireNotNull(
            codecReturning(listOf(30L, AbstractCodecExecutor.DEFAULT_MISSING_GUARD_SENTINEL)).executeAndDecode("key")
        )
        actual.isMissing.assert().isTrue()
        actual.ttlAt.assert().isBetween(TtlAt.currentTime() + 29, TtlAt.currentTime() + 30)
    }
}
