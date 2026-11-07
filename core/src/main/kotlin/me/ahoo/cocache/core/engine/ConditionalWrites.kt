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

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.ConditionalDelete
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cocache.api.spi.Envelope
import me.ahoo.cocache.api.spi.L1ConditionalRead
import me.ahoo.cocache.api.spi.L1ConditionalWrite
import me.ahoo.cocache.core.internal.uncancellable
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration
import java.util.concurrent.CancellationException

/**
 * 条件读写（按摘要比较，RFC 0005 §4.6，I-19），只由 [StoreEngine] 持有：其它类型够不到它（I-A 由结构保证）。只针对 L1，
 * 不读写 L2、不回源、不申请 lease。读不懂的内容从不视为没有值：不是本协议写入的内容（格式 0）、本协议的集合、更高格式版本、
 * 解码失败的值，读取抛出；写入抛出，或因摘要不符而冲突。写入方顺序同 `put`（[CoherentEngine.write]）。
 */
internal class ConditionalWrites<K : Any, V : Any>(private val engine: CoherentEngine<K, V>) {

    /**
     * 条件读取（同步与异步共用的协程核心）。
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun getIfChanged(key: K, known: Digest?): ConditionalGet<V> {
        val conditional = engine.requireConditional("getIfChanged")
        val storageKey = engine.storageKey(key)
        val read = try {
            uncancellable { conditional.readIfChanged(storageKey, known, engine.policy.readTimeout) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw conditionalReadFailure(e)
        }
        return conditionalGetOf(storageKey, read)
    }

    /**
     * 条件读取没有可降级的答案（它确认的就是 L1）：无论 `unknownRead` 如何都抛出。
     */
    private fun conditionalReadFailure(error: Exception): CacheReadException {
        engine.metrics.remoteFailure()
        return CacheReadException(engine.name, "getIfChanged", error)
    }

    /**
     * 同步与异步共用：把后端的条件读取结果换算为用户结果。格式不是本节点的格式、集合、解码失败都是读不懂的内容。
     * 拿到载荷时（[L1ConditionalRead.Changed]）先重算摘要并与存储返回的摘要段比较（K3，[verifyConditional]）：不一致或缺摘要段按损坏失败，
     * 否则写入方或外部工具的缺陷留下的不一致，会让之后的 `getIfChanged` 对已知摘要一直回答 `Unchanged`。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun conditionalGetOf(storageKey: String, read: L1ConditionalRead): ConditionalGet<V> = when (read) {
        // 摘要只覆盖载荷：再确认这个值仍是本节点读得懂的格式
        is L1ConditionalRead.Unchanged -> if (read.format != engine.backendFormat) {
            unreadableGet(storageKey, read.format, null)
        } else {
            engine.metrics.conditionalUnchanged()
            ConditionalGet.Unchanged
        }

        L1ConditionalRead.Absent -> ConditionalGet.Absent
        is L1ConditionalRead.Unreadable -> unreadableGet(storageKey, read.format, null)
        is L1ConditionalRead.Changed -> if (read.format != engine.backendFormat) {
            unreadableGet(storageKey, read.format, null)
        } else {
            try {
                ConditionalGet.Present(engine.content.decodeValue(read.envelope), verifyConditional(read.envelope))
            } catch (e: Exception) {
                unreadableGet(storageKey, read.format, e)
            }
        }
    }

    /**
     * 条件读取拿到的值的摘要段（K3）：必须存在，且等于载荷重算的 SHA-256；否则抛出。
     */
    private fun verifyConditional(envelope: Envelope.Scalar): Digest {
        val digest = checkNotNull(envelope.digest) { "Conditional read returned a value without a digest segment." }
        check(Digest.of(envelope) == digest) { "Payload does not match its digest segment." }
        return digest
    }

    /**
     * 读不懂的内容：计入 `unknownFormats`，抛出（不修改 L1，[unknownFormat]），与条件写入的拒绝一致。
     */
    private fun unreadableGet(storageKey: String, format: Int, error: Exception?): ConditionalGet<V> =
        throw CacheReadException(engine.name, "getIfChanged", engine.unknownFormat(storageKey, format, error))

    /**
     * 与 [CoherentEngine.putWithTtl] 相同的写入顺序（戳、L2 淘汰在 L1 前后各一次）；冲突与拒绝不修改 L1。非原子广播的后端上拒绝不广播，
     * 冲突只在可能是重放时广播（[conditionalChanged]）。
     */
    suspend fun putIfMatch(key: K, expected: Digest?, value: V, ttl: Duration?): ConditionalPut {
        val conditional = engine.requireConditional("putIfMatch")
        val storageKey = engine.storageKey(key)
        val envelope = engine.content.encode(value)
        val bounded = ttl?.boundedTtl()
        var outcome: L1ConditionalWrite? = null
        engine.write(
            listOf(storageKey),
            { frame ->
                val result = conditional.putIfMatch(storageKey, expected, envelope, bounded, frame)
                outcome = result
                refuseUnreadable(storageKey, result)
            },
            { conditionalChanged(envelope, outcome) },
            engine.throwWriteFailure("putIfMatch")
        )
        return conditionalPutOf(envelope, requireNotNull(outcome))
    }

    /**
     * 非原子广播的后端上，条件写入之后是否广播（同步与异步共用）。写入成功时广播；冲突摘要等于这次写入的摘要时也广播：
     * 这可能是重放，上一次同样的写入已在 L1 生效，单独的广播却失败了（调用方因此得到 `CacheWriteException`），
     * 不广播的话其它节点的 L2 陈旧至 `localMaxAge`。多余的广播只淘汰副本，无害。
     */
    private fun conditionalChanged(
        envelope: Envelope.Scalar,
        result: L1ConditionalWrite?
    ): Boolean = when (result) {
        is L1ConditionalWrite.Conflict -> result.current == envelope.digest
        // 拒绝（Unreadable）已在 mutate 中抛出，这里只剩 Written
        else -> result == L1ConditionalWrite.Written
    }

    /**
     * 后端拒绝了写入（key 中是读不懂的内容，未修改、未广播）：计入 `unknownFormats` 并抛出，由 [CoherentEngine.write] 包装为 `CacheWriteException`
     * （同时计入 `writeFailures`），与 `SetStore` 的元素写入相同。
     */
    private fun refuseUnreadable(storageKey: String, result: L1ConditionalWrite): Void? {
        if (result !is L1ConditionalWrite.Unreadable) {
            return null
        }
        throw engine.unknownFormat(storageKey, result.format, null)
    }

    private fun conditionalPutOf(envelope: Envelope.Scalar, result: L1ConditionalWrite): ConditionalPut = when (result) {
        L1ConditionalWrite.Written -> ConditionalPut.Written(requireNotNull(envelope.digest))
        is L1ConditionalWrite.Conflict -> {
            engine.metrics.conditionalConflict()
            ConditionalPut.Conflict(result.current)
        }

        is L1ConditionalWrite.Unreadable -> throw IllegalStateException(
            "Cache[${engine.name}] - unreachable: refused write."
        )
    }

    /**
     * 条件删除（`Store.deleteIfMatch`，同步与异步共用的协程核心）：写入方顺序同 [CoherentEngine.putWithTtl]，同步完成或抛出，从不重试。
     * 非原子广播的后端上，冲突且当前没有值时也广播（RFC 0005 §4.6）：这可能是重放，上一次同样的删除已在 L1 生效，单独的广播却失败了。
     */
    suspend fun deleteIfMatch(key: K, expected: Digest): ConditionalDelete {
        val conditional = engine.requireConditional("deleteIfMatch")
        val storageKey = engine.storageKey(key)
        var outcome: L1ConditionalWrite? = null
        engine.write(
            listOf(storageKey),
            { frame ->
                val result = conditional.deleteIfMatch(storageKey, expected, frame)
                outcome = result
                refuseUnreadable(storageKey, result)
            },
            { conditionalDeleted(outcome) },
            engine.throwWriteFailure("deleteIfMatch")
        )
        return when (val result = requireNotNull(outcome)) {
            L1ConditionalWrite.Written -> ConditionalDelete.Deleted
            is L1ConditionalWrite.Conflict -> {
                engine.metrics.conditionalConflict()
                ConditionalDelete.Conflict(result.current)
            }

            is L1ConditionalWrite.Unreadable -> throw IllegalStateException(
                "Cache[${engine.name}] - unreachable: refused write."
            )
        }
    }

    /**
     * 非原子广播的后端上，条件删除之后是否广播（同 [conditionalChanged]）：删除成功时广播；冲突且当前没有值时也广播，这可能是重放：
     * 上一次同样的删除已在 L1 生效，单独的广播却失败了。拒绝（Unreadable）已在 mutate 中抛出，不会走到这里。
     */
    private fun conditionalDeleted(result: L1ConditionalWrite?): Boolean = when (result) {
        L1ConditionalWrite.Written -> true
        is L1ConditionalWrite.Conflict -> result.current == null
        is L1ConditionalWrite.Unreadable, null -> false
    }
}
