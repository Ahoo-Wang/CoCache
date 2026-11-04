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
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException

/**
 * 集合缓存读到非本协议内容或更高版本格式时的处理（L1 是唯一副本，没有数据源可回源）。
 */
class SetEngineFormatTest {
    private val memory = InMemoryBackend()
    private val backend = HookedBackend(memory)
    private val runtime = CacheRuntime.builder().backend(backend).build()
    private val index: SetStore<String, String> = runtime.create(setStoreSpec("index") { elementCodec = StringCodec })

    @AfterEach
    fun destroy() {
        runtime.close()
    }

    private fun seedForeign(key: String, namespace: String = "index") {
        Suspending.await {
            memory.store(Keyspace("cocache:", namespace)).put(
                "cocache:$namespace:$key",
                Envelope.Scalar(ByteArray(0)),
                null,
                InvalidationFrame.encode(NodeId.random(), emptyList())
            )
        }
        backend.afterRead = { _, read -> if (read is L1Read.Hit) read.copy(format = 0) else read }
    }

    @Test
    fun foreignContentFailsAndIsKept() {
        seedForeign("k")
        val error = runCatching { index["k"] }.exceptionOrNull()
        error.assert().isInstanceOf(CacheReadException::class.java)
        error?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        requireNotNull(runtime.metrics("index")).unknownFormats.assert().isEqualTo(1)
        (
            Suspending.await {
                memory.store(
                    Keyspace("cocache:", "index")
                ).read("cocache:index:k", null, null, null)
            } is L1Read.Hit
            ).assert().isTrue()
    }

    @Test
    fun newerFormatFailsInsteadOfReturningWrongData() {
        index.add("k", listOf("a"))
        backend.afterRead = { _, read -> if (read is L1Read.Hit) read.copy(format = read.format + 1) else read }
        runCatching { index["k"] }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
        requireNotNull(runtime.metrics("index")).unknownFormats.assert().isEqualTo(1)
        backend.afterRead = null
        // 没有写入 L2：恢复后照常读到
        index["k"].assert().containsExactly("a")
    }

    /**
     * 非原子广播的后端：写入失败后通常仍尽力广播（写入可能已部分生效），但后端拒绝的元素写入没有修改 L1，不广播。
     */
    @Test
    fun refusedMemberWritesAreNotBroadcastWithoutAtomicBroadcast() {
        val nonAtomic = HookedBackend(memory).apply { atomicBroadcastOverride = false }
        val writer = CacheRuntime.builder().backend(nonAtomic).build()
        val observer = CacheRuntime.builder().backend(memory).build()
        try {
            val cache: SetStore<String, String> = writer.create(setStoreSpec("plain") { elementCodec = StringCodec })
            observer.create(setStoreSpec<String, String>("plain") { elementCodec = StringCodec })
            Suspending.await {
                memory.store(Keyspace("cocache:", "plain")).put(
                    "cocache:plain:k",
                    Envelope.Scalar("x".toByteArray()),
                    null,
                    InvalidationFrame.encode(NodeId.random(), emptyList())
                )
            }
            val received = requireNotNull(observer.metrics("plain")).invalidationsReceived

            val error = runCatching { cache.add("k", listOf("a")) }.exceptionOrNull()
            error.assert().isInstanceOf(CacheWriteException::class.java)
            error?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
            val asyncError = cache.async().remove("k", listOf("a")).handle { _, e -> e }.join()
            val unwrapped = if (asyncError is CompletionException) asyncError.cause else asyncError
            unwrapped.assert().isInstanceOf(CacheWriteException::class.java)
            unwrapped?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
            requireNotNull(observer.metrics("plain")).invalidationsReceived.assert().isEqualTo(received)

            // 对照：成功的写入会广播（内存后端自己也广播，这里收到两次）
            cache.add("other", listOf("a"))
            requireNotNull(observer.metrics("plain")).invalidationsReceived.assert().isGreaterThan(received)
        } finally {
            writer.close()
            observer.close()
        }
    }
}
