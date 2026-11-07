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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.spi.Token
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 尽力释放令牌（RFC 0005 round 3 K14），每个后端一个，各存储共用：取得或交回令牌的命令失败时（确定未执行除外），
 * 调用方随后不会再交回这个令牌，否则 lease 留到 `lease.ttl`，全集群对该 key 的未命中都要先等 `lease.wait`。
 *
 * - 释放异步发出、不等待（[send]，受 `commandTimeout` 限制）。失败时（含断线期间被拒绝）按退避重发（[MIN_BACKOFF] 起倍增，
 *   至多 [MAX_BACKOFF]，经 [schedule] 在客户端资源的时间轮上），直到执行、过了令牌的寿命（`null` 为 [UNKNOWN_TOKEN_LIFETIME]，
 *   之后令牌已失效）或连接已关闭（[schedule] 返回 `false`）：断线期间被拒绝的释放因此在重连之后到达，lease 通常不必等到 `lease.ttl`。
 * - 令牌不匹配时释放什么也不做，迟到、重复或重连之后才到达都无害。连接被替换之后，释放可能先于旧连接上被排空的原命令执行：
 *   令牌不匹配、释放就此结束，原命令随后取得的令牌留到 `lease.ttl`（TLA+ F4）。
 * - 放弃（过了寿命、连接已关闭、发出时同步失败）时计数（[abandoned]），令牌由它的寿命回收。告警按 [WARNING_INTERVAL] 聚合：
 *   间隔内的第一次立即告警，其余计数，到期时汇总一次。命令超时风暴中每条读取都可能放弃一次释放，逐条告警会刷屏。
 *
 * @param nanoTime 单调时钟（纳秒），测试注入以确定地推进令牌的寿命与告警间隔
 * @param warn 告警的出口（测试注入）
 */
internal class TokenReleaser(
    private val send: (key: String, token: Token) -> CompletableFuture<*>,
    private val schedule: (delay: Duration, task: () -> Unit) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
    private val warn: (error: Throwable?, message: String) -> Unit = { error, message -> log.warn(error) { message } },
) {
    private val abandonedCount = AtomicLong()

    /**
     * 放弃的释放数（令牌留到它的寿命）。
     */
    val abandoned: Long
        get() = abandonedCount.get()

    /**
     * 最近一次告警的时刻（[nanoTime]）；尚未告警时为 `null`。
     */
    @Volatile
    private var lastWarning: Long? = null
    private val warningLock = Any()

    /**
     * 上次告警之后放弃、尚未报告的释放数。
     */
    private val unreported = AtomicLong()
    private val summaryScheduled = AtomicBoolean()

    /**
     * 命令以 [error] 失败：除非确定未执行（[RedisConnections.isNotExecuted]：写出之前就被拒绝，不会取得令牌），释放 [token]。
     * 命令可能已在 Redis 端执行（读取、回收授予了 lease 或刷新令牌），也可能被取消、永远不会执行（fill 没有写出，lease 仍在）。
     */
    fun releaseUnlessNotExecuted(key: String, token: Token, lifetime: Duration?, error: Throwable) {
        if (!RedisConnections.isNotExecuted(error)) {
            release(key, token, lifetime)
        }
    }

    /**
     * 释放 [token]，失败时按退避重发，直到过了它的寿命 [lifetime]（`null` 为 [UNKNOWN_TOKEN_LIFETIME]）。
     */
    fun release(key: String, token: Token, lifetime: Duration?) {
        val deadline = nanoTime() + (lifetime ?: UNKNOWN_TOKEN_LIFETIME).toNanos()
        sendRelease(key, token, deadline, MIN_BACKOFF)
    }

    /**
     * 发出一次释放；失败时安排下一次。发出时同步失败（如客户端资源已关闭）只放弃：调用方抛出的必须是原来的失败，
     * 批量读取也要继续处理其余的 key。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun sendRelease(key: String, token: Token, deadline: Long, backoff: Duration) {
        val release = try {
            send(key, token)
        } catch (e: RuntimeException) {
            abandon(key, e)
            return
        }
        release.whenComplete { _, error ->
            if (error == null) {
                return@whenComplete
            }
            val next = backoff.multipliedBy(2).coerceAtMost(MAX_BACKOFF)
            val retrying = deadline - nanoTime() > backoff.toNanos() &&
                schedule(backoff) { sendRelease(key, token, deadline, next) }
            if (retrying) {
                log.debug(error) { "Release token of key[$key] failed - retry in $backoff." }
            } else {
                abandon(key, error)
            }
        }
    }

    /**
     * 放弃一次释放：计数；距上次告警满 [WARNING_INTERVAL]（或从未告警）时立即告警，否则只计入 [unreported]，
     * 并安排一次到期时的汇总（[summarize]）。
     */
    private fun abandon(key: String, error: Throwable) {
        abandonedCount.incrementAndGet()
        val now = nanoTime()
        val immediate = synchronized(warningLock) {
            val last = lastWarning
            (last == null || now - last >= WARNING_INTERVAL.toNanos()).also { if (it) lastWarning = now }
        }
        if (immediate) {
            warn(error, "Release token of key[$key] failed - reclaimed after its lifetime.")
            return
        }
        unreported.incrementAndGet()
        if (summaryScheduled.compareAndSet(false, true) && !schedule(WARNING_INTERVAL) { summarize() }) {
            // 连接已关闭：不再汇总
            summaryScheduled.set(false)
        }
    }

    /**
     * 汇总上次告警之后放弃、尚未报告的释放。
     */
    private fun summarize() {
        summaryScheduled.set(false)
        val count = unreported.getAndSet(0)
        if (count == 0L) {
            return
        }
        synchronized(warningLock) { lastWarning = nanoTime() }
        warn(
            null,
            "$count more token releases failed in the last $WARNING_INTERVAL - reclaimed after their lifetime " +
                "(${abandonedCount.get()} abandoned in total)."
        )
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * 重发释放的退避：首次重发的间隔与上限。
         */
        val MIN_BACKOFF: Duration = Duration.ofMillis(100)
        val MAX_BACKOFF: Duration = Duration.ofSeconds(2)

        /**
         * 请求中没有寿命的令牌（填充、释放交回的 lease）重发释放的时限：长于默认的 `lease.ttl`（10 s），过期之后的释放只是空操作。
         */
        val UNKNOWN_TOKEN_LIFETIME: Duration = Duration.ofMinutes(1)

        /**
         * 放弃释放的告警至多每隔这么久一次，其余汇总。
         */
        val WARNING_INTERVAL: Duration = Duration.ofSeconds(10)

        /**
         * 释放脚本（[RedisScripts.RELEASE]）。
         */
        fun releaseCommand(connections: RedisConnections, key: String, token: Token): CompletableFuture<List<Any?>> =
            connections.evalAsync(
                RedisScripts.RELEASE,
                listOf(key.toByteArray(Charsets.UTF_8)),
                listOf(token.toBytes())
            )

        /**
         * 经 [connections] 发出释放（受 `commandTimeout` 限制）、在它的时间轮上重发的释放器。
         */
        fun of(connections: RedisConnections): TokenReleaser = TokenReleaser(
            { key, token -> connections.bounded(releaseCommand(connections, key, token)) },
            connections::schedule,
        )
    }
}
