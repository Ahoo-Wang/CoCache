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
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.Digest
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * 接口式声明的代理（RFC 0003 §4.12）：用户接口继承五种用户 API 类型之一（[API_TYPES]），代理把调用转发给引擎。
 *
 * - 按用户接口继承的那个 API 类型分发（RFC 0005 §4.2）：每个类型有自己的转发表，只转发这个类型的方法；表中的每个方法都直接调用引擎
 *   （按方法名与参数个数分派，不经 `Method.invoke`），异常原样抛出。例外是 JDK 代理自身的规则：接口方法没有声明的受检异常
 *   （如 Kotlin loader 抛出的 `IOException`）由 JDK 包装为 [java.lang.reflect.UndeclaredThrowableException]，原始异常是它的 `cause`。
 * - 用户接口的 default 方法经 [InvocationHandler.invokeDefault] 执行（API 类型没有 default 方法）。
 * - `equals` / `hashCode` 按代理对象身份；`toString` 显示接口与引擎。
 */
object CacheProxies {
    /**
     * 可以被声明接口继承的用户 API 类型。`ReadableCache` 不在其中：它没有写入，不对应任何一种 spec。
     */
    @JvmField
    val API_TYPES: List<Class<*>> = listOf(
        Cache::class.java,
        VersionedCache::class.java,
        Store::class.java,
        VersionedStore::class.java,
        SetStore::class.java,
    )

    /**
     * 声明接口继承的那个用户 API 类型（[API_TYPES] 之一）。不是接口、一个都没有继承或继承了多个时抛出 [IllegalArgumentException]。
     */
    @JvmStatic
    fun apiTypeOf(type: Class<*>): Class<*> {
        require(type.isInterface) { "${type.name} must be an interface." }
        val extended = API_TYPES.filter { it.isAssignableFrom(type) }
        require(extended.size == 1) {
            val names = API_TYPES.joinToString { it.simpleName }
            if (extended.isEmpty()) {
                "${type.name} must extend exactly one of $names."
            } else {
                "${type.name} extends ${extended.joinToString { it.simpleName }}: it must extend exactly one of $names."
            }
        }
        return extended.single()
    }

    /**
     * 为 [type] 创建代理。[target] 必须实现 [type] 继承的用户 API 类型（[apiTypeOf]）。
     */
    @JvmStatic
    fun <C : ReadableCache<*, *>> create(type: Class<C>, target: ReadableCache<*, *>): C {
        val apiType = apiTypeOf(type)
        require(apiType.isInstance(target)) {
            "${type.name} extends ${apiType.simpleName}, but the target $target is not a ${apiType.simpleName}."
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type), Handler(type, dispatcherOf(target))) as C
    }

    /**
     * 代理背后的引擎；不是本工厂创建的代理时返回 `null`。
     */
    @JvmStatic
    fun targetOf(proxy: Any): ReadableCache<*, *>? {
        if (!Proxy.isProxyClass(proxy.javaClass)) {
            return null
        }
        return (Proxy.getInvocationHandler(proxy) as? Handler)?.dispatcher?.target
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatcherOf(target: ReadableCache<*, *>): Dispatcher = when (target) {
        is Cache<*, *> -> CacheDispatcher(target as Cache<Any, Any>)
        is VersionedCache<*, *> -> VersionedCacheDispatcher(target as VersionedCache<Any, Any>)
        is Store<*, *> -> StoreDispatcher(target as Store<Any, Any>)
        is VersionedStore<*, *> -> VersionedStoreDispatcher(target as VersionedStore<Any, Any>)
        is SetStore<*, *> -> SetStoreDispatcher(target as SetStore<Any, Any>)
        else -> throw IllegalArgumentException("Unsupported cache type: ${target.javaClass.name}.")
    }

    private class Handler(private val type: Class<*>, val dispatcher: Dispatcher) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: NO_ARGS
            if (method.declaringClass == Any::class.java) {
                return objectMethod(proxy, method, arguments)
            }
            if (method.isDefault) {
                return InvocationHandler.invokeDefault(proxy, method, *arguments)
            }
            val result = dispatcher.dispatch(method.name, arguments)
            return if (result === NOT_FORWARDED) unsupported(method) else result
        }

        private fun unsupported(method: Method): Nothing = throw UnsupportedOperationException(
            "${type.name}.${method.name} is abstract and not part of ${dispatcher.apiType}; declare it as a default method."
        )

        private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>): Any {
            return when (method.name) {
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> "${type.simpleName}(${dispatcher.target})"
            }
        }
    }

    /**
     * 一个用户 API 类型的转发表。
     */
    private abstract class Dispatcher(val target: ReadableCache<Any, Any>, val apiType: String) {
        /**
         * 转发 [ReadableCache] 的方法与 [forward] 的方法，返回结果（`void` 方法为 [Unit]）；不是这个类型的方法时返回 [NOT_FORWARDED]。
         */
        @Suppress("UNCHECKED_CAST")
        fun dispatch(name: String, args: Array<out Any?>): Any? = when (name) {
            "getName" -> target.name
            "getAll" -> target.getAll(args[0] as Collection<Any>)
            "lookup" -> target.lookup(args[0] as Any)
            "lookupAll" -> target.lookupAll(args[0] as Collection<Any>)
            "async" -> target.async()
            "suspending" -> target.suspending()
            else -> forward(name, args)
        }

        abstract fun forward(name: String, args: Array<out Any?>): Any?
    }

    private class CacheDispatcher(private val cache: Cache<Any, Any>) : Dispatcher(cache, "Cache") {
        @Suppress("UNCHECKED_CAST")
        override fun forward(name: String, args: Array<out Any?>): Any? = when (name) {
            "get" -> if (args.size == 1) {
                cache[args[0] as Any]
            } else {
                cache.get(
                    args[0] as Any,
                    args[1] as CacheLoader<Any, Any>
                )
            }
            "put" -> if (args.size == 2) {
                cache.put(args[0] as Any, args[1] as Any)
            } else {
                cache.put(args[0] as Any, args[1] as Any, args[2] as Duration)
            }
            "putAll" -> if (args.size == 1) {
                cache.putAll(args[0] as Map<Any, Any>)
            } else {
                cache.putAll(args[0] as Map<Any, Any>, args[1] as Duration)
            }
            "invalidate" -> cache.invalidate(args[0] as Any)
            "invalidateAll" -> cache.invalidateAll(args[0] as Collection<Any>)
            else -> NOT_FORWARDED
        }
    }

    private class VersionedCacheDispatcher(private val cache: VersionedCache<Any, Any>) : Dispatcher(
        cache,
        "VersionedCache"
    ) {
        override fun forward(name: String, args: Array<out Any?>): Any? = when (name) {
            "get" -> cache[args[0] as Any]
            "getVersioned" -> cache.getVersioned(args[0] as Any)
            "putIfNewer" -> if (args.size == 3) {
                cache.putIfNewer(args[0] as Any, args[1] as Any, args[2] as Long)
            } else {
                cache.putIfNewer(args[0] as Any, args[1] as Any, args[2] as Long, args[3] as Duration)
            }
            "invalidateIfNewer" -> if (args.size == 2) {
                cache.invalidateIfNewer(args[0] as Any, args[1] as Long)
            } else {
                cache.invalidateIfNewer(args[0] as Any, args[1] as Long, args[2] as Duration)
            }
            "evict" -> cache.evict(args[0] as Any)
            "purge" -> cache.purge(args[0] as Any)
            else -> NOT_FORWARDED
        }
    }

    private class StoreDispatcher(private val store: Store<Any, Any>) : Dispatcher(store, "Store") {
        @Suppress("UNCHECKED_CAST")
        override fun forward(name: String, args: Array<out Any?>): Any? = when (name) {
            "get" -> store[args[0] as Any]
            "put" -> if (args.size == 2) {
                store.put(args[0] as Any, args[1] as Any)
            } else {
                store.put(args[0] as Any, args[1] as Any, args[2] as Duration)
            }
            "putAll" -> if (args.size == 1) {
                store.putAll(args[0] as Map<Any, Any>)
            } else {
                store.putAll(args[0] as Map<Any, Any>, args[1] as Duration)
            }
            "delete" -> store.delete(args[0] as Any)
            "deleteIfMatch" -> store.deleteIfMatch(args[0] as Any, args[1] as Digest)
            "getIfChanged" -> store.getIfChanged(args[0] as Any, args[1] as Digest?)
            "putIfMatch" -> if (args.size == 3) {
                store.putIfMatch(args[0] as Any, args[1] as Digest?, args[2] as Any)
            } else {
                store.putIfMatch(args[0] as Any, args[1] as Digest?, args[2] as Any, args[3] as Duration)
            }
            else -> NOT_FORWARDED
        }
    }

    private class VersionedStoreDispatcher(private val store: VersionedStore<Any, Any>) : Dispatcher(
        store,
        "VersionedStore"
    ) {
        override fun forward(name: String, args: Array<out Any?>): Any? = when (name) {
            "get" -> store[args[0] as Any]
            "getVersioned" -> store.getVersioned(args[0] as Any)
            "putIfNewer" -> if (args.size == 3) {
                store.putIfNewer(args[0] as Any, args[1] as Any, args[2] as Long)
            } else {
                store.putIfNewer(args[0] as Any, args[1] as Any, args[2] as Long, args[3] as Duration)
            }
            "deleteIfNewer" -> if (args.size == 2) {
                store.deleteIfNewer(args[0] as Any, args[1] as Long)
            } else {
                store.deleteIfNewer(args[0] as Any, args[1] as Long, args[2] as Duration)
            }
            "purge" -> store.purge(args[0] as Any)
            else -> NOT_FORWARDED
        }
    }

    @Suppress("UNCHECKED_CAST")
    private class SetStoreDispatcher(private val store: SetStore<Any, Any>) :
        Dispatcher(store as ReadableCache<Any, Any>, "SetStore") {
        override fun forward(name: String, args: Array<out Any?>): Any? = when (name) {
            "get" -> store[args[0] as Any]
            "add" -> store.add(args[0] as Any, args[1] as Collection<Any>)
            "remove" -> store.remove(args[0] as Any, args[1] as Collection<Any>)
            "clear" -> store.clear(args[0] as Any)
            "put" -> if (args.size == 2) {
                store.put(args[0] as Any, args[1] as Set<Any>)
            } else {
                store.put(args[0] as Any, args[1] as Set<Any>, args[2] as Duration)
            }
            "putAll" -> if (args.size == 1) {
                store.putAll(args[0] as Map<Any, Set<Any>>)
            } else {
                store.putAll(args[0] as Map<Any, Set<Any>>, args[1] as Duration)
            }
            else -> NOT_FORWARDED
        }
    }

    private val NO_ARGS = emptyArray<Any?>()

    /**
     * 转发表中没有这个方法（不是 `null`：`get` 的结果可以是 `null`）。
     */
    private val NOT_FORWARDED = Any()
}
