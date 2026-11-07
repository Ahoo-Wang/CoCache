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

package me.ahoo.cocache.spring.boot

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.codec.jackson.jacksonCodec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/**
 * `JacksonCodec` 写入的多态集合能从唯一副本读回（#873）：`Store` 的写入方不填 L2，读取解码的是 L1 中的编码结果。
 * 用不带 Kotlin 模块的普通 mapper（默认构造器与 setter）。
 */
class JacksonCodecStoreTest {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Dog::class, name = "dog"))
    sealed interface Animal

    data class Dog(var name: String = "") : Animal

    private val mapper = JsonMapper.builder().build()

    @Test
    fun aStoreReadsBackThePolymorphicListItWrote() {
        verify(jacksonCodec<List<Animal>>(mapper))
    }

    /**
     * 对照：显式按声明类型编码的 codec。
     */
    @Test
    fun aStoreReadsBackWhenEncodingUsesTheDeclaredType() {
        val delegate = jacksonCodec<List<Animal>>(mapper)
        val writer = mapper.writerFor(object : TypeReference<List<Animal>>() {})
        verify(
            object : Codec<List<Animal>> {
                override fun encode(value: List<Animal>): ByteArray = writer.writeValueAsBytes(value)

                override fun decode(bytes: ByteArray): List<Animal> = delegate.decode(bytes)
            }
        )
    }

    private fun verify(codec: Codec<List<Animal>>) {
        InMemoryBackend().use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val store = runtime.create(storeSpec<String, List<Animal>>("pets") { this.codec = codec })
                val value: List<Animal> = listOf(Dog("buddy"))
                store.put("one", value)
                store["one"].assert().isEqualTo(value)
            }
        }
    }
}
