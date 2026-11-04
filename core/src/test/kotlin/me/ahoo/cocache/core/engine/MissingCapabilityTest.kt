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

import me.ahoo.cocache.api.CacheClock
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.RetryPolicy
import me.ahoo.cocache.core.StrippedBackend
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedCacheSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 能力面为 `null` 时，条件读写、按版本写入与集合成员的增删在调用时以 [UnsupportedOperationException] 失败（RFC 0005 §4.3）：
 * 同步抛出，异步以失败的 future 交出，修改任何一层之前就失败。`runtime.create` 在创建时已拒绝这样的缓存，
 * 这里绕过运行时直接创建引擎，检验引擎自身的防线。
 */
class MissingCapabilityTest {
    private val memory = InMemoryBackend()
    private val backend = StrippedBackend(memory, conditional = false, versioned = false, sets = false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }
    private val async = AsyncContext(DIRECT, DIRECT)
    private val context = EngineContext(
        NodeId.random(),
        CacheClock { System.currentTimeMillis() },
        backend,
        DIRECT,
        InvalidationRetrier(scheduler, RetryPolicy()),
        LoadObserver.NOOP,
        async,
    )

    @AfterEach
    fun close() {
        async.close()
        scheduler.shutdownNow()
    }

    private fun unwrap(error: Throwable?): Throwable? = if (error is CompletionException) error.cause else error

    private fun assertUnsupported(error: Throwable?, operation: String, capability: String) {
        error.assert().isInstanceOf(UnsupportedOperationException::class.java)
        error?.message.assert().contains("$operation() needs the $capability capability")
    }

    @Test
    fun versionedWritesFailAtCallTimeWithoutTheVersionedCapability() {
        val spec = versionedCacheSpec<String, String>("doc") {
            codec = StringCodec
            versionedLoader = CacheLoader { null }
        }
        val cache = VersionedCacheEngine(spec.engineSpec(), backend.store(Keyspace("cocache:", "doc")), context)

        assertUnsupported(runCatching { cache.putIfNewer("k", "v", 1) }.exceptionOrNull(), "putIfNewer", "versioned")
        assertUnsupported(
            runCatching { cache.invalidateIfNewer("k", 1) }.exceptionOrNull(),
            "invalidateIfNewer",
            "versioned"
        )
        assertUnsupported(runCatching { cache.evict("k") }.exceptionOrNull(), "evictKeepingVersion", "versioned")
        assertUnsupported(
            unwrap(cache.async().putIfNewer("k", "v", 1).handle { _, e -> e }.join()),
            "putIfNewer",
            "versioned"
        )
        val store = VersionedStoreEngine(
            versionedStoreSpec<String, String>("doc") { codec = StringCodec }.engineSpec(),
            backend.store(Keyspace("cocache:", "doc")),
            context
        )
        assertUnsupported(runCatching { store.deleteIfNewer("k", 1) }.exceptionOrNull(), "deleteIfNewer", "versioned")
        assertUnsupported(
            unwrap(store.async().putIfNewer("k", "v", 1).handle { _, e -> e }.join()),
            "putIfNewer",
            "versioned"
        )
        Suspending.await {
            memory.store(
                Keyspace("cocache:", "doc")
            ).read("cocache:doc:k", null, null, null)
        }.assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * `Store` 的条件读写与条件删除需要条件读写能力面。
     */
    @Test
    fun conditionalOperationsFailAtCallTimeWithoutTheConditionalCapability() {
        val store = StoreEngine(
            storeSpec<String, String>("policy") { codec = StringCodec }.engineSpec(),
            backend.store(Keyspace("cocache:", "policy")),
            context
        )
        val digest = Digest.ofPayload("v".toByteArray())
        assertUnsupported(
            runCatching { store.getIfChanged("k", null) }.exceptionOrNull(),
            "getIfChanged",
            "conditional"
        )
        assertUnsupported(
            runCatching { store.putIfMatch("k", null, "v") }.exceptionOrNull(),
            "putIfMatch",
            "conditional"
        )
        assertUnsupported(
            runCatching { store.deleteIfMatch("k", digest) }.exceptionOrNull(),
            "deleteIfMatch",
            "conditional"
        )
        assertUnsupported(
            unwrap(store.async().deleteIfMatch("k", digest).handle { _, e -> e }.join()),
            "deleteIfMatch",
            "conditional"
        )
        store.metrics.writeFailures.assert().isZero()
    }

    @Test
    fun memberWritesFailAtCallTimeWithoutTheSetsCapability() {
        val engine =
            SetEngine(
                setStoreSpec<String, String>("index") { elementCodec = StringCodec },
                backend.store(Keyspace("cocache:", "index")),
                context
            )
        engine.put("k", setOf("a"))
        engine["k"].assert().containsExactly("a")

        assertUnsupported(runCatching { engine.add("k", listOf("b")) }.exceptionOrNull(), "add", "sets")
        assertUnsupported(runCatching { engine.remove("k", listOf("a")) }.exceptionOrNull(), "remove", "sets")
        assertUnsupported(unwrap(engine.async().add("k", listOf("b")).handle { _, e -> e }.join()), "add", "sets")
        assertUnsupported(unwrap(engine.async().remove("k", listOf("a")).handle { _, e -> e }.join()), "remove", "sets")
        // 失败在修改任何一层之前：L2 的副本仍在，没有计为写入失败
        engine.localSize.assert().isEqualTo(1)
        engine.metrics.writeFailures.assert().isZero()
        engine["k"].assert().containsExactly("a")
    }

    private companion object {
        val DIRECT = Executor { it.run() }
    }
}
