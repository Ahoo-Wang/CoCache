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
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

class CacheProxiesTest {
    interface UserCache : Cache<String, String> {
        fun greet(key: String): String = "hello ${get(key)}"

        override fun get(key: String): String?
    }

    interface BrokenCache : Cache<String, String> {
        fun undeclared(): String
    }

    interface IndexCache : SetCache<String, String>

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
        CacheProxies.targetOf(engine).assert().isNull()
        CacheProxies.targetOf(
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Runnable::class.java)
            ) { _, _, _ -> null }
        ).assert().isNull()
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
    fun setCacheMethods() {
        val set = runtime.create(setCacheSpec<String, String>("index") { elementCodec = StringCodec })
        val cache = CacheProxies.create(IndexCache::class.java, set)
        cache.add("k", listOf("a", "b"))
        cache.remove("k", listOf("a"))
        cache["k"].assert().containsExactly("b")
        cache.clear("k")
        cache["k"].assert().isEmpty()
        runCatching {
            cache.get("k") { setOf("x") }
        }.exceptionOrNull().assert().isInstanceOf(UnsupportedOperationException::class.java)
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
        fails { CacheProxies.create(IndexCache::class.java, engine) }
        val broken = CacheProxies.create(BrokenCache::class.java, engine)
        runCatching { broken.undeclared() }.exceptionOrNull().assert().isInstanceOf(
            UnsupportedOperationException::class.java
        )
    }
}
