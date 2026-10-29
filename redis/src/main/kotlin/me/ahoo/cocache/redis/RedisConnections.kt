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

import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisException
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.BaseRedisAsyncCommands
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.output.CommandOutput
import io.lettuce.core.output.NestedMultiOutput
import io.lettuce.core.protocol.AsyncCommand
import io.lettuce.core.protocol.Command
import io.lettuce.core.protocol.CommandArgs
import io.lettuce.core.protocol.CommandType
import io.lettuce.core.protocol.RedisCommand
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.netty.util.Timer
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 命令连接与订阅连接（单机或集群），统一脚本执行。
 *
 * 并发的异步命令在同一条共享连接上多路复用；调用方线程同步等待结果，续接中不做任何阻塞工作。
 *
 * @param timeouts 异步命令由谁计时（[CommandTimeouts]）
 */
internal class RedisConnections(
    client: AbstractRedisClient,
    private val commandTimeout: Duration,
    private val timeouts: CommandTimeouts = CommandTimeouts.TIMER,
) : AutoCloseable {
    val cluster: Boolean = client is RedisClusterClient
    private val connection: StatefulConnection<ByteArray, ByteArray>
    private val base: BaseRedisAsyncCommands<ByteArray, ByteArray>
    val pubSub: StatefulRedisPubSubConnection<ByteArray, ByteArray>

    init {
        when (client) {
            is RedisClusterClient -> {
                val commands = client.connect(ByteArrayCodec.INSTANCE)
                connection = commands
                base = commands.async()
                pubSub = connectPubSub(commands) { client.connectPubSub(ByteArrayCodec.INSTANCE) }
            }

            is RedisClient -> {
                val commands = client.connect(ByteArrayCodec.INSTANCE)
                connection = commands
                base = commands.async()
                pubSub = connectPubSub(commands) { client.connectPubSub(ByteArrayCodec.INSTANCE) }
            }

            else -> throw IllegalArgumentException("Unsupported Redis client: ${client.javaClass.name}.")
        }
    }

    /**
     * 客户端资源的时间轮（Netty `HashedWheelTimer`，Lettuce 的命令超时也用它）：[CommandTimeouts.guarded] 时为异步命令计时（在连接建立之后取得）。
     */
    private val timer: Timer = client.resources.timer()

    /**
     * 异步执行脚本：EVALSHA，遇到 NOSCRIPT（故障切换、重启、resharding 后脚本缓存丢失）时改用 EVAL（会重新缓存）。
     *
     * 返回的 future 被取消（超时：[bounded]、[await]；等待被中断：[await]）时，尚未写出的命令不再写出（[CommandCall]）。
     */
    fun <T> evalAsync(script: RedisScripts.Script, keys: List<ByteArray>, args: List<ByteArray>): CompletableFuture<T> {
        val call = CommandCall<T>()
        send(call, CommandType.EVALSHA, scriptArgs(keys, args) { add(script.sha) }) { error ->
            if (isNoScript(error)) {
                send(
                    call,
                    CommandType.EVAL,
                    scriptArgs(keys, args) { add(script.source) }
                ) { call.completeExceptionally(it) }
            } else {
                call.completeExceptionally(error)
            }
        }
        return call
    }

    /**
     * 以 `dispatch` 发出脚本命令（与 Lettuce 的 `evalsha` / `eval` 相同的参数与输出），它返回最外层的命令，[CommandCall] 被取消时
     * 完成的是这一条。结果交给 [call]，失败（已解包）交给 [onError]；[onError] 抛出时以该异常完成 [call]，不让调用方永远等待。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> send(
        call: CommandCall<T>,
        type: CommandType,
        args: CommandArgs<ByteArray, ByteArray>,
        onError: (Throwable) -> Unit
    ) {
        @Suppress("UNCHECKED_CAST")
        val output = NestedMultiOutput(ByteArrayCodec.INSTANCE) as CommandOutput<ByteArray, ByteArray, T>
        val command = AsyncCommand(Command(type, output, args))
        command.whenComplete { value, error ->
            if (error == null) {
                call.complete(value)
                return@whenComplete
            }
            try {
                onError(if (error is CompletionException) error.cause ?: error else error)
            } catch (e: Throwable) {
                call.completeExceptionally(e)
            }
        }
        call.track(connection.dispatch(command))
    }

    private fun scriptArgs(
        keys: List<ByteArray>,
        args: List<ByteArray>,
        script: CommandArgs<ByteArray, ByteArray>.() -> Unit
    ): CommandArgs<ByteArray, ByteArray> = CommandArgs(ByteArrayCodec.INSTANCE).apply {
        script()
        add(keys.size.toLong())
        addKeys(keys)
        addValues(args)
    }

    fun <T> eval(script: RedisScripts.Script, keys: List<ByteArray>, args: List<ByteArray>): T = await(
        evalAsync(script, keys, args)
    )

    fun publish(channel: ByteArray, message: ByteArray) {
        await(base.publish(channel, message).toCompletableFuture())
    }

    fun publishAsync(channel: ByteArray, message: ByteArray): CompletableFuture<Void?> =
        mapped(bounded(base.publish(channel, message).toCompletableFuture())) { null }

    /**
     * `SPUBLISH`：集群中按频道的 slot 路由到所属主节点。
     */
    fun spublish(channel: ByteArray, message: ByteArray) {
        await(base.spublish(channel, message).toCompletableFuture())
    }

    fun spublishAsync(channel: ByteArray, message: ByteArray): CompletableFuture<Void?> =
        mapped(bounded(base.spublish(channel, message).toCompletableFuture())) { null }

    /**
     * [await] 的异步版本（RFC 0004 §4.3）：超时与异常类型与同步等待一致。超过 `commandTimeout` 时以 [RedisException]
     * 失败（不再等待应答），并取消 [future]：脚本（[evalAsync]）尚未写出的命令随之不再写出（[CommandCall]）。
     * 执行异常解包为原始的运行时异常（不是运行时异常的包装为 [RedisException]）。
     * 返回的是新的 future（调用方取消它只放弃等待，不影响命令），在 Lettuce 的 I/O 线程、事件执行器或时间轮线程上完成：引擎会切换线程。
     *
     * 自建客户端的超时由 Lettuce 的命令超时触发，这里把它的 [RedisCommandTimeoutException] 映射为同样的异常；
     * [CommandTimeouts.guarded] 时另由 [timer] 计时，命令完成时撤销（RFC 0004 §14）。都不经 JDK 的单线程延迟调度器。
     */
    fun <T> bounded(future: CompletableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (!timeouts.guarded) {
            future.whenComplete { value, error ->
                if (error == null) result.complete(value) else result.completeExceptionally(failureOf(error))
            }
            return result
        }
        val timeout = try {
            timer.newTimeout({
                if (result.completeExceptionally(timedOut(TimeoutException()))) {
                    future.cancel(false)
                }
            }, commandTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: IllegalStateException) {
            // 时间轮已停止：客户端已关闭，命令本身会以连接关闭失败
            null
        }
        future.whenComplete { value, error ->
            timeout?.cancel()
            if (error == null) result.complete(value) else result.completeExceptionally(failureOf(error))
        }
        return result
    }

    /**
     * 以 [transform] 的结果完成的新 future；失败（含 [transform] 抛出）以原始异常完成，不像 `thenApply` 那样包装为 [CompletionException]。
     */
    @Suppress("TooGenericExceptionCaught")
    fun <T, R> mapped(future: CompletableFuture<T>, transform: (T) -> R): CompletableFuture<R> {
        val result = CompletableFuture<R>()
        future.whenComplete { value, error ->
            if (error != null) {
                result.completeExceptionally(if (error is CompletionException) error.cause ?: error else error)
            } else {
                try {
                    result.complete(transform(value))
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                }
            }
        }
        return result
    }

    private fun failureOf(error: Throwable): RuntimeException {
        val cause = if (error is CompletionException || error is ExecutionException) error.cause ?: error else error
        if (timeouts.clientTimed && cause is RedisCommandTimeoutException) {
            return timedOut(cause)
        }
        return cause as? RuntimeException ?: RedisException(cause)
    }

    /**
     * 同步与异步路径、各种计时方式共用的超时异常。
     */
    private fun timedOut(cause: Throwable): RedisException =
        RedisException("Command timed out after $commandTimeout.", cause)

    /**
     * 同步等待：超时、等待被中断、执行异常都转换为运行时异常抛出。
     *
     * 等待本身受 `commandTimeout` 限制（与客户端是否计时无关）。超时或等待被中断时结果未知（[isUnknownOutcome]），两者处理相同：
     * 取消 [future]（同 [bounded]），尚未写出的脚本命令不再写出（[CommandCall]）；被中断时恢复中断标志。中断来自调用方
     * （`Future.cancel(true)`、Resilience4j `TimeLimiter`、`shutdownNow`），也来自同一线程上之前被中断的等待：标志恢复之后，
     * 其后尚未完成的等待都立即以中断失败。[CommandTimeouts.clientTimed] 时客户端的超时可能先于等待触发，映射为同样的异常。
     */
    fun <T> await(future: CompletableFuture<T>): T {
        val failure: RuntimeException = try {
            return future.get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            failureOf(e)
        } catch (e: TimeoutException) {
            future.cancel(false)
            timedOut(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(false)
            RedisException("Interrupted while waiting for Redis.", e)
        }
        throw failure
    }

    /**
     * 各主节点的 `maxmemory-policy`（`CONFIG GET`），键为 `host:port`。`CONFIG` 被禁用、改名或无权限时抛出。
     */
    fun maxmemoryPolicies(): Map<String, String> {
        val connection = connection
        if (connection is StatefulRedisClusterConnection<ByteArray, ByteArray>) {
            return connection.partitions
                .filter { it.`is`(RedisClusterNode.NodeFlag.UPSTREAM) }
                .associate { node ->
                    val config = await(
                        connection.getConnection(node.nodeId).async().configGet(MAXMEMORY_POLICY).toCompletableFuture()
                    )
                    "${node.uri.host}:${node.uri.port}" to policyOf(config)
                }
        }
        val standalone = connection as StatefulRedisConnection<ByteArray, ByteArray>
        val config = await(standalone.async().configGet(MAXMEMORY_POLICY).toCompletableFuture())
        return mapOf(STANDALONE to policyOf(config))
    }

    private fun policyOf(config: Map<String, String>): String =
        requireNotNull(config[MAXMEMORY_POLICY]) { "CONFIG GET $MAXMEMORY_POLICY returned no value." }

    /**
     * 按 slot 分组（集群）；单机为一组。
     */
    fun groupBySlot(keys: List<ByteArray>): Collection<List<ByteArray>> {
        if (!cluster) {
            return listOf(keys)
        }
        return keys.groupBy { SlotHash.getSlot(it) }.values
    }

    override fun close() {
        pubSub.close()
        connection.close()
    }

    companion object {
        private const val MAXMEMORY_POLICY = "maxmemory-policy"
        private const val STANDALONE = "redis"

        /**
         * 建立订阅连接；失败时关闭已建立的命令连接（避免泄漏）再抛出。
         */
        @Suppress("TooGenericExceptionCaught")
        private inline fun <C : StatefulRedisPubSubConnection<ByteArray, ByteArray>> connectPubSub(
            commands: StatefulConnection<ByteArray, ByteArray>,
            connect: () -> C
        ): C {
            try {
                return connect()
            } catch (e: RuntimeException) {
                commands.close()
                throw e
            }
        }

        private fun isNoScript(error: Throwable): Boolean {
            return error is RedisNoScriptException || error.message?.startsWith("NOSCRIPT") == true
        }

        /**
         * 结果未知：命令超时（后端计时的 [timedOut]，或客户端的 [RedisCommandTimeoutException]），或等待被中断（[await]）。
         * 命令可能已在 Redis 端执行，也可能被取消、永远不会执行。
         */
        fun isUnknownOutcome(error: Throwable): Boolean {
            val causes = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            return causes.any {
                it is RedisCommandTimeoutException || it is TimeoutException || it is InterruptedException
            }
        }
    }
}

/**
 * 一条脚本调用的结果：先是 `EVALSHA`，NOSCRIPT 之后是 `EVAL`（[track] 记录当前的那条 Lettuce 命令）。
 *
 * 超时时 [RedisConnections.bounded] 与 [RedisConnections.await]、等待被中断时 [RedisConnections.await] 调用 [cancel]，它同时以异常
 * 完成当前的 Lettuce 命令。Lettuce 写出前跳过已完成的命令（`CommandHandler` 的 `isWriteable`；断线期间缓冲的命令重连后同样跳过），
 * 所以尚未写出的命令不会在调用方看到失败之后才执行（稍后生效的 `put` 覆盖更新的值，`SetCache` 永不过期时错误永久保留）：
 * 集群中等待建立节点连接（首次访问某节点、拓扑变化后）的命令 Lettuce 不计时，调用方的客户端（`RedisBackend.of`）断线时可能缓冲命令。
 * 已写出的命令无从撤回：Redis 照常执行，应答被丢弃。
 *
 * 完成的必须是 `dispatch` 返回的最外层命令：集群中它是 `ClusterCommand`，`isDone` 只看它自己的完成标记（Lettuce 6.8 与 7.5 相同），
 * 取消内层的 `AsyncCommand` 时它仍会在节点连接建立后写出。
 */
private class CommandCall<T> : CompletableFuture<T>() {
    @Volatile
    private var command: RedisCommand<*, *, *>? = null

    /**
     * 记录当前的命令；调用已被取消时立即完成它（与 [cancel] 并发时，两边至少有一方看到对方的写入）。
     */
    fun track(command: RedisCommand<*, *, *>) {
        this.command = command
        if (isCancelled) {
            abort(command)
        }
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cancelled = super.cancel(mayInterruptIfRunning)
        command?.let(::abort)
        return cancelled
    }

    private fun abort(command: RedisCommand<*, *, *>) {
        command.completeExceptionally(CancellationException("Abandoned: the caller stopped waiting."))
    }
}

/**
 * 异步命令由谁计时（RFC 0004 §14）。
 *
 * @property clientTimed 客户端按 `commandTimeout` 为命令计时（[RedisBackend.timeoutOptions]），它的超时映射为与同步等待相同的异常
 * @property guarded 后端另以客户端资源的时间轮计时（客户端的计时不覆盖全程，或未知）
 */
internal enum class CommandTimeouts(val clientTimed: Boolean, val guarded: Boolean) {
    /**
     * 自建的单机客户端：命令从发出到完成都由 Lettuce 计时，异步结果只做异常映射。
     */
    CLIENT(clientTimed = true, guarded = false),

    /**
     * 自建的集群客户端：Lettuce 只为已写入节点连接的命令计时，等待建立节点连接（首次访问某节点、拓扑变化后）的命令不计时，
     * 由时间轮兜底。
     */
    CLIENT_WRITTEN(clientTimed = true, guarded = true),

    /**
     * 调用方的客户端：选项未知，由时间轮计时，客户端自己的异常原样传递。
     */
    TIMER(clientTimed = false, guarded = true),
}
