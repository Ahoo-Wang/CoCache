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

package me.ahoo.cocache.api

import me.ahoo.cocache.api.spi.Digest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * 异步 API 的默认方法（RFC 0004 §3）。
 */
class AsyncApiDefaultsTest {
    @Test
    fun loadAllAsyncDefaultsToSingleLoadsAndSkipsAbsent() {
        val loader = AsyncCacheLoader<String, String> { key ->
            CompletableFuture.completedFuture(if (key == "absent") null else key.uppercase())
        }
        loader.loadAllAsync(listOf("a", "absent", "b")).toCompletableFuture().join()
            .assert().isEqualTo(mapOf("a" to "A", "b" to "B"))
    }

    @Test
    fun loadAllAsyncDefaultFailsWhenAnyLoadFails() {
        val failure = IllegalStateException("down")
        val failing = AsyncCacheLoader<String, String> { key ->
            if (key == "bad") throw failure else CompletableFuture.completedFuture(key)
        }
        val error = runCatching {
            failing.loadAllAsync(
                listOf("a", "bad")
            ).toCompletableFuture().join()
        }.exceptionOrNull()
        error.assert().isInstanceOf(CompletionException::class.java)
        error?.cause.assert().isSameAs(failure)
    }

    private class PlainSetCache : SetCache<String, String> {
        override val name: String = "plain"
        override fun get(key: String): Set<String> = emptySet()
        override fun get(key: String, loader: CacheLoader<String, Set<String>>): Set<String>? = null
        override fun getAll(keys: Collection<String>): Map<String, Set<String>> = emptyMap()
        override fun lookup(key: String): Lookup<Set<String>> = Lookup.Absent(null)
        override fun put(key: String, value: Set<String>) = Unit
        override fun put(key: String, value: Set<String>, ttl: Duration) = Unit
        override fun invalidate(key: String) = Unit
        override fun invalidateAll(keys: Collection<String>) = Unit
        override fun add(key: String, elements: Collection<String>) = Unit
        override fun remove(key: String, elements: Collection<String>) = Unit
        override fun clear(key: String) = Unit
    }

    @Test
    fun implementationsWithoutAsyncSupportFailLoudly() {
        val set = PlainSetCache()
        runCatching { set.async() }.exceptionOrNull().assert().isInstanceOf(UnsupportedOperationException::class.java)
        runCatching { (set as Cache<String, Set<String>>).async() }.exceptionOrNull()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
    }

    private class PlainAsyncCache : AsyncCache<String, String> {
        override val name: String = "plain"
        override fun get(key: String): CompletableFuture<String?> = CompletableFuture.completedFuture(null)
        override fun get(key: String, loader: AsyncCacheLoader<String, String>): CompletableFuture<String?> =
            CompletableFuture.completedFuture(null)
        override fun getAll(
            keys: Collection<String>
        ): CompletableFuture<Map<String, String>> = CompletableFuture.completedFuture(
            emptyMap()
        )
        override fun lookup(key: String): CompletableFuture<Lookup<String>> = CompletableFuture.completedFuture(
            Lookup.Absent(null)
        )
        override fun put(key: String, value: String): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)
        override fun put(
            key: String,
            value: String,
            ttl: Duration
        ): CompletableFuture<Void?> = CompletableFuture.completedFuture(
            null
        )
        override fun putAll(entries: Map<String, String>): CompletableFuture<Void?> = CompletableFuture.completedFuture(
            null
        )
        override fun putAll(
            entries: Map<String, String>,
            ttl: Duration
        ): CompletableFuture<Void?> = CompletableFuture.completedFuture(
            null
        )
        override fun invalidate(key: String): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)
        override fun invalidateAll(
            keys: Collection<String>
        ): CompletableFuture<Void?> = CompletableFuture.completedFuture(
            null
        )
        override fun sync(): Cache<String, String> = throw UnsupportedOperationException()
    }

    /**
     * 条件读写是 6.7 新增的默认方法：不支持的实现同步 API 抛出、异步 API 以 [UnsupportedOperationException] 完成（从不同步抛出）。
     */
    @Test
    fun implementationsWithoutConditionalSupportFailLoudly() {
        val set = PlainSetCache()
        val known = Digest(ByteArray(20))
        runCatching {
            set.getIfChanged("k", known)
        }.exceptionOrNull().assert().isInstanceOf(UnsupportedOperationException::class.java)
        runCatching {
            set.putIfMatch("k", null, setOf("a"))
        }.exceptionOrNull().assert().isInstanceOf(UnsupportedOperationException::class.java)
        runCatching { set.putIfMatch("k", known, setOf("a"), Duration.ofSeconds(1)) }.exceptionOrNull()
            .assert().isInstanceOf(UnsupportedOperationException::class.java)

        val async = PlainAsyncCache()
        listOf(
            async.getIfChanged("k", null),
            async.putIfMatch("k", null, "v"),
            async.putIfMatch("k", known, "v", Duration.ofSeconds(1)),
        ).forEach { future ->
            future.handle { _, error -> error }.join().assert().isInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    /**
     * 按版本有序的写入是 6.8 新增的默认方法：不支持的实现同步 API 抛出、异步 API 以 [UnsupportedOperationException] 完成。
     */
    @Test
    fun implementationsWithoutVersionSupportFailLoudly() {
        val set = PlainSetCache()
        listOf<() -> Any?>(
            { set.putIfNewer("k", setOf("a"), 1) },
            { set.putIfNewer("k", setOf("a"), 1, Duration.ofSeconds(1)) },
            { set.invalidateIfNewer("k", 1) },
            { set.invalidateIfNewer("k", 1, Duration.ofSeconds(1)) },
            { set.getVersioned("k") },
        ).forEach { call ->
            runCatching { call() }.exceptionOrNull().assert().isInstanceOf(UnsupportedOperationException::class.java)
        }

        val async = PlainAsyncCache()
        listOf(
            async.putIfNewer("k", "v", 1),
            async.putIfNewer("k", "v", 1, Duration.ofSeconds(1)),
            async.invalidateIfNewer("k", 1),
            async.invalidateIfNewer("k", 1, Duration.ofSeconds(1)),
            async.getVersioned("k"),
        ).forEach { future ->
            future.handle { _, error -> error }.join().assert().isInstanceOf(UnsupportedOperationException::class.java)
        }
        Versioned("v", 2).assert().isEqualTo(Versioned("v", 2))
        VersionedPut.Stale(2).current.assert().isEqualTo(2L)
    }
}
