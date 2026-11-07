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
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1RejectedException
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException

/**
 * 集合缓存读到非本协议内容或更高版本格式时的处理（L1 是唯一副本，没有数据源可回源），以及非原子广播的后端上失败的写入是否广播。
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

    /**
     * 更高的格式：同其它读不懂的内容以 [CacheReadException] 失败（cause 为 `newer` 的 [ForeignFormatException]），同 `Store`。
     */
    @Test
    fun newerFormatFailsInsteadOfReturningWrongData() {
        index.add("k", listOf("a"))
        backend.afterRead = { _, read -> if (read is L1Read.Hit) read.copy(format = read.format + 1) else read }
        val error = runCatching { index["k"] }.exceptionOrNull()
        error.assert().isInstanceOf(CacheReadException::class.java)
        requireNotNull(error?.cause as? ForeignFormatException).newer.assert().isTrue()
        requireNotNull(runtime.metrics("index")).unknownFormats.assert().isEqualTo(1)
        backend.afterRead = null
        // 没有写入 L2：恢复后照常读到
        index["k"].assert().containsExactly("a")
    }

    /**
     * 元素解码失败：以 [CacheReadException] 失败（cause 为本节点格式的 [ForeignFormatException]，其 cause 为编解码器的异常），
     * 计入 `unknownFormats`，不计为命中，不写 L2、不修改 L1。三种视图相同。
     */
    @Test
    fun undecodableElementFailsAsUnreadable() {
        val failure = IllegalArgumentException("bad element")
        val codec = object : Codec<String> {
            override fun encode(value: String): ByteArray = StringCodec.encode(value)

            override fun decode(bytes: ByteArray): String =
                StringCodec.decode(bytes).also { if (it == "bad") throw failure }
        }
        val strict: SetStore<String, String> = runtime.create(setStoreSpec("strict") { elementCodec = codec })
        strict.add("k", listOf("a", "bad"))

        val error = runCatching { strict["k"] }.exceptionOrNull()
        error.assert().isInstanceOf(CacheReadException::class.java)
        val cause = requireNotNull(error?.cause as? ForeignFormatException)
        cause.format.assert().isEqualTo(memory.format)
        cause.cause.assert().isSameAs(failure)
        val asyncError = strict.async().get("k").handle { _, e -> e }.join()
        (if (asyncError is CompletionException) asyncError.cause else asyncError)
            .assert().isInstanceOf(CacheReadException::class.java)
        runCatching { Suspending.await { strict.suspending().get("k") } }.exceptionOrNull()
            .assert().isInstanceOf(CacheReadException::class.java)
        val metrics = requireNotNull(runtime.metrics("strict"))
        metrics.unknownFormats.assert().isEqualTo(3)
        metrics.remoteHits.assert().isZero()

        strict.remove("k", listOf("bad"))
        strict["k"].assert().containsExactly("a")
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

    /**
     * 真正的非原子后端（存储自己从不广播：内存存储的广播发到没有订阅者的频道，节点只收到引擎经通道发出的帧）上的混合批次：
     * 整体覆盖已写入、随后的删除在写出之前被拒绝。整批部分执行（`outcomeUnknown = true`），引擎补发整批的帧，
     * 另一个运行时不保留被覆盖的 key 的旧副本。同步、异步与挂起视图相同。
     */
    @Test
    fun partiallyAppliedMixedBatchIsBroadcastWithoutAtomicBroadcast() {
        val channel = InMemoryBackend().channel
        val nonAtomic = HookedBackend(
            object : CoherenceBackend by memory {
                override val atomicBroadcast: Boolean = false
                override val channel: InvalidationChannel = channel
            }
        )
        val writerRuntime = CacheRuntime.builder().backend(nonAtomic).build()
        val readerRuntime = CacheRuntime.builder().backend(nonAtomic).build()
        try {
            val spec = setStoreSpec<String, String>("mixed") { elementCodec = StringCodec }
            val writer = writerRuntime.create(spec)
            val reader = readerRuntime.create(spec)
            val views: List<(Map<String, Set<String>>) -> Throwable?> = listOf(
                { runCatching { writer.putAll(it) }.exceptionOrNull() },
                { writer.async().putAll(it).handle { _, e -> e }.join() },
                { runCatching { Suspending.await { writer.suspending().putAll(it) } }.exceptionOrNull() },
            )
            views.forEachIndexed { index, putAll ->
                val key = "updated-$index"
                writer.put(key, setOf("old"))
                writer.put("cleared", setOf("kept"))
                reader[key].assert().containsExactly("old")
                nonAtomic.beforeInvalidate = { throw L1RejectedException("Rejected before it was written: invalidate.") }
                val error = putAll(mapOf(key to setOf("new"), "cleared" to emptySet()))
                nonAtomic.beforeInvalidate = null
                error.assert().isInstanceOf(CacheWriteException::class.java)
                (error as CacheWriteException).outcomeUnknown.assert().isTrue()
                error.cause.assert().isInstanceOf(PartiallyAppliedException::class.java)
                error.cause?.cause.assert().isInstanceOf(L1RejectedException::class.java)
                Concurrency.awaitCondition("the reader drops the old copy of $key") { reader[key] == setOf("new") }
                reader["cleared"].assert().containsExactly("kept")
            }
        } finally {
            writerRuntime.close()
            readerRuntime.close()
        }
    }
}
