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
}
