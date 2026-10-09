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

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.*

internal class SetToSetCodecExecutorTest : CodecExecutorSpec<Set<String>>() {

    override fun createCodecExecutor(): CodecExecutor<Set<String>> = SetToSetCodecExecutor(stringRedisTemplate)

    override fun createCustomSentinelCodecExecutor(): CodecExecutor<Set<String>> {
        return SetToSetCodecExecutor(stringRedisTemplate, CUSTOM_SENTINEL)
    }

    override fun createCacheValue(): Set<String> = setOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())

    override fun createSingleNonSentinelValue(): Set<String> = setOf(UUID.randomUUID().toString())

    @Test
    fun emptySetEvictsKey() {
        val key = newKey()
        codecExecutor.executeAndEncode(key, CacheValue.forever(createCacheValue()))
        codecExecutor.executeAndEncode(key, CacheValue.of(emptySet(), TtlAt.at(60)))
        stringRedisTemplate.hasKey(key).assert().isFalse()
    }
}
