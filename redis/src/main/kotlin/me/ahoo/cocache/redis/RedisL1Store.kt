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
import io.lettuce.core.RedisException
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.ConditionalL1Store
import me.ahoo.cocache.api.spi.ContentFingerprint
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.api.spi.L1Eviction
import me.ahoo.cocache.api.spi.L1Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.L1RejectedException
import me.ahoo.cocache.api.spi.L1SetWrite
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.api.spi.VersionedL1Store
import java.time.Duration
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

/**
 * 一个命名空间的 L1 存储，原生挂起（RFC 0005 §4.3）：每个操作一次 Lua 往返（[RedisScripts]），以 Lettuce 的异步命令发出、
 * 挂起等待结果（[RedisConnections.awaitCommand]）。等待期间不占线程，续接经调用方的调度器恢复，不在 Lettuce 的 I/O 线程上继续。
 * 线格式 2（RFC 0005 §4.7）：版本、摘要与写入标识（[Envelope.Scalar.version]、[Envelope.Scalar.digest]、[Envelope.Scalar.writer]）
 * 写成头部之后的段，读取时原样返回（摘要段不重算，由引擎校验）。
 *
 * - **限时**：每条命令受 `commandTimeout` 限制；读取另可带这次调用的 `timeout`，以两者中较短的一个为限（RFC 0005 round 3 D6，
 *   [RedisConnections.readTimeout]）。超时时尚未写出的脚本命令不再写出（`CommandCall`）。
 * - **批量**：[readAll]、[fillAll] 先发出全部命令再一起等待（共享连接上多路复用，约一次往返）；[putAll]、[invalidate] 按 slot 分组
 *   （Cluster）再按 `invalidateBatchSize` 切分，各组并发执行，全部结束之后才返回或抛出。
 * - **令牌**（RFC 0005 round 3 K14）：取得或交回令牌的命令（读取、回收、填充、释放）失败时，除非确定未执行
 *   （[RedisConnections.isNotExecuted]），都尽力释放该令牌：异步发出，不等待；释放失败（含断线期间被拒绝）时按退避重发，
 *   直到执行或令牌过期（[TokenReleaser]）。令牌不匹配时释放什么也不做，迟到或重连之后才到达都无害；连接被替换之后释放可能
 *   先于被排空的原命令执行，那条命令取得的令牌留到它的寿命（`lease.ttl`，TLA+ F4）。
 * - **取消**：Lettuce 取消的命令（连接重置、关闭）以结果未知的 [io.lettuce.core.RedisException] 失败，从不以 `CancellationException` 报告。
 *
 * 提供全部三个能力面：条件读写（只读头部比较摘要段）、按版本有序的写入（[RedisScripts.PUT_IF_NEWER]、
 * [RedisScripts.EVICT_KEEPING_VERSION]，各一次往返）与集合成员的增删。
 *
 * @param channel 原子广播的频道；为空时不在脚本中广播（后端不支持原子广播，由引擎经通道发布）
 * @param releaser 失败之后释放令牌（后端的各存储共用一个，放弃的释放在后端汇总计数与告警）
 */
internal class RedisL1Store(
    private val connections: RedisConnections,
    private val channel: ByteArray,
    private val invalidateBatchSize: Int,
    private val releaser: TokenReleaser = TokenReleaser.of(connections),
) : L1Store {
    private val conditional = ConditionalFacet()
    private val versioned = VersionedFacet()
    private val sets = SetFacet()

    /**
     * 失败时（确定未执行除外）释放这次请求的 lease 与刷新令牌：读脚本可能已执行、授予了它们，调用方却拿不到结果。
     * 解码失败同样释放：命令已执行。
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read {
        val bound = connections.readTimeout(timeout)
        val command = readCommand(key, lease, refresh)
        try {
            return RedisScripts.decodeRead(connections.awaitCommand(command, bound))
        } catch (e: RuntimeException) {
            if (!RedisConnections.isNotExecuted(e)) {
                lease?.let { releaser.release(key, it.token, it.ttl) }
                refresh?.let { releaser.release(key, it.token, it.tokenTtl) }
            }
            throw e
        }
    }

    /**
     * 先发出全部读取再一起等待。任一失败时，其余读取取得的 lease 先释放（不等待）再抛出第一个异常（其余附为 suppressed）：
     * 否则调用方拿不到这些令牌，所有节点都要白白等待 `lease.ttl`；结果未知的读取同样尽力释放自己的 lease。
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun readAll(
        keys: Collection<String>,
        leases: Map<String, LeaseRequest>,
        timeout: Duration?
    ): Map<String, L1Read> {
        val bound = connections.readTimeout(timeout)
        if (keys.isEmpty()) {
            return emptyMap()
        }
        // 重复的 key 只读一次：否则第二次读到的是第一次取得的 lease，第一次的令牌无人交回
        val distinct = if (keys is Set<String>) keys.toList() else keys.distinct()
        val sent = sendAll(distinct.size, bound) { readCommand(distinct[it], leases[distinct[it]], null) }
        val reads = LinkedHashMap<String, L1Read>(distinct.size)
        var failure: Throwable? = sent.refused
        sent.outcomes.forEachIndexed { index, outcome ->
            val key = distinct[index]
            try {
                reads[key] = RedisScripts.decodeRead(outcome.getOrThrow())
            } catch (e: RuntimeException) {
                leases[key]?.let { releaser.releaseUnlessNotExecuted(key, it.token, it.ttl, e) }
                failure = failure.suppressing(e)
            }
        }
        val error = failure ?: return reads
        reads.forEach { (key, read) ->
            val lease = leases[key]
            if (read is L1Read.Miss && read.leaseGranted && lease != null) {
                releaser.release(key, lease.token, lease.ttl)
            }
        }
        throw error
    }

    private fun readCommand(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<List<Any?>> {
        val args = listOf(
            lease?.token?.toBytes() ?: EMPTY,
            millis(lease?.ttl),
            refresh?.window?.let { millis(it) } ?: EMPTY,
            refresh?.token?.toBytes() ?: EMPTY,
            millis(refresh?.tokenTtl),
        )
        return connections.evalAsync(RedisScripts.READ, listOf(key.bytes()), args)
    }

    /**
     * 结果未知时尽力释放令牌：被取消、没有写出的 fill 否则留下 lease 到 `lease.ttl`；fill 已执行时令牌不匹配，释放什么也不做。
     */
    override suspend fun fill(fill: Fill): L1Fill {
        val command = fillCommand(fill)
        return releasingOnFailure(fill.key, fill.token) { RedisScripts.decodeFill(connections.awaitCommand(command)) }
    }

    /**
     * 先发出全部填充再一起等待，结果与 [fills] 一一对应。任一失败时等全部结束再按整批判定抛出（[throwFirstFailure]），
     * 结果未知的填充各自尽力释放令牌。
     *
     * 确定未执行（[L1RejectedException]）只对整批成立（#818）：有的填充已执行（写入、推进了版本）或结果未知时，整批结果未知。
     * 引擎据此对每个带版本的填充按写入递增戳（TLA+ F2）；报告为确定未执行时它跳过已推进版本的那一个，开始更早的同节点读取者
     * 随后把旧版本写进 L2。
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun fillAll(fills: List<Fill>): List<L1Fill> {
        if (fills.isEmpty()) {
            return emptyList()
        }
        val sent = sendAll(fills.size, null) { fillCommand(fills[it]) }
        val filled = ArrayList<L1Fill>(fills.size)
        // 解码失败同样是失败：命令已执行，不是确定未执行
        val failures = sent.outcomes.mapIndexed { index, outcome ->
            try {
                filled.add(RedisScripts.decodeFill(outcome.getOrThrow()))
                null
            } catch (e: RuntimeException) {
                val fill = fills[index]
                releaser.releaseUnlessNotExecuted(fill.key, fill.token, null, e)
                e
            }
        }
        throwFirstFailure(failures, sent.refused)
        return filled
    }

    /**
     * 只有带版本的填充可能广播（版本规则，见 [RedisScripts.FILL]）：帧的来源是令牌的节点（令牌由引擎签发），只含这个 key；
     * 其余填充的频道与帧为空串。
     */
    private fun fillCommand(fill: Fill): CompletableFuture<List<Any?>> {
        val args = ArrayList<ByteArray>()
        args.add(fill.token.toBytes())
        // 填充的版本（墓碑的版本只能经 Fill.version 传递）
        args.add(shapeOf(fill.envelope, fill.version))
        args.add(millis(fill.ttl))
        if (fill.version != Versioned.NO_VERSION && channel.isNotEmpty()) {
            args.add(channel)
            args.add(InvalidationFrame.encode(fill.token.node, listOf(fill.key)))
        } else {
            args.add(EMPTY)
            args.add(EMPTY)
        }
        addPayload(args, fill.envelope)
        return connections.evalAsync(RedisScripts.FILL, listOf(fill.key.bytes()), args)
    }

    /**
     * 结果未知时再尽力释放一次：超时的释放被取消，尚未写出时永远不会执行；已执行时重复的释放什么也不做。
     */
    override suspend fun release(key: String, token: Token) {
        val command = TokenReleaser.releaseCommand(connections, key, token)
        releasingOnFailure(key, token) { connections.awaitCommand(command) }
    }

    /**
     * 回收成功时取得 lease：失败时（确定未执行除外）与读取一样尽力释放。
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean {
        val args = listOf(observed.toString().bytes(), lease.token.toBytes(), millis(lease.ttl))
        val command = connections.evalAsync<List<Any?>>(RedisScripts.RECLAIM, listOf(key.bytes()), args)
        try {
            return connections.awaitCommand(command).firstFlag()
        } catch (e: RuntimeException) {
            releaser.releaseUnlessNotExecuted(key, lease.token, lease.ttl, e)
            throw e
        }
    }

    override suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
        val args = ArrayList<ByteArray>()
        args.add(shapeOf(envelope))
        args.add(millis(ttl))
        args.add(channel)
        args.add(frame)
        addPayload(args, envelope)
        write<List<Any?>>(RedisScripts.PUT, key, args)
    }

    /**
     * 单 key 的写入脚本：发出并等待。发出时同步失败（`dispatch` 抛出，命令没有写出）按确定未执行报告（[L1RejectedException]），
     * 与批量写入发出时同步失败的那一组相同（[sendAll]）；引擎据此报告 `CacheWriteException.outcomeUnknown = false`。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> write(script: RedisScripts.Script, key: String, args: List<ByteArray>): T {
        val command = try {
            connections.evalAsync<T>(script, listOf(key.bytes()), args)
        } catch (e: RuntimeException) {
            throw e as? L1RejectedException ?: L1RejectedException(e.message ?: e.javaClass.name, e)
        }
        return connections.awaitCommand(command)
    }

    /**
     * 与 [invalidate] 相同的分组：按 slot 分组（Cluster）再按 `invalidateBatchSize` 切分，每组一个 [RedisScripts.PUT_ALL]，
     * 只广播本组的 key，并发执行；原子性只在单个组内成立。
     */
    override suspend fun putAll(puts: List<Put>, frame: ByteArray) {
        val byKey = LinkedHashMap<String, Put>(puts.size)
        puts.forEach { byKey[it.key] = it }
        val putOf = IdentityHashMap<ByteArray, Put>(byKey.size)
        val keyBytes = byKey.values.map { put -> put.key.bytes().also { putOf[it] = put } }
        val groups = groupsOf(keyBytes)
        val frames = framesOf(byKey.keys, frame, groups)
        val sent = sendAll(groups.size, null) { index ->
            val group = groups[index]
            val args = ArrayList<ByteArray>()
            args.add(channel)
            args.add(frames[index])
            group.forEach { key ->
                val put = putOf.getValue(key)
                args.add(shapeOf(put.envelope))
                args.add(millis(put.ttl))
                args.add(payloadCount(put.envelope).toString().bytes())
                addPayload(args, put.envelope)
            }
            connections.evalAsync<List<Any?>>(RedisScripts.PUT_ALL, group, args)
        }
        throwFirstFailure(sent)
    }

    override suspend fun invalidate(keys: Collection<String>, frame: ByteArray) {
        val groups = groupsOf(keys.map { it.bytes() })
        val frames = framesOf(keys, frame, groups)
        val sent = sendAll(groups.size, null) { index ->
            connections.evalAsync<List<Any?>>(RedisScripts.INVALIDATE, groups[index], listOf(channel, frames[index]))
        }
        throwFirstFailure(sent)
    }

    private fun groupsOf(keys: List<ByteArray>): List<List<ByteArray>> =
        connections.groupBySlot(keys).flatMap { it.chunked(invalidateBatchSize) }

    /**
     * 全部组都已结束（[RedisConnections.awaitCommands]）之后，抛出按发出顺序的第一个异常（其余附为 suppressed）：
     * 调用方在失败后尽力广播（非原子模式），广播不能先于仍在执行的组，否则其它节点可能在广播之后、该组写入之前从 L1 读到旧值并填充 L2。
     *
     * 例外是命令超时：放弃等待时，已写出的命令可能仍在 Redis 端执行，失败后补发的广播可能先于它生效，此时其它节点的旧副本由
     * `localMaxAge` 兜底。各组并发发出、各自限时，失败路径上的等待约为一个 `commandTimeout`。
     */
    private fun throwFirstFailure(sent: Sent<*>) {
        throwFirstFailure(sent.outcomes.map { it.exceptionOrNull() }, sent.refused)
    }

    /**
     * 按整批判定抛出（写入的各组、[fillAll] 的各条填充）：[failures] 与已发出的命令一一对应（成功为 `null`），[refused] 是发出时
     * 同步失败的那一条（确定未执行，其后的命令没有发出）。没有失败时什么也不做；否则抛出按发出顺序的第一个异常（其余附为 suppressed）：
     * - 每条已发出的命令都确定未执行（[RedisConnections.isNotExecuted]；没有已发出的命令时同样成立）时为 [L1RejectedException]；
     * - 否则整批结果未知：有的命令已执行或结果未知。第一个异常本身是 [L1RejectedException] 时换成结果未知的 [RedisException]
     *   （cause 是它），不能把整批报告为确定未执行。
     */
    private fun throwFirstFailure(failures: List<Throwable?>, refused: RuntimeException?) {
        var failure: Throwable? = null
        failures.forEach { error -> error?.let { failure = failure.suppressing(it) } }
        refused?.let { failure = failure.suppressing(it) }
        val first = failure ?: return
        // 确定未执行只对整个调用成立：有的组已发出且没有被拒绝时（已执行或结果未知），整个调用结果未知（RFC 0005 §4.3）
        val notExecuted = failures.all { error -> error != null && RedisConnections.isNotExecuted(error) }
        throw when {
            notExecuted -> first as? L1RejectedException ?: L1RejectedException(first.message ?: "", first)
            first !is L1RejectedException -> first
            else -> RedisException(
                "Some commands were rejected before they were written, others may have run: the outcome is unknown.",
                first
            )
        }
    }

    /**
     * 依次发出 [count] 条命令（[send]），再一起等待已发出的全部结束（[RedisConnections.awaitCommands]，约一次往返），
     * 每条至多等 [timeout]（`null` 为 `commandTimeout`）。
     * 某条发出时同步失败（确定未执行）就不再发出其后的命令，但仍等已发出的结束：调用方照常处理它们（释放取得的令牌），
     * 写入方法返回或抛出时它发出的全部命令都已结束。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> sendAll(count: Int, timeout: Duration?, send: (Int) -> CompletableFuture<T>): Sent<T> {
        val commands = ArrayList<CompletableFuture<T>>(count)
        var refused: RuntimeException? = null
        try {
            for (index in 0 until count) {
                commands.add(send(index))
            }
        } catch (e: RuntimeException) {
            refused = e
        }
        return Sent(connections.awaitCommands(commands, timeout ?: connections.commandTimeout), refused)
    }

    /**
     * [sendAll] 的结果：已发出的命令按发出顺序的结果，以及发出时同步失败的那一条的异常（没有时为 `null`）。
     */
    private class Sent<T>(val outcomes: List<Result<T>>, val refused: RuntimeException?)

    /**
     * 每组脚本只广播本组的 key（同一发布者），避免 N 个 key 分成多组时每组都重复广播全部 key（O(N²)）。
     * 只有一组、不在脚本中广播，或帧中的 key 与 [keys] 不一致（无法安全拆分）时，原样使用 [frame]。
     */
    private fun framesOf(keys: Collection<String>, frame: ByteArray, groups: List<List<ByteArray>>): List<ByteArray> {
        if (groups.size <= 1 || channel.isEmpty()) {
            return List(groups.size) { frame }
        }
        val decoded = try {
            InvalidationFrame.decode(frame)
        } catch (e: IllegalArgumentException) {
            log.warn(e) { "Malformed invalidation frame - broadcast it unchanged." }
            return List(groups.size) { frame }
        }
        if (decoded.keys.toSet() != keys.toSet()) {
            return List(groups.size) { frame }
        }
        return groups.map { group ->
            InvalidationFrame.encode(
                decoded.origin,
                group.map { String(it, Charsets.UTF_8) }
            )
        }
    }

    override fun conditional(): ConditionalL1Store = conditional

    override fun versioned(): VersionedL1Store = versioned

    override fun sets(): SetL1Store = sets

    /**
     * 执行交回令牌的步骤 [block]（填充、释放：等待命令并解码结果）；失败时（确定未执行除外）尽力释放 [token]，再原样抛出。
     * 令牌的寿命不在请求中，按 [TokenReleaser.UNKNOWN_TOKEN_LIFETIME] 重发。
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> releasingOnFailure(key: String, token: Token, block: () -> T): T {
        try {
            return block()
        } catch (e: RuntimeException) {
            releaser.releaseUnlessNotExecuted(key, token, null, e)
            throw e
        }
    }

    private inner class ConditionalFacet : ConditionalL1Store {
        override suspend fun readIfChanged(key: String, known: Digest?, timeout: Duration?): L1ConditionalRead {
            val bound = connections.readTimeout(timeout)
            val args = listOf(known?.bytes ?: EMPTY)
            val command = connections.evalAsync<List<Any?>>(RedisScripts.READ_IF_CHANGED, listOf(key.bytes()), args)
            return RedisScripts.decodeConditionalRead(connections.awaitCommand(command, bound))
        }

        override suspend fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): L1ConditionalWrite {
            // 条件写入写入没有版本的值，带写入标识与摘要段（引擎计算）
            val shape = RedisScripts.scalarShape(
                RedisScripts.STATE_VALUE,
                Versioned.NO_VERSION,
                envelope.writer,
                envelope.digest
            )
            val args = listOf(
                expected?.bytes ?: EMPTY,
                shape,
                millis(ttl),
                channel,
                frame,
                envelope.bytes
            )
            return RedisScripts.decodeConditionalWrite(write(RedisScripts.PUT_IF_MATCH, key, args))
        }

        override suspend fun deleteIfMatch(key: String, expected: Digest, frame: ByteArray): L1ConditionalWrite {
            val args = listOf(expected.bytes, channel, frame)
            return RedisScripts.decodeConditionalWrite(write(RedisScripts.DELETE_IF_MATCH, key, args))
        }
    }

    private inner class VersionedFacet : VersionedL1Store {
        override suspend fun putIfNewer(
            key: String,
            envelope: Envelope,
            version: Long,
            ttl: Duration?,
            frame: ByteArray
        ): L1VersionedWrite {
            // 参数错误在发出之前以 IllegalArgumentException 抛出，什么也没有写出（调用方的编程错误，不是 L1 故障）。
            // 版本段中不得是 NO_VERSION（RFC 0005 §4.7，K15），脚本同样拒绝
            require(
                version != Versioned.NO_VERSION
            ) { "putIfNewer takes a version, not Versioned.NO_VERSION (Long.MIN_VALUE)." }
            require(envelope !is Envelope.Members) { "putIfNewer takes a scalar value or Envelope.Absent, not a set." }
            val args = ArrayList<ByteArray>(5)
            args.add(shapeOf(envelope, version))
            args.add(millis(ttl))
            args.add(channel)
            args.add(frame)
            if (envelope is Envelope.Scalar) {
                args.add(envelope.bytes)
            }
            return RedisScripts.decodeVersionedWrite(write(RedisScripts.PUT_IF_NEWER, key, args))
        }

        override suspend fun evictKeepingVersion(key: String, ttl: Duration?, frame: ByteArray): L1Eviction {
            val args = listOf(millis(ttl), channel, frame)
            return RedisScripts.decodeEviction(write(RedisScripts.EVICT_KEEPING_VERSION, key, args))
        }
    }

    private inner class SetFacet : SetL1Store {
        override suspend fun addMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): L1SetWrite =
            writeMembers(RedisScripts.ADD_MEMBERS_IF_READABLE, key, members, ttl, frame)

        override suspend fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): L1SetWrite =
            writeMembers(RedisScripts.REMOVE_MEMBERS_IF_READABLE, key, members, ttl, frame)

        /**
         * `*_IF_READABLE` 脚本的结果：`{1}` 已写入（[L1SetWrite.Written]），`{0, 格式版本}` 拒绝（[L1SetWrite.Unreadable]）。
         */
        private suspend fun writeMembers(
            script: RedisScripts.Script,
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): L1SetWrite {
            val args = ArrayList<ByteArray>(members.size + 3)
            args.add(millis(ttl))
            args.add(channel)
            args.add(frame)
            args.addAll(members)
            return RedisScripts.decodeSetWrite(write(script, key, args))
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private val EMPTY = ByteArray(0)
        private val SHAPE_SET = byteArrayOf('S'.code.toByte())

        /**
         * [first] 为空时就是 [error]；否则把 [error] 附为它的 suppressed 并返回它。同一个异常可能完成多条命令
         * （Lettuce 断线时以同一个实例排空应答栈），不能附给自己（`addSuppressed` 会抛出）。
         */
        private fun Throwable?.suppressing(error: Throwable): Throwable {
            if (this == null) {
                return error
            }
            if (this !== error) {
                addSuppressed(error)
            }
            return this
        }

        private fun addPayload(args: MutableList<ByteArray>, envelope: Envelope) {
            when (envelope) {
                is Envelope.Scalar -> args.add(envelope.bytes)
                is Envelope.Members -> args.addAll(envelope.members)
                Envelope.Absent -> Unit
            }
        }

        private fun payloadCount(envelope: Envelope): Int = when (envelope) {
            is Envelope.Scalar -> 1
            is Envelope.Members -> envelope.size
            Envelope.Absent -> 0
        }

        /**
         * 写脚本的形状参数（见 [RedisScripts.PRELUDE]）：集合为 `S`；标量为状态、标志位与段（[RedisScripts.scalarShape]），
         * 带上信封的版本与写入标识。
         */
        private fun shapeOf(envelope: Envelope): ByteArray =
            shapeOf(envelope, (envelope as? Envelope.Scalar)?.version ?: Versioned.NO_VERSION)

        /**
         * 带 [version] 的形状：值与负缓存带版本时写版本段（负缓存带版本即墓碑）；值带摘要时写摘要段、带写入标识时写扩展区；集合没有段。
         */
        private fun shapeOf(envelope: Envelope, version: Long): ByteArray = when (envelope) {
            is Envelope.Members -> SHAPE_SET
            is Envelope.Scalar -> RedisScripts.scalarShape(
                RedisScripts.STATE_VALUE,
                version,
                envelope.writer,
                envelope.digest
            )
            Envelope.Absent -> RedisScripts.scalarShape(RedisScripts.STATE_MISSING, version, null, null)
        }

        private fun String.bytes(): ByteArray = toByteArray(Charsets.UTF_8)

        /**
         * 毫秒（至少 1），`null` 为 `0`（永不过期）。
         */
        private fun millis(
            duration: Duration?
        ): ByteArray = (duration?.toMillis()?.coerceAtLeast(1) ?: 0L).toString().bytes()

        private fun List<Any?>.firstFlag(): Boolean = firstOrNull() == 1L
    }
}
