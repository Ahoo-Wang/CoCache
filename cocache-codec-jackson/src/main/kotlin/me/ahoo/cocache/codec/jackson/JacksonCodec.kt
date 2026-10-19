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

package me.ahoo.cocache.codec.jackson

import me.ahoo.cocache.api.Codec
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import java.lang.reflect.Type

/**
 * 基于 Jackson 3 的 JSON 编解码。解码失败时抛出异常，引擎按载荷损坏处理（条件回收后回源）。
 */
class JacksonCodec<V>(private val mapper: ObjectMapper, private val type: JavaType) : Codec<V> {
    constructor(mapper: ObjectMapper, type: Type) : this(mapper, mapper.typeFactory.constructType(type))

    override fun encode(value: V): ByteArray = mapper.writeValueAsBytes(value)

    override fun decode(bytes: ByteArray): V = mapper.readValue(bytes, type)

    override fun toString(): String = "JacksonCodec($type)"

    companion object {
        @JvmStatic
        fun <V> of(mapper: ObjectMapper, type: Class<V>): JacksonCodec<V> = JacksonCodec(mapper, type as Type)
    }
}

/**
 * Kotlin：`jacksonCodec<User>(mapper)`，保留泛型参数（如 `List<User>`）。
 */
inline fun <reified V> jacksonCodec(mapper: ObjectMapper): JacksonCodec<V> {
    return JacksonCodec(
        mapper,
        mapper.typeFactory.constructType(object : tools.jackson.core.type.TypeReference<V>() {})
    )
}
