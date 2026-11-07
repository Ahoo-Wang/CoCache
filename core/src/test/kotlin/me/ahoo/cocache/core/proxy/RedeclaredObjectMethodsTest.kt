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

import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.join.JoinView
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.join.SimpleJoinView
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/** 接口重新声明 Object 方法时，代理既有的身份/展示语义仍应有效（#902）。 */
class RedeclaredObjectMethodsTest {
    interface Plain : Store<String, String>

    /** 只重新声明 equals：用例要的正是单独一个 Object 方法。 */
    @Suppress("EqualsWithHashCodeExist")
    interface WithEquals : Store<String, String> {
        override fun equals(other: Any?): Boolean
    }

    /** 只重新声明 hashCode：用例要的正是单独一个 Object 方法。 */
    @Suppress("EqualsWithHashCodeExist")
    interface WithHashCode : Store<String, String> {
        override fun hashCode(): Int
    }

    interface WithToString : Store<String, String> {
        override fun toString(): String
    }

    interface Invalid : Store<String, String> {
        fun unsupported(): String
    }

    /** 与 Object 方法同名、签名不同的重载不是 Object 方法，仍是不能转发的抽象方法。 */
    interface Overloads : Store<String, String> {
        fun toString(radix: Int): String
    }

    interface Joined : JoinView<String, String, String, String> {
        override fun equals(other: Any?): Boolean
        override fun hashCode(): Int
        override fun toString(): String
    }

    @Test
    fun redeclaredEqualsUsesProxyIdentity() = verify(WithEquals::class.java)

    @Test
    fun redeclaredHashCodeUsesProxyIdentity() = verify(WithHashCode::class.java)

    @Test
    fun redeclaredToStringKeepsTheProxyDescription() = verify(WithToString::class.java)

    @Test
    fun inheritedObjectMethodsWork() = verify(Plain::class.java)

    private fun <C : Store<String, String>> verify(type: Class<C>) {
        InMemoryBackend().use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val spec = storeSpec<String, String>("objects") { codec = StringCodec }
                val cache = CoCacheFactory(runtime).create(type, spec)
                cache.equals(cache).assert().isTrue()
                cache.equals(Any()).assert().isFalse()
                cache.hashCode().assert().isEqualTo(System.identityHashCode(cache))
                cache.toString().assert().contains(type.simpleName)
                cache.put("k", "value")
                cache["k"].assert().isEqualTo("value")
            }
        }
    }

    @Test
    fun joinInterfacesCanAlsoRedeclareObjectMethods() {
        InMemoryBackend().use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val store = runtime.create(storeSpec<String, String>("objects") { codec = StringCodec })
                val target = SimpleJoinView("joined", store, store) { it }
                val view = JoinCacheProxies.create(Joined::class.java, target)
                view.equals(view).assert().isTrue()
                view.hashCode().assert().isEqualTo(System.identityHashCode(view))
                view.toString().assert().contains("Joined")
            }
        }
    }

    @Test
    fun anUnsupportedBusinessMethodStillFailsBeforeRegistration() = rejects(Invalid::class.java)

    @Test
    fun anOverloadOfAnObjectMethodIsStillRejected() = rejects(Overloads::class.java)

    private fun <C : Store<String, String>> rejects(type: Class<C>) {
        InMemoryBackend().use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val spec = storeSpec<String, String>("objects") { codec = StringCodec }
                runCatching { CoCacheFactory(runtime).create(type, spec) }
                    .exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
                runtime.cacheNames.assert().isEmpty()
            }
        }
    }

    @Test
    fun jdkProxyHandlesTheSameRedeclarationsAsObjectMethods() {
        val type = Joined::class.java
        val view = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            method.declaringClass.assert().isEqualTo(Any::class.java)
            when (method.name) {
                "equals" -> proxy === args?.first()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "joined-object-methods"
                else -> error("Not an Object method: $method")
            }
        } as Joined
        view.equals(view).assert().isTrue()
        view.hashCode().assert().isEqualTo(System.identityHashCode(view))
        view.toString().assert().isEqualTo("joined-object-methods")
    }
}
