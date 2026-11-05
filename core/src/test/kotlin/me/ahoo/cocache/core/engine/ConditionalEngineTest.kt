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

import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.storeSpec
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException

/**
 * `Store` 的条件读写在引擎中的边界：非原子广播的后端上冲突与拒绝不广播，可能是重放的冲突广播。
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

    private fun create(runtime: CacheRuntime): Store<String, String> = runtime.create(
        storeSpec("doc") { codec = StringCodec }
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
        cache.deleteIfMatch(
            "k",
            Digest.ofPayload("other".toByteArray())
        ).assert().isEqualTo(ConditionalDelete.Conflict(first.digest))
        received().assert().isEqualTo(afterFirst)

        val silent = InvalidationFrame.encode(NodeId.random(), emptyList())
        Suspending.await {
            requireNotNull(
                memory.store(Keyspace("cocache:", "doc")).sets()
            ).addMembers("cocache:doc:set", listOf("a".toByteArray()), null, silent)
        }
        val baseline = received()
        val error = runCatching { cache.putIfMatch("set", null, "v") }.exceptionOrNull()
        error.assert().isInstanceOf(CacheWriteException::class.java)
        error?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        val asyncError = unwrap(cache.async().putIfMatch("set", null, "v").handle { _, e -> e }.join())
        asyncError.assert().isInstanceOf(CacheWriteException::class.java)
        asyncError?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
        val deleteError = runCatching { cache.deleteIfMatch("set", first.digest) }.exceptionOrNull()
        deleteError.assert().isInstanceOf(CacheWriteException::class.java)
        (deleteError as CacheWriteException).outcomeUnknown.assert().isFalse()
        received().assert().isEqualTo(baseline)

        // 对照：成功的条件写入经通道广播（同步与异步）
        cache.putIfMatch("k", first.digest, "v2").assert().isInstanceOf(ConditionalPut.Written::class.java)
        received().assert().isGreaterThan(baseline)
        val afterSync = received()
        cache.async().putIfMatch("other", null, "o").join().assert().isInstanceOf(ConditionalPut.Written::class.java)
        received().assert().isGreaterThan(afterSync)
    }

    /**
     * 非原子广播的后端上，`deleteIfMatch` 删除时广播；冲突且当前没有值时也广播（RFC 0005 §4.6）：这可能是重放，上一次同样的删除
     * 已在 L1 生效，单独的广播却失败了。当前有别的值的冲突不广播。
     */
    @Test
    fun deleteIfMatchBroadcastsWhenDeletedOrPossiblyReplayed() {
        val cache = create(writer)
        val other = create(observer)
        val written = cache.putIfMatch("k", null, "v1") as ConditionalPut.Written
        other["k"].assert().isEqualTo("v1")
        val baseline = received()
        cache.deleteIfMatch("k", written.digest).assert().isEqualTo(ConditionalDelete.Deleted)
        awaitCondition("the deletion reached the observer") { received() > baseline }
        other["k"].assert().isNull()
        val afterDelete = received()
        cache.async().deleteIfMatch("k", written.digest).join().assert().isEqualTo(ConditionalDelete.Conflict(null))
        awaitCondition("the possible replay was broadcast") { received() > afterDelete }
        requireNotNull(writer.metrics("doc")).conditionalConflicts.assert().isEqualTo(1)
    }

    /**
     * 编码失败（用户的 Codec）不同步抛出：异步视图以原始异常完成。
     */
    @Test
    fun asyncEncodingFailureCompletesExceptionally() {
        val failure = IllegalStateException("encode")
        val cache: Store<String, String> = writer.create(
            storeSpec("failing") {
                codec = object : Codec<String> {
                    override fun encode(value: String): ByteArray = throw failure
                    override fun decode(bytes: ByteArray): String = String(bytes)
                }
            }
        )
        unwrap(cache.async().putIfMatch("k", null, "v").handle { _, e -> e }.join()).assert().isSameAs(failure)
        Suspending.await {
            memory.store(Keyspace("cocache:", "failing")).read("cocache:failing:k", null, null, null)
        }.assert().isEqualTo(L1Read.Miss(false))
    }
}
