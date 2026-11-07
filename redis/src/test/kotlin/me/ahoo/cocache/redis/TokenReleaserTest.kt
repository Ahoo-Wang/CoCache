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

package me.ahoo.cocache.redis

import io.lettuce.core.RedisException
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Token
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * 令牌释放器（K14）：时钟可注入，退避与令牌寿命确定地推进；放弃的释放计数，告警按间隔聚合。
 */
class TokenReleaserTest {
    private val now = AtomicLong()
    private val sent = ArrayList<CompletableFuture<Any?>>()
    private val scheduled = ArrayList<Pair<Duration, () -> Unit>>()
    private val warnings = ArrayList<String>()
    private var accepting = true

    private val releaser = TokenReleaser(
        send = { _, _ -> CompletableFuture<Any?>().also { sent.add(it) } },
        schedule = { delay, task -> accepting.also { if (it) scheduled.add(delay to task) } },
        nanoTime = { now.get() },
        warn = { _, message -> warnings.add(message) },
    )

    private val token = Token(NodeId.random(), 1)
    private val rejected = RedisException("Currently not connected. Commands are rejected.")

    /**
     * 运行最早安排的任务，时钟推进它的延迟。
     */
    private fun runNext(): Duration {
        val (delay, task) = scheduled.removeAt(0)
        now.addAndGet(delay.toNanos())
        task()
        return delay
    }

    @Test
    fun retriesWithBackoffUntilTheReleaseRuns() {
        releaser.release("k", token, Duration.ofSeconds(10))
        val delays = List(6) {
            sent.last().completeExceptionally(rejected)
            runNext().toMillis()
        }
        delays.assert().containsExactly(100L, 200L, 400L, 800L, 1600L, 2000L)
        sent.last().complete(listOf(1L))
        scheduled.assert().isEmpty()
        releaser.abandoned.assert().isZero()
    }

    /**
     * 过了令牌的寿命不再重发（按注入的时钟判断）：放弃并计数。
     */
    @Test
    fun abandonsOnceTheTokenExpires() {
        releaser.release("k", token, Duration.ofMillis(250))
        sent.last().completeExceptionally(rejected)
        runNext()
        sent.last().completeExceptionally(rejected)
        // 剩余 150 ms 不足下一次的 200 ms 退避
        scheduled.assert().isEmpty()
        releaser.abandoned.assert().isEqualTo(1)
        warnings.assert().containsExactly("Release token of key[k] failed - reclaimed after its lifetime.")
    }

    /**
     * 确定未执行的命令没有取得令牌，不释放；其余失败都释放。
     */
    @Test
    fun releasesUnlessNotExecuted() {
        releaser.releaseUnlessNotExecuted("k", token, null, rejected)
        sent.assert().isEmpty()
        releaser.releaseUnlessNotExecuted("k", token, null, RedisException("Command timed out"))
        sent.size.assert().isEqualTo(1)
    }

    /**
     * 命令超时风暴中每条读取都可能放弃一次释放：间隔内的第一次立即告警，其余计数，到期时汇总一次。
     */
    @Test
    fun warningsAreAggregatedPerInterval() {
        accepting = false
        repeat(5) { i ->
            releaser.release("k$i", token, null)
            sent.last().completeExceptionally(rejected)
        }
        releaser.abandoned.assert().isEqualTo(5)
        // 连接已关闭（不再安排）：只有第一次告警，汇总也安排不了
        warnings.assert().containsExactly("Release token of key[k0] failed - reclaimed after its lifetime.")

        accepting = true
        now.addAndGet(TokenReleaser.WARNING_INTERVAL.toNanos())
        repeat(3) { i ->
            releaser.release("j$i", token, Duration.ofMillis(1))
            sent.last().completeExceptionally(rejected)
        }
        warnings.size.assert().isEqualTo(2)
        warnings.last().assert().startsWith("Release token of key[j0]")
        // 汇总只安排一次，到期时报告间隔内其余的放弃（含连接关闭期间未报告的）
        scheduled.size.assert().isEqualTo(1)
        runNext()
        warnings.last().assert().isEqualTo(
            "6 more token releases failed in the last PT10S - reclaimed after their lifetime (8 abandoned in total)."
        )
        // 没有新的放弃时汇总什么也不说
        releaser.release("x", token, Duration.ofMillis(1))
        sent.last().completeExceptionally(rejected)
        runNext()
        warnings.size.assert().isEqualTo(4)
        warnings.last().assert().startsWith("1 more token releases failed")
    }

    /**
     * 发出时同步失败（客户端资源已关闭）：放弃，不抛给调用方。
     */
    @Test
    fun aSynchronousSendFailureIsAbandoned() {
        val failing =
            TokenReleaser({ _, _ -> throw IllegalStateException("closed") }, { _, _ -> true }, { 0L }) { _, _ -> }
        failing.release("k", token, null)
        failing.abandoned.assert().isEqualTo(1)
    }
}
