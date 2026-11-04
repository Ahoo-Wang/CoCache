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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.FutureInvalidationChannel
import me.ahoo.cocache.api.spi.FutureL1Store
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

/**
 * `FutureL1Store.adapt` 与 `FutureInvalidationChannel.adapt` 在 kotlinx 的调度器上（RFC 0005 §4.3）：future 在别的线程上失败、
 * 续接经调度器派发时，调用方拿到原始异常本身，不是调试模式（测试以 `-ea` 运行）恢复堆栈时复制的副本（不变量“不包装异常”）。
 */
class FutureAdapterTest {
    private val original = IllegalStateException("L1 down")

    private fun storeReading(future: CompletableFuture<L1Read>): FutureL1Store = mockk {
        every { conditional() } returns null
        every { versioned() } returns null
        every { sets() } returns null
        every { read(any(), any(), any(), null) } returns future
    }

    @Test
    fun aLaterFailureOfAStoreReachesTheCallerAsTheOriginalInstance(): Unit = runBlocking {
        val future = CompletableFuture<L1Read>()
        val store = FutureL1Store.adapt(storeReading(future))
        val reading = async(start = CoroutineStart.UNDISPATCHED) { runCatching { store.read("k", null, null, null) } }
        thread { future.completeExceptionally(original) }

        reading.await().exceptionOrNull().assert().isSameAs(original)
    }

    @Test
    fun aLaterFailureOfAChannelReachesTheCallerAsTheOriginalInstance(): Unit = runBlocking {
        val future = CompletableFuture<Void?>()
        val channel = FutureInvalidationChannel.adapt(mockk { every { publish(any(), any()) } returns future })
        val publishing =
            async(
                start = CoroutineStart.UNDISPATCHED
            ) { runCatching { channel.publish(Keyspace("cocache:", "ns"), ByteArray(0)) } }
        thread { future.completeExceptionally(original) }

        publishing.await().exceptionOrNull().assert().isSameAs(original)
    }

    /**
     * 经引擎：没有数据源的读取遇到 L1 失败，[CacheReadException] 的 cause 是存储给出的原始异常。
     */
    @Test
    fun theEngineSeesTheOriginalInstanceOfALaterFailure() {
        val future = CompletableFuture<L1Read>()
        val failing = storeReading(future)
        val backend = object : CoherenceBackend by InMemoryBackend() {
            override fun store(keyspace: Keyspace): L1Store = FutureL1Store.adapt(failing)
        }
        CacheRuntime.builder().backend(backend).build().use { runtime ->
            val cache = runtime.create(cacheSpec<String, String>("adapted") { codec = StringCodec })
            val reader = fork("reader") { runCatching { cache["k"] }.exceptionOrNull() }
            awaitCondition("read suspended") { reader.parked }
            future.completeExceptionally(original)

            val failure = reader.join()
            failure.assert().isInstanceOf(CacheReadException::class.java)
            failure?.cause.assert().isSameAs(original)
        }
    }
}
