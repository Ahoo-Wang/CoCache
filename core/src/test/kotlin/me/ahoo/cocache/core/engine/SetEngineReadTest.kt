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
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.test.Concurrency
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * `SetStore` 的读路径运行在协程核心上（RFC 0005 V5）：同步变体在调用方线程上读 L1，异步变体的 L1 结果在 I/O 线程上送达。
 */
class SetEngineReadTest {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "l1-io") }
    private val backend = HookedBackend(InMemoryBackend()).also { it.ioExecutor = io }
    private val runtime = CacheRuntime.builder().backend(backend).build()
    private val target: SetStore<String, String> = runtime.create(setStoreSpec("index") { elementCodec = StringCodec })

    @AfterEach
    fun destroy() {
        runtime.close()
        io.shutdownNow()
    }

    @Test
    fun syncReadRunsOnTheCallerThread() {
        // 比较线程对象：调试模式下 kotlinx 在协程运行期间给线程名加上 `@coroutine#n`
        val readers = CopyOnWriteArrayList<Thread>()
        backend.afterRead = { _, read ->
            readers.add(Thread.currentThread())
            read
        }
        target.add("k", listOf("a"))
        target["k"].assert().containsExactly("a")
        readers.assert().containsExactly(Thread.currentThread())
    }

    @Test
    fun asyncReadCompletesOffTheIoThread() {
        target.add("k", listOf("a"))
        val completedOn = target.async().get("k").thenApply { Concurrency.currentThreadName() }.join()
        completedOn.startsWith("l1-io").assert().withFailMessage { "completed on $completedOn" }.isEqualTo(false)
        target.async().get("k").join().assert().containsExactly("a")
    }

    /**
     * L1 读失败是 `Unknown`（RFC 0005 §4.1）：两种视图的 `get` 都以 [CacheReadException] 失败，`cause` 为原始异常；`lookup` 返回
     * `Lookup.Unknown`。异步视图的失败在 I/O 线程上送达、协程随后恢复，这里必须拿到原始的那个异常作为 `cause`。
     */
    @Test
    fun readFailureIsUnknownCarryingTheOriginalException() {
        val failure = IllegalStateException("L1 down")
        backend.afterRead = { _, _ -> throw failure }
        val sync = runCatching { target["k"] }.exceptionOrNull()
        sync.assert().isInstanceOf(CacheReadException::class.java)
        sync?.cause.assert().isSameAs(failure)
        val error = runCatching { target.async().get("k").join() }.exceptionOrNull()
        error.assert().isInstanceOf(CompletionException::class.java)
        error?.cause.assert().isInstanceOf(CacheReadException::class.java)
        error?.cause?.cause.assert().isSameAs(failure)
        val unknown = target.async().lookup("k").join()
        unknown.assert().isInstanceOf(Lookup.Unknown::class.java)
        (unknown as Lookup.Unknown).error.cause.assert().isSameAs(failure)
        target.async().lookupAll(listOf("k")).join().getValue("k").assert().isInstanceOf(Lookup.Unknown::class.java)
        requireNotNull(runtime.metrics("index")).readsUnknown.assert().isEqualTo(4)
    }
}
