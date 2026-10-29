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

package me.ahoo.cocache.core.engine

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.RemoteVersionedWrite
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * 带版本的缓存（6.8）在 TCK 之外的边界：声明校验、创建时的后端检查、门面上的普通读写（不带版本）、异步视图的异常、
 * 非原子广播的后端上版本不更新时不广播。
 */
class VersionedCacheTest {
    private val memory = InMemoryBackend()
    private val runtime = CacheRuntime.builder().backend(memory).build()

    @AfterEach
    fun destroy() = runtime.close()

    private fun versioned(
        name: String = "doc",
        block: CacheSpec.Builder<String, String>.() -> Unit = {}
    ): Cache<String, String> {
        val spec = cacheSpec(name) {
            codec = StringCodec
            versioned = true
            block()
        }
        return runtime.create(spec)
    }

    private fun remote(key: String, name: String = "doc"): RemoteRead =
        memory.store(name).read("cocache:$name:$key", null, null)

    private fun remoteVersion(key: String, name: String): Long {
        val hit = remote(key, name) as RemoteRead.Hit
        return (hit.envelope as Envelope.Scalar).version
    }

    @Test
    fun specDeclaresVersionedUse() {
        val loader = CacheLoader<String, Versioned<String>> { Versioned(it, 1) }
        val spec = cacheSpec<String, String>("v") {
            codec = StringCodec
            versionedLoader(loader)
        }
        spec.versioned.assert().isTrue()
        spec.versionedLoader.assert().isSameAs(loader)
        val copy = spec.toBuilder().build()
        copy.versioned.assert().isTrue()
        copy.versionedLoader.assert().isSameAs(loader)
        cacheSpec<String, String>("plain") { codec = StringCodec }.versioned.assert().isFalse()
        CacheSpec.builder<String, String>("flag").codec(StringCodec).versioned(true).build().versioned.assert().isTrue()
        val async = CacheSpec.builder<String, String>("async").codec(StringCodec)
            .versionedAsyncLoader { CompletableFuture.completedFuture(Versioned(it, 1)) }.build()
        async.versioned.assert().isTrue()
        async.toBuilder().build().versionedAsyncLoader.assert().isSameAs(async.versionedAsyncLoader)
    }

    @Test
    fun specRejectsRefreshAheadAndSeveralLoaders() {
        val refresh = runCatching {
            cacheSpec<String, String>("v") {
                codec = StringCodec
                versioned = true
                policy { refreshWindow = Duration.ofSeconds(10) }
            }
        }.exceptionOrNull()
        refresh.assert().isInstanceOf(IllegalArgumentException::class.java)
        refresh?.message.assert().contains("refreshWindow")
        runCatching {
            cacheSpec<String, String>("v") {
                codec = StringCodec
                loader = CacheLoader { it }
                versionedLoader = CacheLoader { Versioned(it, 1) }
            }
        }.exceptionOrNull()?.message.assert().contains("at most one of")
    }

    @Test
    fun backendsWithoutVersionsFailAtCreation() {
        val plain = object : CoherenceBackend by memory {
            override val versionedWrites: Boolean = false
        }
        CacheRuntime.builder().backend(plain).build().use { other ->
            val error = runCatching {
                other.create(
                    cacheSpec<String, String>("doc") {
                        codec = StringCodec
                        versioned = true
                    }
                )
            }.exceptionOrNull()
            error.assert().isInstanceOf(UnsupportedOperationException::class.java)
            error?.message.assert().contains("versionedWrites")
            other.cacheNames.assert().isEmpty()
            other.create(cacheSpec<String, String>("doc") { codec = StringCodec }).assert().isNotNull()
        }
    }

    @Test
    fun runtimeLooksUpTheVersionedFacade() {
        val cache = versioned()
        runtime.cache("doc").assert().isSameAs(cache)
        cache.name.assert().isEqualTo("doc")
        cache.toString().assert().contains("VersionedCache")
        cache.async().sync().assert().isSameAs(cache)
        cache.async().name.assert().isEqualTo("doc")
        cache.async().toString().assert().contains("VersionedCache")
        cache.putIfNewer("k", "v", 3)
        requireNotNull(
            runtime.admin("doc")
        ).inspect("k").remote.value.assert().isEqualTo("Versioned(value=v, version=3)")
    }

    /**
     * 门面上的普通读写写入没有版本的值；读取返回用户的值类型。
     */
    @Test
    fun plainOperationsWriteUnversionedValues() {
        val cache =
            versioned { ttlOf = TtlFunction { _, value -> if (value == "short") Duration.ofSeconds(30) else null } }
        cache.put("a", "1")
        cache.put("b", "2", Duration.ofMinutes(1))
        cache.putAll(mapOf("c" to "3"))
        cache.putAll(mapOf("d" to "4"), Duration.ofMinutes(1))
        cache.put("short", "short")
        cache.getAll(
            listOf("a", "b", "c", "d", "x")
        ).assert().isEqualTo(mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4"))
        cache.getVersioned("a").assert().isEqualTo(Versioned("1", Versioned.NO_VERSION))
        requireNotNull((remote("short") as RemoteRead.Hit).ttl).assert().isLessThanOrEqualTo(Duration.ofSeconds(30))
        (cache.lookup("a") as Lookup.Present).value.assert().isEqualTo("1")
        cache.lookup("x").assert().isInstanceOf(Lookup.Absent::class.java)
        cache.get("y") { "loaded-$it" }.assert().isEqualTo("loaded-y")
        cache.getVersioned("y").assert().isEqualTo(Versioned("loaded-y", Versioned.NO_VERSION))
        cache.invalidate("a")
        cache.invalidateAll(listOf("b", "c"))
        cache["a"].assert().isNull()

        val written = cache.putIfMatch("cas", null, "v1") as ConditionalPut.Written
        cache.getIfChanged("cas", written.digest).assert().isEqualTo(ConditionalGet.Unchanged)
        cache.getIfChanged("cas", null).assert().isEqualTo(ConditionalGet.Present("v1", written.digest))
        cache.getIfChanged("none", null).assert().isEqualTo(ConditionalGet.Absent)
        cache.putIfMatch(
            "cas",
            written.digest,
            "v2",
            Duration.ofMinutes(1)
        ).assert().isInstanceOf(ConditionalPut.Written::class.java)
        cache.putIfNewer("cas", "v3", 1).assert().isEqualTo(VersionedPut.Written)
    }

    @Test
    fun asyncViewMirrorsTheSyncFacade() {
        val async = versioned().async()
        async.put("a", "1").join()
        async.put("b", "2", Duration.ofMinutes(1)).join()
        async.putAll(mapOf("c" to "3")).join()
        async.putAll(mapOf("d" to "4"), Duration.ofMinutes(1)).join()
        async.get("a").join().assert().isEqualTo("1")
        async.getAll(listOf("a", "b", "c", "d")).join().size.assert().isEqualTo(4)
        (async.lookup("a").join() as Lookup.Present).value.assert().isEqualTo("1")
        async.get(
            "e",
            AsyncCacheLoader {
                CompletableFuture.completedFuture("loaded-$it")
            }
        ).join().assert().isEqualTo("loaded-e")
        async.getVersioned("e").join().assert().isEqualTo(Versioned("loaded-e", Versioned.NO_VERSION))
        async.invalidate("a").join()
        async.invalidateAll(listOf("b")).join()
        async.get("a").join().assert().isNull()

        val written = async.putIfMatch("cas", null, "v1").join() as ConditionalPut.Written
        async.getIfChanged("cas", null).join().assert().isEqualTo(ConditionalGet.Present("v1", written.digest))
        async.putIfMatch(
            "cas",
            written.digest,
            "v2",
            Duration.ofMinutes(1)
        ).join().assert().isInstanceOf(ConditionalPut.Written::class.java)

        async.putIfNewer("v", "v1", 1).join().assert().isEqualTo(VersionedPut.Written)
        async.putIfNewer("v", "v0", 0, Duration.ofMinutes(1)).join().assert().isEqualTo(VersionedPut.Stale(1))
        async.invalidateIfNewer("v", 2).join().assert().isEqualTo(VersionedPut.Written)
        async.invalidateIfNewer("v", 3, Duration.ofMinutes(1)).join().assert().isEqualTo(VersionedPut.Written)
        async.getVersioned("v").join().assert().isNull()
    }

    /**
     * 异步视图以原始异常完成：数据源失败、非法版本都不包装为 CompletionException，也不同步抛出。
     */
    @Test
    fun asyncViewCompletesWithTheOriginalException() {
        val failure = IllegalStateException("down")
        val async = versioned { versionedLoader = CacheLoader { throw failure } }.async()
        val loadError = async.get("k").handle { _, error -> error }.join()
        loadError.assert().isSameAs(failure)
        val versionError = async.putIfNewer("k", "v", Versioned.NO_VERSION).handle { _, error -> error }.join()
        (versionError is CompletionException).assert().isFalse()
        versionError.assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 普通的（不带版本的）数据源在带版本的缓存上：回源写入没有版本的值，同步与异步、单个与批量都是。
     */
    @Test
    fun unversionedLoadersFillWithoutAVersion() {
        val sync = versioned("sync") { loader = CacheLoader { "s-$it" } }
        sync.getVersioned("k").assert().isEqualTo(Versioned("s-k", Versioned.NO_VERSION))
        sync.getAll(listOf("a", "b")).assert().isEqualTo(mapOf("a" to "s-a", "b" to "s-b"))
        remoteVersion("a", "sync").assert().isEqualTo(Versioned.NO_VERSION)

        val async = versioned("async") { asyncLoader = AsyncCacheLoader { CompletableFuture.completedFuture("a-$it") } }
        async.async().getVersioned("k").join().assert().isEqualTo(Versioned("a-k", Versioned.NO_VERSION))
        async.async().getAll(listOf("a", "b")).join().assert().isEqualTo(mapOf("a" to "a-a", "b" to "a-b"))
        async.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
    }

    /**
     * 非原子广播的后端：版本更旧时引擎不广播（存储没有修改 L1）。相等的版本可能是重放，仍然广播（`VersionedSpec`）。
     */
    @Test
    fun staleVersionsAreNotBroadcastWithoutAtomicBroadcast() {
        val writer = CacheRuntime.builder().backend(
            HookedBackend(memory).apply { atomicBroadcastOverride = false }
        ).build()
        writer.use {
            val cache = it.create(
                cacheSpec<String, String>("shared") {
                    codec = StringCodec
                    versioned = true
                }
            )
            val observer = versioned("shared")
            cache.putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
            val received = requireNotNull(runtime.metrics("shared")).invalidationsReceived
            received.assert().isGreaterThan(0L)
            cache.putIfNewer("k", "v0", 0).assert().isEqualTo(VersionedPut.Stale(1))
            cache.async().invalidateIfNewer("k", 0).join().assert().isEqualTo(VersionedPut.Stale(1))
            requireNotNull(runtime.metrics("shared")).invalidationsReceived.assert().isEqualTo(received)
            observer["k"].assert().isEqualTo("v1")
        }
    }

    /**
     * 普通缓存（没有声明版本）的按版本操作：同步抛出、异步以 [UnsupportedOperationException] 完成，提示如何声明。
     */
    @Test
    fun unversionedEngineRejectsEveryVersionedOperation() {
        val plain = runtime.create(cacheSpec<String, String>("plain") { codec = StringCodec })
        listOf<() -> Any?>(
            { plain.putIfNewer("k", "v", 1) },
            { plain.putIfNewer("k", "v", 1, Duration.ofSeconds(1)) },
            { plain.invalidateIfNewer("k", 1) },
            { plain.invalidateIfNewer("k", 1, Duration.ofSeconds(1)) },
            { plain.getVersioned("k") },
        ).forEach { call ->
            val error = runCatching { call() }.exceptionOrNull()
            error.assert().isInstanceOf(UnsupportedOperationException::class.java)
            error?.message.assert().contains("versioned")
        }
        val async = plain.async()
        listOf(
            async.putIfNewer("k", "v", 1),
            async.putIfNewer("k", "v", 1, Duration.ofSeconds(1)),
            async.invalidateIfNewer("k", 1),
            async.invalidateIfNewer("k", 1, Duration.ofSeconds(1)),
            async.getVersioned("k"),
        ).forEach { future ->
            future.handle { _, error -> error }.join().assert().isInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    @Test
    fun payloadCodecAndMemoryStoreEdges() {
        val codec = PayloadCodec(StringCodec)
        codec.decode(codec.encode(Versioned("v", 3))).assert().isEqualTo(Versioned("v", Versioned.NO_VERSION))
        val store = memory.store("edges")
        val frame = InvalidationFrame.encode(NodeId.random(), emptyList())
        runCatching { store.putIfNewer("k", Envelope.Members(emptyList()), 1, null, frame, false) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * 按版本写入的默认寿命：值按 `ttlOf`（否则策略寿命），墓碑与数据源返回的带版本的不存在按策略寿命（`ttlOf` 需要值）；
     * 策略寿命为永不过期时都不过期。
     */
    @Test
    fun versionedWritesFollowTtlOfAndForeverPolicies() {
        val cache = versioned("ttl") {
            ttlOf = TtlFunction { _, value -> if (value == "short") Duration.ofSeconds(30) else null }
            versionedLoader = CacheLoader { Versioned(null, 9) }
            policy {
                ttl = Duration.ofMinutes(10)
                ttlJitter = Duration.ZERO
            }
        }
        cache.putIfNewer("short", "short", 1)
        requireNotNull(
            (remote("short", "ttl") as RemoteRead.Hit).ttl
        ).assert().isLessThanOrEqualTo(Duration.ofSeconds(30))
        cache.putIfNewer("long", "long", 1)
        requireNotNull((remote("long", "ttl") as RemoteRead.Hit).ttl).assert().isGreaterThan(Duration.ofMinutes(9))
        cache["deleted"].assert().isNull()
        requireNotNull((remote("deleted", "ttl") as RemoteRead.Hit).ttl).assert().isGreaterThan(Duration.ofMinutes(9))
        cache.putIfNewer("deleted", "old", 9).assert().isEqualTo(VersionedPut.Stale(9))

        val forever = versioned("forever") { policy { ttl = null } }
        forever.putIfNewer("k", "v", 1)
        (remote("k", "forever") as RemoteRead.Hit).ttl.assert().isNull()
        forever.async().invalidateIfNewer("k", 2).join()
        (remote("k", "forever") as RemoteRead.Hit).ttl.assert().isNull()
    }

    /**
     * 后端声明了 `versionedWrites` 但存储没有实现 `putIfNewer`（SPI 默认实现）：[UnsupportedOperationException] 原样传给调用方，
     * 不包装、不计为写入失败的广播。
     */
    @Test
    fun storesWithoutPutIfNewerFailUnwrapped() {
        val claiming = object : CoherenceBackend by memory {
            override val versionedWrites: Boolean = true

            override fun store(namespace: String): RemoteStore {
                val delegate = memory.store(namespace)
                return object : RemoteStore by delegate {
                    override fun putIfNewer(
                        key: String,
                        envelope: Envelope,
                        version: Long,
                        ttl: Duration?,
                        frame: ByteArray,
                        foreignAsAbsent: Boolean
                    ): RemoteVersionedWrite = throw UnsupportedOperationException("no putIfNewer")
                }
            }
        }
        CacheRuntime.builder().backend(claiming).build().use { other ->
            val cache = other.create(
                cacheSpec<String, String>("doc") {
                    codec = StringCodec
                    versioned = true
                }
            )
            runCatching { cache.putIfNewer("k", "v", 1) }.exceptionOrNull()
                .assert().isInstanceOf(UnsupportedOperationException::class.java)
            cache.async().invalidateIfNewer("k", 1).handle { _, error -> error }.join()
                .assert().isInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    /**
     * 调用方给出的 loader 返回 `null`：没有版本的不存在（同步与异步）。
     */
    @Test
    fun callSiteLoadersReturningNullAreAbsent() {
        val cache = versioned("call-site")
        cache.get("a") { null }.assert().isNull()
        cache.async().get("b", AsyncCacheLoader { CompletableFuture.completedFuture(null) }).join().assert().isNull()
        cache.getVersioned("a").assert().isNull()
        val plainAsync = versioned(
            "plain-async"
        ) { asyncLoader = AsyncCacheLoader { CompletableFuture.completedFuture(null) } }
        plainAsync.async().getAll(listOf("x", "y")).join().assert().isEmpty()
    }

    @Test
    fun formatsRefuseToEncodeAVersionedAbsence() {
        runCatching { VersionedFormat(StringCodec).encode(Versioned(null, 1)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { PayloadCodec(StringCodec).encode(Versioned(null, 1)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        VersionedFormat(StringCodec).versionOf(Versioned("v", 3)).assert().isEqualTo(3L)
        CodecFormat(StringCodec).versionOf("v").assert().isEqualTo(Versioned.NO_VERSION)
    }
}
