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

import java.util.*

internal class ObjectToHashCodecExecutorTest : CodecExecutorSpec<Model>(),
    ObjectToHashCodecExecutor.MapConverter<Model> {

    override fun createCodecExecutor(): CodecExecutor<Model> {
        return ObjectToHashCodecExecutor(this, stringRedisTemplate)
    }

    override fun createCacheValue(): Model {
        return Model(UUID.randomUUID().toString())
    }

    override fun asValue(map: Map<String, String>): Model {
        return Model(map["id"]!!)
    }

    override fun asMap(value: Model): Map<String, String> {
        return mapOf("id" to value.id)
    }
}
