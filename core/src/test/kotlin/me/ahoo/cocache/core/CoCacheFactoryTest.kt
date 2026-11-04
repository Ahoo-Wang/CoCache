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

package me.ahoo.cocache.core

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [CoCacheFactory]（RFC 0005 §4.2、§4.8）：经装配层创建五种类型并标注“已分层”，接口式声明的校验在创建之前完成，
 * 直接经 `runtime.create` 创建的缓存“未分层”（S20）。
 */
class CoCacheFactoryTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()

    @AfterEach
    fun close() = runtime.close()

    interface UserCache : Cache<String, String> {
        fun nameOf(id: String): String = get(id) ?: "unknown"
    }

    interface TokenStore : Store<String, String>

    interface ReadOnly : ReadableCache<String, String>

    interface BrokenCache : Cache<String, String> {
        fun lookUp(id: String): String?
    }

    abstract class HandWritten : Cache<String, String>

    /**
     * 每种 spec 都先经装配层、再交给运行时。
     */
    private class RecordingLayers : CacheLayers {
        private fun <S : Spec<*, *>> layered(spec: S): Layered<S> = Layered(spec, PolicyReport(emptyList()))

        override fun <K : Any, V : Any> apply(spec: CacheSpec<K, V>, locks: Locks): Layered<CacheSpec<K, V>> =
            layered(spec.toBuilder().apply { policy = policy.toBuilder().localMaxAge(LAYERED_AGE).build() }.build())

        override fun <K : Any, V : Any> apply(spec: VersionedCacheSpec<K, V>, locks: Locks): Layered<VersionedCacheSpec<K, V>> =
            layered(spec.toBuilder().apply { policy = policy.toBuilder().localMaxAge(LAYERED_AGE).build() }.build())

        override fun <K : Any, V : Any> apply(spec: StoreSpec<K, V>, locks: Locks): Layered<StoreSpec<K, V>> =
            layered(spec.toBuilder().apply { policy = policy.toBuilder().localMaxAge(LAYERED_AGE).build() }.build())

        override fun <K : Any, V : Any> apply(spec: VersionedStoreSpec<K, V>, locks: Locks): Layered<VersionedStoreSpec<K, V>> =
            layered(spec.toBuilder().apply { policy = policy.toBuilder().localMaxAge(LAYERED_AGE).build() }.build())

        override fun <K : Any, E : Any> apply(spec: SetStoreSpec<K, E>, locks: Locks): Layered<SetStoreSpec<K, E>> =
            layered(spec.toBuilder().apply { policy = policy.toBuilder().localMaxAge(LAYERED_AGE).build() }.build())
    }

    private fun cacheSpec(name: String = "user") = cacheSpec<String, String>(name) {
        codec = StringCodec
        loader = CacheLoader { "loaded-$it" }
    }

    private fun storeSpec(name: String = "token") = storeSpec<String, String>(name) { codec = StringCodec }

    @Test
    fun everyTypeGoesThroughTheLayersAndIsLayered() {
        val created = CopyOnWriteArrayList<String>()
        runtime.addCreationListener { created.add(it.name) }
        val factory = CoCacheFactory(runtime, RecordingLayers())
        val cache: Cache<String, String> = factory.create(cacheSpec())
        val versionedCache: VersionedCache<String, String> = factory.create(
            versionedCacheSpec<String, String>("doc") {
                codec = StringCodec
                versionedLoader = CacheLoader { Versioned("v", 1) }
            }
        )
        val store: Store<String, String> = factory.create(storeSpec())
        val versionedStore: VersionedStore<String, String> = factory.create(
            versionedStoreSpec<String, String>("aggregate") { codec = StringCodec }
        )
        val setStore: SetStore<String, String> = factory.create(
            setStoreSpec<String, String>("tags") { elementCodec = StringCodec }
        )

        val all = listOf(cache, versionedCache, store, versionedStore, setStore)
        created.assert().containsExactlyElementsOf(all.map { it.name })
        all.forEach { cache ->
            runtime.cache(cache.name).assert().isSameAs(cache)
            runtime.policy(cache.name)?.localMaxAge.assert().isEqualTo(LAYERED_AGE)
            val admin = requireNotNull(runtime.admin(cache.name))
            admin.layered.assert().withFailMessage { "${cache.name} is layered" }.isTrue()
        }
        runtime.admin("user")?.apiType.assert().isEqualTo(Cache::class.java)
        runtime.admin("doc")?.apiType.assert().isEqualTo(VersionedCache::class.java)
        runtime.admin("token")?.apiType.assert().isEqualTo(Store::class.java)
        runtime.admin("aggregate")?.apiType.assert().isEqualTo(VersionedStore::class.java)
        runtime.admin("tags")?.apiType.assert().isEqualTo(SetStore::class.java)
    }

    @Test
    fun theSpecOverloadDispatchesByType() {
        val factory = CoCacheFactory(runtime)
        val created = factory.create(storeSpec() as Spec<*, *>)
        created.assert().isInstanceOf(Store::class.java)
        runtime.admin("token")?.layered.assert().isEqualTo(true)
        factory.create(cacheSpec() as Spec<*, *>).assert().isInstanceOf(Cache::class.java)
        factory.toString().assert().contains("CacheLayers.NONE")
    }

    @Test
    fun aCacheCreatedDirectlyIsUnlayered() {
        runtime.create(cacheSpec())
        val admin = requireNotNull(runtime.admin("user"))
        admin.layered.assert().isFalse()
        admin.apiType.assert().isEqualTo(Cache::class.java)
    }

    @Test
    fun anInterfaceGetsAProxyForwardingToTheCache() {
        val factory = CoCacheFactory(runtime)
        val users = factory.create(UserCache::class.java, cacheSpec())
        users["1"].assert().isEqualTo("loaded-1")
        users.nameOf("2").assert().isEqualTo("loaded-2")
        users.put("3", "three")
        users["3"].assert().isEqualTo("three")
        CacheProxies.targetOf(users).assert().isSameAs(runtime.cache("user"))
        runtime.admin("user")?.layered.assert().isEqualTo(true)

        val tokens = factory.create(TokenStore::class.java, storeSpec())
        tokens.put("t", "v")
        tokens["t"].assert().isEqualTo("v")
    }

    /**
     * 校验在创建之前完成：失败时运行时中没有任何缓存，同名的缓存之后仍能创建。
     */
    @Test
    fun aBadInterfaceFailsBeforeAnythingIsCreated() {
        val factory = CoCacheFactory(runtime)

        @Suppress("UNCHECKED_CAST")
        val handWritten = HandWritten::class.java as Class<Cache<String, String>>
        runCatching { factory.create(handWritten, cacheSpec()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("needs an interface")
            .hasMessageContaining("delegate")
        runCatching { factory.create(TokenStore::class.java, cacheSpec()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("extends Store, but its spec declares a Cache")
        runCatching { factory.create(ReadOnly::class.java, cacheSpec()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { factory.create(BrokenCache::class.java, cacheSpec()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lookUp")
        runtime.cacheNames.assert().isEmpty()

        factory.create(UserCache::class.java, cacheSpec())["1"].assert().isEqualTo("loaded-1")
    }

    @Test
    fun layersMustNotRenameACache() {
        val renaming = object : CacheLayers {
            override fun <K : Any, V : Any> apply(spec: StoreSpec<K, V>, locks: Locks): Layered<StoreSpec<K, V>> =
                Layered(storeSpec("renamed") { codec = spec.codec }, PolicyReport(emptyList()))
        }
        runCatching { CoCacheFactory(runtime, renaming).create(storeSpec()) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not rename")
        runtime.cacheNames.assert().isEmpty()
    }

    @Test
    fun noLayersKeepTheDeclaration() {
        val spec = storeSpec()
        val layered = CacheLayers.NONE.apply(spec, Locks.NONE)
        layered.spec.assert().isSameAs(spec)
        layered.report.entry(PolicyField.TTL)?.source.assert().isEqualTo(PolicyReport.Source.TYPE_DEFAULT)
        CacheLayers.NONE.toString().assert().isEqualTo("CacheLayers.NONE")
        CoCacheFactory(runtime).create(spec)
        runtime.policy("token").assert().isEqualTo(spec.policy)
    }

    private companion object {
        val LAYERED_AGE: Duration = Duration.ofSeconds(7)
    }
}
