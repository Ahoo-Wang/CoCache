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

import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncReadableCache
import me.ahoo.cocache.api.AsyncSetStore
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.AsyncVersionedCache
import me.ahoo.cocache.api.AsyncVersionedStore
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.SuspendingCache
import me.ahoo.cocache.api.SuspendingReadableCache
import me.ahoo.cocache.api.SuspendingSetStore
import me.ahoo.cocache.api.SuspendingStore
import me.ahoo.cocache.api.SuspendingVersionedCache
import me.ahoo.cocache.api.SuspendingVersionedStore
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.Digest
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * 接口式声明的代理（RFC 0003 §4.12）：用户接口继承一个可声明的类型（[DECLARABLE_TYPES]：五种用户 API 类型与它们的异步、挂起视图），
 * 代理把调用转发给引擎或引擎的视图。
 *
 * - 同步类型（[API_TYPES]）按用户接口继承的那个 API 类型分发（RFC 0005 §4.2）：每个类型有自己的转发表，只转发这个类型的方法；
 *   表中的每个方法都直接调用引擎（按方法名与参数个数分派，不经 `Method.invoke`），异常原样抛出。例外是 JDK 代理自身的规则：接口方法
 *   没有声明的受检异常（如 Kotlin loader 抛出的 `IOException`）由 JDK 包装为 [java.lang.reflect.UndeclaredThrowableException]，
 *   原始异常是它的 `cause`。
 * - 视图类型（`Async*`、`Suspending*`）转发给引擎的 `async()` / `suspending()` 视图：同一个方法、同样的参数，挂起方法的
 *   `Continuation` 原样传过去，所以挂起视图走协程核心的原生路径，L2 命中不挂起、直接返回。挂起视图的读取经绑定到视图的方法句柄调用，
 *   视图的命中路径不内联进调用方（[ReadHandles]），其余方法经 `Method.invoke`。异常原样抛出；挂起方法在挂起之前抛出的
 *   受检异常经调用方的 `Continuation` 交还（否则会被 JDK 包装），调用方看到的仍是原始异常。
 * - 用户接口的 default 方法经 [DefaultMethods] 执行（API 类型没有 default 方法）：声明它的接口可访问时经
 *   [InvocationHandler.invokeDefault]，包可见时（包括 `public` 接口继承的包可见基接口）经声明接口的私有查找取得的方法句柄。
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
     * 接口式声明可以继承的类型（RFC 0005 §4.2）：五种用户 API 类型与它们的异步、挂起视图。值是对应的用户 API 类型（[API_TYPES] 之一），
     * 决定 spec 的类型，如 `SuspendingStore` 对应 `Store`，即 `StoreSpec`。
     */
    @JvmField
    val DECLARABLE_TYPES: Map<Class<*>, Class<*>> = linkedMapOf(
        Cache::class.java to Cache::class.java,
        AsyncCache::class.java to Cache::class.java,
        SuspendingCache::class.java to Cache::class.java,
        VersionedCache::class.java to VersionedCache::class.java,
        AsyncVersionedCache::class.java to VersionedCache::class.java,
        SuspendingVersionedCache::class.java to VersionedCache::class.java,
        Store::class.java to Store::class.java,
        AsyncStore::class.java to Store::class.java,
        SuspendingStore::class.java to Store::class.java,
        VersionedStore::class.java to VersionedStore::class.java,
        AsyncVersionedStore::class.java to VersionedStore::class.java,
        SuspendingVersionedStore::class.java to VersionedStore::class.java,
        SetStore::class.java to SetStore::class.java,
        AsyncSetStore::class.java to SetStore::class.java,
        SuspendingSetStore::class.java to SetStore::class.java,
    )

    /**
     * 声明接口继承的那个可声明类型（[DECLARABLE_TYPES] 的键之一）。不是接口、一个都没有继承或继承了多个时抛出 [IllegalArgumentException]。
     */
    @JvmStatic
    fun declaredTypeOf(type: Class<*>): Class<*> {
        require(type.isInterface) { "${type.name} must be an interface." }
        val extended = DECLARABLE_TYPES.keys.filter { it.isAssignableFrom(type) }
        require(extended.size == 1) {
            val names = API_TYPES.joinToString { it.simpleName }
            val expected = "exactly one of $names or one of their Async* or Suspending* views"
            if (extended.isEmpty()) {
                "${type.name} must extend $expected."
            } else {
                "${type.name} extends ${extended.joinToString { it.simpleName }}: it must extend $expected."
            }
        }
        return extended.single()
    }

    /**
     * 声明接口对应的用户 API 类型（[API_TYPES] 之一），决定 spec 的类型：同步接口是它继承的那个类型，视图接口是视图所属的类型
     * （`SuspendingStore` → `Store`）。校验同 [declaredTypeOf]。
     */
    @JvmStatic
    fun apiTypeOf(type: Class<*>): Class<*> = DECLARABLE_TYPES.getValue(declaredTypeOf(type))

    /**
     * 在创建缓存之前为 [type] 生成代理类（#879）：JDK 动态代理自己的约束（sealed 或隐藏的接口、类加载器可见性等）在这里失败，
     * 这时缓存还不存在。JDK 按类加载器与接口列表缓存生成的代理类，随后的 [create] 复用它；生成的实例不绑定缓存，随即丢弃。
     */
    @JvmStatic
    internal fun prepare(type: Class<*>) {
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), UNBOUND)
    }

    /**
     * [prepare] 生成的实例的处理器：实例从不交出，不会被调用。
     */
    private val UNBOUND = InvocationHandler { _, method, _ -> error("Unbound cache proxy: $method.") }

    /**
     * 为 [type] 创建代理。[target] 是缓存（引擎），必须实现 [type] 对应的用户 API 类型（[apiTypeOf]）；视图接口转发给它的视图。
     * 公开的入口是 [me.ahoo.cocache.core.CoCacheFactory.create]（`type, spec`）：它在创建缓存之前校验接口、确定 default 方法的执行方式
     * （[defaults]）并生成代理类（[prepare]）。
     */
    @JvmStatic
    internal fun <C : Any> create(
        type: Class<C>,
        target: ReadableCache<*, *>,
        defaults: DefaultMethods = DefaultMethods.of(type),
    ): C {
        val declared = declaredTypeOf(type)
        val apiType = DECLARABLE_TYPES.getValue(declared)
        require(apiType.isInstance(target)) {
            "${type.name} extends ${declared.simpleName}, but the target $target is not a ${apiType.simpleName}."
        }
        val handler: CacheHandler = when {
            declared == apiType -> Handler(type, dispatcherOf(target), defaults)
            else -> ViewHandler(type, target, viewOf(target, declared), declared, defaults)
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as C
    }

    /**
     * 视图接口转发的视图：`Async*` 是 `async()`，`Suspending*` 是 `suspending()`。
     */
    private fun viewOf(target: ReadableCache<*, *>, declared: Class<*>): Any =
        if (AsyncReadableCache::class.java.isAssignableFrom(declared)) target.async() else target.suspending()

    /**
     * 代理背后的缓存（引擎，视图接口的代理也返回引擎本身）；不是本工厂创建的代理时返回 `null`。
     */
    @JvmStatic
    fun targetOf(proxy: Any): ReadableCache<*, *>? {
        if (!Proxy.isProxyClass(proxy.javaClass)) {
            return null
        }
        return (Proxy.getInvocationHandler(proxy) as? CacheHandler)?.target
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

    /**
     * 两种代理的共同部分：代理背后的缓存（[targetOf]）。
     */
    private sealed interface CacheHandler : InvocationHandler {
        val target: ReadableCache<*, *>
    }

    private class Handler(
        private val type: Class<*>,
        val dispatcher: Dispatcher,
        private val defaults: DefaultMethods,
    ) : CacheHandler {
        override val target: ReadableCache<*, *>
            get() = dispatcher.target

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: NO_ARGS
            if (method.declaringClass == Any::class.java) {
                return objectMethod(type, target, proxy, method, arguments)
            }
            if (method.isDefault) {
                return defaults.invoke(proxy, method, arguments)
            }
            val result = dispatcher.dispatch(method.name, arguments)
            return if (result === NOT_FORWARDED) unsupported(method) else result
        }

        private fun unsupported(method: Method): Nothing = throw UnsupportedOperationException(
            "${type.name}.${method.name} is abstract and not part of ${dispatcher.apiType}; declare it as a default method."
        )
    }

    /**
     * 视图接口（`Async*`、`Suspending*`）的代理：API 类型的方法以同样的参数调用 [view] 上的同一个方法（挂起方法的 `Continuation`
     * 原样传过去）；用户接口重新声明的抽象方法调用与它匹配的视图方法（[CacheMethods.counterpartOf]，创建时已校验）。
     * 挂起视图的读取经 [ReadHandles] 的方法句柄调用，其余方法经 `Method.invoke`。
     */
    private class ViewHandler(
        private val type: Class<*>,
        override val target: ReadableCache<*, *>,
        private val view: Any,
        private val viewType: Class<*>,
        private val defaults: DefaultMethods,
    ) : CacheHandler {
        private val redeclared = ConcurrentHashMap<Method, Method>()
        private val reads = ReadHandles.of(view, viewType)

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: NO_ARGS
            val declaring = method.declaringClass
            if (declaring == Any::class.java) {
                return objectMethod(type, target, proxy, method, arguments)
            }
            if (declaring.isAssignableFrom(viewType)) {
                return forward(method, arguments)
            }
            if (method.isDefault) {
                return defaults.invoke(proxy, method, arguments)
            }
            return forward(redeclared.computeIfAbsent(method) { counterpartOf(it) }, arguments)
        }

        private fun counterpartOf(method: Method): Method = CacheMethods.counterpartOf(method, viewType)
            ?: throw UnsupportedOperationException(
                "${type.name}.${method.name} is abstract and not part of ${viewType.simpleName}; declare it as a default method."
            )

        /**
         * 异常路径只把最后一个参数（挂起方法的 `Continuation`）交给 [rethrow]，不传参数数组：C2 的逃逸分析不区分路径，数组一旦传给
         * 不内联的调用（[rethrow] 太大，不内联），代理每次调用都要分配它。
         */
        private fun forward(method: Method, arguments: Array<out Any?>): Any? {
            val read = reads?.handleOf(method.name, arguments.size)
            if (read != null) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    return ReadHandles.invoke(read, arguments)
                } catch (e: Throwable) {
                    return rethrow(e, method, arguments[arguments.size - 1])
                }
            }
            try {
                return method.invoke(view, *arguments)
            } catch (e: InvocationTargetException) {
                val last = if (arguments.isEmpty()) null else arguments[arguments.size - 1]
                return rethrow(e.targetException, method, last)
            }
        }

        /**
         * 原样抛出视图的异常。挂起方法在挂起之前抛出的受检异常不能直接抛出（接口方法没有声明它，JDK 会把它包装为
         * `UndeclaredThrowableException`）：改经调用方的 `Continuation`（挂起方法的最后一个参数 [last]）交还并报告挂起，
         * 调用方收到的仍是原始异常。
         */
        private fun rethrow(error: Throwable, method: Method, last: Any?): Any? {
            if (error is RuntimeException || error is Error || method.parameterTypes.lastOrNull() != Continuation::class.java) {
                throw error
            }
            @Suppress("UNCHECKED_CAST")
            val continuation = last as Continuation<Any?>
            continuation.resumeWith(Result.failure(error))
            return COROUTINE_SUSPENDED
        }
    }

    /**
     * 挂起视图中 L2 命中可以直接返回的读取（`get`、带 loader 的 `get`、`getAll`、`lookup`、`lookupAll`、`getVersioned`），每个绑定到
     * 视图的方法句柄，创建代理时取得。
     *
     * 经方法句柄调用是有意的编译边界：句柄是实例字段，不是编译期常量，C2 不会把视图的方法内联进代理的调用方，视图的命中路径编译在
     * 自己的方法中，内联预算与调用方的深度无关；处理器自己的编译结果保持很小，仍会内联进代理方法，参数数组被标量替换、不分配。
     * `Method.invoke` 在 `Method` 是常量时会一路内联到视图：调用方本身较深时（挂起调用经协程的启动与状态机进入代理），代理多出的
     * 帧把 Caffeine 查找推过 C2 的内联深度上限（`MaxInlineLevel`），查找中的十几个小方法变成真正的调用，命中多出约 10 ns
     * （`docs/investigations/jmh-8.0-ga.md`）。写入总要访问 L1，经 `Method.invoke` 的成本可以忽略。
     */
    private class ReadHandles(view: Any, viewType: Class<*>) {
        private val get = bind(view, viewType, "get", 2)
        private val getLoading = bind(view, viewType, "get", 3)
        private val getAll = bind(view, viewType, "getAll", 2)
        private val lookup = bind(view, viewType, "lookup", 2)
        private val lookupAll = bind(view, viewType, "lookupAll", 2)
        private val getVersioned = bind(view, viewType, "getVersioned", 2)

        /**
         * 方法名与参数个数（含 `Continuation`）对应的读取；不是读取，或视图类型没有这个方法时为 `null`。
         */
        fun handleOf(name: String, parameterCount: Int): MethodHandle? = when (parameterCount) {
            2 -> when (name) {
                "get" -> get
                "getAll" -> getAll
                "lookup" -> lookup
                "lookupAll" -> lookupAll
                "getVersioned" -> getVersioned
                else -> null
            }
            3 -> if (name == "get") getLoading else null
            else -> null
        }

        companion object {
            /**
             * 以代理收到的参数调用 [handle]：只有两种参数个数（[handleOf]），各用一个精确类型的调用点，参数不经数组展开。
             */
            fun invoke(handle: MethodHandle, arguments: Array<out Any?>): Any? = if (arguments.size == 2) {
                handle.invokeExact(arguments[0], arguments[1])
            } else {
                handle.invokeExact(arguments[0], arguments[1], arguments[2])
            }

            /**
             * 挂起视图的读取；异步视图不用：它的调用方不经协程的启动与状态机，经 `Method.invoke` 转发实测只多 0.7 ns（`l2HitViaAsyncInterface`）。
             */
            fun of(view: Any, viewType: Class<*>): ReadHandles? {
                val suspending = SuspendingReadableCache::class.java.isAssignableFrom(viewType)
                return if (suspending) ReadHandles(view, viewType) else null
            }

            /**
             * [viewType] 中名为 [name]、有 [parameterCount] 个参数的方法，绑定到 [view]，类型擦除为全部 `Object`；没有时为 `null`。
             */
            private fun bind(view: Any, viewType: Class<*>, name: String, parameterCount: Int): MethodHandle? {
                val method = viewType.methods.firstOrNull { it.name == name && it.parameterCount == parameterCount } ?: return null
                val handle = MethodHandles.publicLookup().unreflect(method).bindTo(view)
                return handle.asType(MethodType.genericMethodType(parameterCount))
            }
        }
    }

    private fun objectMethod(type: Class<*>, target: ReadableCache<*, *>, proxy: Any, method: Method, args: Array<out Any?>): Any {
        return when (method.name) {
            "equals" -> proxy === args[0]
            "hashCode" -> System.identityHashCode(proxy)
            else -> "${type.simpleName}($target)"
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
