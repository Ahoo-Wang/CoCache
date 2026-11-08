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
import me.ahoo.cocache.api.Lookup
import me.ahoo.cocache.api.spi.L1Read
import java.time.Instant
import java.util.concurrent.CancellationException

/**
 * 权威类型的远端读取（`lookupRemote`，X3），只由 [StoreEngine] 与 [VersionedStoreEngine] 持有：直接读 L1（唯一副本）一次，
 * 只返回答案（[Lookup.Known]）。
 *
 * - 不读也不写 L2、不取失效戳、不申请 lease 或刷新令牌、不经 SingleFlight：L1 不改变，栅栏无关（I-1：L2 只由读 L1 的填充路径写入，
 *   这里从不写 L2）。
 * - L1 读取失败（含 `readTimeout` 超时）总是以 [CacheReadException] 抛出，不受 `unknownRead` 影响：调用方要的就是唯一副本此刻的答案，
 *   没有可降级的结果（同 `getIfChanged`）。
 * - 内容的判定同读路径（[ScalarContent.hitOutcome]）：更高的格式、格式号更低的内容、摘要校验失败（K3）、解码失败都按读不懂抛出，
 *   计入 `unknownFormats`，L1 不动（I-3：从不回收，没有数据源）。
 *
 * @param read 一次不带 lease 与刷新请求的 L1 读取（引擎的 `readRemote`，时限为 `readTimeout`）
 * @param now 引擎的时钟：到期时间从发出读取之前算起（I-C）
 * @param valueOf 引擎的值到用户的值：`Store` 原样，带版本的类型取出 `Versioned.value`（墓碑为 `null`，即不存在）
 */
internal class RemoteLookups<K : Any, E : Any, V : Any>(
    private val engine: CoherentEngine<K, E>,
    private val read: suspend (storageKey: String) -> L1Read,
    private val now: () -> Long,
    private val valueOf: (E) -> V?,
) {
    /**
     * 同步、异步与挂起视图共用的协程核心。
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun lookupRemote(key: K): Lookup.Known<V> {
        val storageKey = engine.storageKey(key)
        val readAt = now()
        val remote = try {
            read(storageKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            engine.metrics.remoteFailure()
            throw CacheReadException(engine.name, OPERATION, e)
        }
        return when (remote) {
            // lease 占位不是值：权威副本的 L1 中没有值就是不存在（I-2）；保留版本的淘汰 `E` 同样读作未命中
            is L1Read.Miss, is L1Read.Leased -> Lookup.Absent(Instant.ofEpochMilli(readAt))
            is L1Read.Hit -> answerOf(storageKey, remote, readAt)
        }
    }

    private fun answerOf(storageKey: String, hit: L1Read.Hit, readAt: Long): Lookup.Known<V> {
        val value = when (val outcome = engine.content.hitOutcome(storageKey, hit)) {
            HitOutcome.Newer -> throw unreadable(storageKey, hit, null)
            is HitOutcome.Corrupt -> throw unreadable(storageKey, hit, outcome.error)
            is HitOutcome.Decoded -> outcome.value?.let(valueOf)
        }
        val expiresAt = hit.ttl?.let { Instant.ofEpochMilli(readAt + it.toMillis()) }
        if (value != null) {
            engine.observeRemoteTtl(hit.ttl)
        }
        return value?.let { Lookup.Present(it, expiresAt) } ?: Lookup.Absent(expiresAt)
    }

    private fun unreadable(storageKey: String, hit: L1Read.Hit, error: Exception?): CacheReadException =
        CacheReadException(engine.name, OPERATION, engine.unknownFormat(storageKey, hit.format, error))

    private companion object {
        const val OPERATION = "lookupRemote"
    }
}
