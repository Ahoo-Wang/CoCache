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

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * 代理接口的 default 方法怎样执行，创建代理之前为每个 default 方法确定（`CacheProxies`、`JoinCacheProxies` 共用）。
 *
 * - 声明 default 方法的接口对 CoCache 可访问（`public`，所在的包对 CoCache 导出）时经 [InvocationHandler.invokeDefault] 执行。
 * - 否则（包可见的接口，或 `public` 接口继承的包可见基接口）`invokeDefault` 会以 `IllegalAccessException` 失败：它按调用方
 *   （CoCache 的类）检查声明接口的可访问性，与最终的代理接口是否 `public` 无关。这时经声明接口的私有查找
 *   （[MethodHandles.privateLookupIn]）取得 `invokespecial` 的方法句柄，创建时取得、之后每次调用复用。类路径上的包对所有模块开放；
 *   具名模块中的接口要把包 `opens` 给 CoCache 的模块，否则创建时抛出 [IllegalArgumentException]，不创建任何东西。
 *
 * 两种方式都原样抛出 default 方法自己的异常（I-14）：`invokeDefault` 解开包装，方法句柄本来就不包装。
 */
internal class DefaultMethods private constructor(private val handles: Map<Method, MethodHandle>) {
    /**
     * 以 [arguments] 在 [proxy] 上执行 default 方法 [method]。
     */
    fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>): Any? {
        val handle = handles[method] ?: return InvocationHandler.invokeDefault(proxy, method, *arguments)
        return handle.invokeExact(proxy, arguments)
    }

    companion object {
        /**
         * 方法句柄的调用形式：接收者与参数数组，返回 `Object`（基本类型装箱，`void` 为 `null`）。
         */
        private val SPREAD_TYPE = MethodType.methodType(Any::class.java, Any::class.java, Array<Any?>::class.java)

        /**
         * [type] 的全部 default 方法（包括继承的）的执行方式。[lookup] 是 CoCache 自己的查找（测试可以换成权限更小的查找）：
         * 它能访问声明接口时经 `invokeDefault`，否则经声明接口的私有查找。
         *
         * @throws IllegalArgumentException 声明接口不可访问，且不能取得它的私有查找（具名模块没有把包开放给 CoCache）
         */
        fun of(type: Class<*>, lookup: MethodHandles.Lookup = MethodHandles.lookup()): DefaultMethods {
            val handles = HashMap<Method, MethodHandle>()
            type.methods.filter { it.isDefault && !accessible(lookup, it.declaringClass) }.forEach { method ->
                handles[method] = handleOf(type, method, lookup)
            }
            return DefaultMethods(handles)
        }

        private fun accessible(lookup: MethodHandles.Lookup, declaring: Class<*>): Boolean =
            runCatching { lookup.accessClass(declaring) }.isSuccess

        private fun handleOf(type: Class<*>, method: Method, lookup: MethodHandles.Lookup): MethodHandle {
            val declaring = method.declaringClass
            val special = try {
                MethodHandles.privateLookupIn(declaring, lookup).unreflectSpecial(method, declaring)
            } catch (e: IllegalAccessException) {
                throw IllegalArgumentException(
                    "Cache interface[${type.name}] inherits the default method ${method.name} from ${declaring.name}, " +
                        "which CoCache cannot access: make ${declaring.name} public, or open its package to CoCache's module.",
                    e
                )
            }
            return special.asFixedArity()
                .asSpreader(Array<Any?>::class.java, method.parameterCount)
                .asType(SPREAD_TYPE)
        }
    }
}
