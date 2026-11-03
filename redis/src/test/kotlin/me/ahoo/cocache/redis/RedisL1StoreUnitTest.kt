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

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisException
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.protocol.RedisCommand
import io.lettuce.core.resource.ClientResources
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.util.Timeout
import io.netty.util.Timer
import io.netty.util.TimerTask
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.test.Concurrency.awaitCondition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * [RedisL1Store] 在 Lettuce 命令的各种完成方式下的行为：模拟的连接记录 `dispatch` 收到的命令，用例逐条完成它们。
 * Lettuce 取消的命令按 L1 故障报告、确定未执行之外的失败释放令牌（失败的释放按退避重发）、同一个异常完成多条命令、
 * 批量等已发出的命令全部结束再抛出（含发出时同步失败）、解码失败。
 */
class RedisL1StoreUnitTest {
    private val node = NodeId.random()
    private val sequence = AtomicLong()
    private val dispatched = CopyOnWriteArrayList<RedisCommand<ByteArray, ByteArray, *>>()
    private val caller = CallerDispatcher()

    /**
     * 返回非空时，`dispatch` 同步抛出它而不发出命令（如客户端资源已关闭时 Lettuce 的命令计时被拒绝）。
     */
    @Volatile
    private var refuse: (RedisCommand<*, *, *>) -> RuntimeException? = { null }

    /**
     * 客户端资源的时间轮：用例逐个执行安排的任务（重发释放）。
     */
    private val timer = ManualTimer()
    private val connections: RedisConnections

    init {
        val commands = mockk<StatefulRedisConnection<ByteArray, ByteArray>>(relaxed = true)
        every { commands.dispatch(any<RedisCommand<ByteArray, ByteArray, Any>>()) } answers {
            val command = firstArg<RedisCommand<ByteArray, ByteArray, Any>>()
            refuse(command)?.let { throw it }
            command.also { dispatched.add(it) }
        }
        val client = mockk<RedisClient>()
        every { client.connect(ByteArrayCodec.INSTANCE) } returns commands
        every { client.connectPubSub(ByteArrayCodec.INSTANCE) } returns mockk(relaxed = true)
        val resources = mockk<ClientResources>(relaxed = true)
        every { resources.timer() } returns timer
        every { client.resources } returns resources
        // 由客户端计时：后端不另外计时，用例决定每条命令何时、如何完成
        connections = RedisConnections(client, Duration.ofSeconds(1), CommandTimeouts.CLIENT)
    }

    @AfterEach
    fun destroy() {
        caller.close()
        connections.close()
    }

    private fun store(invalidateBatchSize: Int = 512) = RedisL1Store(connections, CHANNEL, invalidateBatchSize)

    private fun token() = Token(node, sequence.incrementAndGet())

    private fun lease() = LeaseRequest(token(), Duration.ofMinutes(1))

    private fun frame(vararg keys: String) = InvalidationFrame.encode(node, keys.toList())

    private val RedisCommand<*, *, *>.script: String
        get() = SCRIPTS.getValue(requireNotNull(args.firstString))

    private val RedisCommand<*, *, *>.key: String
        get() = StandardCharsets.UTF_8.decode(requireNotNull(args.firstEncodedKey)).toString()

    @Suppress("UNCHECKED_CAST")
    private fun RedisCommand<ByteArray, ByteArray, *>.reply(value: Any?) {
        (this as CompletableFuture<Any?>).complete(value)
    }

    /**
     * 命令参数按 RESP 编码后的字节：帧等二进制参数在其中连续出现。
     */
    private fun RedisCommand<*, *, *>.carries(bytes: ByteArray): Boolean {
        val buffer = Unpooled.buffer()
        try {
            args.encode(buffer)
            val encoded = ByteBufUtil.getBytes(buffer)
            return (0..encoded.size - bytes.size).any { offset -> bytes.indices.all { encoded[offset + it] == bytes[it] } }
        } finally {
            buffer.release()
        }
    }

    private fun awaitDispatched(count: Int) = awaitCondition("$count commands dispatched") { dispatched.size >= count }

    /**
     * 发出 [count] 条命令之后（调用已挂起），返回这些命令。
     */
    private fun <T> started(count: Int, block: suspend () -> T): CallerDispatcher.Pending<T> =
        caller.start(block).also { awaitDispatched(count) }

    /**
     * 释放（RELEASE 脚本）发往的 key。
     */
    private fun released(): List<String> = dispatched.filter { it.script == "RELEASE" }.map { it.key }

    private fun lastRelease(): RedisCommand<ByteArray, ByteArray, *> = dispatched.last { it.script == "RELEASE" }

    /**
     * 以 [lease] 读取 `k`，读取以结果未知的失败结束：存储随即发出第一次释放。
     */
    private fun failedReadWithLease(store: RedisL1Store, lease: LeaseRequest) {
        dispatched.clear()
        val call = started(1) { store.read("k", lease, null, null) }
        dispatched.single().completeExceptionally(RedisException("Connection disconnected"))
        call.failure().assert().isInstanceOf(RedisException::class.java)
        released().assert().containsExactly("k")
    }

    /**
     * Lettuce 取消的命令（连接重置时取消应答栈中的命令）：以结果未知的 [RedisException] 失败，cause 是原来的取消；
     * 取得令牌的读取释放它请求的 lease 与刷新令牌。
     */
    @Test
    fun commandCancelledByLettuceFailsAsAnL1FaultAndReleasesItsTokens() {
        val store = store()
        val lease = lease()
        val call =
            started(
                1
            ) { store.read("k", lease, RefreshRequest(Duration.ofMinutes(5), token(), Duration.ofMinutes(1)), null) }
        dispatched.single().cancel()
        val failure = call.failure()
        failure.assert().isInstanceOf(RedisException::class.java)
        failure.assert().isNotInstanceOf(CancellationException::class.java)
        failure?.cause.assert().isInstanceOf(CancellationException::class.java)
        RedisConnections.isNotExecuted(requireNotNull(failure)).assert().isFalse()
        released().assert().containsExactly("k", "k")
    }

    /**
     * 已经完成的命令（如 `dispatch` 期间就被取消）不挂起，同样映射。
     */
    @Test
    fun alreadyCancelledCommandFailsWithoutSuspending() {
        val command = CompletableFuture<List<Any?>>().apply { cancel(false) }
        val failure = runCatching { caller.run { connections.awaitCommand(command) } }.exceptionOrNull()
        failure.assert().isInstanceOf(RedisException::class.java)
        failure?.cause.assert().isInstanceOf(CancellationException::class.java)
        // 已完成的批量同样不挂起
        val outcomes = caller.run {
            connections.awaitCommands(
                listOf(command, CompletableFuture.completedFuture(listOf(1L)))
            )
        }
        outcomes[0].exceptionOrNull()?.cause.assert().isInstanceOf(CancellationException::class.java)
        outcomes[1].getOrNull().assert().isEqualTo(listOf(1L))
    }

    /**
     * 确定未执行（Lettuce 在写出之前拒绝、连接建立不起来）：不释放令牌，释放同样会被拒绝；其余失败都是结果未知，释放令牌。
     */
    @Test
    fun onlyFailuresWithAnUnknownOutcomeReleaseTokens() {
        val notExecuted = listOf(
            RedisException("Currently not connected. Commands are rejected."),
            RedisException("Connection is closed"),
            RedisException("Request queue size exceeded: 10. Commands are not accepted until the queue size drops."),
            RedisException("Command buffer size exceeded: 10. Commands are not accepted until the queue size drops."),
            RedisConnectionException("Unable to connect to localhost:6379"),
        )
        val unknown = listOf(
            RedisException("Connection disconnected"),
            RedisException("Connection closed"),
            RedisException("Command timed out after PT1S.", TimeoutException()),
            RedisCommandExecutionException("ERR Error running script"),
            IllegalStateException("Unknown read result code[9]."),
            RedisException(null as String?),
        )
        notExecuted.forEach { RedisConnections.isNotExecuted(it).assert().withFailMessage { "$it" }.isTrue() }
        unknown.forEach { RedisConnections.isNotExecuted(it).assert().withFailMessage { "$it" }.isFalse() }
        // 消息相同、但不是 Lettuce 的 RedisException：不能确定未执行
        RedisConnections.isNotExecuted(IllegalStateException("Connection is closed")).assert().isFalse()

        val store = store()
        (notExecuted + unknown).forEachIndexed { index, error ->
            dispatched.clear()
            val key = "k$index"
            val call = started(1) { store.fill(Fill(key, token(), Envelope.Absent, null)) }
            dispatched.single().completeExceptionally(error)
            call.failure().assert().isSameAs(error)
            if (error in notExecuted) released().assert().isEmpty() else released().assert().containsExactly(key)
        }
    }

    /**
     * 不取得令牌的读取失败时没有可释放的令牌；写入失败不释放任何令牌。
     */
    @Test
    fun failuresWithoutTokensReleaseNothing() {
        val store = store()
        val error = RedisException("Connection disconnected")
        val read = started(1) { store.read("k", null, null, null) }
        dispatched.single().completeExceptionally(error)
        read.failure().assert().isSameAs(error)
        dispatched.clear()
        val put = started(1) { store.put("k", Envelope.Absent, null, frame("k")) }
        dispatched.single().completeExceptionally(error)
        put.failure().assert().isSameAs(error)
        released().assert().isEmpty()
    }

    /**
     * 释放发出时同步失败：只记录，调用方仍得到原来的失败，批量读取照常处理其余的 key。
     */
    @Test
    fun releaseThatCannotBeSentKeepsTheOriginalFailure() {
        val store = store()
        val terminated = RejectedExecutionException("event executor terminated")
        refuse = { command -> terminated.takeIf { command.script == "RELEASE" } }
        val call = started(2) { store.readAll(listOf("a", "b"), mapOf("a" to lease(), "b" to lease()), null) }
        val first = RedisException("Connection disconnected")
        dispatched[0].completeExceptionally(first)
        dispatched[1].reply(listOf(0L, 1L))
        call.failure().assert().isSameAs(first)
        dispatched.size.assert().isEqualTo(2)
    }

    /**
     * 释放失败（这里是断线期间被拒绝）时在时间轮上按退避重发，直到执行（RFC 0005 round 3 K14）：重连之后才到达的释放收回 lease，
     * 不必等到 `lease.ttl`。退避从 100 ms 起倍增，至多 2 s；执行之后不再重发。
     */
    @Test
    fun failedReleaseIsResentWithBackoffUntilItRuns() {
        val store = store()
        failedReadWithLease(store, lease())
        val rejected = RedisException("Currently not connected. Commands are rejected.")
        val delays = List(7) {
            lastRelease().completeExceptionally(rejected)
            timer.runNext()
        }
        delays.assert().containsExactly(100L, 200L, 400L, 800L, 1600L, 2000L, 2000L)
        released().size.assert().isEqualTo(8)
        lastRelease().reply(listOf(1L))
        timer.pending.assert().isEmpty()
    }

    /**
     * 过了令牌的寿命不再重发：令牌已失效，释放只是空操作。
     */
    @Test
    fun releaseIsNotResentAfterTheTokenExpires() {
        val store = store()
        failedReadWithLease(store, LeaseRequest(token(), Duration.ofMillis(1)))
        lastRelease().completeExceptionally(RedisException("Currently not connected. Commands are rejected."))
        timer.pending.assert().isEmpty()
    }

    /**
     * 连接关闭或时间轮停止之后不再重发；已安排的重发在连接关闭之后到期时什么也不做。
     */
    @Test
    fun releaseIsNotResentOnceTheConnectionsAreClosed() {
        val store = store()
        val rejected = RedisException("Currently not connected. Commands are rejected.")
        failedReadWithLease(store, lease())
        lastRelease().completeExceptionally(rejected)
        timer.pending.size.assert().isEqualTo(1)
        connections.close()
        timer.runNext()
        released().size.assert().isEqualTo(1)
        // 关闭之后失败的释放不再安排
        failedReadWithLease(store, lease())
        lastRelease().completeExceptionally(rejected)
        timer.pending.assert().isEmpty()
    }

    @Test
    fun releaseIsNotResentOnceTheTimerIsStopped() {
        val store = store()
        failedReadWithLease(store, lease())
        timer.stop()
        lastRelease().completeExceptionally(RedisException("Currently not connected. Commands are rejected."))
        timer.pending.assert().isEmpty()
    }

    /**
     * 批量命令发出时同步失败（确定未执行）：不再发出其后的命令，但等已发出的结束、照常处理它们（释放其余读取取得的 lease），
     * 再抛出这个异常；发出失败的命令不释放。
     */
    @Test
    fun readAllThatFailsToSendWaitsForTheSentReads() {
        val store = store()
        val refused = RejectedExecutionException("event executor terminated")
        refuse = { command -> refused.takeIf { command.script == "READ" && command.key == "b" } }
        val leases = mapOf("a" to lease(), "b" to lease(), "c" to lease())
        val call = started(1) { store.readAll(listOf("a", "b", "c"), leases, null) }
        dispatched.single().key.assert().isEqualTo("a")
        call.isDone.assert().isFalse()
        dispatched.single().reply(listOf(0L, 1L))
        call.failure().assert().isSameAs(refused)
        released().assert().containsExactly("a")
    }

    @Test
    fun fillAllThatFailsToSendWaitsForTheSentFills() {
        val store = store()
        val refused = RejectedExecutionException("event executor terminated")
        refuse = { command -> refused.takeIf { command.script == "FILL" && command.key == "b" } }
        val call = started(1) { store.fillAll(listOf("a", "b").map { Fill(it, token(), Envelope.Absent, null) }) }
        call.isDone.assert().isFalse()
        dispatched.single().reply(listOf(1L))
        call.failure().assert().isSameAs(refused)
        released().assert().isEmpty()
    }

    /**
     * 写入的某一组发出时同步失败：等已发出的组结束再抛出（写入方法返回或抛出时，它发出的全部命令都已结束）。
     */
    @Test
    fun writeThatFailsToSendAGroupWaitsForTheSentGroups() {
        val store = store(invalidateBatchSize = 1)
        val refused = RejectedExecutionException("event executor terminated")
        refuse = { command -> refused.takeIf { command.key == "b" } }
        val call = started(1) { store.invalidate(listOf("a", "b"), frame("a", "b")) }
        call.isDone.assert().isFalse()
        dispatched.single().reply(listOf(1L))
        call.failure().assert().isSameAs(refused)
    }

    /**
     * 读取的时限（RFC 0005 round 3 D6）：短于命令超时的 `timeout` 由时间轮计时（客户端只按命令超时计时）。超时以 [RedisException]
     * 失败，结果未知，这次读取请求的 lease 随即释放（K14）。
     */
    @Test
    fun readTimeoutShorterThanTheCommandTimeoutIsTimedByTheTimer() {
        val store = store()
        val timeout = Duration.ofMillis(200)
        val reads: List<suspend () -> Any?> = listOf(
            { store.read("k", lease(), null, timeout) },
            { store.readAll(listOf("k"), mapOf("k" to lease()), timeout) },
            { store.conditional().readIfChanged("k", null, timeout) },
        )
        reads.forEachIndexed { index, read ->
            dispatched.clear()
            val call = started(1, read)
            timer.pending.single().second.assert().isEqualTo(timeout.toMillis())
            timer.runNext()
            val failure = call.failure()
            failure.assert().isInstanceOf(RedisException::class.java)
            failure?.message.assert().isEqualTo("Command timed out after $timeout.")
            // 时间轮超时时取消命令：尚未写出的不再写出
            dispatched.first().isCancelled.assert().isTrue()
            if (index < 2) released().assert().containsExactly("k") else released().assert().isEmpty()
        }
    }

    /**
     * 不短于命令超时的 `timeout` 以命令超时为上限：仍由客户端计时，不另外计时；`null` 同样。不为正的 `timeout` 在发出之前就被拒绝。
     */
    @Test
    fun readTimeoutIsCappedAtTheCommandTimeoutAndMustBePositive() {
        val store = store()
        listOf(null, Duration.ofSeconds(1), Duration.ofHours(1)).forEach { timeout ->
            dispatched.clear()
            val call = started(1) { store.read("k", null, null, timeout) }
            timer.pending.assert().isEmpty()
            dispatched.single().reply(listOf(0L, 0L))
            call.await().assert().isEqualTo(L1Read.Miss(false))
        }
        dispatched.clear()
        listOf(Duration.ZERO, Duration.ofMillis(-1)).forEach { timeout ->
            caller.start { store.read("k", lease(), null, timeout) }.failure()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
            caller.start { store.readAll(listOf("k"), emptyMap(), timeout) }.failure()
                .assert().isInstanceOf(IllegalArgumentException::class.java)
        }
        dispatched.assert().isEmpty()
        connections.readTimeout(Duration.ofMillis(300)).assert().isEqualTo(Duration.ofMillis(300))
        connections.readTimeout(Duration.ofSeconds(5)).assert().isEqualTo(Duration.ofSeconds(1))
    }

    /**
     * 解码失败（未知的结果码）：命令已执行、令牌可能已授予，同样释放。
     */
    @Test
    fun undecodableReplyReleasesTheTokens() {
        val store = store()
        val lease = lease()
        val call = started(1) { store.read("k", lease, null, null) }
        dispatched.single().reply(listOf(9L))
        call.failure().assert().isInstanceOf(IllegalStateException::class.java)
        released().assert().containsExactly("k")
    }

    /**
     * 同一个异常完成多条命令（Lettuce 断线时以同一个实例排空应答栈）：抛出的就是它，不能附给自己（`addSuppressed` 会抛出）。
     * 其余读取取得的 lease 与结果未知的读取请求的 lease 都释放。
     */
    @Test
    fun readAllFailingWithOneSharedExceptionThrowsItAndReleasesEveryLease() {
        val store = store()
        val leases = mapOf("a" to lease(), "b" to lease(), "c" to lease())
        val call = started(4) { store.readAll(listOf("a", "b", "c", "d"), leases, null) }
        val drained = RedisException("Connection disconnected")
        dispatched[0].reply(listOf(0L, 1L))
        dispatched[1].completeExceptionally(drained)
        dispatched[2].completeExceptionally(drained)
        dispatched[3].reply(listOf(0L, 0L))
        val failure = call.failure()
        failure.assert().isSameAs(drained)
        failure?.suppressed.assert().isEmpty()
        released().assert().containsExactlyInAnyOrder("a", "b", "c")
    }

    /**
     * 不同的失败：抛出按发出顺序的第一个，其余附为 suppressed。
     */
    @Test
    fun readAllThrowsTheFirstFailureWithTheOthersSuppressed() {
        val store = store()
        val call = started(2) { store.readAll(listOf("a", "b"), emptyMap(), null) }
        val first = RedisException("first")
        val second = RedisException("second")
        dispatched[1].completeExceptionally(second)
        dispatched[0].completeExceptionally(first)
        val failure = call.failure()
        failure.assert().isSameAs(first)
        failure?.suppressed?.toList().assert().containsExactly(second)
    }

    /**
     * 批量填充等全部结束再判定：任一失败时抛出第一个异常，结果未知的填充各自释放令牌，已写入或令牌不匹配的不释放。
     */
    @Test
    fun fillAllWaitsForEveryFillAndReleasesTheUnknownOnes() {
        val store = store()
        val fills = listOf("a", "b", "c").map { Fill(it, token(), Envelope.Absent, null) }
        val call = started(3) { store.fillAll(fills) }
        val timedOut = RedisException("Command timed out after PT1S.", RedisCommandTimeoutException("late"))
        dispatched[1].completeExceptionally(timedOut)
        dispatched[0].reply(listOf(1L))
        call.isDone.assert().isFalse()
        dispatched[2].reply(listOf(0L))
        call.failure().assert().isSameAs(timedOut)
        released().assert().containsExactly("b")
    }

    /**
     * 释放本身结果未知时再尽力释放一次。
     */
    @Test
    fun releaseWithAnUnknownOutcomeIsResent() {
        val store = store()
        val token = token()
        val call = started(1) { store.release("k", token) }
        val reset = dispatched.single()
        reset.cancel()
        call.failure().assert().isInstanceOf(RedisException::class.java)
        released().assert().containsExactly("k", "k")
    }

    /**
     * 回收结果未知时释放它请求的 lease。
     */
    @Test
    fun reclaimWithAnUnknownOutcomeReleasesItsLease() {
        val store = store()
        val call = started(1) { store.reclaim("k", ContentFingerprint.of(Envelope.Absent), lease()) }
        dispatched.single().completeExceptionally(RedisException("Connection disconnected"))
        call.failure().assert().isInstanceOf(RedisException::class.java)
        released().assert().containsExactly("k")
    }

    /**
     * 写入方法返回或抛出时，它发出的全部命令都已结束（RFC 0005 §4.3）：第一组失败后仍等其余组结束，再抛出第一个异常。
     * 一组的批量 `putAll` / `invalidate` 各发出一条命令；分组时帧按组拆分。
     */
    @Test
    fun writesWaitForEveryGroupBeforeThrowing() {
        val store = store(invalidateBatchSize = 1)
        val writes: List<suspend () -> Unit> = listOf(
            { store.putAll(listOf(Put("a", Envelope.Absent, null), Put("b", Envelope.Absent, null)), frame("a", "b")) },
            { store.invalidate(listOf("a", "b"), frame("a", "b")) },
        )
        writes.forEach { write ->
            dispatched.clear()
            val call = started(2) { write() }
            val failed = RedisException("Connection disconnected")
            dispatched[0].completeExceptionally(failed)
            call.isDone.assert().isFalse()
            dispatched[1].reply(listOf(1L))
            call.failure().assert().isSameAs(failed)
            dispatched.map { it.key }.assert().containsExactly("a", "b")
            // 每组只广播本组的 key
            dispatched[0].carries(frame("a")).assert().isTrue()
            dispatched[1].carries(frame("b")).assert().isTrue()
        }
        // 空的批量什么也不发出
        caller.run {
            store.putAll(emptyList(), frame())
            store.invalidate(emptyList(), frame())
        }
        dispatched.size.assert().isEqualTo(2)
    }

    /**
     * 帧中的 key 与写入的 key 不一致，或帧无法解码：每组原样广播整个帧，而不是丢掉广播。
     */
    @Test
    fun framesThatCannotBeSplitAreBroadcastUnchanged() {
        val store = store(invalidateBatchSize = 1)
        val mismatched = frame("a", "other")
        val malformed = byteArrayOf(1, 2, 3)
        listOf(mismatched, malformed).forEach { frame ->
            dispatched.clear()
            val call = started(2) { store.invalidate(listOf("a", "b"), frame) }
            dispatched.forEach { it.reply(listOf(1L)) }
            call.await()
            dispatched.forEach { command -> command.carries(frame).assert().isTrue() }
        }
    }

    /**
     * 手动推进的时间轮：记录安排的任务与延迟（毫秒），[runNext] 执行最早安排的一个；停止之后安排任务抛出 [IllegalStateException]（同 Netty）。
     */
    private class ManualTimer : Timer {
        val pending = CopyOnWriteArrayList<Pair<TimerTask, Long>>()

        @Volatile
        private var stopped = false

        override fun newTimeout(task: TimerTask, delay: Long, unit: TimeUnit): Timeout {
            check(!stopped) { "cannot be started once stopped" }
            pending.add(task to unit.toMillis(delay))
            return mockk(relaxed = true)
        }

        override fun stop(): Set<Timeout> {
            stopped = true
            return emptySet()
        }

        /**
         * 执行最早安排的任务，返回它的延迟（毫秒）。
         */
        fun runNext(): Long {
            val (task, delay) = pending.removeAt(0)
            task.run(mockk(relaxed = true))
            return delay
        }
    }

    private companion object {
        val CHANNEL = "cocache:inv:unit".toByteArray(Charsets.UTF_8)

        /**
         * 脚本的 SHA-1 → 名字（命令的第一个字符串参数是 `EVALSHA` 的 SHA-1）。
         */
        val SCRIPTS: Map<String, String> = mapOf(
            RedisScripts.READ.sha to "READ",
            RedisScripts.FILL.sha to "FILL",
            RedisScripts.RELEASE.sha to "RELEASE",
            RedisScripts.RECLAIM.sha to "RECLAIM",
            RedisScripts.PUT.sha to "PUT",
            RedisScripts.PUT_ALL.sha to "PUT_ALL",
            RedisScripts.INVALIDATE.sha to "INVALIDATE",
            RedisScripts.READ_IF_CHANGED.sha to "READ_IF_CHANGED",
            RedisScripts.PUT_IF_MATCH.sha to "PUT_IF_MATCH",
            RedisScripts.PUT_IF_NEWER.sha to "PUT_IF_NEWER",
            RedisScripts.ADD_MEMBERS_IF_READABLE.sha to "ADD_MEMBERS_IF_READABLE",
            RedisScripts.REMOVE_MEMBERS_IF_READABLE.sha to "REMOVE_MEMBERS_IF_READABLE",
        )
    }
}
