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

package me.ahoo.cocache.core.memory

import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.cocache.test.Concurrency.fork
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * 内存后端的卡顿（[InMemoryBackend.stall]，RFC 0005 round 3 D6 的 TCK 钩子）：卡顿期间的操作等到恢复再执行；
 * 超过时限的操作以 L1 故障失败且永远不执行；不为正的读取时限在执行之前就被拒绝。
 */
class InMemoryStallTest {
    private val frame = InvalidationFrame.encode(NodeId.random(), listOf("k"))

    @Test
    fun stalledOperationRunsOnceResumed() {
        val backend = InMemoryBackend()
        val store = backend.store("ns")
        val stall = backend.stall()
        val writer = fork("stalled-writer") {
            Suspending.await { store.put("k", Envelope.Scalar("v".toByteArray()), null, frame) }
        }
        // 卡顿期间写入停在门上（调用方线程在 Suspending 的事件循环中等待），没有执行
        awaitCondition("writer waits for the stall") { writer.thread.state == Thread.State.TIMED_WAITING }
        stall.close()
        writer.join()
        Suspending.await { store.read("k", null, null, null) }.assert().isInstanceOf(L1Read.Hit::class.java)
    }

    @Test
    fun operationThatTimesOutWhileStalledNeverRuns() {
        val backend = InMemoryBackend(commandTimeout = Duration.ofMillis(100))
        val store = backend.store("ns")
        backend.stall().use {
            val failure = runCatching {
                Suspending.await { store.put("k", Envelope.Scalar("v".toByteArray()), null, frame) }
            }.exceptionOrNull()
            failure.assert().isExactlyInstanceOf(IllegalStateException::class.java)
            failure?.cause.assert().isInstanceOf(TimeoutException::class.java)
            // 读取的时限以命令超时为上限
            val started = System.nanoTime()
            val read = runCatching { Suspending.await { store.read("k", null, null, Duration.ofHours(1)) } }
            read.isFailure.assert().isTrue()
            Duration.ofNanos(System.nanoTime() - started).assert().isLessThan(Duration.ofSeconds(5))
        }
        Suspending.await { store.read("k", null, null, null) }.assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun nonPositiveReadTimeoutIsRejected() {
        val store = InMemoryBackend().store("ns")
        listOf(Duration.ZERO, Duration.ofMillis(-1)).forEach { timeout ->
            runCatching { Suspending.await { store.read("k", null, null, timeout) } }.exceptionOrNull()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
