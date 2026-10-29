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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.SetCache
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 启动时校验接口式声明中的抽象方法。
 *
 * 代理（`CacheProxies`）按方法名与参数个数把调用转发给引擎。用户接口重新声明（或重载）的抽象方法如果参数类型不兼容，
 * 运行时会以 `ClassCastException` 失败；这里在创建缓存时就失败，并指出改为 default 方法。
 */
internal object CacheMethods {
    private val CACHE_METHODS: List<Method> = Cache::class.java.methods.toList()
    private val SET_CACHE_METHODS: List<Method> = CACHE_METHODS + SetCache::class.java.methods

    fun validate(type: Class<*>, isSet: Boolean) {
        val supported = if (isSet) SET_CACHE_METHODS else CACHE_METHODS
        val unsupported = type.methods.filter { method ->
            Modifier.isAbstract(method.modifiers) &&
                method.declaringClass != Cache::class.java &&
                method.declaringClass != SetCache::class.java &&
                supported.none { matches(method, it) }
        }
        check(unsupported.isEmpty()) {
            "Cache interface[${type.name}] declares abstract methods that do not match any " +
                "${if (isSet) "SetCache" else "Cache"} method: ${unsupported.map { signature(it) }}; declare them as default methods."
        }
    }

    /**
     * 同名、同参数个数，且每个参数都能传给对应的缓存方法（擦除后的类型），返回类型兼容。
     */
    private fun matches(method: Method, cacheMethod: Method): Boolean {
        if (method.name != cacheMethod.name || method.parameterCount != cacheMethod.parameterCount) {
            return false
        }
        val parametersMatch = method.parameterTypes.indices.all { index ->
            boxed(cacheMethod.parameterTypes[index]).isAssignableFrom(boxed(method.parameterTypes[index]))
        }
        return parametersMatch && returnMatches(boxed(method.returnType), boxed(cacheMethod.returnType))
    }

    /**
     * 返回类型可以收窄（`V` → `User`），但不能换成不相关的类型；`void` 只与 `void` 兼容。
     */
    private fun returnMatches(declared: Class<*>, cache: Class<*>): Boolean {
        if (declared == Void::class.java || cache == Void::class.java) {
            return declared == cache
        }
        return declared.isAssignableFrom(cache) || cache.isAssignableFrom(declared)
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
