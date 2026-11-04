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
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.ForwardingL1Store
import me.ahoo.cocache.api.spi.ForwardingVersionedL1Store
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.VersionedL1Store
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.StrippedBackend
import me.ahoo.cocache.core.TtlFunction
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 带版本的缓存在 TCK 之外的边界：声明校验、创建时的后端检查、门面上的普通读写（不带版本）、异步视图的异常、
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

    private fun remote(key: String, name: String = "doc"): L1Read =
        Suspending.await { memory.store(Keyspace("cocache:", name)).read("cocache:$name:$key", null, null, null) }

    private fun remoteVersion(key: String, name: String): Long {
        val hit = remote(key, name) as L1Read.Hit
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
    fun specAcceptsRefreshAheadButNotSeveralLoaders() {
        // 带版本的内容取得刷新令牌（RFC 0005 §4.7）：带版本的缓存照常提前刷新
        cacheSpec<String, String>("v") {
            codec = StringCodec
            versioned = true
            policy { refreshWindow = Duration.ofSeconds(10) }
        }.policy.refreshWindow.assert().isEqualTo(Duration.ofSeconds(10))
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
        val plain = StrippedBackend(memory, versioned = false)
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
            error?.message.assert().contains("capabilities[versioned]")
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
        requireNotNull((remote("short") as L1Read.Hit).ttl).assert().isLessThanOrEqualTo(Duration.ofSeconds(30))
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
     * 非原子广播的后端上，版本前进的填充（`E` 上取得的 lease）由引擎广播：广播失败只计数告警，回源值照常返回，版本已写入 L1。
     */
    @Test
    fun failedBroadcastOfAnAdvancingFillIsCounted() {
        val failing = object : CoherenceBackend by memory {
            override val atomicBroadcast: Boolean = false
            override val channel: InvalidationChannel = object : InvalidationChannel by memory.channel {
                override suspend fun publish(keyspace: Keyspace, frame: ByteArray) = throw IllegalStateException(
                    "publish down"
                )
            }
        }
        versioned("evicted").putIfNewer("k", "v1", 1).assert().isEqualTo(VersionedPut.Written)
        requireNotNull(runtime.admin("evicted")).invalidateEncoded("k")
        CacheRuntime.builder().backend(failing).build().use { other ->
            val cache = other.create(
                cacheSpec<String, String>("evicted") {
                    codec = StringCodec
                    versionedLoader = CacheLoader { Versioned("v2", 2) }
                }
            )
            cache["k"].assert().isEqualTo("v2")
            requireNotNull(other.metrics("evicted")).remoteFailures.assert().isEqualTo(1)
        }
        remoteVersion("k", "evicted").assert().isEqualTo(2)
    }

    /**
     * 刷新路径的版本拒绝，而取得令牌时读到的更新的值解码失败：不能以它重新填充 L2，按未命中路径降级（回源值以降级限龄写入 L2），
     * L1 中更新的版本不变。
     */
    @Test
    fun versionRejectedRefreshWithAnUndecodableCopyDegrades() {
        val clock = ManualClock()
        val backend = InMemoryBackend(clock)
        val failDecode = AtomicBoolean(false)
        val codec = object : Codec<String> {
            override fun encode(value: String): ByteArray = StringCodec.encode(value)

            override fun decode(bytes: ByteArray): String =
                if (failDecode.get()) throw IllegalStateException("codec down") else StringCodec.decode(bytes)
        }
        CacheRuntime.builder().backend(backend).clock(clock).refreshExecutor { it.run() }.build().use { other ->
            val cache = other.create(
                cacheSpec<String, String>("refresh") {
                    this.codec = codec
                    versionedLoader = CacheLoader { Versioned("v4", 4) }
                    policy {
                        ttl = Duration.ofSeconds(10)
                        ttlJitter = Duration.ZERO
                        localMaxAge = Duration.ofSeconds(8)
                        refreshWindow = Duration.ofSeconds(5)
                    }
                }
            )
            cache.putIfNewer("k", "v5", 5).assert().isEqualTo(VersionedPut.Written)
            cache["k"].assert().isEqualTo("v5")
            clock.advance(Duration.ofSeconds(6))
            failDecode.set(true)
            cache["k"].assert().isEqualTo("v5")
            val metrics = requireNotNull(other.metrics("refresh"))
            Concurrency.awaitCondition("the refresh was rejected") { metrics.fillRejected == 1L }
            metrics.degradedFills.assert().isEqualTo(1)
            val hit = Suspending.await {
                backend.store(Keyspace("cocache:", "refresh")).read("cocache:refresh:k", null, null, null)
            } as L1Read.Hit
            (hit.envelope as Envelope.Scalar).version.assert().isEqualTo(5L)
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
        val store = requireNotNull(memory.store(Keyspace("cocache:", "edges")).versioned())
        val frame = InvalidationFrame.encode(NodeId.random(), emptyList())
        runCatching { Suspending.await { store.putIfNewer("k", Envelope.Members(emptyList()), 1, null, frame) } }
            .exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
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
            (remote("short", "ttl") as L1Read.Hit).ttl
        ).assert().isLessThanOrEqualTo(Duration.ofSeconds(30))
        cache.putIfNewer("long", "long", 1)
        requireNotNull((remote("long", "ttl") as L1Read.Hit).ttl).assert().isGreaterThan(Duration.ofMinutes(9))
        cache["deleted"].assert().isNull()
        requireNotNull((remote("deleted", "ttl") as L1Read.Hit).ttl).assert().isGreaterThan(Duration.ofMinutes(9))
        cache.putIfNewer("deleted", "old", 9).assert().isEqualTo(VersionedPut.Stale(9))

        val forever = versioned("forever") { policy { ttl = null } }
        forever.putIfNewer("k", "v", 1)
        (remote("k", "forever") as L1Read.Hit).ttl.assert().isNull()
        forever.async().invalidateIfNewer("k", 2).join()
        (remote("k", "forever") as L1Read.Hit).ttl.assert().isNull()
    }

    /**
     * 存储提供了按版本写入的能力面，但它以 [UnsupportedOperationException] 拒绝 `putIfNewer`：原样传给调用方，
     * 不包装、不计为写入失败的广播。
     */
    @Test
    fun storesRefusingPutIfNewerFailUnwrapped() {
        val claiming = object : CoherenceBackend by memory {
            override fun store(keyspace: Keyspace): L1Store = object : ForwardingL1Store(memory.store(keyspace)) {
                private val refusing = delegate.versioned()?.let { surface ->
                    object : ForwardingVersionedL1Store(surface) {
                        override suspend fun putIfNewer(
                            key: String,
                            envelope: Envelope,
                            version: Long,
                            ttl: Duration?,
                            frame: ByteArray
                        ): L1VersionedWrite = throw UnsupportedOperationException("no putIfNewer")
                    }
                }

                override fun versioned(): VersionedL1Store? = refusing
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
        runCatching { VersionedFormat(StringCodec).encode(Versioned(null, 1), null, false) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching { PayloadCodec(StringCodec).encode(Versioned(null, 1)) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        VersionedFormat(StringCodec).versionOf(Versioned("v", 3)).assert().isEqualTo(3L)
        CodecFormat(StringCodec).versionOf("v").assert().isEqualTo(Versioned.NO_VERSION)
    }
}
