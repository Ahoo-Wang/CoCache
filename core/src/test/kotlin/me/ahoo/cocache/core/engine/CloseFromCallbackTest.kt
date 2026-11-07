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
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.awaitOrFail
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * #872：在异步结果的完成回调里关闭运行时，回调所在的协程仍在运行时作用域中，`close` 不等待作用域排空（否则只会等满
 * `closeTimeout`）。运行时的执行器拒绝续接、续接改由 `Dispatchers.IO` 执行时同样如此。
 *
 * 判据是回调线程有没有进入 `AsyncContext.awaitDrained`，而不是比较耗时；`closeTimeout` 只保证失败的用例也能结束。
 */
class CloseFromCallbackTest {
    @Test
    fun closingInACallbackAfterARejectedDispatchDoesNotWaitForItself() = verify(reject = true)

    @Test
    fun closingInACallbackDoesNotWaitForItself() = verify(reject = false)

    private fun verify(reject: Boolean) {
        val rejecting = AtomicBoolean(false)
        val load = CompletableFuture<String?>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        val failure = AtomicReference<Throwable?>()
        Executors.newCachedThreadPool().use { pool ->
            InMemoryBackend().use { backend ->
                CacheRuntime.builder()
                    .backend(backend)
                    .executor { command ->
                        if (rejecting.get()) {
                            throw RejectedExecutionException("saturated")
                        }
                        pool.execute(command)
                    }
                    .closeTimeout(Duration.ofSeconds(2))
                    .build()
                    .use { runtime ->
                        val cache = runtime.create(
                            cacheSpec<String, String>("callback-close") {
                                codec = StringCodec
                                asyncLoader = AsyncCacheLoader { load }
                            }
                        )
                        val result = cache.async().get("k")
                        awaitCondition("source awaited") { load.numberOfDependents > 0 }
                        result.whenComplete { _, error ->
                            failure.set(error)
                            callbackThread.set(Thread.currentThread())
                            started.countDown()
                            try {
                                runtime.close()
                            } finally {
                                finished.countDown()
                            }
                        }
                        rejecting.set(reject)
                        load.complete("value")
                        started.awaitOrFail("callback started")

                        fun draining() = callbackThread.get().stackTrace.any {
                            it.className.endsWith("AsyncContext") && it.methodName == "awaitDrained"
                        }
                        awaitCondition("close returned or waiting") { finished.count == 0L || draining() }
                        val waitedForItself = finished.count != 0L && draining()
                        finished.awaitOrFail("bounded close finished")
                        if (reject) {
                            failure.get().assert().isInstanceOf(RejectedExecutionException::class.java)
                        } else {
                            failure.get().assert().isNull()
                        }
                        waitedForItself.assert().withFailMessage {
                            "close waited for itself on ${callbackThread.get().name}"
                        }.isFalse()
                    }
            }
        }
    }
}
