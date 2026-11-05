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

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.AsyncCache
import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.AsyncReadableCache
import me.ahoo.cocache.api.AsyncSetStore
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SuspendingCache
import me.ahoo.cocache.api.SuspendingStore
import me.ahoo.cocache.api.SuspendingVersionedCache
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.UndeclaredThrowableException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * 声明视图接口的代理（RFC 0005 §4.2）：继承 `Async*` 或 `Suspending*` 的接口转发给引擎的视图，挂起方法的 `Continuation` 原样传过去。
 */
class ViewProxiesTest {
    interface UserCache : SuspendingCache<String, String> {
        suspend fun shout(key: String): String? = get(key)?.uppercase()
    }

    interface RedeclaredCache : SuspendingCache<String, String> {
        override suspend fun get(key: String): String?
    }

    interface BrokenCache : SuspendingCache<String, String> {
        suspend fun undeclared(): String
    }

    interface AsyncUsers : AsyncCache<String, String>

    /**
     * 同时继承两个可声明的类型：两个视图的返回类型冲突，以 `Nothing` 收窄才能编译。
     */
    interface Both : Cache<String, String>, me.ahoo.cocache.api.VersionedCache<String, String> {
        override fun async(): Nothing

        override fun suspending(): Nothing
    }

    interface Tokens : SuspendingStore<String, String>

    interface AsyncTokens : AsyncStore<String, String>

    interface Orders : SuspendingVersionedCache<String, String>

    interface Members : AsyncSetStore<String, String>

    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
    private val factory = CoCacheFactory(runtime)

    @AfterEach
    fun destroy() = runtime.close()

    private fun userSpec(name: String = "user") = cacheSpec<String, String>(name) {
        codec = StringCodec
        loader = CacheLoader { "u-$it" }
    }

    @Test
    fun suspendingCacheForwardsToTheSuspendingView(): Unit = runBlocking {
        val users = factory.create(UserCache::class.java, userSpec())
        val engine = requireNotNull(CacheProxies.targetOf(users)) as Cache<*, *>
        users.get("1").assert().isEqualTo("u-1")
        users.shout("1").assert().isEqualTo("U-1")
        users.put("2", "two")
        users.get("2").assert().isEqualTo("two")
        users.getAll(listOf("1", "2")).assert().isEqualTo(mapOf("1" to "u-1", "2" to "two"))
        users.invalidate("2")
        users.get("2").assert().isEqualTo("u-2")
        users.get("3") { "loaded-$it" }.assert().isEqualTo("loaded-3")
        users.name.assert().isEqualTo("user")
        users.async().assert().isSameAs(engine.async())
        users.toString().assert().isEqualTo("UserCache($engine)")
        users.assert().isEqualTo(users).isNotEqualTo(factory.create(RedeclaredCache::class.java, userSpec("other")))
        users.hashCode().assert().isEqualTo(System.identityHashCode(users))
    }

    /**
     * L2 命中经代理不挂起：挂起方法的 `Continuation` 原样传给视图，命中直接返回值。
     */
    @Test
    fun suspendingHitReturnsWithoutSuspending() {
        val users = factory.create(UserCache::class.java, userSpec())
        runBlocking { users.get("1") }
        val hit: suspend () -> String? = { users.get("1") }
        hit.startCoroutineUninterceptedOrReturn(Continuation(EmptyCoroutineContext) {}).assert().isEqualTo("u-1")
            .isNotSameAs(COROUTINE_SUSPENDED)
    }

    @Test
    fun redeclaredAbstractMethodsForwardToTheirCounterpart(): Unit = runBlocking {
        val users = factory.create(RedeclaredCache::class.java, userSpec())
        users.get("1").assert().isEqualTo("u-1")
        val broken = runCatching { factory.create(BrokenCache::class.java, userSpec("broken")) }.exceptionOrNull()
        broken.assert().isInstanceOf(IllegalStateException::class.java).hasMessageContaining("undeclared")
    }

    @Test
    fun asyncCacheForwardsToTheAsyncView() {
        val users = factory.create(AsyncUsers::class.java, userSpec())
        val engine = requireNotNull(CacheProxies.targetOf(users))
        users.get("1").join().assert().isEqualTo("u-1")
        users.put("2", "two").join()
        users.get("2").join().assert().isEqualTo("two")
        users.get("3") { CompletableFuture.completedFuture("loaded-$it") }.join().assert().isEqualTo("loaded-3")
        users.sync().assert().isSameAs(engine)
    }

    @Test
    fun storeViewsForwardToTheirViews(): Unit = runBlocking {
        val tokens = factory.create(Tokens::class.java, storeSpec<String, String>("token") { codec = StringCodec })
        tokens.put("t", "v")
        tokens.get("t").assert().isEqualTo("v")
        val asyncTokens = factory.create(
            AsyncTokens::class.java,
            storeSpec<String, String>("async-token") { codec = StringCodec }
        )
        asyncTokens.put("t", "v").join()
        asyncTokens.delete("t").join()
        asyncTokens.get("t").join().assert().isNull()
        val members = factory.create(
            Members::class.java,
            setStoreSpec<String, String>("member") { elementCodec = StringCodec }
        )
        members.add("g", listOf("a")).join()
        members.get("g").join().assert().isEqualTo(setOf("a"))
    }

    /**
     * 异常原样交给调用方：挂起视图的异常不包装，挂起之前抛出的受检异常同样是原始异常（不是 `UndeclaredThrowableException`）；
     * 异步视图的 future 以原始异常失败。
     */
    @Test
    fun exceptionsAreNotWrapped(): Unit = runBlocking {
        val users = factory.create(UserCache::class.java, userSpec())
        val failure = IllegalStateException("source down")
        runCatching { users.get("1") { throw failure } }.exceptionOrNull().assert().isSameAs(failure)
        val checked = IOException("disk")
        runCatching { users.get("2") { throw checked } }.exceptionOrNull().assert().isSameAs(checked)

        val inline = CacheProxies.create(
            UserCache::class.java,
            ThrowingEngine(runtime.create(userSpec("inline")), checked)
        )
        runCatching { inline.get("1") }.exceptionOrNull().assert().isSameAs(checked)
        runCatching { inline.shout("1") }.exceptionOrNull().assert().isSameAs(checked)

        val asyncUsers = factory.create(AsyncUsers::class.java, userSpec("async"))
        val error = runCatching {
            asyncUsers.get("1") { CompletableFuture.failedFuture(checked) }.get()
        }.exceptionOrNull()
        error.assert().isInstanceOf(ExecutionException::class.java)
        requireNotNull(error).cause.assert().isSameAs(checked)
    }

    /**
     * 挂起视图在挂起之前同步抛出受检异常的引擎：`suspending()` 的 `get` 直接抛出 [error]。
     */
    private class ThrowingEngine(private val engine: Cache<String, String>, private val error: Throwable) :
        Cache<String, String> by engine {
        override fun suspending(): SuspendingCache<String, String> = object : SuspendingCache<String, String> by engine.suspending() {
            override suspend fun get(key: String): String? = throw error
        }
    }

    @Test
    fun reentryThroughTheProxyFailsFast(): Unit = runBlocking {
        val users = factory.create(UserCache::class.java, userSpec())
        runCatching { users.get("k") { key -> users.get(key) } }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun declarationsMatchTheSpecType() {
        CacheProxies.declaredTypeOf(Orders::class.java).assert().isEqualTo(SuspendingVersionedCache::class.java)
        CacheProxies.apiTypeOf(Orders::class.java).assert().isEqualTo(me.ahoo.cocache.api.VersionedCache::class.java)
        CacheProxies.apiTypeOf(Members::class.java).assert().isEqualTo(me.ahoo.cocache.api.SetStore::class.java)
        val mismatch = runCatching { factory.create(Tokens::class.java, userSpec()) }.exceptionOrNull()
        mismatch.assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("extends SuspendingStore, but its spec declares a Cache")
        runCatching { CacheProxies.declaredTypeOf(AsyncReadableCache::class.java) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("or one of their Async* or Suspending* views")
        runCatching { CacheProxies.declaredTypeOf(Both::class.java) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("extends Cache, VersionedCache: it must extend exactly one of")
        // 绕过工厂的校验：不能转发的抽象方法在调用时失败
        val broken = CacheProxies.create(BrokenCache::class.java, runtime.create(userSpec("unchecked")))
        runCatching { runBlocking { broken.undeclared() } }.exceptionOrNull()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("not part of SuspendingCache")
    }

    /**
     * 视图同步抛出的 `Error` 原样抛出；非挂起方法同步抛出的受检异常照 JDK 代理的规则包装，原始异常是 `cause`。
     */
    @Test
    fun errorsAndSynchronousCheckedExceptionsFollowTheProxyRules() {
        val error = AssertionError("broken")
        val failing = CacheProxies.create(
            UserCache::class.java,
            ThrowingEngine(runtime.create(userSpec("error")), error)
        )
        runCatching { runBlocking { failing.get("1") } }.exceptionOrNull().assert().isSameAs(error)

        val checked = IOException("disk")
        val engine = runtime.create(userSpec("sync-checked"))
        val throwingAsync = object : Cache<String, String> by engine {
            override fun async(): AsyncCache<String, String> = object : AsyncCache<String, String> by engine.async() {
                override fun get(key: String): CompletableFuture<String?> = throw checked
            }
        }
        val asyncUsers = CacheProxies.create(AsyncUsers::class.java, throwingAsync)
        runCatching { asyncUsers.get("1") }.exceptionOrNull()
            .assert().isInstanceOf(UndeclaredThrowableException::class.java)
            .cause().isSameAs(checked)
    }

    /**
     * 每个视图类型的每个方法都以同一个方法、同样的参数转发给视图（挂起方法连同 `Continuation`）。
     */
    @Test
    fun everyMethodOfEveryViewTypeIsForwarded() {
        CacheProxies.DECLARABLE_TYPES.filter { (declared, apiType) -> declared != apiType }.forEach { (viewType, apiType) ->
            val calls = CopyOnWriteArrayList<Method>()
            val view = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(viewType)) { _, method, _ ->
                calls.add(method)
                null
            }
            val engine = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(apiType)) { _, method, _ ->
                if (method.name == "async" || method.name == "suspending") view else null
            } as ReadableCache<*, *>
            val proxy = CacheProxies.create(viewType, engine)
            val methods = viewType.methods.filter { it.declaringClass != Any::class.java }
            methods.forEach { method ->
                val args = method.parameterTypes.map { argumentOf(it) }.toTypedArray()
                runCatching { method.invoke(proxy, *args) }.exceptionOrNull()
                    .assert().withFailMessage { "${viewType.simpleName}.${method.name} is not forwarded" }.isNull()
            }
            // 协变的重写（`AsyncCache.sync()` 收窄 `AsyncReadableCache.sync()`）在 JDK 代理中是同一个方法：按名称与参数比较
            calls.map { signatureOf(it) }.toSet().assert().withFailMessage { "${viewType.simpleName} forwards $calls" }
                .isEqualTo(methods.map { signatureOf(it) }.toSet())
        }
    }

    private fun signatureOf(method: Method): String = "${method.name}${method.parameterTypes.toList()}"

    private fun argumentOf(type: Class<*>): Any = when {
        type == java.lang.Long.TYPE -> 1L
        Duration::class.java.isAssignableFrom(type) -> Duration.ofSeconds(1)
        Digest::class.java.isAssignableFrom(type) -> Digest(ByteArray(Digest.SIZE))
        AsyncCacheLoader::class.java.isAssignableFrom(type) ->
            AsyncCacheLoader<Any, Any> { CompletableFuture.completedFuture(null) }
        Continuation::class.java.isAssignableFrom(type) -> Continuation<Any?>(EmptyCoroutineContext) {}
        Function2::class.java.isAssignableFrom(type) -> { _: Any?, _: Any? -> null }
        Set::class.java.isAssignableFrom(type) -> setOf("v")
        Collection::class.java.isAssignableFrom(type) -> listOf("k")
        Map::class.java.isAssignableFrom(type) -> mapOf("k" to "v")
        else -> "k"
    }
}
