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

import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cocache.api.join.JoinCache
import me.ahoo.cocache.api.join.JoinValue
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class JoinCacheProxiesTest {
    interface UserJoinCache : JoinCache<String, String, String, String> {
        fun secondOf(key: String): String? = get(key)?.secondValue

        override fun get(key: String): JoinValue<String, String, String>?

        override fun invalidate(firstKey: String, joinKey: String)
    }

    interface NarrowedJoinCache : JoinCache<String, String, String, String> {
        override fun getAll(keys: Collection<String>): HashMap<String, JoinValue<String, String, String>>
    }

    interface UnknownAbstractJoinCache : JoinCache<String, String, String, String> {
        fun undeclared(): String
    }

    interface WrongArityJoinCache : JoinCache<String, String, String, String> {
        fun get(key: String, other: String): JoinValue<String, String, String>?
    }

    interface VoidReturnJoinCache : JoinCache<String, String, String, String> {
        fun getAll(keys: List<String>)
    }

    interface NonVoidInvalidateJoinCache : JoinCache<String, String, String, String> {
        fun invalidate(key: Int): Boolean
    }

    interface PrimitiveReturnJoinCache : JoinCache<String, String, String, String> {
        fun get(key: Int): Int
    }

    interface UnrelatedReturnJoinCache : JoinCache<String, String, String, String> {
        fun get(key: Long): String
    }

    abstract class NotAnInterface : JoinCache<String, String, String, String>

    @Suppress("UNCHECKED_CAST")
    private val target = mockk<JoinCache<String, String, String, String>>(relaxed = true).also {
        io.mockk.every { it.name } returns "users"
        io.mockk.every { it["a"] } returns JoinValue("a", "k", "v")
        io.mockk.every { it.getAll(listOf("a")) } returns mapOf("a" to JoinValue("a", "k", "v"))
    }

    @Test
    fun forwardsJoinCacheMethodsToTheTarget() {
        val cache = JoinCacheProxies.create(UserJoinCache::class.java, target)
        cache.name.assert().isEqualTo("users")
        cache["a"].assert().isEqualTo(JoinValue("a", "k", "v"))
        cache.secondOf("a").assert().isEqualTo("v")
        cache.getAll(listOf("a")).assert().isEqualTo(mapOf("a" to JoinValue("a", "k", "v")))
        cache.invalidate("a")
        cache.invalidate("a", "k")
        verify(exactly = 1) { target.invalidate("a") }
        verify(exactly = 1) { target.invalidate("a", "k") }
        JoinCacheProxies.targetOf(cache).assert().isSameAs(target)
        JoinCacheProxies.targetOf(target).assert().isNull()
        JoinCacheProxies.targetOf(
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Runnable::class.java)
            ) { _, _, _ -> null }
        ).assert().isNull()
    }

    @Test
    fun objectMethodsUseProxyIdentity() {
        val cache = JoinCacheProxies.create(UserJoinCache::class.java, target)
        val other = JoinCacheProxies.create(UserJoinCache::class.java, target)
        (cache == cache).assert().isTrue()
        cache.equals(other).assert().isFalse()
        cache.hashCode().assert().isEqualTo(System.identityHashCode(cache))
        cache.toString().assert().startsWith("UserJoinCache(")
    }

    @Test
    fun narrowedReturnTypesAreAccepted() {
        JoinCacheProxies.create(NarrowedJoinCache::class.java, target).name.assert().isEqualTo("users")
    }

    @Test
    fun rejectsInvalidTypes() {
        @Suppress("UNCHECKED_CAST")
        val notInterface = NotAnInterface::class.java as Class<JoinCache<*, *, *, *>>
        runCatching { JoinCacheProxies.create(notInterface, target) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("must be an interface")

        @Suppress("UNCHECKED_CAST")
        val notJoinCache = Runnable::class.java as Class<JoinCache<*, *, *, *>>
        runCatching { JoinCacheProxies.create(notJoinCache, target) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("must extend")
    }

    @Test
    fun rejectsAbstractMethodsThatAreNotJoinCacheMethods() {
        mapOf(
            UnknownAbstractJoinCache::class.java to "String undeclared()",
            WrongArityJoinCache::class.java to "JoinValue get(String, String)",
            VoidReturnJoinCache::class.java to "void getAll(List)",
            NonVoidInvalidateJoinCache::class.java to "boolean invalidate(int)",
            PrimitiveReturnJoinCache::class.java to "int get(int)",
            UnrelatedReturnJoinCache::class.java to "String get(long)",
        ).forEach { (type, signature) ->
            runCatching { JoinCacheProxies.create(type, target) }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(type.name)
                .hasMessageContaining(signature)
        }
    }
}
