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

package me.ahoo.cocache.core.proxy

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable

/**
 * 启动时校验接口式声明中的抽象方法。
 *
 * 代理（`CacheProxies`）按接口继承的可声明类型（[apiType]）把调用转发给引擎或它的视图：同步类型按方法名与参数个数分派，视图类型
 * 调用匹配的视图方法（[counterpartOf]）。用户接口重新声明（或重载）的抽象方法如果参数类型或返回类型不兼容，运行时会以
 * `ClassCastException` 失败或无法转发；这里在创建缓存时就失败，并指出改为 default 方法。
 */
internal object CacheMethods {
    /**
     * @param apiType 接口继承的可声明类型（`CacheProxies.declaredTypeOf`）
     */
    fun validate(type: Class<*>, apiType: Class<*>) {
        val supported = apiType.methods.toList()
        val arguments = typeArgumentsOf(apiType)
        val unsupported = type.methods.filter { method ->
            Modifier.isAbstract(method.modifiers) &&
                !method.declaringClass.isAssignableFrom(apiType) &&
                supported.none { matches(method, it, arguments) }
        }
        check(unsupported.isEmpty()) {
            "Cache interface[${type.name}] declares abstract methods that do not match any " +
                "${apiType.simpleName} method: ${unsupported.map { signature(it) }}; declare them as default methods."
        }
    }

    /**
     * 用户接口重新声明的抽象方法 [method] 对应的 [apiType] 方法；没有时为 `null`。
     */
    fun counterpartOf(method: Method, apiType: Class<*>): Method? {
        val arguments = typeArgumentsOf(apiType)
        return apiType.methods.firstOrNull { matches(method, it, arguments) }
    }

    /**
     * 参数个数相同，且 [method] 的每个参数都能传给 [target] 的对应参数（擦除后的类型，基本类型按包装类型比较）。
     * 组合视图的代理（`JoinCacheProxies`）用同一条规则。
     */
    fun parametersMatch(method: Method, target: Method): Boolean {
        if (method.parameterCount != target.parameterCount) {
            return false
        }
        return method.parameterTypes.indices.all { index ->
            boxed(target.parameterTypes[index]).isAssignableFrom(boxed(method.parameterTypes[index]))
        }
    }

    /**
     * 同名、参数兼容（[parametersMatch]），返回类型兼容（[returnMatches]，缓存方法的返回类型按 [arguments] 代入可声明类型中的实参）。
     */
    private fun matches(method: Method, cacheMethod: Method, arguments: Map<Type, Type>): Boolean {
        if (method.name != cacheMethod.name || !parametersMatch(method, cacheMethod)) {
            return false
        }
        val returned = cacheMethod.genericReturnType
        return returnMatches(boxed(method.returnType), arguments[returned] ?: returned)
    }

    /**
     * 返回类型 [cache] 是可声明类型自己的类型变量（业务泛型，如 `get` 的 `V`）时可以收窄为具体的值类型（`V` → `User`）：
     * 引擎返回的就是解码出的业务值，类型由 spec 与接口的声明保证。其余是固定的返回面（`Map`、`Set`、`Lookup`、`CompletableFuture`、
     * 视图类型等，包括 `SetStore` 中代入为 `Set<E>` 的 `V`）：引擎返回自己的实现类（如 `LinkedHashMap`），只能放宽或相同，
     * 收窄（`Map` → `SortedMap`）会在调用时 `ClassCastException`。`void` 只与 `void` 兼容。
     */
    private fun returnMatches(declared: Class<*>, cache: Type): Boolean {
        if (cache is TypeVariable<*>) {
            return declared != Void::class.java
        }
        val fixed = boxed(if (cache is ParameterizedType) cache.rawType as Class<*> else cache as Class<*>)
        if (declared == Void::class.java || fixed == Void::class.java) {
            return declared == fixed
        }
        return declared.isAssignableFrom(fixed)
    }

    /**
     * [type] 的父接口的类型变量在 [type] 中的实参，沿继承链逐层代入（[type] 自己的类型变量不在其中）：
     * `SetStore<K, E>` 中 `ReadableCache` 的 `V` 是 `Set<out E>`，`K` 是 `SetStore` 的 `K`。
     */
    private fun typeArgumentsOf(type: Class<*>, arguments: MutableMap<Type, Type> = HashMap()): Map<Type, Type> {
        type.genericInterfaces.filterIsInstance<ParameterizedType>().forEach { parent ->
            val raw = parent.rawType as Class<*>
            raw.typeParameters.zip(parent.actualTypeArguments).forEach { (variable, argument) ->
                arguments[variable] = arguments[argument] ?: argument
            }
            typeArgumentsOf(raw, arguments)
        }
        return arguments
    }

    private fun boxed(type: Class<*>): Class<*> {
        if (!type.isPrimitive) {
            return type
        }
        return when (type) {
            Void.TYPE -> Void::class.java
            else -> type.kotlin.javaObjectType
        }
    }

    private fun signature(method: Method): String {
        return "${method.returnType.simpleName} ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
    }
}
