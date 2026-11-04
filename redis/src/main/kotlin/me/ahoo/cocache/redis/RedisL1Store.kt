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
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.api.spi.VersionedL1Store
import java.nio.ByteBuffer
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
 *   直到执行或令牌过期（[releaseQuietly]）。令牌不匹配时释放什么也不做，迟到或重连之后才到达都无害；连接被替换之后释放可能
 *   先于被排空的原命令执行，那条命令取得的令牌留到它的寿命（`lease.ttl`，TLA+ F4）。
 * - **取消**：Lettuce 取消的命令（连接重置、关闭）以结果未知的 [io.lettuce.core.RedisException] 失败，从不以 `CancellationException` 报告。
 *
 * 提供全部三个能力面：条件读写（只读头部比较摘要段）、按版本有序的写入（[RedisScripts.PUT_IF_NEWER]、
 * [RedisScripts.EVICT_KEEPING_VERSION]，各一次往返）与集合成员的增删。
 *
 * @param channel 原子广播的频道；为空时不在脚本中广播（后端不支持原子广播，由引擎经通道发布）
 */
internal class RedisL1Store(
    private val connections: RedisConnections,
    private val channel: ByteArray,
    private val invalidateBatchSize: Int,
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
            return decodeRead(connections.awaitCommand(command, bound))
        } catch (e: RuntimeException) {
            if (!RedisConnections.isNotExecuted(e)) {
                lease?.let { releaseQuietly(key, it.token, it.ttl) }
                refresh?.let { releaseQuietly(key, it.token, it.tokenTtl) }
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
                reads[key] = decodeRead(outcome.getOrThrow())
            } catch (e: RuntimeException) {
                leases[key]?.let { releaseUnlessNotExecuted(key, it.token, it.ttl, e) }
                failure = failure.suppressing(e)
            }
        }
        val error = failure ?: return reads
        reads.forEach { (key, read) ->
            val lease = leases[key]
            if (read is L1Read.Miss && read.leaseGranted && lease != null) {
                releaseQuietly(key, lease.token, lease.ttl)
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
        return releasingOnFailure(fill.key, fill.token) { fillOf(connections.awaitCommand(command)) }
    }

    /**
     * 先发出全部填充再一起等待，结果与 [fills] 一一对应。任一失败时等全部结束再抛出第一个异常（其余附为 suppressed），
     * 结果未知的填充各自尽力释放令牌。
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun fillAll(fills: List<Fill>): List<L1Fill> {
        if (fills.isEmpty()) {
            return emptyList()
        }
        val sent = sendAll(fills.size, null) { fillCommand(fills[it]) }
        val filled = ArrayList<L1Fill>(fills.size)
        var failure: Throwable? = sent.refused
        sent.outcomes.forEachIndexed { index, outcome ->
            val fill = fills[index]
            try {
                filled.add(fillOf(outcome.getOrThrow()))
            } catch (e: RuntimeException) {
                releaseUnlessNotExecuted(fill.key, fill.token, null, e)
                failure = failure.suppressing(e)
            }
        }
        failure?.let { throw it }
        return filled
    }

    /**
     * [RedisScripts.FILL] 的结果：`{1, advanced}` 写入、`{0}` 令牌不匹配、`{2, 当前版本}` 版本拒绝（RFC 0005 §4.7）。
     */
    private fun fillOf(result: List<Any?>): L1Fill = when (result[0] as Long) {
        FILL_WRITTEN -> if (result[1] == 1L) ADVANCED else FILLED
        FILL_VERSION_REJECTED -> L1Fill.Rejected(ByteBuffer.wrap(result[1] as ByteArray).long)
        else -> TOKEN_MISMATCH
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
        val command = releaseCommand(key, token)
        releasingOnFailure(key, token) { connections.awaitCommand(command) }
    }

    private fun releaseCommand(key: String, token: Token): CompletableFuture<List<Any?>> =
        connections.evalAsync(RedisScripts.RELEASE, listOf(key.bytes()), listOf(token.toBytes()))

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
            releaseUnlessNotExecuted(key, lease.token, lease.ttl, e)
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
        connections.awaitCommand(connections.evalAsync<List<Any?>>(RedisScripts.PUT, listOf(key.bytes()), args))
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
        var failure: Throwable? = null
        sent.outcomes.forEach { outcome ->
            outcome.exceptionOrNull()?.let { failure = failure.suppressing(it) }
        }
        sent.refused?.let { failure = failure.suppressing(it) }
        failure?.let { throw it }
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
        return Sent(connections.awaitCommands(commands, timeout ?: connections.readTimeout(null)), refused)
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
     * 令牌的寿命不在请求中，按 [UNKNOWN_TOKEN_LIFETIME] 重发。
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> releasingOnFailure(key: String, token: Token, block: () -> T): T {
        try {
            return block()
        } catch (e: RuntimeException) {
            releaseUnlessNotExecuted(key, token, null, e)
            throw e
        }
    }

    /**
     * 取得或交回令牌的命令以 [error] 失败：除非确定未执行（[RedisConnections.isNotExecuted]：写出之前就被拒绝，不会取得令牌），
     * 尽力释放 [token]。命令可能已在 Redis 端执行（读取、回收授予了 lease 或刷新令牌），也可能被取消、永远不会执行
     * （fill 没有写出，lease 仍在），调用方随后都不会再交回这个令牌；否则 lease 留到 `lease.ttl`，全集群对该 key 的未命中都要先等 `lease.wait`。
     */
    private fun releaseUnlessNotExecuted(key: String, token: Token, lifetime: Duration?, error: Throwable) {
        if (!RedisConnections.isNotExecuted(error)) {
            releaseQuietly(key, token, lifetime)
        }
    }

    /**
     * 尽力释放令牌：异步发出，不等待（RFC 0005 round 3 K14）。释放失败时（含断线期间被拒绝）在时间轮上按退避重发
     * （[MIN_RELEASE_BACKOFF] 起倍增，至多 [MAX_RELEASE_BACKOFF]），直到执行、过了令牌的寿命 [lifetime]（`null` 为 [UNKNOWN_TOKEN_LIFETIME]，
     * 之后令牌已失效）或连接已关闭：断线期间被拒绝的释放因此在重连之后到达，lease 通常不必等到 `lease.ttl`。令牌不匹配时释放什么也不做，
     * 迟到、重复或重连之后才到达都无害。连接被替换之后，释放可能先于旧连接上被排空的原命令执行：令牌不匹配、释放就此结束，
     * 原命令随后取得的令牌留到 `lease.ttl`（TLA+ F4）。放弃时记录告警，lease 最终由 `lease.ttl` 回收。
     */
    private fun releaseQuietly(key: String, token: Token, lifetime: Duration?) {
        val deadline = System.nanoTime() + (lifetime ?: UNKNOWN_TOKEN_LIFETIME).toNanos()
        sendRelease(key, token, deadline, MIN_RELEASE_BACKOFF)
    }

    /**
     * 发出一次释放（受 `commandTimeout` 限制）；失败时安排下一次。发出时同步失败（如客户端资源已关闭）只记录：
     * 调用方抛出的必须是原来的失败，批量读取也要继续处理其余的 key。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun sendRelease(key: String, token: Token, deadline: Long, backoff: Duration) {
        val release = try {
            connections.bounded(releaseCommand(key, token))
        } catch (e: RuntimeException) {
            log.warn(e) { "Release token of key[$key] failed - reclaimed after lease ttl." }
            return
        }
        release.whenComplete { _, error ->
            if (error == null) {
                return@whenComplete
            }
            val next = backoff.multipliedBy(2).coerceAtMost(MAX_RELEASE_BACKOFF)
            val retrying = deadline - System.nanoTime() > backoff.toNanos() &&
                connections.schedule(backoff) { sendRelease(key, token, deadline, next) }
            if (retrying) {
                log.debug(error) { "Release token of key[$key] failed - retry in $backoff." }
            } else {
                log.warn(error) { "Release token of key[$key] failed - reclaimed after lease ttl." }
            }
        }
    }

    private inner class ConditionalFacet : ConditionalL1Store {
        override suspend fun readIfChanged(key: String, known: Digest?, timeout: Duration?): L1ConditionalRead {
            val bound = connections.readTimeout(timeout)
            val args = listOf(known?.bytes ?: EMPTY)
            val command = connections.evalAsync<List<Any?>>(RedisScripts.READ_IF_CHANGED, listOf(key.bytes()), args)
            return decodeConditionalRead(connections.awaitCommand(command, bound))
        }

        override suspend fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): L1ConditionalWrite {
            // 条件写入写入没有版本的值，带写入标识与摘要段（引擎计算）
            val shape = RedisScripts.scalarShape(STATE_VALUE, Versioned.NO_VERSION, envelope.writer, envelope.digest)
            val args = listOf(
                expected?.bytes ?: EMPTY,
                shape,
                millis(ttl),
                channel,
                frame,
                envelope.bytes
            )
            return decodeConditionalWrite(
                connections.awaitCommand(connections.evalAsync(RedisScripts.PUT_IF_MATCH, listOf(key.bytes()), args))
            )
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
            // 发出之前同步失败：确定未执行。版本段中不得是 NO_VERSION（RFC 0005 §4.7，K15），脚本同样拒绝
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
            return decodeVersionedWrite(
                connections.awaitCommand(connections.evalAsync(RedisScripts.PUT_IF_NEWER, listOf(key.bytes()), args))
            )
        }

        override suspend fun evictKeepingVersion(key: String, ttl: Duration?, frame: ByteArray): L1Eviction {
            val args = listOf(millis(ttl), channel, frame)
            return decodeEviction(
                connections.awaitCommand(
                    connections.evalAsync(RedisScripts.EVICT_KEEPING_VERSION, listOf(key.bytes()), args)
                )
            )
        }
    }

    private inner class SetFacet : SetL1Store {
        override suspend fun addMembers(
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): Int? =
            writeMembers(RedisScripts.ADD_MEMBERS_IF_READABLE, key, members, ttl, frame)

        override suspend fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int? =
            writeMembers(RedisScripts.REMOVE_MEMBERS_IF_READABLE, key, members, ttl, frame)

        /**
         * `*_IF_READABLE` 脚本的结果：`{1}` 已写入（`null`），`{0, 格式版本}` 拒绝。
         */
        private suspend fun writeMembers(
            script: RedisScripts.Script,
            key: String,
            members: Collection<ByteArray>,
            ttl: Duration?,
            frame: ByteArray
        ): Int? {
            val args = ArrayList<ByteArray>(members.size + 3)
            args.add(millis(ttl))
            args.add(channel)
            args.add(frame)
            args.addAll(members)
            val result = connections.awaitCommand(connections.evalAsync<List<Any?>>(script, listOf(key.bytes()), args))
            return if (result.firstFlag()) null else (result[1] as Long).toInt()
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private val EMPTY = ByteArray(0)
        private const val STATE_VALUE = 'V'.code.toByte()
        private const val STATE_MISSING = 'M'.code.toByte()
        private val SHAPE_SET = byteArrayOf('S'.code.toByte())
        private const val MEMBER_PREFIX = '+'.code.toByte()

        private val FILLED: L1Fill = L1Fill.Filled(advanced = false)
        private val ADVANCED: L1Fill = L1Fill.Filled(advanced = true)
        private val TOKEN_MISMATCH: L1Fill = L1Fill.Rejected(currentVersion = null)

        /**
         * [RedisScripts.FILL] 的结果码：写入、版本拒绝（`0` 为令牌不匹配）。
         */
        private const val FILL_WRITTEN = 1L
        private const val FILL_VERSION_REJECTED = 2L

        /**
         * 重发释放的退避：首次重发的间隔与上限。
         */
        private val MIN_RELEASE_BACKOFF = Duration.ofMillis(100)
        private val MAX_RELEASE_BACKOFF = Duration.ofSeconds(2)

        /**
         * 请求中没有寿命的令牌（填充、释放交回的 lease）重发释放的时限：长于默认的 `lease.ttl`（10 s），过期之后的释放只是空操作。
         */
        private val UNKNOWN_TOKEN_LIFETIME = Duration.ofMinutes(1)

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
            is Envelope.Scalar -> RedisScripts.scalarShape(STATE_VALUE, version, envelope.writer, envelope.digest)
            Envelope.Absent -> RedisScripts.scalarShape(STATE_MISSING, version, null, null)
        }

        /**
         * 解码保留版本的失效的结果（见 [RedisScripts.EVICT_KEEPING_VERSION]）。
         */
        internal fun decodeEviction(result: List<Any?>): L1Eviction = when (result[0] as Long) {
            RedisScripts.EVICT_DONE -> L1Eviction.Evicted
            RedisScripts.EVICT_AUTHORITATIVE -> L1Eviction.Authoritative
            RedisScripts.EVICT_UNREADABLE -> L1Eviction.Unreadable((result[1] as Long).toInt())
            else -> throw IllegalStateException("Unknown eviction result code[${result[0]}].")
        }

        /**
         * 解码按版本写入脚本的结果（见 [RedisScripts.PUT_IF_NEWER]）：版本不更新时第二项为当前版本（8 字节）。
         */
        internal fun decodeVersionedWrite(result: List<Any?>): L1VersionedWrite = when (result[0] as Long) {
            RedisScripts.VERSION_WRITTEN -> L1VersionedWrite.Written
            RedisScripts.VERSION_STALE -> L1VersionedWrite.Stale(ByteBuffer.wrap(result[1] as ByteArray).long)
            RedisScripts.VERSION_UNREADABLE -> L1VersionedWrite.Unreadable((result[1] as Long).toInt())
            else -> throw IllegalStateException("Unknown versioned write result code[${result[0]}].")
        }

        /**
         * 解码条件读取脚本的结果（结果码见 [RedisScripts.READ_IF_CHANGED]）：值是整个 String，按 [decodeScalar] 解析出载荷、
         * 摘要段与写入标识（脚本已确认它是带摘要段、不带版本的本格式值）。
         */
        internal fun decodeConditionalRead(result: List<Any?>): L1ConditionalRead = when (result[0] as Long) {
            RedisScripts.COND_ABSENT -> L1ConditionalRead.Absent
            RedisScripts.COND_UNCHANGED -> L1ConditionalRead.Unchanged((result[1] as Long).toInt())
            RedisScripts.COND_VALUE -> conditionalValueOf(result[2] as ByteArray, (result[1] as Long).toInt())
            RedisScripts.COND_UNREADABLE -> L1ConditionalRead.Unreadable((result[1] as Long).toInt())
            else -> throw IllegalStateException("Unknown conditional read result code[${result[0]}].")
        }

        private fun conditionalValueOf(raw: ByteArray, format: Int): L1ConditionalRead {
            val hit = decodeScalar(raw, null, false)
            val envelope = hit.envelope as? Envelope.Scalar
            if (envelope == null || hit.format != format) {
                throw IllegalStateException("Malformed conditional read value (format[${hit.format}]).")
            }
            return L1ConditionalRead.Changed(envelope, format)
        }

        /**
         * 解码条件写入脚本的结果（见 [RedisScripts.PUT_IF_MATCH]）：冲突时第二项为当前摘要段（32 字节），没有值时缺省。
         */
        internal fun decodeConditionalWrite(result: List<Any?>): L1ConditionalWrite = when (result[0] as Long) {
            RedisScripts.PUT_WRITTEN -> L1ConditionalWrite.Written
            RedisScripts.PUT_CONFLICT -> L1ConditionalWrite.Conflict(
                (result.getOrNull(1) as ByteArray?)?.let { Digest(it) }
            )
            RedisScripts.PUT_UNREADABLE -> L1ConditionalWrite.Unreadable((result[1] as Long).toInt())
            else -> throw IllegalStateException("Unknown conditional write result code[${result[0]}].")
        }

        private const val BYTE_MASK = 0xFF

        private fun ttlOf(pttl: Long): Duration? = if (pttl < 0) null else Duration.ofMillis(pttl.coerceAtLeast(1))

        /**
         * 解码读脚本结果（结果码见 [RedisScripts]）。无法识别的内容以格式版本 `0` 返回，由引擎按损坏处理。
         */
        @Suppress("UNCHECKED_CAST")
        internal fun decodeRead(result: List<Any?>): L1Read {
            return when (result[0] as Long) {
                RedisScripts.RESULT_MISS -> L1Read.Miss(result[1] == 1L)
                RedisScripts.RESULT_LEASED -> L1Read.Leased(Duration.ofMillis((result[1] as Long).coerceAtLeast(0)))
                RedisScripts.RESULT_SCALAR -> decodeScalar(
                    result[2] as ByteArray,
                    ttlOf(result[1] as Long),
                    result[3] == 1L
                )
                RedisScripts.RESULT_SET -> decodeSet(
                    result[2] as List<ByteArray>,
                    result[3] as ByteArray,
                    result[4] as ByteArray,
                    ttlOf(result[1] as Long)
                )
                else -> throw IllegalStateException("Unknown read result code[${result[0]}].")
            }
        }

        /**
         * 标量的解码，分类与脚本的 `scalar_format`（[RedisScripts]）一致：先看格式号、再看标志位与状态（RFC 0005 §4.7，P1、P18）。
         * - 不是本协议写入的内容（不以 magic 开头、太短、格式字节为 0、段超出值的长度）：格式 [UNRECOGNIZED]；其它格式字节
         *   原样报告。信封都是整个值：低于本格式的按读不懂处理，回收比较的指纹与脚本相同。
         * - 格式 2 中未知的必须理解的位或未知的状态：[RedisScripts.NEWER_FORMAT]。更高的格式由引擎绕过，信封不被解码。
         * - 本格式：载荷从 [RedisScripts.payloadOffset] 起，版本段、摘要段与扩展区中的写入标识随信封返回。`E` 与 `L` 由读脚本
         *   换成未命中与 lease，不会到这里。
         */
        private fun decodeScalar(raw: ByteArray, ttl: Duration?, refreshGranted: Boolean): L1Read.Hit {
            if (raw.size < RedisScripts.HEADER_SIZE || raw[0] != RedisScripts.MAGIC) {
                return L1Read.Hit(Envelope.Scalar(raw), UNRECOGNIZED, ttl)
            }
            // 无符号（与 Lua 的 string.byte 一致）：有符号时 0x80 以上的更高格式成了负数，被当作读不懂的内容回收
            val format = raw[1].toInt() and BYTE_MASK
            if (format != RedisScripts.FORMAT) {
                return L1Read.Hit(Envelope.Scalar(raw), format, ttl)
            }
            val flags = raw[RedisScripts.FLAGS_OFFSET].toInt() and BYTE_MASK
            val state = raw[RedisScripts.STATE_OFFSET]
            if (flags and RedisScripts.UNKNOWN_FLAGS != 0 || (state != STATE_VALUE && state != STATE_MISSING)) {
                return L1Read.Hit(Envelope.Scalar(raw), RedisScripts.NEWER_FORMAT, ttl)
            }
            val offset = RedisScripts.payloadOffset(raw, flags)
            if (offset < 0) {
                return L1Read.Hit(Envelope.Scalar(raw), UNRECOGNIZED, ttl)
            }
            if (state == STATE_MISSING) {
                return L1Read.Hit(Envelope.Absent, format, ttl)
            }
            val version = if (flags and RedisScripts.FLAG_VERSION != 0) {
                ByteBuffer.wrap(raw, RedisScripts.HEADER_SIZE, RedisScripts.VERSION_SIZE).long
            } else {
                Versioned.NO_VERSION
            }
            val writer = if (flags and RedisScripts.FLAG_EXTENSION != 0) {
                RedisScripts.writerOf(raw, RedisScripts.extensionOffset(flags))
            } else {
                null
            }
            val digest = if (flags and RedisScripts.FLAG_DIGEST != 0) {
                val start = RedisScripts.digestOffset(flags)
                Digest(raw.copyOfRange(start, start + RedisScripts.DIGEST_SIZE))
            } else {
                null
            }
            return L1Read.Hit(
                Envelope.Scalar(raw.copyOfRange(offset, raw.size), version, writer, digest),
                format,
                ttl,
                refreshGranted
            )
        }

        /**
         * 集合：[fields] 为 Hash 的全部字段名（`HKEYS`），[format] 与 [mustUnderstand] 为 `!f` 与 `!m` 的值（字段不存在时为空串），
         * 格式按 [RedisScripts.setFormat]（K10）。只取 `+` 前缀的成员字段，以 `!` 开头的元数据字段与其它字段忽略。
         */
        private fun decodeSet(
            fields: List<ByteArray>,
            format: ByteArray,
            mustUnderstand: ByteArray,
            ttl: Duration?
        ): L1Read.Hit {
            val members = ArrayList<ByteArray>(fields.size)
            for (field in fields) {
                if (field.isNotEmpty() && field[0] == MEMBER_PREFIX) {
                    members.add(field.copyOfRange(1, field.size))
                }
            }
            return L1Read.Hit(Envelope.Members(members), RedisScripts.setFormat(format, mustUnderstand), ttl)
        }

        /**
         * 无法识别的内容（不是本协议写入的值）：引擎按读不懂处理（有数据源时条件回收后回源，没有时失败）。
         */
        const val UNRECOGNIZED = RedisScripts.UNRECOGNIZED

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
