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
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.LoadObserver
import me.ahoo.cocache.core.LoadOperation
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * #864：异步数据源的 future 以 [InterruptedException] 失败时，同步 leader 与合并到它的调用方共享这个失败、只回源一次（#835）。
 * 符合契约的回源观测器（同线程执行 block 恰好一次、原样抛出它的异常）在记录失败时读取另一个缓存，那次读取的数据源同样以
 * [InterruptedException] 失败：嵌套的加载不能改变外层失败的归属，外层的失败仍是数据源的失败，跟随者不再回源。
 */
class NestedSourceInterruptTest {
    @Test
    fun aNestedReadInTheObserverKeepsTheOuterSourceInterruptShared() = verify(Nested.READ)

    @Test
    fun aNestedBatchReadInTheObserverKeepsTheOuterSourceInterruptShared() = verify(Nested.BATCH)

    @Test
    fun aTransparentObserverWithoutNestedReadsSharesTheSourceInterrupt() = verify(Nested.NONE)

    private enum class Nested { NONE, READ, BATCH }

    private fun verify(nested: Nested) {
        val primaryFailure = InterruptedException("primary async worker interrupted")
        val auxiliaryFailure = InterruptedException("auxiliary async worker interrupted")
        val pending = CompletableFuture<String?>()
        val entered = CountDownLatch(1)
        val sourceCalls = AtomicInteger()
        lateinit var auxiliary: Cache<String, String>
        val observer = object : LoadObserver {
            override fun <T> observe(
                cacheName: String,
                operation: LoadOperation,
                keyCount: Int,
                block: Supplier<T>
            ): T {
                try {
                    return block.get()
                } catch (error: Throwable) {
                    if (cacheName == PRIMARY && nested != Nested.NONE) {
                        nestedFailure(nested, auxiliary).assert().isSameAs(auxiliaryFailure)
                    }
                    throw error
                }
            }
        }
        InMemoryBackend().use { backend ->
            CacheRuntime.builder().backend(backend).loadObserver(observer).build().use { runtime ->
                auxiliary = runtime.create(
                    cacheSpec<String, String>("auxiliary") {
                        codec = StringCodec
                        asyncLoader = AsyncCacheLoader { CompletableFuture.failedFuture(auxiliaryFailure) }
                    }
                )
                val primary = runtime.create(
                    cacheSpec<String, String>(PRIMARY) {
                        codec = StringCodec
                        asyncLoader = AsyncCacheLoader {
                            sourceCalls.incrementAndGet()
                            entered.countDown()
                            pending
                        }
                    }
                )
                val leader = fork("primary-leader") {
                    runCatching { primary["k"] }.exceptionOrNull() to Thread.currentThread().isInterrupted
                }
                entered.awaitOrFail("primary source entered")
                val follower = fork("primary-follower") { primary["k"] }
                awaitCondition("follower joined") { follower.parked }
                pending.completeExceptionally(primaryFailure)

                val (leaderError, interrupted) = leader.join()
                leaderError.assert().isSameAs(primaryFailure)
                interrupted.assert().isFalse()
                follower.joinFailure().assert().isSameAs(primaryFailure)
                sourceCalls.get().assert().withFailMessage {
                    "The nested read in the observer changed how the source failure is shared; calls=${sourceCalls.get()}"
                }.isEqualTo(1)
            }
        }
    }

    /**
     * 观测器在记录主缓存的失败时读取辅助缓存（单 key 或批量），返回那次读取的失败。
     */
    private fun nestedFailure(nested: Nested, auxiliary: Cache<String, String>): Throwable? =
        if (nested == Nested.BATCH) {
            runCatching { auxiliary.getAll(listOf("a", "b")) }.exceptionOrNull()
        } else {
            runCatching { auxiliary["aux"] }.exceptionOrNull()
        }

    private companion object {
        const val PRIMARY = "primary"
    }
}
