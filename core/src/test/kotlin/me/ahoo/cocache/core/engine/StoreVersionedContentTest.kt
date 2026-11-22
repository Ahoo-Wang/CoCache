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

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.core.versionedStoreSpec
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException

/**
 * 缺陷复现（#1001）：`Store` 读到带版本段的标量时按读不懂处理，即使它同时带着正确的摘要段。摘要只说明载荷一致，不改变这份内容
 * 属于带版本的类型；普通读取曾照常返回它（并填入 L2），条件读取却拒绝同一份内容。`VersionedStore` 照常读取这种合法的段组合。
 */
class StoreVersionedContentTest {
    /**
     * 五种读取入口 × 三种视图都按读不懂失败（`CacheReadException`）。
     */
    @ParameterizedTest
    @MethodSource("reads")
    fun aStoreRejectsVersionedContentEvenWithAValidDigest(operation: String, view: String) {
        fixture { _, cache, remote, key, frame ->
            put(remote, key, frame, versioned = true, digest = true)
            val outcome = runCatching { read(cache, operation, view) }
            val failure = outcome.exceptionOrNull().let { if (it is ExecutionException) it.cause else it }
            failure.assert()
                .withFailMessage { "$operation $view accepted version 7 with a digest: ${outcome.getOrNull()}" }
                .isInstanceOf(CacheReadException::class.java)
        }
    }

    /**
     * 运维查看同样标为读不懂。
     */
    @Test
    fun inspectionReportsItUnreadable() {
        fixture { runtime, _, remote, key, frame ->
            put(remote, key, frame, versioned = true, digest = true)
            requireNotNull(runtime.admin("store")).inspect("k").remote.state
                .assert().isEqualTo(KeyInspection.RemoteState.UNREADABLE)
        }
    }

    /**
     * 对照：只带摘要段的值照常读取；只带版本段的值与条件读取照旧拒绝；`VersionedStore` 读取同一份带版本与摘要段的内容。
     */
    @Test
    fun controls() {
        fixture { runtime, cache, remote, key, frame ->
            put(remote, key, frame, versioned = false, digest = true)
            cache.lookupRemote("k").valueOrThrow().assert().isEqualTo("value")
            put(remote, key, frame, versioned = true, digest = false)
            runCatching { cache.lookupRemote("k") }.exceptionOrNull()
                .assert().isInstanceOf(CacheReadException::class.java)
            put(remote, key, frame, versioned = true, digest = true)
            runCatching { cache.getIfChanged("k", null) }.exceptionOrNull()
                .assert().isInstanceOf(CacheReadException::class.java)
            val namespace = requireNotNull(runtime.admin(cache.name)).keyspace.namespace
            CacheRuntime.builder().backend(runtime.backend).build().use { other ->
                val versioned = other.create(
                    versionedStoreSpec<String, String>("versioned") {
                        this.namespace = namespace
                        codec = StringCodec
                    }
                )
                versioned.getVersioned("k").assert().isEqualTo(Versioned("value", 7))
            }
        }
    }

    private fun put(remote: L1Store, key: String, frame: ByteArray, versioned: Boolean, digest: Boolean) {
        val bytes = StringCodec.encode("value")
        val envelope = Envelope.Scalar(bytes, digest = if (digest) Digest.ofPayload(bytes) else null)
        Suspending.await {
            remote.invalidate(listOf(key), frame)
            if (versioned) {
                requireNotNull(remote.versioned()).putIfNewer(key, envelope, 7, Duration.ofMinutes(1), frame)
            } else {
                remote.put(key, envelope, Duration.ofMinutes(1), frame)
            }
            val stored = (remote.read(key, null, null, null) as L1Read.Hit).envelope as Envelope.Scalar
            stored.version.assert().isEqualTo(if (versioned) 7L else Versioned.NO_VERSION)
            stored.digest.assert().isEqualTo(envelope.digest)
        }
    }

    private fun read(cache: Store<String, String>, operation: String, view: String): Any? = when (view) {
        "async" -> readAsync(cache, operation)
        "suspending" -> Suspending.await { readSuspending(cache, operation) }
        else -> readSync(cache, operation)
    }

    private fun readSync(cache: Store<String, String>, operation: String): Any? = when (operation) {
        "get" -> cache["k"]
        "getAll" -> cache.getAll(listOf("k"))
        "lookup" -> cache.lookup("k")
        "lookupAll" -> cache.lookupAll(listOf("k"))
        else -> cache.lookupRemote("k")
    }

    private fun readAsync(cache: Store<String, String>, operation: String): Any? = when (operation) {
        "get" -> cache.async().get("k").get()
        "getAll" -> cache.async().getAll(listOf("k")).get()
        "lookup" -> cache.async().lookup("k").get()
        "lookupAll" -> cache.async().lookupAll(listOf("k")).get()
        else -> cache.async().lookupRemote("k").get()
    }

    private suspend fun readSuspending(cache: Store<String, String>, operation: String): Any? = when (operation) {
        "get" -> cache.suspending().get("k")
        "getAll" -> cache.suspending().getAll(listOf("k"))
        "lookup" -> cache.suspending().lookup("k")
        "lookupAll" -> cache.suspending().lookupAll(listOf("k"))
        else -> cache.suspending().lookupRemote("k")
    }

    private fun fixture(block: (CacheRuntime, Store<String, String>, L1Store, String, ByteArray) -> Unit) {
        val backend = InMemoryBackend()
        backend.use {
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val spec = storeSpec<String, String>("store") {
                    namespace = "segments-" + UUID.randomUUID()
                    codec = StringCodec
                }
                val cache = runtime.create(spec)
                val remote = backend.store(spec.keyspace)
                val key = spec.keyspace.storageKey("k")
                val frame = InvalidationFrame.encode(NodeId.random(), listOf(key))
                block(runtime, cache, remote, key, frame)
            }
        }
    }

    companion object {
        @JvmStatic
        fun reads(): List<Arguments> = listOf(
            "get",
            "getAll",
            "lookup",
            "lookupAll",
            "lookupRemote"
        ).flatMap { operation ->
            listOf("sync", "async", "suspending").map { view -> Arguments.of(operation, view) }
        }
    }
}
