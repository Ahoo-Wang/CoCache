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

import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ExternalInvalidatorTest {
    private val database = ConcurrentHashMap<String, String>()

    private fun spec() = cacheSpec<String, String>("user") {
        codec = StringCodec
        loader = CacheLoader { database[it] }
    }

    @Test
    fun invalidatesRemoteAndEveryNodeLocalCopy() {
        val memory = InMemoryBackend()
        CacheRuntime.builder().backend(memory).build().use { node ->
            val cache = node.create(spec())
            database["1"] = "v0"
            cache["1"].assert().isEqualTo("v0")
            database["1"] = "v1"
            ExternalInvalidator(memory).use { invalidator ->
                invalidator.invalidate("user", "1")
            }
            Suspending.await {
                memory.store(
                    Keyspace("cocache:", "user")
                ).read("cocache:user:1", null, null, null)
            }.assert().isEqualTo(L1Read.Miss(false))
            awaitCondition { cache["1"] == "v1" }
        }
    }

    @Test
    fun nonAtomicBackendPublishesThroughTheChannel() {
        val backend = HookedBackend(InMemoryBackend()).apply { atomicBroadcastOverride = false }
        CacheRuntime.builder().backend(backend).build().use { node ->
            val cache = node.create(spec())
            database["1"] = "v0"
            cache["1"]
            database["1"] = "v1"
            val invalidator = ExternalInvalidator(backend)
            invalidator.invalidate("user", listOf("1", "1"))
            awaitCondition { cache["1"] == "v1" }
            invalidator.close()
            invalidator.close()
            runCatching {
                invalidator.invalidate(
                    "user",
                    "1"
                )
            }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        }
    }

    /**
     * 只广播不删除（RFC 0005 §4.9）：L1 原样保留，各节点淘汰 L2 副本之后从 L1 重新填充；`Store` 的唯一副本因此不丢。
     * 订阅者收到编码 key（S4）。
     */
    @Test
    fun broadcastEvictsEveryNodeLocalCopyAndKeepsL1() {
        val memory = InMemoryBackend()
        CacheRuntime.builder().backend(memory).build().use { node ->
            val cache = node.create(storeSpec<String, String>("user") { codec = StringCodec })
            cache.put("1", "v0")
            cache["1"].assert().isEqualTo("v0")
            val received = CopyOnWriteArrayList<List<String>>()
            val external = object : InvalidationListener {
                override fun onInvalidated(keys: List<String>, origin: NodeId) {
                    received.add(keys)
                }

                override fun onReset() = Unit
            }
            val keyspace = Keyspace("cocache:", "user")
            memory.channel.subscribe(keyspace, external).use {
                ExternalInvalidator(memory).use { invalidator ->
                    invalidator.broadcast("user", emptyList())
                    runCatching { invalidator.broadcast("user", "") }.exceptionOrNull()
                        .assert().isInstanceOf(IllegalArgumentException::class.java)
                    invalidator.broadcast("user", "1")
                    invalidator.close()
                    runCatching { invalidator.broadcast("user", "1") }.exceptionOrNull()
                        .assert().isInstanceOf(IllegalStateException::class.java)
                }
            }
            received.assert().containsExactly(listOf("1"))
            val metrics = requireNotNull(node.metrics("user"))
            metrics.invalidationsReceived.assert().isEqualTo(1)
            Suspending.await { memory.store(keyspace).read("cocache:user:1", null, null, null) }
                .assert().isInstanceOf(L1Read.Hit::class.java)
            // 本节点的副本已被淘汰：再读一次 L1
            cache["1"].assert().isEqualTo("v0")
            metrics.remoteHits.assert().isEqualTo(2)
        }
    }

    @Test
    fun emptyKeysDoNothing() {
        val backend = HookedBackend(InMemoryBackend())
        ExternalInvalidator(backend).use { it.invalidate("user", emptyList()) }
        backend.reads.get().assert().isZero()
    }

    @Test
    fun customKeyPrefixAndEmptyKeyValidation() {
        val memory = InMemoryBackend()
        CacheRuntime.builder().backend(memory).build().use { node ->
            val cache = node.create(
                cacheSpec<String, String>("user") {
                    codec = StringCodec
                    keyPrefix = "app:"
                    loader = CacheLoader { database[it] }
                }
            )
            database["1"] = "v0"
            cache["1"]
            database["1"] = "v1"
            ExternalInvalidator(memory, "app:").use { invalidator ->
                runCatching { invalidator.invalidate("user", "") }.exceptionOrNull()
                    .assert().isInstanceOf(IllegalArgumentException::class.java)
                invalidator.invalidate("user", "1")
            }
            awaitCondition { cache["1"] == "v1" }
        }
    }

    /**
     * 按 8.x 的布局构造 key 与频道（RFC 0005 §4.7）：前缀决定频道，同一命名空间在别的前缀下的缓存收不到这次失效；
     * 命名空间不符合字符集时抛出，与缓存相同。
     */
    @Test
    fun invalidatesOnlyTheKeyspaceOfItsPrefix() {
        val memory = InMemoryBackend()
        CacheRuntime.builder().backend(memory).build().use { node ->
            fun cacheUnder(name: String, prefix: String) = node.create(
                cacheSpec<String, String>(name) {
                    codec = StringCodec
                    namespace = "user"
                    keyPrefix = prefix
                    loader = CacheLoader { database[it] }
                }
            )
            val app = cacheUnder("app-user", "app:")
            val other = cacheUnder("other-user", "other:")
            database["1"] = "v0"
            app["1"]
            other["1"]
            database["1"] = "v1"
            ExternalInvalidator(memory, "app:").use { invalidator ->
                runCatching { invalidator.invalidate("app:user", "1") }.exceptionOrNull()
                    .assert().isInstanceOf(IllegalArgumentException::class.java)
                invalidator.invalidate("user", "1")
            }
            app["1"].assert().isEqualTo("v1")
            other["1"].assert().isEqualTo("v0")
            requireNotNull(node.metrics("other-user")).invalidationsReceived.assert().isZero()
            Suspending.await {
                memory.store(Keyspace("other:", "user")).read("other:user:1", null, null, null)
            }.assert().isInstanceOf(L1Read.Hit::class.java)
        }
    }

    /**
     * P2（GA 前的架构审查）：以 keyspace 的存储前缀开头的 key 是粘贴进来的完整 Redis key。按编码 key 处理会删除前缀重复的另一个 key，
     * 调用看起来成功、缓存却一直陈旧。整批在删除与发布之前以 [IllegalArgumentException] 拒绝，信息给出应传的编码 key；
     * 别的前缀开头的 key 只是普通的编码 key，照常失效。
     */
    @Test
    fun rejectsAFullRemoteKeyBeforeTouchingAnything() {
        val memory = InMemoryBackend()
        CacheRuntime.builder().backend(memory).build().use { node ->
            val cache = node.create(spec())
            database["1"] = "v0"
            database["other:user:1"] = "v0"
            cache["1"].assert().isEqualTo("v0")
            cache["other:user:1"].assert().isEqualTo("v0")
            database["1"] = "v1"
            database["other:user:1"] = "v1"
            ExternalInvalidator(memory).use { invalidator ->
                listOf<() -> Unit>(
                    { invalidator.invalidate("user", listOf("other:user:1", "cocache:user:1")) },
                    { invalidator.broadcast("user", listOf("other:user:1", "cocache:user:1")) },
                ).forEach { call ->
                    val error = runCatching { call() }.exceptionOrNull()
                    error.assert().isInstanceOf(IllegalArgumentException::class.java)
                    requireNotNull(error).message.assert()
                        .contains("Namespace[user]", "key[cocache:user:1]", "storage prefix[cocache:user:]", "[1]")
                }
                cache["1"].assert().isEqualTo("v0")
                cache["other:user:1"].assert().isEqualTo("v0")
                Suspending.await {
                    memory.store(Keyspace("cocache:", "user")).read("cocache:user:1", null, null, null)
                }.assert().isInstanceOf(L1Read.Hit::class.java)
                invalidator.invalidate("user", "other:user:1")
            }
            awaitCondition { cache["other:user:1"] == "v1" }
            cache["1"].assert().isEqualTo("v0")
        }
    }

    /**
     * 含未配对 UTF-16 代理的 key：按 UTF-8 编码时被替换为 `?`，删除与帧中的就成了另一个 key（缓存同样拒绝它）。
     * 整批在删除与发布之前以 [IllegalArgumentException] 拒绝：合法的 key 也不失效，信息给出命名空间与下标、转义未配对的代理。
     * 合法的补充平面字符照常失效。
     */
    @Test
    fun rejectsKeysWithAnUnpairedSurrogateBeforeTouchingAnything() {
        val memory = InMemoryBackend()
        CacheRuntime.builder().backend(memory).build().use { node ->
            val cache = node.create(spec())
            database["ok"] = "v0"
            database["😀"] = "v0"
            cache["ok"].assert().isEqualTo("v0")
            cache["😀"].assert().isEqualTo("v0")
            database["ok"] = "v1"
            database["😀"] = "v1"
            val frames = AtomicInteger()
            val counter = object : InvalidationListener {
                override fun onInvalidated(keys: List<String>, origin: NodeId) {
                    frames.incrementAndGet()
                }

                override fun onReset() = Unit
            }
            val keyspace = Keyspace("cocache:", "user")
            memory.channel.subscribe(keyspace, counter).use {
                ExternalInvalidator(memory).use { invalidator ->
                    listOf<() -> Unit>(
                        { invalidator.invalidate("user", listOf("ok", "bad-\uD800")) },
                        { invalidator.broadcast("user", listOf("ok", "bad-\uD800")) },
                    ).forEach { call ->
                        val error = runCatching { call() }.exceptionOrNull()
                        error.assert().isInstanceOf(IllegalArgumentException::class.java)
                        requireNotNull(error).message.assert()
                            .contains("Namespace[user]", "key[bad-\\uD800]", "index[4]")
                    }
                    frames.get().assert().isZero()
                    Suspending.await { memory.store(keyspace).read("cocache:user:ok", null, null, null) }
                        .assert().isInstanceOf(L1Read.Hit::class.java)
                    cache["ok"].assert().isEqualTo("v0")
                    invalidator.invalidate("user", "😀")
                }
            }
            frames.get().assert().isEqualTo(1)
            awaitCondition { cache["😀"] == "v1" }
        }
    }
}
