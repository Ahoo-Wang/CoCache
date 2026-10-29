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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.Digest
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * 接口式声明的代理（RFC 0003 §4.12）：用户接口继承 [Cache] / [SetCache]，代理把调用转发给引擎。
 *
 * - [Cache] / [SetCache] 的方法直接调用引擎（按方法名分派，不经 `Method.invoke`），异常原样抛出。
 * - 用户接口的 default 方法经 [InvocationHandler.invokeDefault] 执行。
 * - `equals` / `hashCode` 按代理对象身份；`toString` 显示接口与引擎。
 */
object CacheProxies {
    /**
     * 为 [type] 创建代理。[target] 必须实现 [type] 继承的缓存接口（继承 [SetCache] 时必须是 [SetCache]）。
     */
    @JvmStatic
    fun <C : Cache<*, *>> create(type: Class<C>, target: Cache<*, *>): C {
        require(type.isInterface) { "${type.name} must be an interface." }
        require(Cache::class.java.isAssignableFrom(type)) { "${type.name} must extend ${Cache::class.java.name}." }
        require(!SetCache::class.java.isAssignableFrom(type) || target is SetCache<*, *>) {
            "${type.name} extends ${SetCache::class.java.simpleName}, but the target is not a set cache."
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type), Handler(type, target as Cache<Any, Any>)) as C
    }

    /**
     * 代理背后的引擎；不是本工厂创建的代理时返回 `null`。
     */
    @JvmStatic
    fun targetOf(proxy: Any): Cache<*, *>? {
        if (!Proxy.isProxyClass(proxy.javaClass)) {
            return null
        }
        return (Proxy.getInvocationHandler(proxy) as? Handler)?.target
    }

    private class Handler(private val type: Class<*>, val target: Cache<Any, Any>) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: NO_ARGS
            if (method.declaringClass == Any::class.java) {
                return objectMethod(proxy, method, arguments)
            }
            if (method.isDefault && !isCacheMethod(method)) {
                return InvocationHandler.invokeDefault(proxy, method, *arguments)
            }
            return dispatch(method, arguments)
        }

        private fun isCacheMethod(method: Method): Boolean {
            return method.declaringClass == Cache::class.java || method.declaringClass == SetCache::class.java
        }

        @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod")
        private fun dispatch(method: Method, args: Array<out Any?>): Any? {
            return when (method.name) {
                "get" -> get(args)
                "getAll" -> target.getAll(args[0] as Collection<Any>)
                "lookup" -> target.lookup(args[0] as Any)
                "put" -> put(args)
                "putAll" -> putAll(args)
                "invalidate" -> target.invalidate(args[0] as Any)
                "invalidateAll" -> target.invalidateAll(args[0] as Collection<Any>)
                "getIfChanged" -> target.getIfChanged(args[0] as Any, args[1] as Digest?)
                "putIfMatch" -> putIfMatch(args)
                "putIfNewer" -> putIfNewer(args)
                "invalidateIfNewer" -> invalidateIfNewer(args)
                "getVersioned" -> target.getVersioned(args[0] as Any)
                "getName" -> target.name
                "async" -> target.async()
                "add" -> (target as SetCache<Any, Any>).add(args[0] as Any, args[1] as Collection<Any>)
                "remove" -> (target as SetCache<Any, Any>).remove(args[0] as Any, args[1] as Collection<Any>)
                "clear" -> (target as SetCache<Any, Any>).clear(args[0] as Any)
                else -> throw UnsupportedOperationException(
                    "${type.name}.${method.name} is abstract and not part of ${Cache::class.java.simpleName}; declare it as a default method."
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun get(args: Array<out Any?>): Any? {
            if (args.size == 1) {
                return target.get(args[0] as Any)
            }
            return target.get(args[0] as Any, args[1] as CacheLoader<Any, Any>)
        }

        private fun put(args: Array<out Any?>) {
            if (args.size == 2) {
                target.put(args[0] as Any, args[1] as Any)
            } else {
                target.put(args[0] as Any, args[1] as Any, args[2] as Duration)
            }
        }

        private fun putIfMatch(args: Array<out Any?>): Any {
            if (args.size == 3) {
                return target.putIfMatch(args[0] as Any, args[1] as Digest?, args[2] as Any)
            }
            return target.putIfMatch(args[0] as Any, args[1] as Digest?, args[2] as Any, args[3] as Duration)
        }

        private fun putIfNewer(args: Array<out Any?>): Any {
            if (args.size == 3) {
                return target.putIfNewer(args[0] as Any, args[1] as Any, args[2] as Long)
            }
            return target.putIfNewer(args[0] as Any, args[1] as Any, args[2] as Long, args[3] as Duration)
        }

        private fun invalidateIfNewer(args: Array<out Any?>): Any {
            if (args.size == 2) {
                return target.invalidateIfNewer(args[0] as Any, args[1] as Long)
            }
            return target.invalidateIfNewer(args[0] as Any, args[1] as Long, args[2] as Duration)
        }

        @Suppress("UNCHECKED_CAST")
        private fun putAll(args: Array<out Any?>) {
            if (args.size == 1) {
                target.putAll(args[0] as Map<Any, Any>)
            } else {
                target.putAll(args[0] as Map<Any, Any>, args[1] as Duration)
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
