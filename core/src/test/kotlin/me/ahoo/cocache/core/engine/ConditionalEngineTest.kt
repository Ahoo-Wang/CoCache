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
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.RemoteConditionalWrite
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletionException

/**
 * 条件读写在引擎中的边界：非原子广播的后端上冲突与拒绝不广播，`SetCache` 不支持条件读写。
 */
class ConditionalEngineTest {
    private val memory = InMemoryBackend()

    /**
     * 写入方经非原子广播的后端（存储之外由引擎经通道发布），观察方直接订阅内存后端。
     */
    private val writer = CacheRuntime.builder().backend(
        HookedBackend(memory).apply {
            atomicBroadcastOverride = false
        }
    ).build()
    private val observer = CacheRuntime.builder().backend(memory).build()

    @AfterEach
    fun destroy() {
        writer.close()
        observer.close()
    }

    private fun create(runtime: CacheRuntime): Cache<String, String> = runtime.create(
        cacheSpec("doc") { codec = StringCodec }
    )

    private fun received(): Long = requireNotNull(observer.metrics("doc")).invalidationsReceived

    private fun unwrap(error: Throwable?): Throwable? = if (error is CompletionException) error.cause else error

    @Test
    fun conflictsAndRefusalsAreNotBroadcastWithoutAtomicBroadcast() {
        val cache = create(writer)
        create(observer)
        val first = cache.putIfMatch("k", null, "v1") as ConditionalPut.Written
        val afterFirst = received()
        afterFirst.assert().isGreaterThan(0L)

        cache.putIfMatch("k", null, "x").assert().isEqualTo(ConditionalPut.Conflict(first.digest))
        cache.async().putIfMatch("k", null, "x").join().assert().isEqualTo(ConditionalPut.Conflict(first.digest))
        received().assert().isEqualTo(afterFirst)

        val silent = InvalidationFrame.encode(NodeId.random(), emptyList())
        memory.store("doc").addMembers("cocache:doc:set", listOf("a".toByteArray()), null, silent)
        val baseline = received()
        val error = runCatching { cache.putIfMatch("set", null, "v") }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        error?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        val asyncError = unwrap(cache.async().putIfMatch("set", null, "v").handle { _, e -> e }.join())
        asyncError.assert().isInstanceOf(CacheWriteException::class.java)
        asyncError?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        received().assert().isEqualTo(baseline)

        // 对照：成功的条件写入经通道广播（同步与异步）
        cache.putIfMatch("k", first.digest, "v2").assert().isInstanceOf(ConditionalPut.Written::class.java)
        received().assert().isGreaterThan(baseline)
        val afterSync = received()
        cache.async().putIfMatch("other", null, "o").join().assert().isInstanceOf(ConditionalPut.Written::class.java)
        received().assert().isGreaterThan(afterSync)
    }

    @Test
    fun setCachesDoNotSupportConditionalOperations() {
        val set: SetCache<String, String> = writer.create(setCacheSpec("set") { elementCodec = StringCodec })
        runCatching {
            set.getIfChanged("k", null)
        }.exceptionOrNull().assert().isInstanceOf(UnsupportedOperationException::class.java)
        runCatching {
            set.putIfMatch("k", null, setOf("a"))
        }.exceptionOrNull().assert().isInstanceOf(UnsupportedOperationException::class.java)
        unwrap(set.async().getIfChanged("k", null).handle { _, e -> e }.join())
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
        unwrap(set.async().putIfMatch("k", null, setOf("a")).handle { _, e -> e }.join())
            .assert().isInstanceOf(UnsupportedOperationException::class.java)
    }

    /**
     * 编码失败（用户的 Codec）不同步抛出：异步视图以原始异常完成。
     */
    @Test
    fun asyncEncodingFailureCompletesExceptionally() {
        val failure = IllegalStateException("encode")
        val cache: Cache<String, String> = writer.create(
            cacheSpec("failing") {
                codec = object : Codec<String> {
                    override fun encode(value: String): ByteArray = throw failure
                    override fun decode(bytes: ByteArray): String = String(bytes)
                }
            }
        )
        unwrap(cache.async().putIfMatch("k", null, "v").handle { _, e -> e }.join()).assert().isSameAs(failure)
        memory.store("failing").read("cocache:failing:k", null, null).assert().isEqualTo(RemoteRead.Miss(false))
    }

    /**
     * 只实现同步 SPI、不覆盖条件写入的存储（第三方后端在 6.6 编译的样子）：引擎经阻塞执行器调用同步方法。
     */
    private class DefaultsStore(private val delegate: RemoteStore) : RemoteStore by delegate {
        override fun readIfChanged(key: String, known: Digest?) = super<RemoteStore>.readIfChanged(key, known)

        override fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray,
            foreignAsAbsent: Boolean
        ): RemoteConditionalWrite = super<RemoteStore>.putIfMatch(key, expected, envelope, ttl, frame, foreignAsAbsent)
    }

    /**
     * SPI 默认实现不提供条件写入：引擎原样抛出 [UnsupportedOperationException]（不包装、不广播、不修改 L1），
     * 而不是先读后写、让两个并发的写入方都成功。条件读取的默认实现（先读后比较）照常可用。
     */
    @Test
    fun backendsWithoutConditionalWritesFailLoudly() {
        val defaults = object : CoherenceBackend by memory {
            override fun store(namespace: String): RemoteStore = DefaultsStore(memory.store(namespace))
        }
        val runtime = CacheRuntime.builder().backend(
            HookedBackend(defaults).apply { atomicBroadcastOverride = false }
        ).build()
        try {
            val cache = create(runtime)
            create(observer)
            cache.put("k", "v")
            val baseline = received()
            runCatching { cache.putIfMatch("k", null, "x") }.exceptionOrNull()
                .assert().isInstanceOf(UnsupportedOperationException::class.java)
            unwrap(cache.async().putIfMatch("k", null, "x").handle { _, e -> e }.join())
                .assert().isInstanceOf(UnsupportedOperationException::class.java)
            received().assert().isEqualTo(baseline)
            val digest = Digest.of(Envelope.Scalar("v".toByteArray()))
            cache.getIfChanged("k", digest).assert().isEqualTo(ConditionalGet.Unchanged)
            cache.async().getIfChanged("k", null).join().assert().isEqualTo(ConditionalGet.Present("v", digest))
        } finally {
            runtime.close()
        }
    }
}
