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

package me.ahoo.cocache.core.engine

import io.github.oshai.kotlinlogging.KLogger
import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.Fill
import me.ahoo.cocache.api.spi.L1Read
import me.ahoo.cocache.api.spi.Token
import me.ahoo.cocache.core.CacheMetrics
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个标量引擎在 L1 中的内容（RFC 0005 §4.7），由 [CoherentEngine] 持有：按副本类型（[Role]）写入哪些段、读取方的摘要校验（K3）与
 * 类型不符（D4）、命中判定（[hitOutcome]）、编解码与回源写回的填充。按角色派生的标志集中在这里（[authoritative]、[versioned]）。
 * 不在 L2 命中路径上：只有读到 L1 与写入 L1 的路径使用它。
 *
 * @param versioned 引擎的值是否为 `Versioned<V?>`（带版本的类型）
 * @param backendFormat 本节点的格式版本（`CoherenceBackend.format`）
 * @param nextToken 新的写入标识（与令牌同形，全局唯一）
 * @param log 引擎的日志（告警沿用引擎的日志名）
 */
@Suppress("LongParameterList")
internal class ScalarContent<V : Any>(
    private val name: String,
    role: Role,
    val versioned: Boolean,
    private val format: ValueFormat<V>,
    private val metrics: CacheMetrics,
    private val log: KLogger,
    private val backendFormat: Int,
    private val nextToken: () -> Token,
) {
    /**
     * 权威副本（[Role.authoritative]，RFC 0005 §4.7）：每次标量写入都带一个新的写入标识，读取不申请 lease，读取方校验摘要（[digestProblem]）。
     * 派生副本不带任何段，L1 头部保持 36 字节。
     */
    val authoritative: Boolean = role.authoritative

    /**
     * 不带版本的标量写入是否带载荷的摘要段：只有 `Store`（D1）。
     */
    private val writesDigest: Boolean = role == Role.STORE

    /**
     * 类型不符（D4，[verifyDigest]）只在第一次告警，之后只计数：同一个 namespace 上的误声明会在每次 L1 读取时出现。
     */
    private val typeMismatchWarned = AtomicBoolean()

    /**
     * 同步与异步共用的命中判定：更高的格式（[AbstractEngine.isNewerFormat]）既不回收也不写回；格式号低于本节点格式的内容（不是本格式写入的）、
     * 摘要校验失败（[verifyDigest]）或解码失败按损坏处理。
     */
    @Suppress("TooGenericExceptionCaught")
    fun hitOutcome(storageKey: String, read: L1Read.Hit): HitOutcome<V> {
        if (read.format > backendFormat) {
            return HitOutcome.Newer
        }
        return try {
            check(
                read.format == backendFormat
            ) { "Unrecognized remote content (format[${read.format}], not written in this node's format)." }
            verifyDigest(storageKey, read.envelope)
            HitOutcome.Decoded(decode(read.envelope))
        } catch (e: Exception) {
            HitOutcome.Corrupt(e)
        }
    }

    /**
     * 读取方的摘要校验（RFC 0005 §4.7，K3；同步与异步共用，对所有后端相同）：权威副本（[authoritative]）从 L1 读到的不带版本的值
     * 必须带摘要段，且与载荷重算的 SHA-256 一致；`Store` 读到带版本的值同样不接受。否则抛出，调用方按损坏处理（权威副本没有数据源，读取失败）。
     * 派生副本读到带摘要段的值是类型不符（D4）：计入 `typeMismatches` 并告警，照常读取。成本只在 L1 读取上，不在 L2 命中上。
     */
    fun verifyDigest(storageKey: String, envelope: Envelope) {
        digestProblem(envelope)?.let { throw IllegalStateException("Cache[$name] - key[$storageKey] - $it") }
        if (!authoritative && (envelope as? Envelope.Scalar)?.digest != null) {
            typeMismatch(storageKey)
        }
    }

    /**
     * 权威副本读到的值的问题（不计数，运维查看也用它）；没有问题时为 `null`。负缓存没有载荷，不校验；带版本的值不带摘要段，
     * 只有带版本的类型（`VersionedStore`）读到它是正常的，`Store` 读到它说明同一 namespace 上另有带版本的类型，按读不懂处理。
     */
    fun digestProblem(envelope: Envelope): String? {
        if (!authoritative || envelope !is Envelope.Scalar) {
            return null
        }
        val digest = envelope.digest
        if (digest == null) {
            return when {
                envelope.version == Versioned.NO_VERSION ->
                    "value without a digest segment in an authoritative cache (not written by a Store)."
                versioned -> null
                else -> "versioned value in a Store (written by a VersionedCache or a VersionedStore)."
            }
        }
        return if (Digest.of(envelope) == digest) null else "payload does not match its digest segment."
    }

    private fun typeMismatch(storageKey: String) {
        typeMismatch {
            "Cache[$name] - key[$storageKey] - holds a value written by a Store (digest segment): " +
                "the namespace is shared with an authoritative cache. It is read, but never reclaimed or refreshed."
        }
    }

    /**
     * 类型不符（D4）：计入 `typeMismatches`；每个缓存只在第一次告警，之后记为 DEBUG（命名空间配置错误时每次读取或 `evict` 都会遇到它）。
     */
    fun typeMismatch(message: () -> Any?) {
        metrics.typeMismatch()
        if (typeMismatchWarned.compareAndSet(false, true)) log.warn(message) else log.debug(message)
    }

    fun decode(envelope: Envelope): V? = when (envelope) {
        is Envelope.Scalar -> format.decode(envelope)
        Envelope.Absent -> null
        is Envelope.Members -> throw IllegalStateException("Cache[$name] - expected a scalar but found a set.")
    }

    /**
     * 写入 L1 的内容（RFC 0005 §4.7“写入哪些段”）：权威副本（[authoritative]）的值带一个新的写入标识，`Store` 的值另带载荷的摘要段
     * （[writesDigest]，D1）；带版本的值从不带摘要段。派生副本的值不带任何段（L1 头部保持 36 字节）。
     */
    fun envelopeOf(
        value: V?
    ): Envelope = value?.let { format.envelopeOf(it, writerId(), writesDigest) } ?: Envelope.Absent

    fun encode(value: V): Envelope.Scalar = format.encode(value, writerId(), writesDigest)

    /**
     * 一次标量写入的写入标识（与令牌同形，全局唯一）；派生副本为 `null`。
     */
    private fun writerId(): Token? = if (authoritative) nextToken() else null

    /**
     * 回源写回 L1 的填充：与内容一起带上值的版本（带版本的缓存；墓碑的版本只能经 [Fill.version] 传递）。
     */
    fun fillOf(storageKey: String, token: Token, value: V?, envelope: Envelope, ttl: Duration?): Fill =
        Fill(storageKey, token, envelope, ttl, versionOf(value))

    fun versionOf(value: V?): Long = value?.let { format.versionOf(it) } ?: Versioned.NO_VERSION

    /**
     * 带摘要段的值（权威标量）不设刷新点：`READ` 从不为它授予刷新令牌（D4），刷新只会一次次重读 L1。
     */
    fun refreshable(envelope: Envelope): Boolean = (envelope as? Envelope.Scalar)?.digest == null

    /**
     * 运维查看：权威副本先校验摘要（不计数），不一致时按读不懂展示。
     */
    fun describe(envelope: Envelope): Any? {
        digestProblem(envelope)?.let { throw IllegalStateException(it) }
        return decode(envelope)
    }
}

/**
 * 读到值时的判定（同步与异步共用）。
 */
internal sealed interface HitOutcome<out V> {
    data object Newer : HitOutcome<Nothing>

    class Corrupt(val error: Exception) : HitOutcome<Nothing>

    class Decoded<V>(val value: V?) : HitOutcome<V>
}
