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

package me.ahoo.cocache.core.suspending

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.SourceLoadTimeoutException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 挂起视图直接调用协程核心（RFC 0005 §4.4）的各条路径与同步 API 的结果一致：L2 命中内联返回（含调用级 loader 与全部命中的批量），
 * 批量中的重复 key，`Store` 与 `SetStore` 的带寿命写入与条件读写，调用级 loader 的 `load.timeout`。
 */
class SuspendingViewsTest {
    private val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
    private val loads = AtomicInteger()

    @AfterEach
    fun destroy() = runtime.close()

    /**
     * 调用级 loader 的读取命中 L2 时不回源；批量读取全部命中时内联返回，重复的 key 只读一次。
     */
    @Test
    fun cacheHitsAreServedInline(): Unit = runBlocking<Unit> {
        val cache = runtime.create(
            cacheSpec<String, String>("cache") {
                codec = StringCodec
                loader = CacheLoader {
                    loads.incrementAndGet()
                    "l-$it"
                }
            }
        ).suspending()
        cache.get("a") { "call-$it" }.assert().isEqualTo("call-a")
        cache.get("a") { "never" }.assert().isEqualTo("call-a")
        cache.getAll(listOf("b", "b", "c")).assert().isEqualTo(mapOf("b" to "l-b", "c" to "l-c"))
        loads.get().assert().isEqualTo(2)
        cache.getAll(listOf("a", "b", "c")).assert().isEqualTo(mapOf("a" to "call-a", "b" to "l-b", "c" to "l-c"))
        cache.lookupAll(listOf("a")).getValue("a").assert().isInstanceOf(Lookup.Present::class.java)
        loads.get().assert().isEqualTo(2)
    }

    /**
     * 调用级挂起 loader 超过 `load.timeout`：被取消（协作式），调用方以 [SourceLoadTimeoutException] 失败，计入 `loadTimeouts`。
     */
    @Test
    fun aCallLevelLoaderPastTheLoadTimeoutIsCancelled(): Unit = runBlocking<Unit> {
        val cache = runtime.create(
            cacheSpec<String, String>("slow") {
                codec = StringCodec
                externallySourced()
                policy = CachePolicy(load = LoadPolicy(timeout = Duration.ofMillis(200)))
            }
        )
        val cancelled = CompletableDeferred<Unit>()
        runCatching {
            cache.suspending().get("k") {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }.exceptionOrNull().assert().isInstanceOf(SourceLoadTimeoutException::class.java)
        withTimeout(10_000) { cancelled.await() }
        requireNotNull(runtime.metrics("slow")).loadTimeouts.assert().isEqualTo(1)
    }

    @Test
    fun storeViewMatchesTheSyncApi(): Unit = runBlocking<Unit> {
        val store = runtime.create(storeSpec<String, String>("store") { codec = StringCodec })
        val view = store.suspending()
        view.put("a", "1", Duration.ofMinutes(1))
        view.putAll(mapOf("b" to "2"), Duration.ofMinutes(1))
        view.getAll(listOf("a", "b")).assert().isEqualTo(mapOf("a" to "1", "b" to "2"))
        val current = view.getIfChanged("a", null) as ConditionalGet.Present
        val written = view.putIfMatch("a", current.digest, "3", Duration.ofMinutes(1)) as ConditionalPut.Written
        view.getIfChanged("a", written.digest).assert().isEqualTo(ConditionalGet.Unchanged)
        view.delete("b")
        store["b"].assert().isNull()
        store["a"].assert().isEqualTo("3")
    }

    /**
     * 批量读取：命中的 key 内联取得，未命中的 key 并发读 L1，重复的 key 只出现一次，结果按 key 的顺序、不含不存在的 key；
     * 全部命中时不进入核心。
     */
    @Test
    fun setViewBatchesHitsAndMisses(): Unit = runBlocking<Unit> {
        val set = runtime.create(setStoreSpec<String, String>("members") { elementCodec = StringCodec })
        val view = set.suspending()
        view.add("a", listOf("1"))
        view.add("b", listOf("2"))
        view.get("a").assert().isEqualTo(setOf("1"))
        // a 命中 L2，b 与 absent 未命中；结果与同步 API 相同（顺序同 key 的顺序，重复的 key 只出现一次）
        val keys = listOf("b", "a", "b", "absent")
        val mixed = view.getAll(keys)
        mixed.keys.toList().assert().containsExactly("b", "a")
        mixed.assert().isEqualTo(mapOf("b" to setOf("2"), "a" to setOf("1")))
        mixed.assert().isEqualTo(set.getAll(keys))
        view.getAll(listOf("a", "b")).assert().isEqualTo(mapOf("a" to setOf("1"), "b" to setOf("2")))
        view.lookup("absent").assert().isInstanceOf(Lookup.Absent::class.java)
    }
}
