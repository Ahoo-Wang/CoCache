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
import me.ahoo.cocache.api.join.JoinView
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * 组合视图的接口式声明代理：用户接口继承 [JoinView]（只读）或 [JoinCache]（两侧都是 `Cache`，另有失效），代理把调用转发给 [target]
 * （通常是 `SimpleJoinView` / `SimpleJoinCache`）。
 *
 * - 用户接口继承的那个类型（[JoinView] 或 [JoinCache]）的方法直接调用目标（按方法名与参数个数分派，不经 `Method.invoke`），异常原样抛出。
 * - 用户接口的 default 方法经 [DefaultMethods] 执行：声明它的接口可访问时经 [InvocationHandler.invokeDefault]，包可见时经声明接口的
 *   私有查找取得的方法句柄；不能取得时创建失败（[IllegalArgumentException]）。
 * - 用户接口中的抽象方法必须对应这个类型的方法（同名、参数兼容、返回类型兼容），否则创建时失败。
 * - `equals` / `hashCode` 按代理对象身份；`toString` 显示接口与目标。
 */
object JoinCacheProxies {
    @JvmStatic
    fun <C : JoinView<*, *, *, *>> create(type: Class<C>, target: JoinView<*, *, *, *>): C {
        require(type.isInterface) { "${type.name} must be an interface." }
        require(
            JoinView::class.java.isAssignableFrom(type)
        ) { "${type.name} must extend ${JoinView::class.java.name}." }
        val viewType = if (JoinCache::class.java.isAssignableFrom(type)) JoinCache::class.java else JoinView::class.java
        require(viewType.isInstance(target)) {
            "${type.name} extends ${viewType.simpleName}, but the target $target is not a ${viewType.simpleName}."
        }
        val methods = viewType.methods.toList()
        val unsupported = type.methods.filter { method ->
            Modifier.isAbstract(method.modifiers) &&
                !method.declaringClass.isAssignableFrom(viewType) &&
                // 重新声明的 Object 方法由处理器按 Object 的语义处理（#902）
                !CacheMethods.isObjectMethod(method) &&
                methods.none { matches(method, it) }
        }
        require(unsupported.isEmpty()) {
            "Join cache interface[${type.name}] declares abstract methods that do not match any ${viewType.simpleName} method: " +
                "${unsupported.map { "${it.returnType.simpleName} ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }}; " +
                "declare them as default methods."
        }
        val defaults = DefaultMethods.of(type)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
            Handler(type, target as JoinView<Any, Any, Any, Any>, defaults)
        ) as C
    }

    /**
     * 代理背后的目标；不是本工厂创建的代理时返回 `null`。
     */
    @JvmStatic
    fun targetOf(proxy: Any): JoinView<*, *, *, *>? {
        if (!Proxy.isProxyClass(proxy.javaClass)) {
            return null
        }
        return (Proxy.getInvocationHandler(proxy) as? Handler)?.target
    }

    /**
     * 同名、参数兼容（与普通缓存的代理同一条规则，[CacheMethods.parametersMatch]：`getAll` 的参数是 `Collection`，`getAll(String)`
     * 不匹配），返回类型兼容：返回类型只能放宽（声明的类型是 JoinCache 方法返回类型的父类型），不能收窄或换成不相关的类型；
     * `void` 只与 `void` 兼容。
     */
    private fun matches(method: Method, joinMethod: Method): Boolean {
        if (method.name != joinMethod.name || !CacheMethods.parametersMatch(method, joinMethod)) {
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

    private class Handler(
        private val type: Class<*>,
        val target: JoinView<Any, Any, Any, Any>,
        private val defaults: DefaultMethods,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: NO_ARGS
            if (method.declaringClass == Any::class.java) {
                return objectMethod(proxy, method, arguments)
            }
            // JoinView / JoinCache 没有 default 方法，default 方法一定来自用户接口
            if (method.isDefault) {
                return defaults.invoke(proxy, method, arguments)
            }
            return dispatch(method, arguments)
        }

        @Suppress("UNCHECKED_CAST")
        private fun dispatch(method: Method, args: Array<out Any?>): Any? {
            return when (method.name) {
                "get" -> target[args[0] as Any]
                "getAll" -> target.getAll(args[0] as Collection<Any>)
                "invalidate" -> {
                    // 创建时已校验：声明 invalidate 的接口继承 JoinCache，目标是 JoinCache
                    val cache = target as JoinCache<Any, Any, Any, Any>
                    if (args.size == 1) {
                        cache.invalidate(
                            args[0] as Any
                        )
                    } else {
                        cache.invalidate(args[0] as Any, args[1] as Any)
                    }
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
