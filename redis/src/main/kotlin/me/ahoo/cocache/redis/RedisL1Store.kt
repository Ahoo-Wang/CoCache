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
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.L1Store
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteConditionalRead
import me.ahoo.cocache.api.spi.RemoteConditionalWrite
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteVersionedWrite
import me.ahoo.cocache.api.spi.SetL1Store
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.api.spi.VersionedL1Store
import java.nio.ByteBuffer
import java.time.Duration
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

/**
 * 一个命名空间的 L1 存储，原生挂起（RFC 0005 §4.3）：每个操作一次 Lua 往返（[RedisScripts]），以 Lettuce 的异步命令发出、
 * 挂起等待结果（[RedisConnections.awaitCommand]）。等待期间不占线程，续接经调用方的调度器恢复，不在 Lettuce 的 I/O 线程上继续；
 * 线格式与 [RedisRemoteStore] 相同（同一组脚本与参数）。
 *
 * - **限时**：每条命令受 `commandTimeout` 限制；超时时尚未写出的脚本命令不再写出（`CommandCall`）。
 * - **批量**：[readAll]、[fillAll] 先发出全部命令再一起等待（共享连接上多路复用，约一次往返）；[putAll]、[invalidate] 按 slot 分组
 *   （Cluster）再按 `invalidateBatchSize` 切分，各组并发执行，全部结束之后才返回或抛出。
 * - **令牌**：取得或交回令牌的命令（读取、回收、填充、释放）结果未知时（[RedisConnections.isNotExecuted] 之外的失败），尽力释放
 *   该令牌：异步发出，不等待。释放与原命令按 key 路由到同一连接、排在它之后；令牌不匹配时释放什么也不做。
 * - **取消**：Lettuce 取消的命令（连接重置、关闭）以结果未知的 [io.lettuce.core.RedisException] 失败，从不以 `CancellationException` 报告。
 *
 * 提供全部三个能力面：条件读写、按版本有序的写入（[RedisScripts.PUT_IF_NEWER]，一次往返）与集合成员的增删。
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

    override suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead {
        val command = readCommand(key, lease, refresh)
        return releasingOnFailure(key, lease?.token, refresh?.token) {
            RedisRemoteStore.decodeRead(connections.awaitCommand(command))
        }
    }

    /**
     * 先发出全部读取再一起等待。任一失败时，其余读取取得的 lease 先释放（不等待）再抛出第一个异常（其余附为 suppressed）：
     * 否则调用方拿不到这些令牌，所有节点都要白白等待 `lease.ttl`；结果未知的读取同样尽力释放自己的 lease。
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> {
        if (keys.isEmpty()) {
            return emptyMap()
        }
        // 重复的 key 只读一次：否则第二次读到的是第一次取得的 lease，第一次的令牌无人交回
        val distinct = keys as? Set<String> ?: LinkedHashSet(keys)
        val outcomes = connections.awaitCommands(distinct.map { readCommand(it, leases[it], null) })
        val reads = LinkedHashMap<String, RemoteRead>(distinct.size)
        var failure: Throwable? = null
        distinct.forEachIndexed { index, key ->
            try {
                reads[key] = RedisRemoteStore.decodeRead(outcomes[index].getOrThrow())
            } catch (e: RuntimeException) {
                releaseIfUnknown(key, leases[key]?.token, null, e)
                failure = failure.suppressing(e)
            }
        }
        val error = failure ?: return reads
        reads.forEach { (key, read) ->
            val lease = leases[key]
            if (read is RemoteRead.Miss && read.leaseGranted && lease != null) {
                releaseQuietly(key, lease.token)
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
    override suspend fun fill(fill: Fill): Boolean {
        val command = fillCommand(fill)
        return releasingOnFailure(fill.key, fill.token, null) { connections.awaitCommand(command).firstFlag() }
    }

    /**
     * 先发出全部填充再一起等待，结果与 [fills] 一一对应。任一失败时等全部结束再抛出第一个异常（其余附为 suppressed），
     * 结果未知的填充各自尽力释放令牌。
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun fillAll(fills: List<Fill>): List<Boolean> {
        if (fills.isEmpty()) {
            return emptyList()
        }
        val outcomes = connections.awaitCommands(fills.map { fillCommand(it) })
        val filled = ArrayList<Boolean>(fills.size)
        var failure: Throwable? = null
        fills.forEachIndexed { index, fill ->
            try {
                filled.add(outcomes[index].getOrThrow().firstFlag())
            } catch (e: RuntimeException) {
                releaseIfUnknown(fill.key, fill.token, null, e)
                failure = failure.suppressing(e)
            }
        }
        failure?.let { throw it }
        return filled
    }

    private fun fillCommand(fill: Fill): CompletableFuture<List<Any?>> {
        val args = ArrayList<ByteArray>()
        args.add(fill.token.toBytes())
        // 填充的版本（墓碑的版本只能经 Fill.version 传递）
        args.add(shapeOf(fill.envelope, fill.version))
        args.add(millis(fill.ttl))
        addPayload(args, fill.envelope)
        return connections.evalAsync(RedisScripts.FILL, listOf(fill.key.bytes()), args)
    }

    /**
     * 结果未知时再尽力释放一次：超时的释放被取消，尚未写出时永远不会执行；已执行时重复的释放什么也不做。
     */
    override suspend fun release(key: String, token: Token) {
        val command = releaseCommand(key, token)
        releasingOnFailure(key, token, null) { connections.awaitCommand(command) }
    }

    private fun releaseCommand(key: String, token: Token): CompletableFuture<List<Any?>> =
        connections.evalAsync(RedisScripts.RELEASE, listOf(key.bytes()), listOf(token.toBytes()))

    /**
     * 回收成功时取得 lease：结果未知时与读取一样尽力释放。
     */
    override suspend fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean {
        val args = listOf(observed.toString().bytes(), lease.token.toBytes(), millis(lease.ttl))
        val command = connections.evalAsync<List<Any?>>(RedisScripts.RECLAIM, listOf(key.bytes()), args)
        return releasingOnFailure(key, lease.token, null) { connections.awaitCommand(command).firstFlag() }
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
        val commands = groups.mapIndexed { index, group ->
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
        throwFirstFailure(connections.awaitCommands(commands))
    }

    override suspend fun invalidate(keys: Collection<String>, frame: ByteArray) {
        val groups = groupsOf(keys.map { it.bytes() })
        val frames = framesOf(keys, frame, groups)
        val commands = groups.mapIndexed { index, group ->
            connections.evalAsync<List<Any?>>(RedisScripts.INVALIDATE, group, listOf(channel, frames[index]))
        }
        throwFirstFailure(connections.awaitCommands(commands))
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
    private fun throwFirstFailure(outcomes: List<Result<*>>) {
        var failure: Throwable? = null
        outcomes.forEach { outcome ->
            outcome.exceptionOrNull()?.let { failure = failure.suppressing(it) }
        }
        failure?.let { throw it }
    }

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
     * 执行取得或交回令牌的步骤 [block]（等待命令并解码结果）；失败时结果未知（[releaseIfUnknown]）则尽力释放 [first]、[second]，再原样抛出。
     * 解码失败同样释放：命令已执行，令牌可能已授予。
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> releasingOnFailure(key: String, first: Token?, second: Token?, block: () -> T): T {
        try {
            return block()
        } catch (e: RuntimeException) {
            releaseIfUnknown(key, first, second, e)
            throw e
        }
    }

    /**
     * 结果未知时（[RedisConnections.isNotExecuted] 之外的失败）尽力释放令牌：命令可能已在 Redis 端执行（读取、回收授予了 lease 或刷新令牌），
     * 也可能被取消、永远不会执行（fill 没有写出，lease 仍在；释放没有写出），调用方随后都不会再交回这些令牌，lease 留到 `lease.ttl`，
     * 全集群对该 key 的未命中都要先等 `lease.wait`。确定未执行时不释放：命令被拒绝时释放同样会被拒绝，lease 由 `lease.ttl` 回收。
     */
    private fun releaseIfUnknown(key: String, first: Token?, second: Token?, error: Throwable) {
        if ((first == null && second == null) || RedisConnections.isNotExecuted(error)) {
            return
        }
        first?.let { releaseQuietly(key, it) }
        second?.let { releaseQuietly(key, it) }
    }

    /**
     * 尽力释放令牌：异步发出，不等待；失败只记录，lease 最终由 `lease.ttl` 回收。发出时同步失败（如客户端资源已关闭）同样只记录：
     * 调用方抛出的必须是原来的失败，批量读取也要继续处理其余的 key。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun releaseQuietly(key: String, token: Token) {
        val release = try {
            releaseCommand(key, token)
        } catch (e: RuntimeException) {
            log.warn(e) { "Release token of key[$key] failed - reclaimed after lease ttl." }
            return
        }
        release.whenComplete { _, error ->
            if (error != null) {
                log.warn(error) { "Release token of key[$key] failed - reclaimed after lease ttl." }
            }
        }
    }

    private inner class ConditionalFacet : ConditionalL1Store {
        override suspend fun readIfChanged(key: String, known: Digest?): RemoteConditionalRead {
            val args = listOf(known?.toString()?.bytes() ?: EMPTY)
            return RedisRemoteStore.decodeConditionalRead(
                connections.awaitCommand(connections.evalAsync(RedisScripts.READ_IF_CHANGED, listOf(key.bytes()), args))
            )
        }

        override suspend fun putIfMatch(
            key: String,
            expected: Digest?,
            envelope: Envelope.Scalar,
            ttl: Duration?,
            frame: ByteArray
        ): RemoteConditionalWrite {
            val args = listOf(expected?.toString()?.bytes() ?: EMPTY, millis(ttl), channel, frame, envelope.bytes)
            return RedisRemoteStore.decodeConditionalWrite(
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
        ): RemoteVersionedWrite {
            val args = ArrayList<ByteArray>(5)
            args.add(
                when (envelope) {
                    is Envelope.Scalar -> versionedShape(STATE_VALUE, version)
                    Envelope.Absent -> versionedShape(STATE_MISSING, version)
                    // 发出之前同步失败：确定未执行
                    is Envelope.Members -> throw IllegalArgumentException(
                        "putIfNewer takes a scalar value or Envelope.Absent, not a set."
                    )
                }
            )
            args.add(millis(ttl))
            args.add(channel)
            args.add(frame)
            if (envelope is Envelope.Scalar) {
                args.add(envelope.bytes)
            }
            return RedisRemoteStore.decodeVersionedWrite(
                connections.awaitCommand(connections.evalAsync(RedisScripts.PUT_IF_NEWER, listOf(key.bytes()), args))
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
        private val SHAPE_VALUE = byteArrayOf(STATE_VALUE)
        private val SHAPE_MISSING = byteArrayOf(STATE_MISSING)
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
         * 写脚本的形状参数（见 [RedisScripts.PRELUDE]）：`V` / `S` / `M`；带版本的值后跟 8 字节版本。
         */
        private fun shapeOf(envelope: Envelope): ByteArray =
            shapeOf(envelope, (envelope as? Envelope.Scalar)?.version ?: Versioned.NO_VERSION)

        /**
         * 带 [version] 的形状：值与负缓存带版本时后跟 8 字节版本（负缓存带版本即墓碑）；集合没有版本。
         */
        private fun shapeOf(envelope: Envelope, version: Long): ByteArray {
            if (envelope is Envelope.Members) {
                return SHAPE_SET
            }
            val state = if (envelope is Envelope.Scalar) STATE_VALUE else STATE_MISSING
            if (version != Versioned.NO_VERSION) {
                return versionedShape(state, version)
            }
            return if (state == STATE_VALUE) SHAPE_VALUE else SHAPE_MISSING
        }

        private fun versionedShape(state: Byte, version: Long): ByteArray =
            ByteBuffer.allocate(1 + RedisScripts.VERSION_SIZE).put(state).putLong(version).array()

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
