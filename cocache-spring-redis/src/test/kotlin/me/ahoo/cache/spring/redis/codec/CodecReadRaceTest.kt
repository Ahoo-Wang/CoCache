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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.SessionCallback
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * pipeline 两条命令之间 key 被删除/过期的交错：值已读到，但 TTL 显示 key 不存在或缺失。
 * 必须按未命中处理（回源），不得返回读到的旧值，更不得推断为负缓存。
 */
internal class CodecReadRaceTest {
    private fun codecReturning(vararg results: Any?): StringToStringCodecExecutor {
        val redisTemplate = mockk<StringRedisTemplate> {
            every { executePipelined(any<SessionCallback<Any?>>()) } returns results.toList()
        }
        return StringToStringCodecExecutor(redisTemplate)
    }

    @Test
    fun keyDeletedBetweenReadAndTtlIsMiss() {
        codecReturning("value", -2L).executeAndDecode("key").assert().isNull()
    }

    @Test
    fun missingTtlResultIsMiss() {
        codecReturning("value", null).executeAndDecode("key").assert().isNull()
    }

    @Test
    fun sentinelReadDuringDeletionIsMiss() {
        codecReturning(
            AbstractCodecExecutor.DEFAULT_MISSING_GUARD_SENTINEL,
            -2L
        ).executeAndDecode("key").assert().isNull()
    }
}
