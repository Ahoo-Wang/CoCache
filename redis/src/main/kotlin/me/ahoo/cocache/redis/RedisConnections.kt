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
import io.lettuce.core.RedisConnectionException
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
import me.ahoo.cocache.api.spi.L1RejectedException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 命令连接与订阅连接（单机或集群），统一脚本执行。
 *
 * 并发的异步命令在同一条共享连接上多路复用。存储与发布挂起等待（[awaitCommand]），不占线程；只有本身阻塞的路径（启动时的探测与
 * `INFO`、订阅、心跳）阻塞等待（[join]）。续接中不做任何阻塞工作。
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

    @Volatile
    private var closed = false

    /**
     * 异步执行脚本：EVALSHA，遇到 NOSCRIPT（故障切换、重启、resharding 后脚本缓存丢失）时改用 EVAL（会重新缓存）。
     *
     * 返回的 future 被取消（超时：[bounded]）时，尚未写出的命令不再写出（[CommandCall]）。
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
     *
     * 完成回调先于 `dispatch` 安装：I/O 线程可能在 `dispatch` 返回之前就以 NOSCRIPT 完成 `EVALSHA`，[onError] 随即发出并登记 `EVAL`，
     * 之后 `EVALSHA` 的 `dispatch` 才返回。所以每次发送按 [type] 登记在自己的位置（[CommandCall.track]），后返回的旧命令不顶替新命令。
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
        call.track(type, connection.dispatch(command))
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

    /**
     * 发布失效帧（挂起，[RedisInvalidationChannel] 的 `publish`），限时与失败同 [awaitCommand]。
     */
    suspend fun publish(channel: ByteArray, message: ByteArray) {
        awaitCommand(publishAsync(channel, message))
    }

    /**
     * `PUBLISH` 的 future（阻塞的路径经 [join] 等待，如创建存储时的频道探测）。
     */
    fun publishAsync(channel: ByteArray, message: ByteArray): CompletableFuture<Long> =
        base.publish(channel, message).toCompletableFuture()

    /**
     * `SPUBLISH`：集群中按频道的 slot 路由到所属主节点。挂起，同 [publish]。
     */
    suspend fun spublish(channel: ByteArray, message: ByteArray) {
        awaitCommand(spublishAsync(channel, message))
    }

    /**
     * `SPUBLISH` 的 future，同 [publishAsync]。
     */
    fun spublishAsync(channel: ByteArray, message: ByteArray): CompletableFuture<Long> =
        base.spublish(channel, message).toCompletableFuture()

    /**
     * 读取的时限（RFC 0005 round 3 D6）：`null` 为 `commandTimeout`；给出时必须为正，以 `commandTimeout` 为上限。
     */
    fun readTimeout(timeout: Duration?): Duration {
        if (timeout == null) {
            return commandTimeout
        }
        require(!timeout.isNegative && !timeout.isZero) { "timeout[$timeout] must be positive." }
        return if (timeout < commandTimeout) timeout else commandTimeout
    }

    /**
     * 限时的结果（RFC 0004 §4.3）：超过 [timeout]（默认 `commandTimeout`，至多为它）时以 [RedisException] 失败（不再等待应答），
     * 并取消 [future]：脚本（[evalAsync]）尚未写出的命令随之不再写出（[CommandCall]）。
     * 执行异常解包为原始的运行时异常（不是运行时异常的包装为 [RedisException]）。
     * 返回的是新的 future（调用方取消它只放弃等待，不影响命令），在 Lettuce 的 I/O 线程、事件执行器或时间轮线程上完成。
     *
     * 自建客户端的超时由 Lettuce 的命令超时触发，这里把它的 [RedisCommandTimeoutException] 映射为同样的异常；
     * [CommandTimeouts.guarded] 时，或 [timeout] 短于 `commandTimeout`（Lettuce 只按 `commandTimeout` 计时）时，
     * 另由 [timer] 计时，命令完成时撤销（RFC 0004 §14）。都不经 JDK 的单线程延迟调度器。
     */
    fun <T> bounded(future: CompletableFuture<T>, timeout: Duration = commandTimeout): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (!timeouts.guarded && timeout >= commandTimeout) {
            future.whenComplete { value, error ->
                if (error == null) result.complete(value) else result.completeExceptionally(failureOf(error))
            }
            return result
        }
        val expiry = try {
            timer.newTimeout({
                if (result.completeExceptionally(timedOut(timeout, TimeoutException()))) {
                    future.cancel(false)
                }
            }, timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: IllegalStateException) {
            // 时间轮已停止：客户端已关闭，命令本身会以连接关闭失败
            null
        }
        future.whenComplete { value, error ->
            expiry?.cancel()
            if (error == null) result.complete(value) else result.completeExceptionally(failureOf(error))
        }
        return result
    }

    /**
     * 挂起等待一条命令（RFC 0005 §4.3，[RedisL1Store]）：等待期间不占线程，续接经调用方的调度器恢复（标准库的 `suspendCoroutine`），
     * 不在 Lettuce 的 I/O 线程上继续。不响应协程的取消（引擎在 `NonCancellable` 中调用存储），一直等到命令结束或超时。
     *
     * 限时同 [bounded]：超过 [timeout] 时以 [RedisException] 失败并取消 [command]，尚未写出的脚本命令不再写出（[CommandCall]）。
     * 失败按 [l1Failure] 映射，从不以 [CancellationException] 抛出。
     */
    suspend fun <T> awaitCommand(command: CompletableFuture<T>, timeout: Duration = commandTimeout): T {
        val bounded = bounded(command, timeout)
        val outcome = if (bounded.isDone) {
            outcomeOf(bounded)
        } else {
            // 以总是成功的 Result 恢复、在自己的栈帧里抛出：经 kotlinx 的调度器以异常恢复时，调试模式会复制异常
            suspendCoroutine { continuation ->
                bounded.whenComplete { value, error ->
                    continuation.resume(if (error == null) Result.success(value) else Result.failure(l1Failure(error)))
                }
            }
        }
        return outcome.getOrThrow()
    }

    /**
     * 挂起等待全部 [commands]（失败的映射同 [awaitCommand]）：只挂起一次，全部结束后按顺序返回各自的结果。
     * 调用方先发出全部命令再调用它，共享连接上多路复用，约一次往返。
     *
     * 整批共享一个截止时间（[Batch]）：至多一个时间轮任务，而不是每条命令一个任务与一个派生的 future。到期时仍未结束的命令
     * 各自以超时失败并被取消，尚未写出的脚本命令不再写出（[CommandCall]），与 [bounded] 的单条命令相同。
     */
    suspend fun <T> awaitCommands(
        commands: List<CompletableFuture<T>>,
        timeout: Duration = commandTimeout
    ): List<Result<T>> {
        val batch = Batch(commands, timeout)
        if (!batch.done) {
            suspendCoroutine { continuation -> batch.onDone { continuation.resume(Unit) } }
        }
        return batch.outcomes()
    }

    /**
     * 阻塞等待（只用于本身阻塞的路径：启动时的探测与 `INFO`、订阅与退订、心跳），限时与失败的映射同 [awaitCommand]。
     * 不响应中断（RFC 0005 §4.3：中断不到达 L1 命令）：等待由 [bounded] 限时，至多一个 `commandTimeout`，被中断时保留中断标志。
     */
    fun <T> join(future: CompletableFuture<T>): T {
        val bounded = bounded(future)
        // 总是正常完成的派生 future：join 不响应中断，等到完成后恢复中断标志
        bounded.handle { _, _ -> }.join()
        return outcomeOf(bounded).getOrThrow()
    }

    /**
     * 阻塞等待全部 [futures]（同 [join]：不响应中断，被中断时保留中断标志），按顺序返回各自的结果，失败的映射同 [join]。
     * 各自限时、同时计时，所以一共至多一个 `commandTimeout`，而不是逐个等待时的 N 个（如 Sharded 模式心跳并发 PING 各节点）。
     */
    fun <T> joinAll(futures: List<CompletableFuture<T>>): List<Result<T>> {
        val batch = Batch(futures, commandTimeout)
        if (!batch.done) {
            // 总是正常完成：不响应中断，等到完成后恢复中断标志（同 join）
            val finished = CompletableFuture<Unit>()
            batch.onDone { finished.complete(Unit) }
            finished.join()
        }
        return batch.outcomes()
    }

    /**
     * 一批命令的结果，共享一个截止时间：每条命令结束时记下结果（失败按 [l1Failure] 映射），全部结束时撤销计时并通知等待方。
     * 计时同 [bounded]：只有 [CommandTimeouts.guarded]，或 [timeout] 短于 `commandTimeout`（Lettuce 只按 `commandTimeout` 计时）时
     * 才由时间轮计时；到期时先记下超时、再取消仍未结束的命令（取消引起的失败因此不顶替超时）。
     */
    private inner class Batch<T>(private val commands: List<CompletableFuture<T>>, private val timeout: Duration) {
        private val results = AtomicReferenceArray<Result<T>?>(commands.size)
        private val remaining = AtomicInteger(commands.size)

        @Volatile
        private var listener: (() -> Unit)? = null
        private val notified = java.util.concurrent.atomic.AtomicBoolean()
        private val expiry: io.netty.util.Timeout?

        val done: Boolean
            get() = remaining.get() == 0

        init {
            expiry = if (commands.isNotEmpty() && (timeouts.guarded || timeout < commandTimeout)) schedule() else null
            commands.forEachIndexed { index, command ->
                command.whenComplete { value, error ->
                    settle(index, if (error == null) Result.success(value) else Result.failure(l1Failure(error)))
                }
            }
        }

        private fun schedule(): io.netty.util.Timeout? = try {
            timer.newTimeout({ expire() }, timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: IllegalStateException) {
            // 时间轮已停止：客户端已关闭，命令本身会以连接关闭失败
            null
        }

        private fun expire() {
            val timedOut = Result.failure<T>(timedOut(timeout, TimeoutException()))
            commands.forEachIndexed { index, command ->
                if (settle(index, timedOut)) {
                    command.cancel(false)
                }
            }
        }

        /**
         * 记下第 [index] 条命令的结果；已记下时什么也不做，返回 `false`。
         */
        private fun settle(index: Int, result: Result<T>): Boolean {
            if (!results.compareAndSet(index, null, result)) {
                return false
            }
            if (remaining.decrementAndGet() == 0) {
                expiry?.cancel()
                notifyDone()
            }
            return true
        }

        /**
         * 全部结束时调用 [callback]（已结束时立即调用），至多一次。
         */
        fun onDone(callback: () -> Unit) {
            listener = callback
            if (done) {
                notifyDone()
            }
        }

        private fun notifyDone() {
            val callback = listener ?: return
            if (notified.compareAndSet(false, true)) {
                callback()
            }
        }

        fun outcomes(): List<Result<T>> = List(commands.size) { requireNotNull(results.get(it)) }
    }

    /**
     * 已完成的 future 的结果；失败按 [l1Failure] 映射（`join` 对以 [CancellationException] 完成的 future 直接抛出它，不包装）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> outcomeOf(future: CompletableFuture<T>): Result<T> = try {
        Result.success(future.join())
    } catch (e: Throwable) {
        Result.failure(l1Failure(e))
    }

    /**
     * L1 故障（RFC 0005 §4.3）：在 [failureOf] 之上，
     * - 把 Lettuce 在写出之前拒绝的命令（[isNotExecuted]）换成 SPI 的 [L1RejectedException]：确定未执行，引擎据此报告
     *   `CacheWriteException.outcomeUnknown = false`；
     * - 把 Lettuce 取消的命令（连接重置时取消应答栈中的命令、关闭时取消缓冲的命令）换成结果未知的 [RedisException]。
     *   在 JVM 上 [CancellationException] 就是协程的取消，原样抛出会被引擎当作调用方被取消。
     */
    private fun l1Failure(error: Throwable): RuntimeException {
        val failure = failureOf(error)
        if (failure is CancellationException) {
            return RedisException(
                "Command cancelled by the Redis client (connection reset or closed); its outcome is unknown.",
                failure
            )
        }
        if (failure !is L1RejectedException && isNotExecuted(failure)) {
            return L1RejectedException(failure.message ?: failure.javaClass.name, failure)
        }
        return failure
    }

    private fun failureOf(error: Throwable): RuntimeException {
        val cause = if (error is CompletionException || error is ExecutionException) error.cause ?: error else error
        if (timeouts.clientTimed && cause is RedisCommandTimeoutException) {
            return timedOut(commandTimeout, cause)
        }
        return cause as? RuntimeException ?: RedisException(cause)
    }

    /**
     * 各种计时方式共用的超时异常。
     */
    private fun timedOut(timeout: Duration, cause: Throwable): RedisException =
        RedisException("Command timed out after $timeout.", cause)

    /**
     * 各主节点的 `maxmemory-policy`（`INFO memory` 的 `maxmemory_policy` 字段），键为 `host:port`。
     * 托管 Redis 通常禁用或改名 `CONFIG`，但允许 `INFO`。`INFO` 被禁用、改名或无权限时抛出。
     */
    fun maxmemoryPolicies(): Map<String, String> {
        val connection = connection
        if (connection is StatefulRedisClusterConnection<ByteArray, ByteArray>) {
            return connection.partitions
                .filter { it.`is`(RedisClusterNode.NodeFlag.UPSTREAM) }
                .associate { node ->
                    val commands = connection.getConnection(node.nodeId).async()
                    val info = join(commands.info(MEMORY_SECTION).toCompletableFuture())
                    "${node.uri.host}:${node.uri.port}" to maxmemoryPolicyOf(info)
                }
        }
        val standalone = connection as StatefulRedisConnection<ByteArray, ByteArray>
        val info = join(standalone.async().info(MEMORY_SECTION).toCompletableFuture())
        return mapOf(STANDALONE to maxmemoryPolicyOf(info))
    }

    /**
     * 按 slot 分组（集群）；单机为一组。
     */
    fun groupBySlot(keys: List<ByteArray>): Collection<List<ByteArray>> {
        if (!cluster) {
            return listOf(keys)
        }
        return keys.groupBy { SlotHash.getSlot(it) }.values
    }

    /**
     * 在 [delay] 之后于客户端资源的时间轮线程上执行 [task]（[RedisL1Store] 重发失败的释放）。连接已关闭或时间轮已停止时不安排，
     * 返回 `false`；安排之后连接才关闭的，到期时不再执行。
     */
    fun schedule(delay: Duration, task: () -> Unit): Boolean {
        if (closed) {
            return false
        }
        return try {
            timer.newTimeout({
                if (!closed) {
                    task()
                }
            }, delay.toNanos(), TimeUnit.NANOSECONDS)
            true
        } catch (_: IllegalStateException) {
            // 时间轮已停止：客户端资源已关闭
            false
        }
    }

    override fun close() {
        closed = true
        pubSub.close()
        connection.close()
    }

    companion object {
        private const val MEMORY_SECTION = "memory"
        private const val MAXMEMORY_POLICY_FIELD = "maxmemory_policy:"
        private const val STANDALONE = "redis"

        /**
         * 从 `INFO memory` 的回复（`field:value` 行，以 CRLF 分隔）中取出 `maxmemory_policy`；没有这个字段时抛出。
         */
        internal fun maxmemoryPolicyOf(info: String): String = info.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(MAXMEMORY_POLICY_FIELD) }
            ?.substring(MAXMEMORY_POLICY_FIELD.length)
            ?: throw RedisException("INFO $MEMORY_SECTION returned no ${MAXMEMORY_POLICY_FIELD.dropLast(1)}.")

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
         * Lettuce 在写出之前拒绝命令时的消息（`DefaultEndpoint.validateWrite`）：连接已关闭、请求队列或命令缓冲已满、断线时拒绝命令。
         * 注意与断线时排空应答栈的 `Connection closed` / `Connection disconnected` 区分：那些命令可能已经写出。
         */
        private val NOT_WRITTEN = listOf(
            "Connection is closed",
            "Request queue size exceeded",
            "Command buffer size exceeded",
            "Currently not connected",
        )

        /**
         * 确定未执行（RFC 0005 §4.3 的 P8）：Lettuce 在写出之前就拒绝了命令（[NOT_WRITTEN]），或连接建立不起来
         * （[RedisConnectionException]，如集群中节点连接失败）；等待命令的方法把它们报告为 [L1RejectedException]（[l1Failure]）。
         * 其余失败都是结果未知：超时、断线时被排空、被 Lettuce 取消、脚本的错误回复等，命令可能已在 Redis 端执行。
         * [RedisL1Store] 据此决定是否释放令牌。
         */
        fun isNotExecuted(error: Throwable): Boolean {
            if (error is L1RejectedException || error is RedisConnectionException) {
                return true
            }
            val message = error.message ?: return false
            return error is RedisException && NOT_WRITTEN.any { message.startsWith(it) }
        }
    }
}

/**
 * 一条脚本调用的结果：先是 `EVALSHA`，NOSCRIPT 之后是 `EVAL`（[track] 记录这两条 Lettuce 命令）。
 *
 * 超时时 [RedisConnections.bounded] 调用 [cancel]，它同时以异常
 * 完成已发出的 Lettuce 命令。Lettuce 写出前跳过已完成的命令（`CommandHandler` 的 `isWriteable`；断线期间缓冲的命令重连后同样跳过），
 * 所以尚未写出的命令不会在调用方看到失败之后才执行（稍后生效的 `put` 覆盖更新的值，`SetStore` 永不过期时错误永久保留）：
 * 集群中等待建立节点连接（首次访问某节点、拓扑变化后）的命令 Lettuce 不计时，调用方的客户端（`RedisBackend.of`）断线时可能缓冲命令。
 * 已写出的命令无从撤回：Redis 照常执行，应答被丢弃。
 *
 * 完成的必须是 `dispatch` 返回的最外层命令：集群中它是 `ClusterCommand`，`isDone` 只看它自己的完成标记（Lettuce 6.8 与 7.5 相同），
 * 取消内层的 `AsyncCommand` 时它仍会在节点连接建立后写出。
 *
 * 两条命令各占一个位置，而不是只记“当前的”一条（#808）：NOSCRIPT 可能在 `EVALSHA` 的 `dispatch` 返回之前就到达，回退的 `EVAL`
 * 先登记，`EVALSHA` 的 `dispatch` 后返回；只记一条时旧命令顶替新命令，取消只完成已结束的 `EVALSHA`，仍在排队的 `EVAL` 照常写出。
 * 取消时两条都完成：`EVAL` 只在 `EVALSHA` 结束之后才发出，完成已结束的命令什么也不做。
 */
private class CommandCall<T> : CompletableFuture<T>() {
    @Volatile
    private var evalsha: RedisCommand<*, *, *>? = null

    @Volatile
    private var eval: RedisCommand<*, *, *>? = null

    /**
     * 按 [type] 记录 `dispatch` 返回的命令；调用已被取消时立即完成它（与 [cancel] 并发时，两边至少有一方看到对方的写入）。
     */
    fun track(type: CommandType, command: RedisCommand<*, *, *>) {
        if (type == CommandType.EVAL) {
            eval = command
        } else {
            evalsha = command
        }
        if (isCancelled) {
            abort(command)
        }
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cancelled = super.cancel(mayInterruptIfRunning)
        // 只在取消（超时）时分配；尚未登记的位置跳过
        listOfNotNull(evalsha, eval).forEach(::abort)
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
