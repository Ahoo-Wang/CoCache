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

import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisException
import io.lettuce.core.TimeoutOptions
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.test.Suspending
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Redis 存储的故障与边界（RFC 0005 §4.3）：脚本缓存丢失、命令超时、非运行时异常、在途期间断连。
 */
class RedisStoreFaultTest {
    private val namespace = "async-fault-" + UUID.randomUUID().toString().substring(0, 8)
    private val node = NodeId.random()
    private var sequence = 0L
    private val closeables = ArrayList<AutoCloseable>()
    private val caller = CallerDispatcher().also { closeables.add(it) }

    @AfterEach
    fun destroy() {
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables.add(it) }

    private fun key(name: String) = "cocache:$namespace:$name"

    private fun token() = Token(node, ++sequence)

    private fun frame(vararg keys: String) = InvalidationFrame.encode(node, keys.toList())

    private fun scalar(text: String) = Envelope.Scalar(text.toByteArray())

    private fun <T> CompletableFuture<T>.await(): T = get(10, TimeUnit.SECONDS)

    private fun <T> CompletableFuture<T>.failure(): Throwable? = handle { _, e -> e }.get(10, TimeUnit.SECONDS)

    /**
     * 可以暂停的连接（[RedisTestSupport.pausable]），随用例关闭：暂停期间发出的命令已写出，到期后按序执行。只暂停用它连接的客户端。
     */
    private fun pausable() = RedisTestSupport.pausable().closing()

    @Test
    fun everyOperationSurvivesScriptCacheLoss() = Suspending.await<Unit> {
        val backend = RedisTestSupport.backend().closing()
        val store = backend.store(Keyspace("cocache:", namespace))
        val lease = LeaseRequest(token(), Duration.ofSeconds(30))
        val refresh = RefreshRequest(Duration.ofMinutes(5), token(), Duration.ofSeconds(10))
        val leases = mapOf(key("b") to LeaseRequest(token(), Duration.ofSeconds(30)))
        val members = listOf("m".toByteArray())
        val a = key("a")
        val b = key("b")
        val c = key("c")
        val d = key("d")
        val s = key("s")
        // 每个操作之前清空脚本缓存：EVALSHA 得到 NOSCRIPT 后改用 EVAL
        val operations: List<suspend () -> Unit> = listOf(
            { store.read(a, lease, null, null).assert().isEqualTo(L1Read.Miss(true)) },
            {
                store.fill(
                    Fill(a, lease.token, scalar("v"), Duration.ofMinutes(1))
                ).assert().isInstanceOf(L1Fill.Filled::class.java)
            },
            { (store.read(a, null, refresh, null) as L1Read.Hit).envelope.assert().isEqualTo(scalar("v")) },
            { store.readAll(listOf(a, b), leases, null)[b].assert().isEqualTo(L1Read.Miss(true)) },
            {
                store.fillAll(listOf(Fill(b, leases.getValue(b).token, scalar("w"), null)))
                    .assert().containsExactly(L1Fill.Filled(advanced = false))
            },
            { store.release(b, token()) },
            { store.put(c, scalar("p"), null, frame(c)) },
            { store.putAll(listOf(Put(d, scalar("q"), null)), frame(d)) },
            {
                store.reclaim(
                    c,
                    ContentFingerprint.of(scalar("other"), RedisScripts.FORMAT),
                    LeaseRequest(token(), Duration.ofSeconds(30))
                ).assert().isFalse()
            },
            { store.setSurface.addMembers(s, members, null, frame(s)).assert().isNull() },
            { store.setSurface.removeMembers(s, members, null, frame(s)).assert().isNull() },
            { store.invalidate(listOf(a, b, c, d), frame(a, b, c, d)) },
        )
        operations.forEach { operation ->
            RedisTestSupport.flushScripts()
            operation()
        }
        store.read(a, null, null, null).assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 命令超过 `commandTimeout`：以 [RedisException] 失败，连接随后照常可用。自建单机客户端的超时由 Lettuce 的
     * 命令超时触发（cause 为 [RedisCommandTimeoutException]），不经 JDK 的延迟调度器（RFC 0004 §14）。单机与集群。
     */
    @Test
    fun commandTimeoutFailsTheCallAndTheConnectionRecovers() {
        val redis = pausable()
        val backend = RedisTestSupport.backend(
            RedisBackendOptions(commandTimeout = Duration.ofMillis(200), clientResources = redis.resources)
        ).closing()
        val store = backend.store(Keyspace("cocache:", namespace))
        // 先缓存脚本：否则 NOSCRIPT 回退的 EVAL 是另一条命令，暂停期间各自计时
        Suspending.await { store.read(key("warm"), null, null, null) }
        redis.pause(PAUSE_MILLIS)
        val error = caller.start { store.read(key("slow"), null, null, null) }.failure()
        error.assert().isInstanceOf(RedisException::class.java)
        error?.message.assert().isEqualTo("Command timed out after PT0.2S.")
        if (!RedisTestSupport.isCluster) {
            // 单机只由 Lettuce 计时；集群中时间轮与 Lettuce 同时计时，先到者生效
            error?.cause.assert().isInstanceOf(RedisCommandTimeoutException::class.java)
        }
        // 批量操作（集群中跨节点：首次访问的节点在暂停期间建立连接，Lettuce 尚未计时，由时间轮兜底）：全部完成后以第一个异常失败（与同步版本相同的异常类型）
        val leases = mapOf(key("b1") to LeaseRequest(token(), Duration.ofSeconds(30)))
        listOf(
            caller.start { store.readAll(listOf(key("b1"), key("b2")), leases, null) },
            caller.start { store.fillAll(listOf(Fill(key("b1"), token(), scalar("v"), null))) },
            caller.start { store.putAll(listOf(Put(key("b3"), scalar("p"), null)), frame(key("b3"))) },
            caller.start { store.invalidate(listOf(key("b1"), key("b2")), frame(key("b1"), key("b2"))) },
        ).forEachIndexed { index, call ->
            val failure = call.failure()
            failure.assert().withFailMessage { "batch #$index did not fail" }.isNotNull()
            failure.assert().isInstanceOf(RedisException::class.java)
            failure?.message.assert().isEqualTo("Command timed out after PT0.2S.")
        }
        Thread.sleep(PAUSE_MILLIS)
        Suspending.await { store.read(key("slow"), null, null, null) }.assert().isEqualTo(L1Read.Miss(false))
    }

    /**
     * 调用方的客户端（`RedisBackend.of`）不为命令计时时，超时仍由后端以 `commandTimeout` 计时：同样的异常，连接照常可用。
     */
    @Test
    fun commandTimeoutStillHoldsForAClientWithoutCommandTimeouts() {
        assumeFalse(RedisTestSupport.isCluster)
        val redis = pausable()
        val client = RedisTestSupport.standaloneClient(redis.resources)
        client.options = ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .timeoutOptions(TimeoutOptions.builder().timeoutCommands(false).build())
            .build()
        closeables.add(AutoCloseable { client.shutdown() })
        val backend = RedisBackend.of(client, RedisBackendOptions(commandTimeout = Duration.ofMillis(200))).closing()
        val store = backend.store(Keyspace("cocache:", namespace))
        Suspending.await { store.read(key("warm"), null, null, null) }
        redis.pause(1_000)
        val error = caller.start { store.read(key("slow"), null, null, null) }.failure()
        error.assert().isInstanceOf(RedisException::class.java)
        error?.message.assert().isEqualTo("Command timed out after PT0.2S.")
        error?.cause.assert().isInstanceOf(TimeoutException::class.java)
        Thread.sleep(1_000)
        Suspending.await { store.read(key("slow"), null, null, null) }.assert().isEqualTo(L1Read.Miss(false))
    }

    @Test
    fun ownedClientsTimeCommandsWithoutRelaxingDuringMaintenance() {
        val options = RedisBackend.timeoutOptions(Duration.ofMillis(1500))
        options.isTimeoutCommands.assert().isTrue()
        options.isApplyConnectionTimeout.assert().isFalse()
        options.source.getTimeout(null).assert().isEqualTo(Duration.ofMillis(1500).toNanos())
        if (lettuceHasMaintenanceRelaxation()) {
            options.relaxedTimeout.isNegative.assert().isTrue()
        }
    }

    /**
     * 当前类路径上的 Lettuce 是否有维护期间的超时放宽（Lettuce 7 有，Lettuce 6 没有）：本类的用例在两个版本上都要通过。
     */
    private fun lettuceHasMaintenanceRelaxation(): Boolean =
        TimeoutOptions::class.java.methods.any { it.name == "getRelaxedTimeout" }

    /**
     * Lettuce 6（Spring Boot 4.0 管理的版本）没有维护期间的放宽：缺少的符号在执行到时才抛出，跳过这一项，其余选项照常生效。
     */
    @Test
    fun maintenanceRelaxationIsSkippedWhenLettuceLacksIt() {
        val builder = TimeoutOptions.builder().timeoutCommands(true).fixedTimeout(Duration.ofMillis(1500))
        val missingMethod: (TimeoutOptions.Builder) -> Unit = { throw NoSuchMethodError("relaxedTimeouts") }
        val missingField: (TimeoutOptions.Builder) -> Unit = { throw NoSuchFieldError("DISABLED_TIMEOUT") }
        RedisBackend.disableMaintenanceRelaxation(builder, missingMethod).assert().isFalse()
        RedisBackend.disableMaintenanceRelaxation(builder, missingField).assert().isFalse()
        val options = builder.build()
        options.isTimeoutCommands.assert().isTrue()
        options.source.getTimeout(null).assert().isEqualTo(Duration.ofMillis(1500).toNanos())
        val supported = lettuceHasMaintenanceRelaxation()
        RedisBackend.disableMaintenanceRelaxation(TimeoutOptions.builder()).assert().isEqualTo(supported)
    }

    /**
     * 命令在途期间连接断开：调用以异常结束（不会永远挂起），不在断线期间缓冲重放。
     */
    @Test
    fun disconnectWhileACommandIsInFlightFailsTheCall() {
        assumeFalse(RedisTestSupport.isCluster)
        val proxy = RedisTestSupport.proxy().closing()
        val backend = RedisTestSupport.proxiedBackend(proxy).closing()
        val store = backend.store(Keyspace("cocache:", namespace))
        Suspending.await { store.read(key("warm"), null, null, null) }
        proxy.pause(1_000)
        val pending = caller.start { store.put(key("cut"), scalar("v"), null, frame(key("cut"))) }
        val restore = proxy.cut()
        pending.failure().assert().isNotNull()
        restore.close()
    }

    @Test
    fun boundedMapsNonRuntimeFailuresAndUnwrapsCompletionExceptions() {
        assumeFalse(RedisTestSupport.isCluster)
        val client = RedisTestSupport.standaloneClient()
        val connections = RedisConnections(client, Duration.ofSeconds(2))
        try {
            val checked = Exception("checked")
            val mapped = connections.bounded(CompletableFuture.failedFuture<String>(checked)).failure()
            mapped.assert().isInstanceOf(RedisException::class.java)
            mapped?.cause.assert().isSameAs(checked)
            val runtime = IllegalStateException("runtime")
            connections.bounded(
                CompletableFuture.failedFuture<String>(CompletionException(runtime))
            ).failure().assert().isSameAs(runtime)
            connections.bounded(CompletableFuture.completedFuture("v")).await().assert().isEqualTo("v")
            // 客户端不计时：客户端自己的超时原样传递（消息里的时长不是 commandTimeout）
            val lettuceTimeout = RedisCommandTimeoutException("Command timed out after 1 second(s)")
            connections.bounded(CompletableFuture.failedFuture<String>(lettuceTimeout)).failure()
                .assert().isSameAs(lettuceTimeout)
        } finally {
            connections.close()
            client.shutdown()
        }
    }

    /**
     * 客户端按 `commandTimeout` 计时：它的超时在异步与同步路径上都映射为同样的 [RedisException]，其它失败照常解包。
     */
    @Test
    fun clientTimedCommandsMapTheClientTimeout() {
        assumeFalse(RedisTestSupport.isCluster)
        val client = RedisTestSupport.standaloneClient()
        val connections = RedisConnections(client, Duration.ofSeconds(2), CommandTimeouts.CLIENT)
        try {
            val lettuceTimeout = RedisCommandTimeoutException("Command timed out after 2 second(s)")
            val asyncFailure = connections.bounded(CompletableFuture.failedFuture<String>(lettuceTimeout)).failure()
            asyncFailure.assert().isInstanceOf(RedisException::class.java)
            asyncFailure?.message.assert().isEqualTo("Command timed out after PT2S.")
            asyncFailure?.cause.assert().isSameAs(lettuceTimeout)
            val joinFailure = runCatching {
                connections.join(CompletableFuture.failedFuture<String>(lettuceTimeout))
            }.exceptionOrNull()
            joinFailure?.message.assert().isEqualTo("Command timed out after PT2S.")
            joinFailure?.cause.assert().isSameAs(lettuceTimeout)
            val runtime = IllegalStateException("runtime")
            connections.bounded(
                CompletableFuture.failedFuture<String>(CompletionException(runtime))
            ).failure().assert().isSameAs(runtime)
            connections.bounded(CompletableFuture.completedFuture("v")).await().assert().isEqualTo("v")
            // 客户端计时全程时 bounded 不另外计时：超过 commandTimeout 仍未完成的 future 不会被后端超时
            val shortTimeouts = RedisConnections(client, Duration.ofMillis(50), CommandTimeouts.CLIENT)
            val pending = CompletableFuture<String>()
            val bounded = shortTimeouts.bounded(pending)
            Thread.sleep(300)
            bounded.isDone.assert().isFalse()
            pending.complete("late")
            bounded.await().assert().isEqualTo("late")
            shortTimeouts.close()
        } finally {
            connections.close()
            client.shutdown()
        }
    }

    /**
     * 集群的自建客户端（[CommandTimeouts.CLIENT_WRITTEN]）：时间轮兜底未交给客户端计时的命令，客户端自己的超时同样映射。
     */
    @Test
    fun guardedClientTimedCommandsTimeOutOnTheTimerAndMapTheClientTimeout() {
        assumeFalse(RedisTestSupport.isCluster)
        val client = RedisTestSupport.standaloneClient()
        val connections = RedisConnections(client, Duration.ofMillis(100), CommandTimeouts.CLIENT_WRITTEN)
        try {
            val pending = CompletableFuture<String>()
            val error = connections.bounded(pending).failure()
            error?.message.assert().isEqualTo("Command timed out after PT0.1S.")
            error?.cause.assert().isInstanceOf(TimeoutException::class.java)
            // 超时后不再等待：迟到的结果被丢弃，命令 future 被取消
            pending.isCancelled.assert().isTrue()
            val lettuceTimeout = RedisCommandTimeoutException("Command timed out after 100 millisecond(s)")
            val mapped = connections.bounded(CompletableFuture.failedFuture<String>(lettuceTimeout)).failure()
            mapped?.message.assert().isEqualTo("Command timed out after PT0.1S.")
            mapped?.cause.assert().isSameAs(lettuceTimeout)
            connections.bounded(CompletableFuture.completedFuture("v")).await().assert().isEqualTo("v")
        } finally {
            connections.close()
            client.shutdown()
        }
        // 时间轮已停止（客户端已关闭）：不计时，结果照常传递
        connections.bounded(CompletableFuture.completedFuture("v")).await().assert().isEqualTo("v")
    }

    private companion object {
        /**
         * 超时用例的暂停时长：留足余量让暂停覆盖全部命令（集群中批量操作还要等待建立节点连接）。
         */
        const val PAUSE_MILLIS = 2_000L
    }
}
