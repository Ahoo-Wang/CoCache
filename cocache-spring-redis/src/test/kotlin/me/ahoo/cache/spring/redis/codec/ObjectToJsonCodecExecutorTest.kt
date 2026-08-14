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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.*

internal class ObjectToJsonCodecExecutorTest : CodecExecutorSpec<Model>() {

    override fun createCodecExecutor(): CodecExecutor<Model> {
        return ObjectToJsonCodecExecutor(Model::class.java, stringRedisTemplate, Json)
    }

    override fun createCacheValue(): Model {
        return Model(UUID.randomUUID().toString())
    }

    @Test
    fun executeAndDecodeWhenCorruptedPayloadEvictsAndReturnsNull() {
        val key = "corrupted:" + UUID.randomUUID().toString()
        stringRedisTemplate.opsForValue()[key] = "{invalid-json"

        val actual = codecExecutor.executeAndDecode(key, ComputedTtlAt.FOREVER)

        actual.assert().isNull()
        stringRedisTemplate.opsForValue()[key].assert().isNull()
    }
}

data class Model(val id: String)
