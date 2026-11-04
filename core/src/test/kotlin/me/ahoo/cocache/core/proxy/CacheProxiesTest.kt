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
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class CacheProxiesTest {
    interface UserCache : Cache<String, String> {
        fun greet(key: String): String = "hello ${get(key)}"

        override fun get(key: String): String?
    }

    interface BrokenCache : Cache<String, String> {
        fun undeclared(): String
    }

    interface IndexStore : SetStore<String, String>

    interface PolicyStore : Store<String, String>

    interface OrderCache : VersionedCache<String, String>

    interface AggregateStore : VersionedStore<String, String>

    interface ReadOnly : ReadableCache<String, String>

    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
    private val engine = runtime.create(
        cacheSpec<String, String>("user") {
            codec = StringCodec
            loader = CacheLoader { "u-$it" }
        }
    )

    @AfterEach
    fun destroy() = runtime.close()

    @Test
    fun forwardsCacheMethodsToTheEngine() {
        val cache = CacheProxies.create(UserCache::class.java, engine)
        cache.name.assert().isEqualTo("user")
        cache["1"].assert().isEqualTo("u-1")
        cache.getAll(listOf("1", "2")).assert().isEqualTo(mapOf("1" to "u-1", "2" to "u-2"))
        (cache.lookup("3") as Lookup.Present).value.assert().isEqualTo("u-3")
        (cache.lookupAll(listOf("3")).getValue("3") as Lookup.Present).value.assert().isEqualTo("u-3")
        cache.put("4", "x")
        cache["4"].assert().isEqualTo("x")
        cache.put("5", "y", Duration.ofMinutes(1))
        engine["5"].assert().isEqualTo("y")
        cache.invalidate("4")
        cache["4"].assert().isEqualTo("u-4")
        cache.invalidateAll(listOf("5"))
        cache["5"].assert().isEqualTo("u-5")
        cache.putAll(mapOf("8" to "a", "9" to "b"))
        engine.getAll(listOf("8", "9")).assert().isEqualTo(mapOf("8" to "a", "9" to "b"))
        cache.putAll(mapOf("10" to "c"), Duration.ofMinutes(1))
        engine["10"].assert().isEqualTo("c")
        cache.greet("6").assert().isEqualTo("hello u-6")
        cache.get("7") { "loaded-$it" }.assert().isEqualTo("loaded-7")
        CacheProxies.targetOf(cache).assert().isSameAs(engine)
        cache.async().assert().isSameAs(engine.async())
        cache.suspending().assert().isSameAs(engine.suspending())
        cache.async().get("1").join().assert().isEqualTo("u-1")
        CacheProxies.targetOf(engine).assert().isNull()
        CacheProxies.targetOf(
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Runnable::class.java)
            ) { _, _, _ -> null }
        ).assert().isNull()
    }

    @Test
    fun forwardsStoreMethodsToTheEngine() {
        val store = runtime.create(storeSpec<String, String>("record") { codec = StringCodec })
        val cache = CacheProxies.create(PolicyStore::class.java, store)
        val written = cache.putIfMatch("c", null, "v1") as ConditionalPut.Written
        cache.getIfChanged("c", written.digest).assert().isEqualTo(ConditionalGet.Unchanged)
        val second = cache.putIfMatch("c", written.digest, "v2", Duration.ofMinutes(1)) as ConditionalPut.Written
        cache.putIfMatch("c", written.digest, "v3").assert().isEqualTo(ConditionalPut.Conflict(second.digest))
        cache.getIfChanged("c", null).assert().isEqualTo(ConditionalGet.Present("v2", second.digest))
        cache.deleteIfMatch("c", written.digest).assert().isEqualTo(ConditionalDelete.Conflict(second.digest))
        cache.deleteIfMatch("c", second.digest).assert().isEqualTo(ConditionalDelete.Deleted)
        cache.put("p", "1")
        cache.put("q", "2", Duration.ofMinutes(1))
        cache.putAll(mapOf("r" to "3"))
        cache.putAll(mapOf("s" to "4"), Duration.ofMinutes(1))
        cache.getAll(listOf("p", "q", "r", "s")).assert().hasSize(4)
        cache.delete("p")
        cache["p"].assert().isNull()
        cache.async().assert().isSameAs(store.async())
    }

    @Test
    fun forwardsVersionedMethodsToTheEngine() {
        val versioned = runtime.create(
            versionedCacheSpec<String, String>("versioned-user") {
                codec = StringCodec
                versionedLoader = CacheLoader { null }
            }
        )
        val cache = CacheProxies.create(OrderCache::class.java, versioned)
        cache.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        cache.putIfNewer("k", "v0", 0, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Stale(1))
        cache.getVersioned("k").assert().isEqualTo(Versioned("v1", 1))
        cache.invalidateIfNewer("k", 1).assert().isEqualTo(VersionedPut.Stale(1))
        cache.invalidateIfNewer("k", 2, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        cache["k"].assert().isNull()
        cache.evict("k")
        cache.purge("k")
        cache.putIfNewer("k", "v0", 0).assert().isEqualTo(VersionedPut.Written)

        val aggregate = runtime.create(versionedStoreSpec<String, String>("aggregate") { codec = StringCodec })
        val store = CacheProxies.create(AggregateStore::class.java, aggregate)
        store.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        store.putIfNewer("k", "v2", 2, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        store.getVersioned("k").assert().isEqualTo(Versioned("v2", 2))
        store.deleteIfNewer("k", 3).assert().isEqualTo(VersionedPut.Written)
        store.deleteIfNewer("k", 4, Duration.ofMinutes(1)).assert().isEqualTo(VersionedPut.Written)
        store["k"].assert().isNull()
        store.purge("k")
    }

    @Test
    fun objectMethodsUseProxyIdentity() {
        val cache = CacheProxies.create(UserCache::class.java, engine)
        val other = CacheProxies.create(UserCache::class.java, engine)
        (cache == cache).assert().isTrue()
        (cache == other).assert().isFalse()
        cache.hashCode().assert().isEqualTo(System.identityHashCode(cache))
        cache.toString().assert().startsWith("UserCache(")
    }

    @Test
    fun exceptionsAreNotWrapped() {
        val failure = IllegalStateException("source down")
        val failing = runtime.create(
            cacheSpec<String, String>("failing") {
                codec = StringCodec
                loader = CacheLoader { throw failure }
            }
        )
        val cache = CacheProxies.create(UserCache::class.java, failing)
        runCatching { cache["k"] }.exceptionOrNull().assert().isSameAs(failure)
        runCatching { cache.greet("k") }.exceptionOrNull().assert().isSameAs(failure)
    }

    @Test
    fun setStoreMethods() {
        val set = runtime.create(setStoreSpec<String, String>("index") { elementCodec = StringCodec })
        val cache = CacheProxies.create(IndexStore::class.java, set)
        cache.add("k", listOf("a", "b"))
        cache.remove("k", listOf("a"))
        cache["k"].assert().containsExactly("b")
        cache.async().assert().isSameAs(set.async())
        cache.async().get("k").join().assert().isEqualTo(setOf("b"))
        cache.clear("k")
        cache["k"].assert().isEmpty()
        cache.put("p", setOf("x"))
        cache.put("q", setOf("y"), Duration.ofMinutes(1))
        cache.putAll(mapOf("r" to setOf("z")))
        cache.putAll(mapOf("s" to setOf("w")), Duration.ofMinutes(1))
        cache.getAll(listOf("p", "q", "r", "s")).assert().hasSize(4)
    }

    /**
     * 每个用户 API 类型的每个方法（含继承的 [ReadableCache] 的方法）都转发给目标的同名、同参数个数的方法：
     * 代理按接口的实际方法集分发（RFC 0005 §4.2），漏转发的方法在这里以 [UnsupportedOperationException] 失败。
     */
    @Test
    fun everyMethodOfEveryApiTypeIsForwarded() {
        CacheProxies.API_TYPES.forEach { apiType ->
            val calls = CopyOnWriteArrayList<String>()
            val target = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(apiType)) { _, method, _ ->
                calls.add(signatureOf(method))
                null
            } as ReadableCache<*, *>

            @Suppress("UNCHECKED_CAST")
            val proxy = CacheProxies.create(apiType as Class<ReadableCache<*, *>>, target)
            val methods = apiType.methods.filter { it.declaringClass != Any::class.java }
            methods.forEach { method ->
                val args = method.parameterTypes.map { argumentOf(it) }.toTypedArray()
                runCatching { method.invoke(proxy, *args) }.exceptionOrNull()
                    .assert().withFailMessage { "${apiType.simpleName}.${method.name} is not forwarded" }.isNull()
            }
            calls.toSet().assert().withFailMessage { "${apiType.simpleName} forwards $calls" }
                .isEqualTo(methods.map { signatureOf(it) }.toSet())
        }
    }

    private fun signatureOf(method: Method): String = "${method.name}/${method.parameterCount}"

    private fun argumentOf(type: Class<*>): Any = when {
        type == java.lang.Long.TYPE -> 1L
        Duration::class.java.isAssignableFrom(type) -> Duration.ofSeconds(1)
        Digest::class.java.isAssignableFrom(type) -> Digest(ByteArray(Digest.SIZE))
        CacheLoader::class.java.isAssignableFrom(type) -> CacheLoader<Any, Any> { null }
        Set::class.java.isAssignableFrom(type) -> setOf("v")
        Collection::class.java.isAssignableFrom(type) -> listOf("k")
        Map::class.java.isAssignableFrom(type) -> mapOf("k" to "v")
        else -> "k"
    }

    @Test
    fun rejectsInvalidDeclarations() {
        fun fails(
            block: () -> Any
        ) = runCatching(block).exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        @Suppress("UNCHECKED_CAST")
        fails { CacheProxies.create(String::class.java as Class<Cache<*, *>>, engine) }
        @Suppress("UNCHECKED_CAST")
        fails { CacheProxies.create(Runnable::class.java as Class<Cache<*, *>>, engine) }
        fails { CacheProxies.create(IndexStore::class.java, engine) }
        fails { CacheProxies.create(PolicyStore::class.java, engine) }
        fails { CacheProxies.create(ReadOnly::class.java, engine) }
        CacheProxies.apiTypeOf(OrderCache::class.java).assert().isEqualTo(VersionedCache::class.java)
        val broken = CacheProxies.create(BrokenCache::class.java, engine)
        runCatching { broken.undeclared() }.exceptionOrNull().assert().isInstanceOf(
            UnsupportedOperationException::class.java
        ).hasMessageContaining("not part of Cache")
    }
}
