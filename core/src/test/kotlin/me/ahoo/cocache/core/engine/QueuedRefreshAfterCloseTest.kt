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
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.clock.ManualClock
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * 关闭之前排进刷新执行器、关闭之后才开始的刷新（#850）：执行器由调用方给出，可能与别的组件共用、关闭之后仍在运行。
 * 这样的刷新跳过：不读 L1、不回源、不写回，并释放本节点的刷新守卫；运行时开着时同一个排队的刷新照常执行。
 */
class QueuedRefreshAfterCloseTest {
    /**
     * 只排队、显式 [drain] 才执行的刷新执行器。
     */
    private class QueuedExecutor : Executor {
        val tasks = ConcurrentLinkedQueue<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun drain() {
            while (true) {
                (tasks.poll() ?: return).run()
            }
        }
    }

    private val clock = ManualClock()
    private val memory = InMemoryBackend(clock)
    private val executor = QueuedExecutor()
    private val writer = CacheRuntime.builder().backend(memory).clock(clock).refreshExecutor(executor).build()
    private val reader = CacheRuntime.builder().backend(memory).clock(clock).build()
    private val database = ConcurrentHashMap<String, String>()
    private val loads = AtomicInteger()

    @AfterEach
    fun destroy() {
        writer.close()
        reader.close()
    }

    private fun spec(block: CacheSpec.Builder<String, String>.() -> Unit) = cacheSpec<String, String>(NAME) {
        codec = StringCodec
        block()
        policy {
            ttl = Duration.ofSeconds(10)
            ttlJitter = Duration.ZERO
            localMaxAge = Duration.ofSeconds(8)
            refreshWindow = Duration.ofSeconds(5)
        }
    }

    private fun syncSource(): CacheSpec<String, String> = spec {
        loader = CacheLoader {
            loads.incrementAndGet()
            database[it]
        }
    }

    private fun asyncSource(): CacheSpec<String, String> = spec {
        asyncLoader = AsyncCacheLoader {
            loads.incrementAndGet()
            CompletableFuture.completedFuture(database[it])
        }
    }

    /**
     * 在 [writer] 上读出 `old`，再在刷新窗口内命中、排进一个刷新，返回缓存。
     */
    private fun queueARefresh(spec: CacheSpec<String, String>): Cache<String, String> {
        val cache = writer.create(spec)
        database["k"] = "old"
        cache["k"].assert().isEqualTo("old")
        clock.advance(Duration.ofSeconds(6))
        database["k"] = "new"
        cache["k"].assert().isEqualTo("old")
        executor.tasks.assert().hasSize(1)
        loads.get().assert().isEqualTo(1)
        return cache
    }

    private fun l1(): String? = reader.create(
        cacheSpec<String, String>(NAME) {
            codec = StringCodec
            externallySourced()
        }
    )["k"]

    @Test
    fun aQueuedSyncRefreshStartingAfterCloseIsSkipped() {
        queueARefresh(syncSource())
        writer.close()
        executor.drain()
        loads.get().assert().withFailMessage { "the queued refresh must not load after close" }.isEqualTo(1)
        l1().assert().withFailMessage { "the queued refresh must not write L1 after close" }.isEqualTo("old")
    }

    @Test
    fun aQueuedAsyncRefreshStartingAfterCloseIsSkipped() {
        queueARefresh(asyncSource())
        writer.close()
        executor.drain()
        loads.get().assert().isEqualTo(1)
        l1().assert().isEqualTo("old")
    }

    /**
     * 对照：运行时开着时同一个排队的刷新照常执行，并在结束之后释放守卫（下一次进入窗口的命中可以再排一个刷新）。
     */
    @Test
    fun aQueuedRefreshRunsWhileTheRuntimeIsOpen() {
        val cache = queueARefresh(syncSource())
        executor.drain()
        loads.get().assert().isEqualTo(2)
        l1().assert().isEqualTo("new")
        cache["k"].assert().isEqualTo("new")
        clock.advance(Duration.ofSeconds(6))
        cache["k"].assert().isEqualTo("new")
        executor.tasks.assert().withFailMessage { "the guard was released, so a new refresh is queued" }.hasSize(1)
    }

    private companion object {
        const val NAME = "queued"
    }
}
