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

import me.ahoo.cocache.api.Store
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.core.ResolvableType
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Type

/**
 * 解析后的类型 → 不含类型变量的反射类型：经中间泛型接口继承的类型参数取实际类型，结果与直接写出的类型相等。
 */
class ResolvedTypesTest {
    data class User(val id: String)

    interface BaseStore<V : Any> : Store<String, V>

    interface NestedStore : BaseStore<Map<String, List<User>>>

    interface ArrayStore : BaseStore<Array<User>>

    interface GenericArrayStore : BaseStore<Array<List<User>>>

    interface WildcardStore : BaseStore<List<*>>

    interface UserStore : BaseStore<User>

    interface DirectStore : Store<String, List<User>>

    /**
     * 自引用边界：`E` 只能按边界 `Enum<E>` 解析，展开时又遇到 `E`。
     */
    interface BoundedStore<E : Enum<E>> : BaseStore<E>

    /**
     * 直接写出的期望类型：取方法的泛型返回类型（`User` 是 final 类，Kotlin 不为它生成通配符）。
     */
    @Suppress("unused")
    interface Expected {
        fun nested(): Map<String, List<User>>

        fun genericArray(): Array<List<User>>

        fun otherGenericArray(): Array<List<String>>

        fun objects(): List<Any>

        fun users(): List<User>
    }

    private fun valueTypeOf(type: Class<*>): Type {
        return ResolvedTypes.of(ResolvableType.forClass(type).`as`(Store::class.java).generics[1])
    }

    private fun expected(name: String): Type = Expected::class.java.getMethod(name).genericReturnType

    @Test
    fun inheritedTypeArgumentsTakeTheirActualTypes() {
        // 未经转换的类型仍是 BaseStore 的类型变量 V
        val inherited = ResolvableType.forClass(UserStore::class.java).`as`(Store::class.java).generics[1]
        inherited.type.typeName.assert().isEqualTo("V")
        valueTypeOf(UserStore::class.java).assert().isEqualTo(User::class.java)
        valueTypeOf(NestedStore::class.java).assert().isEqualTo(expected("nested"))
        valueTypeOf(DirectStore::class.java).assert().isEqualTo(expected("users"))
    }

    @Test
    fun arraysKeepTheirComponentTypes() {
        valueTypeOf(ArrayStore::class.java).assert().isEqualTo(Array<User>::class.java)
        val genericArray = valueTypeOf(GenericArrayStore::class.java)
        genericArray.assert().isInstanceOf(GenericArrayType::class.java)
        genericArray.assert().isEqualTo(expected("genericArray"))
        genericArray.assert().isEqualTo(valueTypeOf(GenericArrayStore::class.java))
        genericArray.hashCode().assert().isEqualTo(valueTypeOf(GenericArrayStore::class.java).hashCode())
        genericArray.assert().isNotEqualTo(List::class.java)
        genericArray.assert().isNotEqualTo(expected("otherGenericArray"))
        genericArray.toString().assert().isEqualTo("java.util.List<${User::class.java.name}>[]")
    }

    @Test
    fun unresolvablePartsBecomeObject() {
        valueTypeOf(WildcardStore::class.java).assert().isEqualTo(expected("objects"))
    }

    @Test
    fun selfBoundedVariablesStopAtTheirRawType() {
        val type = valueTypeOf(BoundedStore::class.java)
        // BaseStore 的 V 解析为 E，按边界 Enum<E> 展开；第二次遇到 E 时取原始类型 Enum
        type.typeName.assert().isEqualTo("java.lang.Enum<java.lang.Enum<java.lang.Enum>>")
        val mapper = JsonMapper.builder().findAndAddModules().build()
        mapper.typeFactory.constructType(type).rawClass.assert().isEqualTo(Enum::class.java)
    }

    @Test
    fun jacksonDecodesTheInheritedType() {
        val mapper = JsonMapper.builder().findAndAddModules().build()
        val javaType = mapper.typeFactory.constructType(valueTypeOf(NestedStore::class.java))
        val decoded: Map<String, List<User>> = mapper.readValue("""{"a":[{"id":"1"}]}""", javaType)
        decoded.assert().isEqualTo(mapOf("a" to listOf(User("1"))))
    }
}
