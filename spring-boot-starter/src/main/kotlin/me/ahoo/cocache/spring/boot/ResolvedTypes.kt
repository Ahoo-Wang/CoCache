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

import org.springframework.core.ResolvableType
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable

/**
 * 把解析后的 [ResolvableType] 转成不含类型变量的反射类型，供默认的 Jackson 编解码与 AOT 的绑定提示使用。
 *
 * 经中间泛型接口继承的类型参数（`interface UserStore : BaseStore<User>`，`BaseStore<V> : Store<String, V>`）在 `ResolvableType`
 * 中借继承关系解析为 `User`，它的 [ResolvableType.getType] 却仍是未绑定的类型变量 `V`：直接交给 Jackson 会按 `Object` 解码。
 * 这里按解析结果逐层重建：类型参数（`List<User>` 的 `User`）、数组的元素类型都取解析后的类型。
 *
 * 无法解析的部分（无界通配符、无界类型变量）取 `Object`，与 Jackson 对它们的处理相同；自引用边界的类型变量
 * （`E : Enum<E>`）在同一条路径上第二次出现时取它的原始类型，不再展开。
 */
internal object ResolvedTypes {
    fun of(type: ResolvableType): Type = of(type, emptySet())

    private fun of(type: ResolvableType, visiting: Set<TypeVariable<*>>): Type {
        val variable = type.type as? TypeVariable<*>
        if (variable != null && variable in visiting) {
            return type.toClass()
        }
        val path = variable?.let { visiting + it } ?: visiting
        if (type.isArray) {
            return when (val component = of(type.componentType, path)) {
                is Class<*> -> component.arrayType()
                else -> ResolvedArrayType(component)
            }
        }
        val raw = type.resolve() ?: return Any::class.java
        val generics = type.generics
        if (generics.isEmpty()) {
            return raw
        }
        val arguments = generics.map { ResolvableType.forType(of(it, path)) }
        // 参数都不是类型变量：合成的参数化类型（`ResolvableType.getType`）原样带着它们
        return ResolvableType.forClassWithGenerics(raw, *arguments.toTypedArray()).type
    }

    /**
     * 元素是参数化类型的数组（`Array<List<User>>`）。相等性与 JDK 的实现一致（只比较元素类型）。
     */
    private class ResolvedArrayType(private val component: Type) : GenericArrayType {
        override fun getGenericComponentType(): Type = component

        override fun equals(other: Any?): Boolean {
            return other is GenericArrayType && component == other.genericComponentType
        }

        override fun hashCode(): Int = component.hashCode()

        override fun toString(): String = "${component.typeName}[]"
    }
}
