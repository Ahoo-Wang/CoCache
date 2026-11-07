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
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 命令连接的心跳：每个 [heartbeat] 周期并发 `PING` 命令连接（[RedisConnections.commandConnections]：单机一条，集群每个主节点一条），
 * 一起至多等一个 `commandTimeout`。某条连接连续 [failuresBeforeReconnect] 次失败时断开它（[RedisInvalidationChannel.disconnect]），
 * 由 Lettuce 重连：半开的 TCP 连接上 Lettuce 仍认为已连接，不会自行重连，命令一直超时到内核发现（没有 epoll / io_uring 时可达
 * 十几分钟）。断线期间命令被拒绝（`REJECT_COMMANDS`，I-12），在途的命令以“断线”失败、结果未知（I-E），被取得的令牌照常释放（I-F）。
 *
 * 误判的代价是把在途写入变成结果未知，所以只在连续多次失败后才断开，不在一次超时之后：大载荷的队头阻塞会让一次 `PING` 超时。
 * 成功一次即清零。[failuresBeforeReconnect] 为 `0` 时只报告状态、从不断开。
 *
 * 最近一次心跳的结果以只读状态暴露（[lastFailure]，`RedisBackend.commandConnectionHealthy`），不访问 Redis。
 */
internal class CommandConnectionHeartbeat(
    private val connections: RedisConnections,
    heartbeat: Duration,
    private val failuresBeforeReconnect: Int,
    private val disconnect: (StatefulConnection<*, *>) -> Boolean = RedisInvalidationChannel::disconnect,
    schedule: Boolean = true,
) : AutoCloseable {
    /**
     * 每条连接（`host:port`，单机为 `redis`）连续失败的次数。
     */
    private val failures = ConcurrentHashMap<String, Int>()

    /**
     * 强制断开的次数（测试用）。
     */
    internal val reconnects = AtomicInteger()

    /**
     * 最近一次心跳失败的连接与原因；最近一次心跳全部成功（或尚未执行心跳）时为 `null`。
     */
    @Volatile
    var lastFailure: String? = null
        private set

    private val scheduler: ScheduledExecutorService? = if (schedule) {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cocache-redis-command-heartbeat").apply { isDaemon = true }
        }.apply {
            scheduleWithFixedDelay(::beat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS)
        }
    } else {
        null
    }

    /**
     * 心跳任务：任何异常都不能逃出去，否则 `scheduleWithFixedDelay` 不再执行它（同订阅连接的心跳）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun beat() {
        try {
            probe()
        } catch (e: Throwable) {
            lastFailure = e.toString()
            log.error(e) { "Command connection heartbeat task failed - continue with the next heartbeat." }
        }
    }

    /**
     * 一次心跳：并发 PING 每条命令连接，失败的累计次数，达到 [failuresBeforeReconnect] 时断开它并清零。
     */
    internal fun probe() {
        val targets = connections.commandConnections()
        val names = targets.keys.toList()
        failures.keys.retainAll(names.toSet())
        val outcomes = connections.joinAll(names.map { name -> ping(requireNotNull(targets[name])) })
        val failed = linkedMapOf<String, String>()
        names.zip(outcomes).forEach { (name, outcome) ->
            val error = outcome.exceptionOrNull()
            if (error == null) {
                failures.remove(name)
                return@forEach
            }
            failed[name] = error.toString()
            val count = failures.merge(name, 1) { previous, one -> previous + one } ?: 1
            log.warn { "Command connection[$name] heartbeat failed ($count in a row): $error" }
            if (failuresBeforeReconnect in 1..count) {
                failures.remove(name)
                reconnect(name, requireNotNull(targets[name]))
            }
        }
        lastFailure = failed.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { (name, error) -> "$name: $error" }
    }

    private fun ping(target: CompletableFuture<out StatefulConnection<ByteArray, ByteArray>>): CompletableFuture<String> {
        return target.thenCompose { connection ->
            (connection as StatefulRedisConnection<ByteArray, ByteArray>).async().ping().toCompletableFuture()
        }
    }

    /**
     * 连接本身建立不起来（集群的节点连接）时没有可断开的连接，Lettuce 下次取用时再建立。
     */
    private fun reconnect(name: String, target: CompletableFuture<out StatefulConnection<ByteArray, ByteArray>>) {
        val connection = if (target.isCompletedExceptionally) null else target.getNow(null)
        if (connection == null) {
            log.debug { "Command connection[$name] is not established - nothing to reconnect." }
            return
        }
        if (disconnect(connection)) {
            reconnects.incrementAndGet()
            log.warn {
                "Command connection[$name] failed $failuresBeforeReconnect heartbeats in a row - force reconnect; " +
                    "commands in flight on it fail with an unknown outcome."
            }
        } else {
            log.warn { "Command connection[$name] - cannot force reconnect, rely on TCP keepalive." }
        }
    }

    override fun close() {
        scheduler?.shutdownNow()
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
