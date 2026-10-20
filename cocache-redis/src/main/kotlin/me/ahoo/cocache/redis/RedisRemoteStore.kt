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
import me.ahoo.cocache.api.spi.AsyncRemoteStore
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.Put
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.Token
import java.time.Duration
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * 一个命名空间的 L1 存储：每个操作一次 Lua 往返（[RedisScripts]）。
 *
 * 原生异步（RFC 0004 §4.3）：每个操作只构造一次命令（`xxxCommand`），同步方法等待它，异步方法以与同步等待相同的超时与异常类型
 * 返回它（[RedisConnections.bounded]），同步与异步只有一份协议实现。异步结果在 Lettuce 的 I/O 线程上完成，引擎负责切换线程。
 *
 * @param channel 原子广播的频道；为空时不在脚本中广播（后端不支持原子广播，由引擎经通道发布）
 */
internal class RedisRemoteStore(
    private val connections: RedisConnections,
    private val channel: ByteArray,
    private val invalidateBatchSize: Int,
) : AsyncRemoteStore {

    override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead {
        return decodeRead(connections.await(readCommand(key, lease, refresh)))
    }

    override fun readAsync(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<RemoteRead> =
        connections.mapped(connections.bounded(readCommand(key, lease, refresh))) { decodeRead(it) }

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
     * 并发发出，在共享连接上多路复用（约一次往返）。
     *
     * 等待全部结果：任一失败时，其余读取取得的 lease 要先释放（不等待）再抛出第一个异常，
     * 否则调用方拿不到这些令牌，所有节点都要白白等待 `lease.ttl`。
     */
    @Suppress("TooGenericExceptionCaught")
    override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> {
        val futures = keys.associateWith { readCommand(it, leases[it], null) }
        val results = LinkedHashMap<String, RemoteRead>(futures.size)
        var failure: RuntimeException? = null
        for ((key, future) in futures) {
            try {
                results[key] = decodeRead(connections.await(future))
            } catch (e: RuntimeException) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        val error = failure ?: return results
        releaseGranted(results, leases)
        throw error
    }

    /**
     * [readAll] 的异步版本：等全部读取完成再判定；任一失败时先尽力释放其余读取取得的 lease，再以第一个异常失败。
     */
    @Suppress("TooGenericExceptionCaught")
    override fun readAllAsync(
        keys: Collection<String>,
        leases: Map<String, LeaseRequest>
    ): CompletableFuture<Map<String, RemoteRead>> {
        val futures = keys.associateWith { key ->
            connections.mapped(connections.bounded(readCommand(key, leases[key], null))) { decodeRead(it) }
        }
        val result = CompletableFuture<Map<String, RemoteRead>>()
        CompletableFuture.allOf(*futures.values.toTypedArray()).whenComplete { _, _ ->
            // 处理体抛出时 result 也必须完成，否则调用方永远等不到结果
            try {
                val reads = LinkedHashMap<String, RemoteRead>(futures.size)
                var failure: Throwable? = null
                for ((key, future) in futures) {
                    val (read, error) = outcome(future)
                    if (error == null) {
                        reads[key] = requireNotNull(read)
                    } else {
                        val first = failure
                        if (first == null) failure = error else first.addSuppressed(error)
                    }
                }
                val error = failure
                if (error == null) {
                    result.complete(reads)
                } else {
                    releaseGranted(reads, leases)
                    result.completeExceptionally(error)
                }
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    private fun releaseGranted(reads: Map<String, RemoteRead>, leases: Map<String, LeaseRequest>) {
        reads.forEach { (key, read) ->
            val lease = leases[key]
            if (read is RemoteRead.Miss && read.leaseGranted && lease != null) {
                releaseQuietly(key, lease.token)
            }
        }
    }

    /**
     * 尽力释放令牌（不等待）：失败只记录，lease 最终由 `lease.ttl` 回收。
     */
    private fun releaseQuietly(key: String, token: Token) {
        releaseCommand(key, token).whenComplete { _, error ->
            if (error != null) {
                log.warn(error) { "Release lease of key[$key] failed - reclaimed after lease ttl." }
            }
        }
    }

    override fun fill(fill: Fill): Boolean = connections.await(fillCommand(fill)).firstFlag()

    override fun fillAsync(fill: Fill): CompletableFuture<Boolean> =
        connections.mapped(connections.bounded(fillCommand(fill))) { it.firstFlag() }

    private fun fillCommand(fill: Fill): CompletableFuture<List<Any?>> {
        val args = ArrayList<ByteArray>()
        args.add(fill.token.toBytes())
        addShape(args, fill.envelope, fill.ttl)
        return connections.evalAsync(RedisScripts.FILL, listOf(fill.key.bytes()), args)
    }

    override fun fillAll(fills: List<Fill>): List<Boolean> {
        val futures = fills.map { fillCommand(it) }
        return futures.map { connections.await(it).firstFlag() }
    }

    override fun fillAllAsync(fills: List<Fill>): CompletableFuture<List<Boolean>> {
        val futures = fills.map { fillAsync(it) }
        return connections.mapped(allDone(futures)) { futures.map { it.join() } }
    }

    override fun release(key: String, token: Token) {
        connections.await(releaseCommand(key, token))
    }

    override fun releaseAsync(key: String, token: Token): CompletableFuture<Void?> =
        connections.mapped(connections.bounded(releaseCommand(key, token))) { null }

    private fun releaseCommand(key: String, token: Token): CompletableFuture<List<Any?>> =
        connections.evalAsync(RedisScripts.RELEASE, listOf(key.bytes()), listOf(token.toBytes()))

    override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
        connections.await(putCommand(key, envelope, ttl, frame))
    }

    override fun putAsync(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
        connections.mapped(connections.bounded(putCommand(key, envelope, ttl, frame))) { null }

    private fun putCommand(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray): CompletableFuture<List<Any?>> {
        val args = ArrayList<ByteArray>()
        args.add(kindOf(envelope).bytes())
        args.add(millis(ttl))
        args.add(channel)
        args.add(frame)
        addPayload(args, envelope)
        return connections.evalAsync(RedisScripts.PUT, listOf(key.bytes()), args)
    }

    /**
     * 与 [invalidate] 相同的分组：按 slot 分组（Cluster）再按 `invalidateBatchSize` 切分，每组一个 [RedisScripts.PUT_ALL]，
     * 只广播本组的 key，并发执行；原子性只在单个组内成立。
     */
    override fun putAll(puts: List<Put>, frame: ByteArray) {
        awaitAll(putAllCommands(puts, frame))
    }

    override fun putAllAsync(puts: List<Put>, frame: ByteArray): CompletableFuture<Void?> =
        allDone(putAllCommands(puts, frame).map { connections.bounded(it) })

    private fun putAllCommands(puts: List<Put>, frame: ByteArray): List<CompletableFuture<List<Any?>>> {
        val byKey = LinkedHashMap<String, Put>(puts.size)
        puts.forEach { byKey[it.key] = it }
        val putOf = IdentityHashMap<ByteArray, Put>(byKey.size)
        val keyBytes = byKey.values.map { put -> put.key.bytes().also { putOf[it] = put } }
        val groups = connections.groupBySlot(keyBytes).flatMap { it.chunked(invalidateBatchSize) }
        val frames = framesOf(byKey.keys, frame, groups)
        return groups.mapIndexed { index, group ->
            val args = ArrayList<ByteArray>()
            args.add(channel)
            args.add(frames[index])
            group.forEach { key ->
                val put = putOf.getValue(key)
                args.add(kindOf(put.envelope).bytes())
                args.add(millis(put.ttl))
                args.add(payloadCount(put.envelope).toString().bytes())
                addPayload(args, put.envelope)
            }
            connections.evalAsync(RedisScripts.PUT_ALL, group, args)
        }
    }

    override fun invalidate(keys: Collection<String>, frame: ByteArray) {
        awaitAll(invalidateCommands(keys, frame))
    }

    /**
     * 等待全部组结束后再抛出按发出顺序的第一个异常（其余附为 suppressed）：调用方在失败后尽力广播（非原子模式），
     * 广播不能先于仍在执行的组，否则其它节点可能在广播之后、该组写入之前从 L1 读到旧值并填充 L2。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun awaitAll(futures: List<CompletableFuture<List<Any?>>>) {
        var failure: Exception? = null
        futures.forEach { future ->
            try {
                connections.await(future)
            } catch (e: Exception) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    override fun invalidateAsync(keys: Collection<String>, frame: ByteArray): CompletableFuture<Void?> =
        allDone(invalidateCommands(keys, frame).map { connections.bounded(it) })

    private fun invalidateCommands(keys: Collection<String>, frame: ByteArray): List<CompletableFuture<List<Any?>>> {
        val groups = connections.groupBySlot(keys.map { it.bytes() }).flatMap { it.chunked(invalidateBatchSize) }
        val frames = framesOf(keys, frame, groups)
        return groups.mapIndexed { index, group ->
            connections.evalAsync(
                RedisScripts.INVALIDATE,
                group,
                listOf(channel, frames[index])
            )
        }
    }

    /**
     * 全部完成后完成；有失败时以按发出顺序的第一个异常失败（与同步版本逐个等待时抛出的异常一致）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun allDone(futures: List<CompletableFuture<*>>): CompletableFuture<Void?> {
        val result = CompletableFuture<Void?>()
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            try {
                val error = futures.firstNotNullOfOrNull { outcome(it).second }
                if (error == null) result.complete(null) else result.completeExceptionally(error)
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    private fun <T> outcome(future: CompletableFuture<T>): Pair<T?, Throwable?> = try {
        future.getNow(null) to null
    } catch (e: CompletionException) {
        null to (e.cause ?: e)
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

    override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean =
        connections.await(reclaimCommand(key, observed, lease)).firstFlag()

    override fun reclaimAsync(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<Boolean> =
        connections.mapped(connections.bounded(reclaimCommand(key, observed, lease))) { it.firstFlag() }

    private fun reclaimCommand(key: String, observed: Digest, lease: LeaseRequest): CompletableFuture<List<Any?>> {
        val args = listOf(observed.toString().bytes(), lease.token.toBytes(), millis(lease.ttl))
        return connections.evalAsync(RedisScripts.RECLAIM, listOf(key.bytes()), args)
    }

    override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
        connections.await(membersCommand(RedisScripts.ADD_MEMBERS, key, members, ttl, frame))
    }

    override fun addMembersAsync(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): CompletableFuture<Void?> =
        connections.mapped(
            connections.bounded(membersCommand(RedisScripts.ADD_MEMBERS, key, members, ttl, frame))
        ) { null }

    override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
        connections.await(membersCommand(RedisScripts.REMOVE_MEMBERS, key, members, ttl, frame))
    }

    override fun removeMembersAsync(
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<Void?> =
        connections.mapped(
            connections.bounded(membersCommand(RedisScripts.REMOVE_MEMBERS, key, members, ttl, frame))
        ) { null }

    private fun membersCommand(
        script: RedisScripts.Script,
        key: String,
        members: Collection<ByteArray>,
        ttl: Duration?,
        frame: ByteArray
    ): CompletableFuture<List<Any?>> = connections.evalAsync(
        script,
        listOf(key.bytes()),
        memberArgs(ttl, frame, members)
    )

    private fun memberArgs(ttl: Duration?, frame: ByteArray, members: Collection<ByteArray>): List<ByteArray> {
        val args = ArrayList<ByteArray>(members.size + 3)
        args.add(millis(ttl))
        args.add(channel)
        args.add(frame)
        args.addAll(members)
        return args
    }

    private fun addShape(args: MutableList<ByteArray>, envelope: Envelope, ttl: Duration?) {
        args.add(kindOf(envelope).bytes())
        args.add(millis(ttl))
        addPayload(args, envelope)
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

    private fun kindOf(envelope: Envelope): String = when (envelope) {
        is Envelope.Scalar -> "V"
        is Envelope.Members -> "S"
        Envelope.Absent -> "M"
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private val EMPTY = ByteArray(0)
        private const val MEMBER_PREFIX = '+'.code.toByte()
        private const val STATE_VALUE = 'V'.code.toByte()
        private const val STATE_MISSING = 'M'.code.toByte()

        private fun String.bytes(): ByteArray = toByteArray(Charsets.UTF_8)

        /**
         * 毫秒（至少 1），`null` 为 `0`（永不过期）。
         */
        private fun millis(
            duration: Duration?
        ): ByteArray = (duration?.toMillis()?.coerceAtLeast(1) ?: 0L).toString().bytes()

        private fun List<Any?>.firstFlag(): Boolean = firstOrNull() == 1L

        private fun ttlOf(pttl: Long): Duration? = if (pttl < 0) null else Duration.ofMillis(pttl.coerceAtLeast(1))

        /**
         * 解码读脚本结果（结果码见 [RedisScripts]）。无法识别的内容以格式版本 `0` 返回，由引擎按损坏处理。
         */
        @Suppress("UNCHECKED_CAST")
        internal fun decodeRead(result: List<Any?>): RemoteRead {
            return when (result[0] as Long) {
                RedisScripts.RESULT_MISS -> RemoteRead.Miss(result[1] == 1L)
                RedisScripts.RESULT_LEASED -> RemoteRead.Leased(Duration.ofMillis((result[1] as Long).coerceAtLeast(0)))
                RedisScripts.RESULT_SCALAR -> decodeScalar(
                    result[2] as ByteArray,
                    ttlOf(result[1] as Long),
                    result[3] == 1L
                )
                RedisScripts.RESULT_SET -> decodeSet(
                    result[2] as List<ByteArray>,
                    result[3] as ByteArray,
                    ttlOf(result[1] as Long)
                )
                else -> throw IllegalStateException("Unknown read result code[${result[0]}].")
            }
        }

        private fun decodeScalar(raw: ByteArray, ttl: Duration?, refreshGranted: Boolean): RemoteRead.Hit {
            if (raw.size < RedisScripts.HEADER_SIZE || raw[0] != RedisScripts.MAGIC) {
                return RemoteRead.Hit(Envelope.Scalar(raw), UNRECOGNIZED, ttl)
            }
            val format = raw[1].toInt()
            return when (raw[2]) {
                STATE_VALUE -> RemoteRead.Hit(
                    Envelope.Scalar(raw.copyOfRange(RedisScripts.HEADER_SIZE, raw.size)),
                    format,
                    ttl,
                    refreshGranted
                )
                STATE_MISSING -> RemoteRead.Hit(Envelope.Absent, format, ttl)
                else -> RemoteRead.Hit(
                    Envelope.Scalar(raw.copyOfRange(RedisScripts.HEADER_SIZE, raw.size)),
                    UNRECOGNIZED,
                    ttl
                )
            }
        }

        /**
         * 集合：[fields] 为 Hash 的全部字段名（`HKEYS`），[format] 为 `!f` 的值（字段不存在时为空串）。
         * 只取 `+` 前缀的成员字段，`!f` 与其它字段忽略。
         */
        private fun decodeSet(fields: List<ByteArray>, format: ByteArray, ttl: Duration?): RemoteRead.Hit {
            val members = ArrayList<ByteArray>(fields.size)
            for (field in fields) {
                if (field.isNotEmpty() && field[0] == MEMBER_PREFIX) {
                    members.add(field.copyOfRange(1, field.size))
                }
            }
            val version = String(format, Charsets.UTF_8).toIntOrNull() ?: UNRECOGNIZED
            return RemoteRead.Hit(Envelope.Members(members), version, ttl)
        }

        /**
         * 无法识别的内容（不是 CoCache 6.0 写入的值）：引擎按损坏处理（条件回收后回源）。
         */
        const val UNRECOGNIZED = 0
    }
}
