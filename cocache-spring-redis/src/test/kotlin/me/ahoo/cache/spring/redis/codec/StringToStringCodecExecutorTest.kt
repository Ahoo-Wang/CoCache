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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.*

internal class StringToStringCodecExecutorTest : CodecExecutorSpec<String>() {
    override fun createCodecExecutor(): CodecExecutor<String> = StringToStringCodecExecutor(stringRedisTemplate)

    override fun createCustomSentinelCodecExecutor(): CodecExecutor<String> {
        return StringToStringCodecExecutor(stringRedisTemplate, CUSTOM_SENTINEL)
    }

    override fun createCacheValue(): String = UUID.randomUUID().toString()

    @Test
    fun customSentinelReadsDefaultSentinelAsValue() {
        val executor = StringToStringCodecExecutor(stringRedisTemplate, CUSTOM_SENTINEL)
        val key = newKey()
        stringRedisTemplate.opsForValue()[key] = "_nil_"
        requireNotNull(executor.executeAndDecode(key)).value.assert().isEqualTo("_nil_")
    }

    @Test
    fun wireFormatOfMissingIsSentinel() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, me.ahoo.cache.api.CacheValue.missing())
        stringRedisTemplate.opsForValue()[key].assert().isEqualTo(AbstractCodecExecutor.DEFAULT_MISSING_GUARD_SENTINEL)
    }
}
