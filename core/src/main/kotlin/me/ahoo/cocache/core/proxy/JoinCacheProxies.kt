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

import me.ahoo.cocache.api.join.JoinCache
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * 组合视图的接口式声明代理：用户接口继承 [JoinCache]，代理把调用转发给 [target]（通常是 `SimpleJoinCache`）。
 *
 * - [JoinCache] 的方法直接调用目标（按方法名与参数个数分派，不经 `Method.invoke`），异常原样抛出。
 * - 用户接口的 default 方法经 [InvocationHandler.invokeDefault] 执行。
 * - 用户接口中的抽象方法必须对应 [JoinCache] 的方法（同名、同参数个数、返回类型兼容），否则创建时失败。
 * - `equals` / `hashCode` 按代理对象身份；`toString` 显示接口与目标。
 */
object JoinCacheProxies {
    private val JOIN_CACHE_METHODS: List<Method> = JoinCache::class.java.methods.toList()

    @JvmStatic
    fun <C : JoinCache<*, *, *, *>> create(type: Class<C>, target: JoinCache<*, *, *, *>): C {
        require(type.isInterface) { "${type.name} must be an interface." }
        require(
            JoinCache::class.java.isAssignableFrom(type)
        ) { "${type.name} must extend ${JoinCache::class.java.name}." }
        val unsupported = type.methods.filter { method ->
            Modifier.isAbstract(method.modifiers) &&
                method.declaringClass != JoinCache::class.java &&
                JOIN_CACHE_METHODS.none { matches(method, it) }
        }
        require(unsupported.isEmpty()) {
            "Join cache interface[${type.name}] declares abstract methods that do not match any JoinCache method: " +
                "${unsupported.map { "${it.returnType.simpleName} ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }}; " +
                "declare them as default methods."
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
            Handler(type, target as JoinCache<Any, Any, Any, Any>)
        ) as C
    }

    /**
     * 代理背后的目标；不是本工厂创建的代理时返回 `null`。
     */
    @JvmStatic
    fun targetOf(proxy: Any): JoinCache<*, *, *, *>? {
        if (!Proxy.isProxyClass(proxy.javaClass)) {
            return null
        }
        return (Proxy.getInvocationHandler(proxy) as? Handler)?.target
    }

    /**
     * [JoinCache] 的参数擦除后都是 `Object`，所以只比较名称、参数个数与返回类型：
     * 返回类型只能放宽（声明的类型是 JoinCache 方法返回类型的父类型），不能收窄或换成不相关的类型；`void` 只与 `void` 兼容。
     */
    private fun matches(method: Method, joinMethod: Method): Boolean {
        if (method.name != joinMethod.name || method.parameterCount != joinMethod.parameterCount) {
            return false
        }
        val declared = method.returnType
        val expected = joinMethod.returnType
        if (declared == Void.TYPE || expected == Void.TYPE) {
            return declared == expected
        }
        // 只允许放宽返回类型：收窄（如 Map → HashMap）会在调用时因目标返回的实现类不同而 ClassCastException
        return !declared.isPrimitive && declared.isAssignableFrom(expected)
    }

    private class Handler(private val type: Class<*>, val target: JoinCache<Any, Any, Any, Any>) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: NO_ARGS
            if (method.declaringClass == Any::class.java) {
                return objectMethod(proxy, method, arguments)
            }
            // JoinCache 没有 default 方法，default 方法一定来自用户接口
            if (method.isDefault) {
                return InvocationHandler.invokeDefault(proxy, method, *arguments)
            }
            return dispatch(method, arguments)
        }

        @Suppress("UNCHECKED_CAST")
        private fun dispatch(method: Method, args: Array<out Any?>): Any? {
            return when (method.name) {
                "get" -> target[args[0] as Any]
                "getAll" -> target.getAll(args[0] as Collection<Any>)
                "invalidate" -> if (args.size == 1) {
                    target.invalidate(args[0] as Any)
                } else {
                    target.invalidate(args[0] as Any, args[1] as Any)
                }
                // 创建时已校验抽象方法，剩下的只有 getName
                else -> target.name
            }
        }

        private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>): Any {
            return when (method.name) {
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> "${type.simpleName}($target)"
            }
        }
    }

    private val NO_ARGS = emptyArray<Any?>()
}
