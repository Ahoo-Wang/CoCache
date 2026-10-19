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

import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.LeaseRequest
import me.ahoo.cocache.api.spi.RefreshRequest
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.api.spi.RemoteStore
import me.ahoo.cocache.api.spi.Token
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * 一个命名空间的 L1 存储：每个操作一次 Lua 往返（[RedisScripts]）。
 *
 * @param channel 原子广播的频道；为空时不在脚本中广播（后端不支持原子广播，由引擎经通道发布）
 */
internal class RedisRemoteStore(
    private val connections: RedisConnections,
    private val channel: ByteArray,
    private val invalidateBatchSize: Int,
) : RemoteStore {

    override fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead {
        return decodeRead(connections.await(readAsync(key, lease, refresh)))
    }

    private fun readAsync(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): CompletableFuture<List<Any?>> {
        val args = listOf(
            lease?.token?.toBytes() ?: EMPTY,
            millis(lease?.ttl),
            refresh?.window?.let { millis(it) } ?: EMPTY,
            refresh?.token?.toBytes() ?: EMPTY,
            millis(refresh?.tokenTtl),
        )
        return connections.evalAsync(RedisScripts.READ, listOf(key.bytes()), args)
    }

    override fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead> {
        // 并发发出，在共享连接上多路复用（约一次往返）
        val futures = keys.associateWith { readAsync(it, leases[it], null) }
        return futures.mapValues { (_, future) -> decodeRead(connections.await(future)) }
    }

    override fun fill(fill: Fill): Boolean = connections.await(fillAsync(fill)).firstFlag()

    private fun fillAsync(fill: Fill): CompletableFuture<List<Any?>> {
        val args = ArrayList<ByteArray>()
        args.add(fill.token.toBytes())
        addShape(args, fill.envelope, fill.ttl)
        return connections.evalAsync(RedisScripts.FILL, listOf(fill.key.bytes()), args)
    }

    override fun fillAll(fills: List<Fill>): List<Boolean> {
        val futures = fills.map { fillAsync(it) }
        return futures.map { connections.await(it).firstFlag() }
    }

    override fun release(key: String, token: Token) {
        connections.eval<List<Any?>>(RedisScripts.RELEASE, listOf(key.bytes()), listOf(token.toBytes()))
    }

    override fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray) {
        val args = ArrayList<ByteArray>()
        args.add(kindOf(envelope).bytes())
        args.add(millis(ttl))
        args.add(channel)
        args.add(frame)
        addPayload(args, envelope)
        connections.eval<List<Any?>>(RedisScripts.PUT, listOf(key.bytes()), args)
    }

    override fun invalidate(keys: Collection<String>, frame: ByteArray) {
        val groups = connections.groupBySlot(keys.map { it.bytes() }).flatMap { it.chunked(invalidateBatchSize) }
        val futures = groups.map { group ->
            connections.evalAsync<List<Any?>>(
                RedisScripts.INVALIDATE,
                group,
                listOf(channel, frame)
            )
        }
        futures.forEach { connections.await(it) }
    }

    override fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean {
        val args = listOf(observed.toString().bytes(), lease.token.toBytes(), millis(lease.ttl))
        return connections.eval<List<Any?>>(RedisScripts.RECLAIM, listOf(key.bytes()), args).firstFlag()
    }

    override fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
        connections.eval<List<Any?>>(RedisScripts.ADD_MEMBERS, listOf(key.bytes()), memberArgs(ttl, frame, members))
    }

    override fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray) {
        connections.eval<List<Any?>>(RedisScripts.REMOVE_MEMBERS, listOf(key.bytes()), memberArgs(ttl, frame, members))
    }

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

    private fun kindOf(envelope: Envelope): String = when (envelope) {
        is Envelope.Scalar -> "V"
        is Envelope.Members -> "S"
        Envelope.Absent -> "M"
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val FIELD_FORMAT = "!f"
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
                RedisScripts.RESULT_SET -> decodeSet(result[2] as List<ByteArray>, ttlOf(result[1] as Long))
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

        private fun decodeSet(flat: List<ByteArray>, ttl: Duration?): RemoteRead.Hit {
            var format = UNRECOGNIZED
            val members = ArrayList<ByteArray>(flat.size / 2)
            for (index in flat.indices step 2) {
                val field = flat[index]
                if (field.isNotEmpty() && field[0] == MEMBER_PREFIX) {
                    members.add(field.copyOfRange(1, field.size))
                } else if (String(field, Charsets.UTF_8) == FIELD_FORMAT) {
                    format = String(flat[index + 1], Charsets.UTF_8).toIntOrNull() ?: UNRECOGNIZED
                }
            }
            return RemoteRead.Hit(Envelope.Members(members), format, ttl)
        }

        /**
         * 无法识别的内容（不是 CoCache 6.0 写入的值）：引擎按损坏处理（条件回收后回源）。
         */
        const val UNRECOGNIZED = 0
    }
}
