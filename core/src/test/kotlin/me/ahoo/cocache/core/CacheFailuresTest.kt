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

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [CacheFailures.isTransient]：下游（Wow）曾逐个捕获四种“暂时不可用”的异常；判定函数把它们收成一处，读不懂的内容不算暂时的失败。
 */
class CacheFailuresTest {
    private val foreign = ForeignFormatException("c", "cocache:c:k", 0, 2, null)

    @Test
    fun classifiesEachFailure() {
        val transient = listOf(
            SourceLoadTimeoutException("c", Duration.ofSeconds(1)),
            SourceLoadRejectedException("c", LoadPolicy()),
            SourceLoadCancelledException("c", CancellationException("cancelled")),
            CacheReadException("c", "read", IllegalStateException("L1 down")),
            CacheWriteException("c", "put", IllegalStateException("L1 down")),
            CacheWriteException("c", "put", IllegalStateException("not sent"), outcomeUnknown = false),
        )
        transient.forEach {
            CacheFailures.isTransient(it).assert().withFailMessage { "$it must be transient" }.isTrue()
        }
        val permanent = listOf(
            CacheReadException("c", "read", foreign),
            CacheWriteException("c", "add", foreign),
            UnsupportedOperationException("no conditional writes"),
            IllegalStateException("loader failed"),
            CompletionException("no cause", null),
        )
        permanent.forEach {
            CacheFailures.isTransient(it).assert().withFailMessage { "$it must not be transient" }.isFalse()
        }
    }

    /**
     * 异步视图的 future 以原始异常完成，`join()` / `get()` 再包一层：先拆开。
     */
    @Test
    fun unwrapsFutureWrappers() {
        val timeout = SourceLoadTimeoutException("c", Duration.ofSeconds(1))
        CacheFailures.isTransient(CompletionException(timeout)).assert().isTrue()
        val unreadable = CacheReadException("c", "read", foreign)
        CacheFailures.isTransient(ExecutionException(CompletionException(unreadable))).assert().isFalse()
    }

    @Test
    fun classifiesWhatTheEngineThrows() {
        HookedBackend(InMemoryBackend()).use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val store = runtime.create(storeSpec<String, String>("failures") { codec = StringCodec })
                backend.failReads = true
                val read = runCatching { store["k"] }.exceptionOrNull()
                read.assert().isInstanceOf(CacheReadException::class.java)
                CacheFailures.isTransient(requireNotNull(read)).assert().isTrue()
                val asyncRead = runCatching { store.async().get("k").join() }.exceptionOrNull()
                asyncRead.assert().isInstanceOf(CompletionException::class.java)
                CacheFailures.isTransient(requireNotNull(asyncRead)).assert().isTrue()
                backend.failReads = false

                backend.failWrites = true
                val write = runCatching { store.put("k", "v") }.exceptionOrNull()
                write.assert().isInstanceOf(CacheWriteException::class.java)
                CacheFailures.isTransient(requireNotNull(write)).assert().isTrue()
                backend.failWrites = false

                // 读不懂的内容（不是本协议写入的）：重试读到的仍是它
                val keyspace = Keyspace("cocache:", "failures")
                (backend.delegate as InMemoryBackend)
                    .writeForeign(keyspace, keyspace.storageKey("old"), Envelope.Scalar("x".toByteArray()), 0, null)
                val unreadable = runCatching { store["old"] }.exceptionOrNull()
                unreadable.assert().isInstanceOf(CacheReadException::class.java)
                unreadable?.cause.assert().isInstanceOf(ForeignFormatException::class.java)
                CacheFailures.isTransient(requireNotNull(unreadable)).assert().isFalse()
            }
        }
    }
}
