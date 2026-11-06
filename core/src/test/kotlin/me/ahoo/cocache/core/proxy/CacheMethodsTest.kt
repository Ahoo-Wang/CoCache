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
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.SuspendingSetStore
import me.ahoo.cocache.api.SuspendingStore
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.SortedMap
import java.util.SortedSet
import kotlin.coroutines.Continuation

/**
 * [CacheMethods] 的返回类型规则（#843）：业务泛型 `V` 可以收窄为具体的值类型，固定的返回面（`Map`、`Set`、`Lookup`、视图类型……）
 * 只能放宽或相同；挂起方法比较 `Continuation` 的结果类型。
 */
class CacheMethodsTest {
    interface SortedStore : Store<String, String> {
        override fun getAll(keys: Collection<String>): SortedMap<String, String>
    }

    interface PlainStore : Store<String, String> {
        override fun get(key: String): String?

        override fun getAll(keys: Collection<String>): Map<String, String>

        override fun lookup(key: String): Lookup<String>

        fun getAll(keys: List<String>): Any
    }

    interface NarrowedLookupStore : Store<String, String> {
        override fun lookup(key: String): Lookup.Present<String>
    }

    /**
     * 业务泛型 `V` 也不能声明为 `void`。
     */
    interface VoidGetStore : Store<String, String> {
        fun get(key: Int)
    }

    interface UserAsyncStore : AsyncStore<String, String>

    interface NarrowedViewStore : Store<String, String> {
        override fun async(): UserAsyncStore
    }

    interface SortedSetStore : SetStore<String, String> {
        override fun get(key: String): SortedSet<String>
    }

    interface PlainSetStore : SetStore<String, String> {
        override fun get(key: String): Set<String>
    }

    interface SortedSuspendingStore : SuspendingStore<String, String> {
        override suspend fun getAll(keys: Collection<String>): SortedMap<String, String>
    }

    interface PlainSuspendingStore : SuspendingStore<String, String> {
        override suspend fun get(key: String): String?

        override suspend fun getAll(keys: Collection<String>): Map<String, String>
    }

    /**
     * 显式的 `Continuation` 参数，类型实参不带通配符（`Continuation<String>`，不是 `Continuation<? super String>`）。
     */
    interface ExplicitContinuationStore : SuspendingStore<String, String> {
        fun get(key: String, continuation: Continuation<@JvmSuppressWildcards String?>): Any?
    }

    interface SortedSuspendingSetStore : SuspendingSetStore<String, String> {
        override suspend fun get(key: String): SortedSet<String>
    }

    /**
     * 泛型的用户接口：自己的类型参数不检查。
     */
    interface GenericSuspendingStore<V : Any> : SuspendingStore<String, V> {
        override suspend fun get(key: String): V?
    }

    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()

    @AfterEach
    fun close() = runtime.close()

    private fun rejects(type: Class<*>, apiType: Class<*>, signature: String) {
        runCatching { CacheMethods.validate(type, apiType) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(type.name)
            .hasMessageContaining(signature)
            .hasMessageContaining("declare them as default methods")
    }

    @Test
    fun aFixedReturnTypeMustNotBeNarrowed() {
        rejects(SortedStore::class.java, Store::class.java, "SortedMap getAll(Collection)")
        rejects(NarrowedLookupStore::class.java, Store::class.java, "Present lookup(String)")
        rejects(NarrowedViewStore::class.java, Store::class.java, "UserAsyncStore async()")
        rejects(VoidGetStore::class.java, Store::class.java, "void get(int)")
        // SetStore 中 ReadableCache 的 V 代入为 Set<E>：也是固定的返回面
        rejects(SortedSetStore::class.java, SetStore::class.java, "SortedSet get(String)")
    }

    @Test
    fun theResultOfASuspendingMethodMustNotBeNarrowed() {
        rejects(SortedSuspendingStore::class.java, SuspendingStore::class.java, "getAll(Collection, Continuation)")
        rejects(SortedSuspendingSetStore::class.java, SuspendingSetStore::class.java, "get(String, Continuation)")
    }

    @Test
    fun theBusinessValueNarrowsAndFixedTypesStayOrWiden() {
        CacheMethods.validate(PlainStore::class.java, Store::class.java)
        CacheMethods.validate(PlainSetStore::class.java, SetStore::class.java)
        CacheMethods.validate(PlainSuspendingStore::class.java, SuspendingStore::class.java)
        CacheMethods.validate(ExplicitContinuationStore::class.java, SuspendingStore::class.java)
        CacheMethods.validate(GenericSuspendingStore::class.java, SuspendingStore::class.java)
    }

    /**
     * 经公开入口：创建时就拒绝，运行时中没有任何缓存；放宽或相同的声明照常转发。
     */
    @Test
    fun theFactoryRejectsANarrowedFixedReturnTypeBeforeCreatingTheCache() {
        val factory = CoCacheFactory(runtime)
        val spec = storeSpec<String, String>("tokens") { codec = StringCodec }
        runCatching { factory.create(SortedStore::class.java, spec) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SortedMap getAll(Collection)")
        runCatching { factory.create(SortedSuspendingStore::class.java, spec) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("getAll(Collection, Continuation)")
        runtime.cacheNames.assert().isEmpty()

        val store = factory.create(PlainStore::class.java, spec)
        store.put("k", "v")
        store["k"].assert().isEqualTo("v")
        store.getAll(listOf("k", "missing")).assert().isEqualTo(mapOf("k" to "v"))
        store.getAll(emptyList<String>()).assert().isEqualTo(emptyMap<String, String>())
        store.lookup("k").assert().isInstanceOf(Lookup.Present::class.java)

        val setSpec = setStoreSpec<String, String>("index") { elementCodec = StringCodec }
        val sets = factory.create(PlainSetStore::class.java, setSpec)
        sets.add("k", listOf("a"))
        sets["k"].assert().isEqualTo(setOf("a"))

        val suspending = factory.create(
            PlainSuspendingStore::class.java,
            storeSpec<String, String>("suspending") { codec = StringCodec }
        )
        runBlocking {
            suspending.put("k", "v")
            suspending.get("k").assert().isEqualTo("v")
            suspending.getAll(listOf("k")).assert().isEqualTo(mapOf("k" to "v"))
        }
    }
}
