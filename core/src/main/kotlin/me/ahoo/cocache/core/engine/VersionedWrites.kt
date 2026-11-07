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

import me.ahoo.cocache.api.Versioned
import me.ahoo.cocache.api.VersionedPut
import me.ahoo.cocache.api.spi.L1Eviction
import me.ahoo.cocache.api.spi.L1VersionedWrite
import me.ahoo.cocache.core.policy.boundedTtl
import java.time.Duration

/**
 * 按版本有序的写入（RFC 0005 §4.7，I-K）与保留版本的失效，只由带版本的引擎（[VersionedEngine]）持有：其它类型够不到它（I-A 由结构保证）。
 * 比较与写入由后端在 L1 中原子完成，写入方顺序同 `put`（[CoherentEngine.write]，不写 L2）；版本不更新时不修改 L1
 * （相等的版本仍广播，见 [versionedChanged]）。引擎的值是 `Versioned`，`value` 为 `null` 时写入墓碑。读不懂的内容的分类与条件写入相同：总是拒绝。
 */
internal class VersionedWrites<K : Any, V : Any>(private val engine: CoherentEngine<K, V>) {

    /**
     * 按版本写入（同步与异步共用的协程核心）。
     *
     * @param operation 写入失败时 [CacheWriteException.operation] 的名字
     */
    suspend fun write(
        key: K,
        value: V?,
        version: Long,
        ttl: Duration?,
        operation: String
    ): VersionedPut {
        requireVersion(version)
        val versioned = engine.requireVersioned(operation)
        val storageKey = engine.storageKey(key)
        val envelope = engine.content.envelopeOf(value)
        val bounded = ttl?.boundedTtl()
        var outcome: L1VersionedWrite? = null
        engine.write(
            listOf(storageKey),
            { frame ->
                val result = versioned.putIfNewer(storageKey, envelope, version, bounded, frame)
                outcome = result
                refuseUnreadableVersion(storageKey, result)
            },
            { versionedChanged(version, outcome) },
            engine.throwWriteFailure(operation)
        )
        return versionedPutOf(requireNotNull(outcome))
    }

    /**
     * 非原子广播的后端上，按版本写入之后是否广播（同步与异步共用），与原子广播的脚本相同。写入成功时广播；当前版本等于给出的版本时
     * 也广播：这个版本可能是不广播的填充（lease 上的回源）写入的，别的节点还持有更旧的副本（RFC 0005 §4.7，F1），也可能是重放的、
     * 已在 L1 生效而广播失败的写入（理由同 [conditionalChanged]）。版本更旧时不广播。
     */
    private fun versionedChanged(version: Long, result: L1VersionedWrite?): Boolean = when (result) {
        is L1VersionedWrite.Stale -> result.current == version
        // 拒绝（Unreadable）已在 mutate 中抛出，这里只剩 Written
        else -> result == L1VersionedWrite.Written
    }

    private fun requireVersion(version: Long) {
        require(
            version != Versioned.NO_VERSION
        ) { "Cache[${engine.name}] - version must not be Versioned.NO_VERSION (Long.MIN_VALUE)." }
    }

    private fun refuseUnreadableVersion(storageKey: String, result: L1VersionedWrite): Void? {
        if (result !is L1VersionedWrite.Unreadable) {
            return null
        }
        throw engine.unknownFormat(storageKey, result.format, null)
    }

    private fun versionedPutOf(result: L1VersionedWrite): VersionedPut = when (result) {
        L1VersionedWrite.Written -> VersionedPut.Written
        is L1VersionedWrite.Stale -> {
            engine.metrics.staleVersion()
            VersionedPut.Stale(result.current)
        }

        is L1VersionedWrite.Unreadable -> throw IllegalStateException(
            "Cache[${engine.name}] - unreachable: refused write."
        )
    }

    /**
     * 保留版本栅栏的失效（`VersionedCache.evict`，RFC 0005 §4.7，P1）：带版本的内容转为 `E(当前版本)`，迟到的旧版本（数据源投递的事件、
     * 回源）仍被拒绝；没有版本的内容照常删除。写入方顺序同 [CoherentEngine.invalidateStorageKeys]；`E` 的寿命同墓碑（`tombstoneTtl`）。
     * 失败时抛出 [CacheWriteException]，不进入失效重试：重试的 `DEL` 会丢掉版本。拒绝（带摘要段或读不懂的内容）不修改 L1、不广播。
     *
     * 带摘要段的内容是 `Store` 写入的值（D4）：它属于同一 namespace 上的另一个缓存，拒绝（[AuthoritativeContentException]，计入 `typeMismatches`）。
     */
    suspend fun evictKeepingVersion(storageKey: String) {
        val versioned = engine.requireVersioned(EVICT)
        var outcome: L1Eviction? = null
        engine.write(
            listOf(storageKey),
            { frame ->
                val result = versioned.evictKeepingVersion(
                    storageKey,
                    engine.policy.tombstoneTtl()?.boundedTtl(),
                    frame
                )
                outcome = result
                refuseEviction(storageKey, result)
            },
            { outcome == L1Eviction.Evicted },
            engine.throwWriteFailure(EVICT)
        )
    }

    private fun refuseEviction(storageKey: String, result: L1Eviction): Void? = when (result) {
        L1Eviction.Evicted -> null
        is L1Eviction.Unreadable -> throw engine.unknownFormat(storageKey, result.format, null)
        L1Eviction.Authoritative -> {
            val refused = AuthoritativeContentException(engine.name, storageKey)
            engine.content.typeMismatch { refused.message }
            throw refused
        }
    }

    private companion object {
        /**
         * 保留版本的失效的操作名（`CacheWriteException.operation`）。
         */
        const val EVICT = "evictKeepingVersion"
    }
}
