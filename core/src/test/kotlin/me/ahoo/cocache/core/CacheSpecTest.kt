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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture

class CacheSpecTest {
    private val loader = CacheLoader<String, String> { it }
    private val versionedLoader = CacheLoader<String, Versioned<String?>> { Versioned(it, 1) }

    @Test
    fun builderDefaults() {
        val spec = CacheSpec.builder<Int, String>("user").codec(StringCodec).externallySourced().build()
        spec.name.assert().isEqualTo("user")
        spec.namespace.assert().isEqualTo("user")
        spec.keyPrefix.assert().isEqualTo(CacheSpec.DEFAULT_KEY_PREFIX)
        spec.keyEncoder.encode(42).assert().isEqualTo("42")
        spec.loader.assert().isNull()
        spec.asyncLoader.assert().isNull()
        spec.externallySourced.assert().isTrue()
        spec.unknownRead.assert().isEqualTo(UnknownReadMode.THROW)
        spec.ttlOf.assert().isNull()
        spec.keyFilter.assert().isNull()
        spec.policy.assert().isSameAs(DefaultPolicies.CACHE)
        spec.toString().assert().contains("name='user'").contains("externallySourced=true")
    }

    @Test
    fun builderSetsEveryField() {
        val ttlOf = TtlFunction<String, String> { _, _ -> Duration.ofSeconds(1) }
        val filter = KeyFilter { false }
        val spec = CacheSpec.builder<String, String>("user")
            .namespace("ns")
            .keyPrefix("app:")
            .keyEncoder { "id-$it" }
            .codec(StringCodec)
            .loader(loader)
            .ttlOf(ttlOf)
            .keyFilter(filter)
            .policy { ttl = Duration.ofMinutes(1) }
            .build()
        spec.namespace.assert().isEqualTo("ns")
        spec.keyPrefix.assert().isEqualTo("app:")
        spec.keyEncoder.encode("1").assert().isEqualTo("id-1")
        spec.loader.assert().isSameAs(loader)
        spec.externallySourced.assert().isFalse()
        spec.ttlOf.assert().isSameAs(ttlOf)
        spec.keyFilter.assert().isSameAs(filter)
        spec.policy.ttl.assert().isEqualTo(Duration.ofMinutes(1))
        CacheSpec.builder<String, String>("x").codec(StringCodec).loader(loader)
            .policy(CachePolicy(ttl = null)).build().policy.ttl.assert().isNull()
        val async = AsyncCacheLoader<String, String> { CompletableFuture.completedFuture(it) }
        CacheSpec.builder<String, String>("async").codec(StringCodec).asyncLoader(async).build()
            .asyncLoader.assert().isSameAs(async)
    }

    /**
     * 派生缓存必须声明数据来源（RFC 0005 §4.1）：`loader`、`asyncLoader` 与 `externallySourced()` 恰好一个；`unknownRead` 只能用于
     * 外部来源的缓存（带数据源的读取由 `readFailure` 决定）。
     */
    @Test
    fun cacheSpecDeclaresExactlyOneSource() {
        val noSource = runCatching { cacheSpec<String, String>("user") { codec = StringCodec } }.exceptionOrNull()
        noSource.assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("declare exactly one data source").hasMessageContaining("Store")
        val async = AsyncCacheLoader<String, String> { CompletableFuture.completedFuture(it) }
        listOf<CacheSpec.Builder<String, String>.() -> Unit>(
            {
                loader = this@CacheSpecTest.loader
                asyncLoader = async
            },
            {
                loader = this@CacheSpecTest.loader
                externallySourced()
            },
            {
                asyncLoader = async
                externallySourced()
            },
        ).forEach { block ->
            runCatching {
                cacheSpec<String, String>("user") {
                    codec = StringCodec
                    block()
                }
            }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        val sourced = runCatching {
            cacheSpec<String, String>("user") {
                codec = StringCodec
                loader = this@CacheSpecTest.loader
                unknownRead = UnknownReadMode.ABSENT
            }
        }.exceptionOrNull()
        sourced.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("unknownRead")
        cacheSpec<String, String>("user") {
            codec = StringCodec
            externallySourced()
            unknownRead(UnknownReadMode.ABSENT)
        }.unknownRead.assert().isEqualTo(UnknownReadMode.ABSENT)
    }

    /**
     * `VersionedCache` 必须有带版本的数据源：`versionedLoader` 与 `versionedAsyncLoader` 恰好一个。
     */
    @Test
    fun versionedCacheSpecNeedsExactlyOneVersionedSource() {
        val ttlOf = TtlFunction<String, String> { _, _ -> Duration.ofSeconds(1) }
        val spec = VersionedCacheSpec.builder<String, String>("order")
            .namespace("orders")
            .keyPrefix("app:")
            .keyEncoder { "id-$it" }
            .codec(StringCodec)
            .versionedLoader(versionedLoader)
            .ttlOf(ttlOf)
            .keyFilter { false }
            .policy { refreshWindow = Duration.ofSeconds(10) }
            .build()
        spec.versionedLoader.assert().isSameAs(versionedLoader)
        spec.versionedAsyncLoader.assert().isNull()
        spec.keyspace.assert().isEqualTo(Keyspace("app:", "orders"))
        spec.policy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(10))
        spec.toString().assert().contains("VersionedCacheSpec(name='order'")
        val copy = spec.toBuilder().build()
        copy.versionedLoader.assert().isSameAs(versionedLoader)
        copy.ttlOf.assert().isSameAs(ttlOf)
        copy.keyFilter.assert().isSameAs(spec.keyFilter)
        copy.keyEncoder.assert().isSameAs(spec.keyEncoder)
        copy.policy.assert().isSameAs(spec.policy)
        copy.namespace.assert().isEqualTo("orders")
        copy.keyPrefix.assert().isEqualTo("app:")
        copy.codec.assert().isSameAs(StringCodec)

        runCatching { versionedCacheSpec<String, String>("order") { codec = StringCodec } }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("VersionedStore")
        val async = AsyncCacheLoader<String, Versioned<String?>> { CompletableFuture.completedFuture(null) }
        runCatching {
            versionedCacheSpec<String, String>("order") {
                codec = StringCodec
                versionedLoader = this@CacheSpecTest.versionedLoader
                versionedAsyncLoader = async
            }
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        VersionedCacheSpec.builder<String, String>("order").codec(StringCodec).versionedAsyncLoader(async)
            .updatePolicy { it.ttl(Duration.ofMinutes(2)) }.build().policy.ttl.assert().isEqualTo(Duration.ofMinutes(2))
        runCatching { VersionedCacheSpec.builder<String, String>("order").build() }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 权威类型的默认策略（RFC 0005 §4.1，D7）：`ttl` 永不过期，`localMaxAge` 30 s，`absentTtl` 10 s；`VersionedStore` 的墓碑另有
     * 有限的寿命（1 天）。派生类型的默认策略是 [DefaultPolicies.CACHE]。
     */
    @Test
    fun typesHaveTheirOwnDefaultPolicies() {
        listOf(DefaultPolicies.STORE, DefaultPolicies.VERSIONED_STORE, DefaultPolicies.SET_STORE).forEach {
            it.ttl.assert().isNull()
            it.localMaxAge.assert().isEqualTo(Duration.ofSeconds(30))
            it.absentTtl.assert().isEqualTo(Duration.ofSeconds(10))
        }
        DefaultPolicies.STORE.tombstoneTtl.assert().isNull()
        DefaultPolicies.VERSIONED_STORE.tombstoneTtl.assert().isEqualTo(Duration.ofDays(1))
        DefaultPolicies.VERSIONED_STORE.tombstoneTtl().assert().isEqualTo(
            requireNotNull(DefaultPolicies.VERSIONED_STORE.tombstoneTtl)
        )
        storeSpec<String, String>("s") { codec = StringCodec }.policy.assert().isSameAs(DefaultPolicies.STORE)
        versionedStoreSpec<String, String>("v") { codec = StringCodec }.policy
            .assert().isSameAs(DefaultPolicies.VERSIONED_STORE)
        setStoreSpec<String, String>(
            "x"
        ) { elementCodec = StringCodec }.policy.assert().isSameAs(DefaultPolicies.SET_STORE)
        cacheSpec<String, String>("c") {
            codec = StringCodec
            externallySourced()
        }.policy.assert().isSameAs(DefaultPolicies.CACHE)
        // 墓碑寿命未设置时同值的寿命
        CachePolicy(
            ttl = Duration.ofMinutes(3),
            ttlJitter = Duration.ZERO
        ).tombstoneTtl().assert().isEqualTo(Duration.ofMinutes(3))
        CachePolicy(ttl = null).tombstoneTtl().assert().isNull()
        CachePolicy(
            tombstoneTtl = Duration.ofMinutes(4)
        ).toBuilder().build().tombstoneTtl.assert().isEqualTo(Duration.ofMinutes(4))
        runCatching { CachePolicy(tombstoneTtl = Duration.ZERO) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun storeSpecBuilderMethods() {
        val ttlOf = TtlFunction<String, String> { _, _ -> Duration.ofSeconds(1) }
        val spec = StoreSpec.builder<String, String>("policy")
            .namespace("ns")
            .keyPrefix("p:")
            .keyEncoder { it }
            .codec(StringCodec)
            .ttlOf(ttlOf)
            .keyFilter { false }
            .unknownRead(UnknownReadMode.ABSENT)
            .updatePolicy { it.localMaxAge(Duration.ofSeconds(5)) }
            .build()
        spec.unknownRead.assert().isEqualTo(UnknownReadMode.ABSENT)
        spec.policy.ttl.assert().isNull()
        spec.policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(5))
        spec.toString().assert().contains("StoreSpec(name='policy'").contains("unknownRead=ABSENT")
        val copy = spec.toBuilder().build()
        copy.ttlOf.assert().isSameAs(ttlOf)
        copy.keyFilter.assert().isSameAs(spec.keyFilter)
        copy.unknownRead.assert().isEqualTo(UnknownReadMode.ABSENT)
        copy.policy.assert().isSameAs(spec.policy)
        copy.namespace.assert().isEqualTo("ns")
        copy.keyPrefix.assert().isEqualTo("p:")
        copy.codec.assert().isSameAs(StringCodec)
        copy.keyEncoder.assert().isSameAs(spec.keyEncoder)
        StoreSpec.builder<String, String>("s").codec(StringCodec).policy { ttl = Duration.ofMinutes(1) }.build()
            .policy.ttl.assert().isEqualTo(Duration.ofMinutes(1))
        StoreSpec.builder<String, String>("s").codec(StringCodec).policy(DefaultPolicies.CACHE).build()
            .policy.assert().isSameAs(DefaultPolicies.CACHE)
        runCatching { StoreSpec.builder<String, String>("s").build() }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 墓碑必须有有限的寿命：`tombstoneTtl` 与 `ttl` 都是永不过期时构建失败。
     */
    @Test
    fun versionedStoreSpecNeedsAFiniteTombstoneLifetime() {
        val spec = VersionedStoreSpec.builder<String, String>("aggregate")
            .namespace("ns")
            .keyPrefix("p:")
            .keyEncoder { it }
            .codec(StringCodec)
            .ttlOf { _, _ -> Duration.ofSeconds(1) }
            .keyFilter { false }
            .unknownRead(UnknownReadMode.ABSENT)
            .policy { tombstoneTtl = Duration.ofHours(2) }
            .build()
        spec.policy.tombstoneTtl.assert().isEqualTo(Duration.ofHours(2))
        spec.toString().assert().contains("VersionedStoreSpec(name='aggregate'")
        val copy = spec.toBuilder().build()
        copy.unknownRead.assert().isEqualTo(UnknownReadMode.ABSENT)
        copy.ttlOf.assert().isSameAs(spec.ttlOf)
        copy.keyFilter.assert().isSameAs(spec.keyFilter)
        copy.policy.assert().isSameAs(spec.policy)
        copy.codec.assert().isSameAs(StringCodec)
        copy.keyEncoder.assert().isSameAs(spec.keyEncoder)
        copy.namespace.assert().isEqualTo("ns")
        copy.keyPrefix.assert().isEqualTo("p:")

        val forever = runCatching {
            versionedStoreSpec<String, String>("aggregate") {
                codec = StringCodec
                policy(CachePolicy(ttl = null))
            }
        }.exceptionOrNull()
        forever.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("tombstoneTtl")
        // 值有有限的寿命时，墓碑寿命未设置也可以（同值）
        VersionedStoreSpec.builder<String, String>("aggregate").codec(StringCodec)
            .policy(CachePolicy(ttl = Duration.ofMinutes(1))).build().policy.tombstoneTtl().assert().isNotNull()
        VersionedStoreSpec.builder<String, String>("aggregate").codec(StringCodec)
            .updatePolicy { it.localMaxAge(Duration.ofSeconds(1)) }.build().policy.localMaxAge
            .assert().isEqualTo(Duration.ofSeconds(1))
        runCatching { VersionedStoreSpec.builder<String, String>("a").build() }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun dslBuildsSpecs() {
        val spec = cacheSpec<String, String>("user") {
            codec = StringCodec
            loader = this@CacheSpecTest.loader
        }
        spec.name.assert().isEqualTo("user")
        val setSpec = setStoreSpec<String, String>("index") {
            elementCodec = StringCodec
            namespace = "idx"
            keyPrefix = "p:"
            keyEncoder = KeyEncoder { it.lowercase() }
        }
        setSpec.namespace.assert().isEqualTo("idx")
        setSpec.keyPrefix.assert().isEqualTo("p:")
        setSpec.keyEncoder.encode("A").assert().isEqualTo("a")
        setSpec.policy.ttl.assert().isNull()
        setSpec.toString().assert().contains("index")
    }

    @Test
    fun setSpecBuilderMethods() {
        val spec = SetStoreSpec.builder<String, String>("index")
            .namespace("ns")
            .keyPrefix("p:")
            .keyEncoder { it }
            .elementCodec(StringCodec)
            .policy { ttl = Duration.ofMinutes(1) }
            .build()
        spec.policy.ttl.assert().isEqualTo(Duration.ofMinutes(1))
        SetStoreSpec.builder<String, String>(
            "i"
        ).elementCodec(
            StringCodec
        ).policy(DefaultPolicies.CACHE).build().policy.assert().isSameAs(DefaultPolicies.CACHE)
    }

    @Test
    fun rejectsMissingCodecAndBlankNames() {
        fun fails(
            block: () -> Any
        ) = runCatching(block).exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        fails { CacheSpec.builder<String, String>("user").externallySourced().build() }
        fails { CacheSpec.builder<String, String>(" ").codec(StringCodec).externallySourced().build() }
        fails {
            CacheSpec.builder<String, String>(
                "user"
            ).namespace(" ").codec(StringCodec).externallySourced().build()
        }
        fails { SetStoreSpec.builder<String, String>("index").build() }
        fails { StoreSpec.builder<String, String>(" ").codec(StringCodec).build() }
    }

    /**
     * 命名空间的字符集为 `[A-Za-z0-9._-]+`（RFC 0005 §4.7，D5）：构建时校验，信息带上缓存名与类型；含 `:` 的给出把前缀移到 keyPrefix 的写法。
     * 默认的命名空间是缓存名，所以缓存名不符合时同样失败。
     */
    @Test
    fun namespaceOutsideTheCharsetFailsWithTheCacheName() {
        val error = runCatching {
            CacheSpec.builder<String, String>(
                "user"
            ).namespace("app:user").codec(StringCodec).externallySourced().build()
        }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java).hasMessage(
            "Cache[user] - Namespace[app:user] must match [A-Za-z0-9._-]+. A namespace cannot contain ':': " +
                "move the prefix into keyPrefix, for example keyPrefix = \"cocache:app:\" and namespace = \"user\"."
        )
        error?.cause.assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            cacheSpec<String, String>("user cache") {
                codec = StringCodec
                externallySourced()
            }
        }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Cache[user cache] - Namespace[user cache] must match [A-Za-z0-9._-]+.")
        listOf<Pair<String, () -> Any>>(
            "SetStore" to {
                setStoreSpec<String, String>("idx") {
                    elementCodec = StringCodec
                    namespace = "a:b"
                }
            },
            "Store" to {
                storeSpec<String, String>("idx") {
                    codec = StringCodec
                    namespace = "a:b"
                }
            },
            "VersionedStore" to {
                versionedStoreSpec<String, String>("idx") {
                    codec = StringCodec
                    namespace = "a:b"
                }
            },
            "VersionedCache" to {
                versionedCacheSpec<String, String>("idx") {
                    codec = StringCodec
                    versionedLoader = this@CacheSpecTest.versionedLoader
                    namespace = "a:b"
                }
            },
        ).forEach { (kind, build) ->
            runCatching(build).exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageStartingWith("$kind[idx] - Namespace[a:b] must match")
        }
        val spec = CacheSpec.builder<String, String>(
            "user"
        ).keyPrefix("app:").codec(StringCodec).externallySourced().build()
        spec.keyspace.assert().isEqualTo(Keyspace("app:", "user"))
        spec.keyspace.storagePrefix.assert().isEqualTo("app:user:")
    }

    @Test
    fun toBuilderCopiesEveryField() {
        val spec = CacheSpec.builder<String, String>("user")
            .namespace("ns").keyPrefix("p:").codec(StringCodec).loader(loader)
            .ttlOf { _, _ -> Duration.ofSeconds(1) }.keyFilter { false }
            .policy { ttl = Duration.ofMinutes(1) }.build()
        val copy = spec.toBuilder().build()
        copy.namespace.assert().isEqualTo("ns")
        copy.keyPrefix.assert().isEqualTo("p:")
        copy.codec.assert().isSameAs(StringCodec)
        copy.loader.assert().isSameAs(loader)
        copy.ttlOf.assert().isSameAs(spec.ttlOf)
        copy.keyFilter.assert().isSameAs(spec.keyFilter)
        copy.policy.assert().isSameAs(spec.policy)
        copy.keyEncoder.assert().isSameAs(spec.keyEncoder)
        val external = CacheSpec.builder<String, String>("ext").codec(StringCodec).externallySourced()
            .unknownRead(UnknownReadMode.ABSENT).build().toBuilder().build()
        external.externallySourced.assert().isTrue()
        external.unknownRead.assert().isEqualTo(UnknownReadMode.ABSENT)

        val setSpec = setStoreSpec<String, String>("index") {
            elementCodec = StringCodec
            namespace = "idx"
        }
        val setCopy = setSpec.toBuilder().build()
        setCopy.namespace.assert().isEqualTo("idx")
        setCopy.elementCodec.assert().isSameAs(StringCodec)
        setCopy.policy.assert().isSameAs(setSpec.policy)
        setCopy.keyEncoder.assert().isSameAs(setSpec.keyEncoder)
        setCopy.keyPrefix.assert().isEqualTo(setSpec.keyPrefix)
    }

    @Test
    fun updatePolicyStartsFromTheCurrentPolicy() {
        val setSpec = SetStoreSpec.builder<String, String>("index")
            .elementCodec(StringCodec)
            .updatePolicy { it.localMaxAge(Duration.ofMinutes(1)) }
            .build()
        // 集合保持永不过期，只改了限龄
        setSpec.policy.ttl.assert().isNull()
        setSpec.policy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(1))
        val spec = CacheSpec.builder<String, String>("user")
            .codec(StringCodec)
            .loader(loader)
            .updatePolicy { it.ttl(Duration.ofMinutes(2)) }
            .build()
        spec.policy.ttl.assert().isEqualTo(Duration.ofMinutes(2))
    }
}
