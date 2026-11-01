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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * 单 key 读路径运行在协程核心上（RFC 0005 V5）：运行时关闭取消作用域时，进行中的异步读取以关闭失败，取得的 lease 照常交还。
 */
class CoherentEngineCoreTest {
    private val memory = InMemoryBackend()
    private val runtimes = mutableListOf<CacheRuntime>()

    @AfterEach
    fun destroy() {
        runtimes.forEach { it.close() }
    }

    private fun runtime(): CacheRuntime = CacheRuntime.builder().backend(memory).build().also { runtimes += it }

    private fun spec(block: CacheSpec.Builder<String, String>.() -> Unit) = cacheSpec<String, String>("core") {
        codec = StringCodec
        block()
    }

    private fun failureOf(future: CompletableFuture<*>): Throwable? = try {
        future.get(10, TimeUnit.SECONDS)
        null
    } catch (e: ExecutionException) {
        e.cause
    }

    /**
     * 数据源尚未完成时关闭运行时：读取以 [IllegalStateException] 失败，协程被取消之前取得的 lease 被释放，
     * 另一个节点随后的未命中直接回源，不等待 lease。
     */
    @Test
    fun closingTheRuntimeMidLoadReleasesTheLease() {
        val started = CountDownLatch(1)
        val closing = runtime()
        val cache = closing.create(
            spec {
                asyncLoader = AsyncCacheLoader {
                    started.countDown()
                    CompletableFuture<String>()
                }
            }
        )
        val read = cache.async().get("k")
        started.awaitOrFail("loader started")
        closing.close()
        val failure = failureOf(read)
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().isEqualTo("CacheRuntime closed.")

        val other = runtime()
        other.create(spec { loader = CacheLoader { "v-$it" } })["k"].assert().isEqualTo("v-k")
        requireNotNull(other.metrics("core")).leaseWaits.assert().isZero()
    }

    /**
     * 等待其它节点的 lease 期间关闭运行时：定时重读不再触发，读取立即以关闭失败，而不是等满 `lease.wait`。
     */
    @Test
    fun closingTheRuntimeDuringALeaseWaitFailsTheAsyncReadAsClosed() {
        val holderStarted = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holder = runtime().create(
            spec {
                loader = CacheLoader {
                    holderStarted.countDown()
                    releaseHolder.awaitOrFail("release holder")
                    "v"
                }
            }
        )
        val holding = fork("holder") { holder["k"] }
        holderStarted.awaitOrFail("holder started")

        val closing = runtime()
        val cache = closing.create(
            spec {
                loader = CacheLoader { "w" }
                policy = CachePolicy(lease = LeasePolicy(wait = Duration.ofSeconds(30)))
            }
        )
        val read = cache.async().get("k")
        awaitCondition("waiting for the lease") { requireNotNull(closing.metrics("core")).leaseWaits == 1L }
        closing.close()
        val failure = failureOf(read)
        failure.assert().isInstanceOf(IllegalStateException::class.java)
        failure?.message.assert().isEqualTo("CacheRuntime closed.")

        releaseHolder.countDown()
        holding.join().assert().isEqualTo("v")
    }
}
