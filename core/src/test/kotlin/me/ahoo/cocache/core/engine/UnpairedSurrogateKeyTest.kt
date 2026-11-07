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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedCache
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.FillKey
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.KeyInspection.LocalState
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 含未配对 UTF-16 代理的 key 在五种类型、三种变体与运维接口上都在到达 L1 之前被拒绝：同步与挂起变体抛出
 * [IllegalArgumentException]，异步视图不同步抛出、以失败的 future 交出。既不读写 L1、不回源、不广播，也不进入 L2。
 * 自定义 `KeyEncoder` 的输出同样检查；合法的 key（含正确配对的代理）不受影响。
 */
class UnpairedSurrogateKeyTest {
    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val backend = HookedBackend(memory)
    private val runtime = CacheRuntime.builder().backend(backend).clock(clock).build()
    private val loads = AtomicInteger()
    private val frames = AtomicInteger()

    @AfterEach
    fun destroy() {
        runtime.close()
    }

    /**
     * 发往 [name] 的 keyspace 的帧都计入 [frames]（包括本节点自己的）。
     */
    private fun countFrames(name: String) {
        memory.channel.subscribe(
            Keyspace("cocache:", name),
            object : InvalidationListener {
                override fun onInvalidated(keys: List<String>, origin: NodeId) {
                    frames.incrementAndGet()
                }

                override fun onReset() = Unit
            }
        )
    }

    private val counted = listOf(
        backend.reads,
        backend.readAlls,
        backend.fills,
        backend.puts,
        backend.putAlls,
        loads,
        frames
    )

    private fun counters(): List<Int> = counted.map { it.get() }

    private fun assertRejected(name: String, operation: String, error: Throwable?) {
        error.assert().withFailMessage { "$operation: $error" }.isInstanceOf(IllegalArgumentException::class.java)
        requireNotNull(error).message.assert().withFailMessage { "$operation: ${error.message}" }
            .contains("Cache[$name]", "index[2]", "key[k-\\uDC00]")
            .doesNotContain(BAD)
    }

    /**
     * [sync] 与 [suspending] 的调用抛出，[async] 的调用返回失败的 future（不同步抛出）；之后计数不变，L2 中没有副本。
     * 运维接口同样拒绝（[versioned] 时也检查只有带版本的类型支持的 `purgeEncoded`）。
     */
    private fun assertRejectedEverywhere(
        name: String,
        sync: Map<String, () -> Any?>,
        async: Map<String, () -> CompletableFuture<*>> = emptyMap(),
        suspending: Map<String, suspend () -> Any?> = emptyMap(),
        versioned: Boolean = false,
    ) {
        val before = counters()
        sync.forEach { (operation, call) -> assertRejected(name, operation, runCatching { call() }.exceptionOrNull()) }
        async.forEach { (operation, call) ->
            val future = call()
            assertRejected(name, "async $operation", future.handle { _, error -> error }.join())
        }
        suspending.forEach { (operation, call) ->
            assertRejected(name, "suspending $operation", runCatching { Suspending.await(call) }.exceptionOrNull())
        }
        counters().assert().isEqualTo(before)
        val admin = requireNotNull(runtime.admin(name))
        admin.localSize.assert().isZero()
        admin.pendingInvalidationCount.assert().isZero()
        val adminCalls = mutableMapOf<String, () -> Any?>(
            "inspect" to { admin.inspect(BAD) },
            "invalidateEncoded" to { admin.invalidateEncoded(BAD) },
            "broadcastEncoded" to { admin.broadcastEncoded(BAD) },
        )
        if (versioned) {
            adminCalls["purgeEncoded"] = { admin.purgeEncoded(BAD) }
        }
        adminCalls.forEach { (operation, call) ->
            assertRejected(name, "admin $operation", runCatching { call() }.exceptionOrNull())
        }
        counters().assert().isEqualTo(before)
    }

    @Test
    fun cacheRejectsOnEveryVariant() {
        countFrames("user")
        val cache: Cache<String, String> = runtime.create(
            cacheSpec("user") {
                codec = StringCodec
                loader = CacheLoader {
                    loads.incrementAndGet()
                    "loaded"
                }
            }
        )
        val loader = CacheLoader<String, String> { "called" }
        assertRejectedEverywhere(
            "user",
            sync = mapOf(
                "get" to { cache[BAD] },
                "get(loader)" to { cache.get(BAD, loader) },
                "getAll" to { cache.getAll(listOf(BAD)) },
                "lookup" to { cache.lookup(BAD) },
                "lookupAll" to { cache.lookupAll(listOf(BAD)) },
                "put" to { cache.put(BAD, "v") },
                "put(ttl)" to { cache.put(BAD, "v", Duration.ofMinutes(1)) },
                "putAll" to { cache.putAll(mapOf("ok" to "v", BAD to "v")) },
                "invalidate" to { cache.invalidate(BAD) },
                "invalidateAll" to { cache.invalidateAll(listOf("ok", BAD)) },
                "evictLocal" to { FillKey.of(cache, BAD)?.evictLocal() },
            ),
            async = mapOf(
                "get" to { cache.async().get(BAD) },
                "get(loader)" to { cache.async().get(BAD) { CompletableFuture.completedFuture("called") } },
                "getAll" to { cache.async().getAll(listOf(BAD)) },
                "lookup" to { cache.async().lookup(BAD) },
                "put" to { cache.async().put(BAD, "v") },
                "putAll" to { cache.async().putAll(mapOf(BAD to "v")) },
                "invalidate" to { cache.async().invalidate(BAD) },
                "invalidateAll" to { cache.async().invalidateAll(listOf(BAD)) },
            ),
            suspending = mapOf(
                "get" to { cache.suspending().get(BAD) },
                "get(loader)" to { cache.suspending().get(BAD) { "called" } },
                "getAll" to { cache.suspending().getAll(listOf(BAD)) },
                "lookup" to { cache.suspending().lookup(BAD) },
                "put" to { cache.suspending().put(BAD, "v") },
                "invalidate" to { cache.suspending().invalidate(BAD) },
            ),
        )
        // 批量写入整批拒绝：合法的 key 也没有写入
        cache["ok"].assert().isEqualTo("loaded")
    }

    @Test
    fun versionedCacheRejects() {
        countFrames("order")
        val cache: VersionedCache<String, String> = runtime.create(
            versionedCacheSpec("order") {
                codec = StringCodec
                versionedLoader = CacheLoader {
                    loads.incrementAndGet()
                    Versioned("loaded", 1)
                }
            }
        )
        assertRejectedEverywhere(
            "order",
            sync = mapOf(
                "get" to { cache[BAD] },
                "getVersioned" to { cache.getVersioned(BAD) },
                "getAll" to { cache.getAll(listOf(BAD)) },
                "putIfNewer" to { cache.putIfNewer(BAD, "v", 2) },
                "invalidateIfNewer" to { cache.invalidateIfNewer(BAD, 2) },
                "evict" to { cache.evict(BAD) },
                "purge" to { cache.purge(BAD) },
            ),
            async = mapOf(
                "getVersioned" to { cache.async().getVersioned(BAD) },
                "putIfNewer" to { cache.async().putIfNewer(BAD, "v", 2) },
                "evict" to { cache.async().evict(BAD) },
                "purge" to { cache.async().purge(BAD) },
            ),
            suspending = mapOf(
                "getVersioned" to { cache.suspending().getVersioned(BAD) },
                "evict" to { cache.suspending().evict(BAD) },
            ),
            versioned = true,
        )
    }

    @Test
    fun storeRejects() {
        countFrames("token")
        val store: Store<String, String> = runtime.create(storeSpec("token") { codec = StringCodec })
        val digest = Digest.ofPayload("v".toByteArray())
        assertRejectedEverywhere(
            "token",
            sync = mapOf(
                "get" to { store[BAD] },
                "lookup" to { store.lookup(BAD) },
                "put" to { store.put(BAD, "v") },
                "putAll" to { store.putAll(mapOf(BAD to "v")) },
                "delete" to { store.delete(BAD) },
                "getIfChanged" to { store.getIfChanged(BAD, null) },
                "putIfMatch" to { store.putIfMatch(BAD, null, "v") },
                "deleteIfMatch" to { store.deleteIfMatch(BAD, digest) },
            ),
            async = mapOf(
                "get" to { store.async().get(BAD) },
                "put" to { store.async().put(BAD, "v") },
                "delete" to { store.async().delete(BAD) },
                "getIfChanged" to { store.async().getIfChanged(BAD, null) },
            ),
            suspending = mapOf(
                "put" to { store.suspending().put(BAD, "v") },
                "delete" to { store.suspending().delete(BAD) },
            ),
        )
    }

    @Test
    fun versionedStoreRejects() {
        countFrames("ledger")
        val store: VersionedStore<String, String> = runtime.create(versionedStoreSpec("ledger") { codec = StringCodec })
        assertRejectedEverywhere(
            "ledger",
            sync = mapOf(
                "get" to { store[BAD] },
                "getVersioned" to { store.getVersioned(BAD) },
                "putIfNewer" to { store.putIfNewer(BAD, "v", 1) },
                "deleteIfNewer" to { store.deleteIfNewer(BAD, 1) },
                "purge" to { store.purge(BAD) },
            ),
            async = mapOf(
                "deleteIfNewer" to { store.async().deleteIfNewer(BAD, 1) },
                "purge" to { store.async().purge(BAD) },
            ),
            suspending = mapOf(
                "putIfNewer" to { store.suspending().putIfNewer(BAD, "v", 1) },
                "purge" to { store.suspending().purge(BAD) },
            ),
            versioned = true,
        )
    }

    @Test
    fun setStoreRejects() {
        countFrames("index")
        val index: SetStore<String, String> = runtime.create(setStoreSpec("index") { elementCodec = StringCodec })
        assertRejectedEverywhere(
            "index",
            sync = mapOf(
                "get" to { index[BAD] },
                "getAll" to { index.getAll(listOf(BAD)) },
                "add" to { index.add(BAD, listOf("a")) },
                "remove" to { index.remove(BAD, listOf("a")) },
                "clear" to { index.clear(BAD) },
                "put" to { index.put(BAD, setOf("a")) },
                "putAll" to { index.putAll(mapOf("ok" to setOf("a"), BAD to setOf("a"))) },
            ),
            async = mapOf(
                "get" to { index.async().get(BAD) },
                "add" to { index.async().add(BAD, listOf("a")) },
                "clear" to { index.async().clear(BAD) },
            ),
            suspending = mapOf(
                "get" to { index.suspending().get(BAD) },
                "clear" to { index.suspending().clear(BAD) },
            ),
        )
        index["ok"].assert().isEmpty()
    }

    /**
     * 检查的是编码后的 key：自定义 `KeyEncoder` 产生未配对的代理同样被拒绝（下标按编码后的 key 计）；
     * 产生补充平面字符（正确配对的代理）的编码照常读写、失效与查看。
     */
    @Test
    fun checksTheOutputOfACustomKeyEncoder() {
        val broken: Cache<Int, String> = runtime.create(
            cacheSpec("broken") {
                codec = StringCodec
                keyEncoder = KeyEncoder { "k-" + it.toChar() }
                loader = CacheLoader { "v$it" }
            }
        )
        assertRejected("broken", "get", runCatching { broken[BAD.last().code] }.exceptionOrNull())
        assertRejected("broken", "put", runCatching { broken.put(BAD.last().code, "v") }.exceptionOrNull())

        val emoji: Cache<Int, String> = runtime.create(
            cacheSpec("emoji") {
                codec = StringCodec
                keyEncoder = KeyEncoder { "😀-$it" }
                loader = CacheLoader {
                    loads.incrementAndGet()
                    "v$it"
                }
            }
        )
        emoji[1].assert().isEqualTo("v1")
        val admin = requireNotNull(runtime.admin("emoji"))
        requireNotNull(admin.inspect("😀-1").local).state.assert().isEqualTo(LocalState.VALUE)
        emoji.put(1, "w1")
        emoji[1].assert().isEqualTo("w1")
        emoji.invalidate(1)
        emoji[1].assert().isEqualTo("v1")
        loads.get().assert().isEqualTo(2)
    }

    /**
     * 批量读取整批拒绝，与 key 的顺序无关（#866）：前面的 key 命中 L2 且已进入刷新窗口时，后面不能无损编码的 key
     * 仍让整批在任何副作用之前失败：不触发提前刷新、不回源、L1 不变，命中与未命中也都不计数。三种变体相同。
     */
    @Test
    fun aLaterUnpairedKeyRejectsTheBatchBeforeRefreshingAnEarlierHit() {
        Variant.entries.forEach { verifyBatchRefresh(Placement.LAST, it) }
    }

    @Test
    fun anUnpairedKeyAtTheStartRejectsTheBatchBeforeAnyRefresh() {
        Variant.entries.forEach { verifyBatchRefresh(Placement.FIRST, it) }
    }

    /**
     * 对照：合法的批量照常触发提前刷新，L1 得到新值。
     */
    @Test
    fun aValidBatchStillRefreshesAnEligibleHit() {
        Variant.entries.forEach { verifyBatchRefresh(Placement.NONE, it) }
    }

    private enum class Placement { FIRST, LAST, NONE }

    private enum class Variant { SYNC, ASYNC, SUSPENDING }

    private fun verifyBatchRefresh(placement: Placement, variant: Variant) {
        val clock = ManualClock(0)
        val calls = AtomicInteger()
        val source = AtomicReference("old")
        InMemoryBackend(clock).use { backend ->
            CacheRuntime.builder()
                .backend(backend)
                .clock(clock)
                .recordLocalHits(true)
                .refreshExecutor { it.run() }
                .build()
                .use { runtime ->
                    val cache = refreshingCache(runtime, calls, source)
                    cache["valid"].assert().isEqualTo("old")
                    calls.get().assert().isEqualTo(1)
                    source.set("new")
                    // valid 仍命中 L2，且已进入刷新窗口
                    clock.advance(Duration.ofSeconds(6))
                    assertBatchRead(runtime, cache, placement, variant, calls)
                }
        }
    }

    private fun refreshingCache(runtime: CacheRuntime, calls: AtomicInteger, source: AtomicReference<String>) =
        runtime.create(
            cacheSpec<String, String>("batch-refresh") {
                codec = StringCodec
                loader = CacheLoader {
                    calls.incrementAndGet()
                    source.get()
                }
                policy {
                    ttl = Duration.ofSeconds(10)
                    ttlJitter = Duration.ZERO
                    localMaxAge = Duration.ofSeconds(8)
                    refreshWindow = Duration.ofSeconds(5)
                }
            }
        )

    /**
     * 被拒的批量没有任何副作用：不回源、L1 不变、命中与未命中都不计数；合法的批量照常刷新。
     */
    private fun assertBatchRead(
        runtime: CacheRuntime,
        cache: Cache<String, String>,
        placement: Placement,
        variant: Variant,
        calls: AtomicInteger,
    ) {
        val label = "$placement/$variant"
        val keys = when (placement) {
            Placement.LAST -> listOf("valid", BAD)
            Placement.FIRST -> listOf(BAD, "valid")
            Placement.NONE -> listOf("valid")
        }
        val admin = requireNotNull(runtime.admin(cache.name))
        val hitsBefore = admin.metrics.localHits
        val missesBefore = admin.metrics.misses
        val failure = runCatching {
            when (variant) {
                Variant.SYNC -> cache.getAll(keys)
                Variant.ASYNC -> cache.async().getAll(keys).join()
                Variant.SUSPENDING -> Suspending.await { cache.suspending().getAll(keys) }
            }
        }.exceptionOrNull()
        val rejected = placement != Placement.NONE
        if (rejected) {
            assertRejected(cache.name, label, if (failure is CompletionException) failure.cause else failure)
        } else {
            failure.assert().withFailMessage { "$label: $failure" }.isNull()
        }
        calls.get().assert().withFailMessage { "$label: source calls" }.isEqualTo(if (rejected) 1 else 2)
        admin.inspect("valid").remote.value.assert().withFailMessage { label }
            .isEqualTo(if (rejected) "old" else "new")
        admin.metrics.localHits.assert().withFailMessage { "$label: L2 hits" }
            .isEqualTo(if (rejected) hitsBefore else hitsBefore + 1)
        admin.metrics.misses.assert().withFailMessage { label }.isEqualTo(missesBefore)
    }

    /**
     * SetStore 的批量读取同样整批拒绝：被拒的批量不计入前面 key 的 L2 命中。
     */
    @Test
    fun aRejectedSetBatchCountsNoHit() {
        InMemoryBackend(clock).use { backend ->
            CacheRuntime.builder().backend(backend).clock(clock).recordLocalHits(true).build().use { runtime ->
                verifyRejectedSetBatch(runtime)
            }
        }
    }

    private fun verifyRejectedSetBatch(runtime: CacheRuntime) {
        val index: SetStore<String, String> = runtime.create(setStoreSpec("hits-index") { elementCodec = StringCodec })
        index.put("ok", setOf("a"))
        // 写入方不填 L2：这次读取经 L1 填充 L2
        index["ok"].assert().containsExactly("a")
        val metrics = requireNotNull(runtime.admin("hits-index")).metrics
        val before = metrics.localHits
        assertRejected("hits-index", "getAll", runCatching { index.getAll(listOf("ok", BAD)) }.exceptionOrNull())
        metrics.localHits.assert().isEqualTo(before)
        index.getAll(listOf("ok")).assert().containsExactlyEntriesOf(mapOf("ok" to setOf("a")))
        metrics.localHits.assert().isEqualTo(before + 1)
    }

    private companion object {
        /**
         * 下标 2 处是未配对的低代理。
         */
        const val BAD = "k-\uDC00"
    }
}
