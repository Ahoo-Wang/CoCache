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
import me.ahoo.cocache.core.CacheMetrics
import me.ahoo.cocache.core.internal.runSync
import me.ahoo.cocache.core.internal.uncancellable
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * 派生副本的延迟失效（RFC 0005 §4.6，I-D）：失效写入 L1 失败、或 `Cache` 的写入结果未知需要补偿时，这些存储 key 登记为“重试中”，
 * 交给运行时的重试队列（[InvalidationRetrier]）重试失效。登记期间本节点的读取绕过 L1（L1 中可能仍是旧值）：单 key 与批量读取不读 L1，
 * 提前刷新不从 L1 重新填充 L2。重试成功之后本地再递增戳并淘汰 L2（[evictLocal]）；放弃时解除登记，陈旧以 L1 的剩余寿命为界。
 *
 * 只由 [CoherentEngine] 持有，不在命中路径上：只有未命中、刷新与写入失败的路径查询它。
 *
 * @param remoteTtl 策略的 L1 寿命，只用于放弃时的告警
 * @param log 引擎的日志（告警沿用引擎的日志名）
 * @param invalidate 一次重试：删除 L1 中的 key，非原子广播的后端上再发布（在重试线程上，规则同同步变体）
 * @param evictLocal 重试成功之后，按存储 key 递增戳并淘汰 L2 副本
 */
@Suppress("LongParameterList")
internal class PendingInvalidations(
    private val name: String,
    private val metrics: CacheMetrics,
    private val retrier: InvalidationRetrier,
    private val remoteTtl: Duration?,
    private val log: KLogger,
    private val invalidate: suspend (List<String>) -> Unit,
    private val evictLocal: (String) -> Unit,
) {
    /**
     * 存储 key → 仍在重试的失效数：同一 key 的多次失败失效各自登记，任何一次放弃都不能提前解除其它仍在重试的。
     */
    private val counts = ConcurrentHashMap<String, Int>()

    /**
     * 仍在重试中的 key 数（供运维与测试查看）。
     */
    val size: Int
        get() = counts.size

    /**
     * [storageKey] 的失效是否仍在重试中：是时读取绕过 L1。
     */
    fun contains(storageKey: String): Boolean = counts.containsKey(storageKey)

    /**
     * 登记 [storageKeys] 为“重试中”（未命中时跳过 L1），交给重试队列重试失效；成功后本地再递增戳并淘汰 L2。
     * 重试队列已满或已关闭时立即放弃（[drop]）。
     *
     * @param operation 失败的操作名（告警用）：`invalidate`，或要补偿的 `put` / `putAll`
     */
    fun schedule(storageKeys: List<String>, error: Exception, operation: String) {
        log.warn(error) { "Cache[$name] - $operation $storageKeys failed, retrying the invalidation." }
        storageKeys.forEach { counts.merge(it, 1, Int::plus) }
        val accepted = retrier.submit(
            action = { runSync { uncancellable { invalidate(storageKeys) } } },
            onRetry = { metrics.invalidationRetry() },
            onSuccess = {
                storageKeys.forEach {
                    unregister(it)
                    evictLocal(it)
                }
            },
            onGiveUp = { giveUpError -> drop(storageKeys, giveUpError) },
        )
        if (!accepted) {
            drop(storageKeys, error)
        }
    }

    private fun drop(storageKeys: List<String>, error: Throwable) {
        storageKeys.forEach { unregister(it) }
        metrics.invalidationDropped()
        // L1 仍是旧值，其它节点的 L2 到期后会从 L1 重新填充：陈旧度的上界是 L1 的剩余寿命
        log.error(error) {
            "Cache[$name] - invalidate $storageKeys dropped: L1 keeps the stale value until its remote TTL[${remoteTtl ?: "never"}] expires."
        }
    }

    private fun unregister(storageKey: String) {
        counts.computeIfPresent(storageKey) { _, count -> if (count > 1) count - 1 else null }
    }
}
