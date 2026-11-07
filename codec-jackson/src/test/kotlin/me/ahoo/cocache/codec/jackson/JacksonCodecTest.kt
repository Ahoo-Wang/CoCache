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

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class JacksonCodecTest {
    data class User(val id: String, val tags: List<String>)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(JsonSubTypes.Type(value = Dog::class, name = "dog"))
    sealed interface Animal

    data class Dog(val name: String) : Animal

    open class Base(val id: String = "")

    class Derived(id: String = "", val extra: String = "") : Base(id)

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun roundTripsClassTypes() {
        val codec = JacksonCodec.of(mapper, User::class.java)
        val user = User("1", listOf("a"))
        codec.decode(codec.encode(user)).assert().isEqualTo(user)
        codec.toString().assert().contains("User")
    }

    @Test
    fun roundTripsGenericTypes() {
        val codec = jacksonCodec<List<User>>(mapper)
        val users = listOf(User("1", emptyList()), User("2", listOf("x")))
        codec.decode(codec.encode(users)).assert().isEqualTo(users)
    }

    @Test
    fun corruptPayloadFailsToDecode() {
        val codec = JacksonCodec.of(mapper, User::class.java)
        runCatching { codec.decode("{not json".toByteArray()) }.exceptionOrNull().assert().isNotNull()
    }

    /**
     * 编码与解码用同一个声明类型（#873）：泛型容器的元素类型是多态的时，按运行时类型编码会丢掉类型 id，解码失败。
     */
    @Test
    fun aDeclaredPolymorphicListRoundTrips() {
        val codec = jacksonCodec<List<Animal>>(mapper)
        val value: List<Animal> = listOf(Dog("buddy"))
        String(codec.encode(value)).assert().contains("\"kind\":\"dog\"")
        codec.decode(codec.encode(value)).assert().isEqualTo(value)
    }

    @Test
    fun aDeclaredPolymorphicMapRoundTrips() {
        val codec = jacksonCodec<Map<String, Animal>>(mapper)
        val value: Map<String, Animal> = mapOf("pet" to Dog("buddy"))
        codec.decode(codec.encode(value)).assert().isEqualTo(value)
    }

    /**
     * 与 `mapper.writerFor(声明类型)` 的输出相同：保留 mapper 的模块与注解。
     */
    @Test
    fun encodesLikeAWriterForTheDeclaredType() {
        val codec = jacksonCodec<List<Animal>>(mapper)
        val value: List<Animal> = listOf(Dog("buddy"))
        val bytes = mapper.writerFor(object : TypeReference<List<Animal>>() {}).writeValueAsBytes(value)
        codec.encode(value).assert().isEqualTo(bytes)
        codec.decode(bytes).assert().isEqualTo(value)
    }

    @Test
    fun aPolymorphicScalarStillRoundTrips() {
        val codec = jacksonCodec<Animal>(mapper)
        val value: Animal = Dog("buddy")
        codec.decode(codec.encode(value)).assert().isEqualTo(value)
    }

    /**
     * 声明类型为 `Object` 时按运行时类型编码；声明为基本类型时接受它的包装类型。
     */
    @Test
    fun untypedAndPrimitiveDeclarationsStillRoundTrip() {
        val untyped = JacksonCodec.of(mapper, Any::class.java)
        String(untyped.encode(User("1", listOf("a")))).assert().isEqualTo("{\"id\":\"1\",\"tags\":[\"a\"]}")
        val primitive = JacksonCodec.of(mapper, Int::class.javaPrimitiveType!!)
        primitive.decode(primitive.encode(42)).assert().isEqualTo(42)
    }

    /**
     * 不带类型信息的声明类型按声明类型编码：子类多出的属性不写入，解码得到的就是声明类型能表示的值。
     */
    @Test
    fun aSubclassValueIsEncodedAsTheDeclaredType() {
        val codec = JacksonCodec.of(mapper, Base::class.java)
        String(codec.encode(Derived("1", "x"))).assert().isEqualTo("{\"id\":\"1\"}")
        codec.decode(codec.encode(Derived("1", "x"))).id.assert().isEqualTo("1")
    }
}
