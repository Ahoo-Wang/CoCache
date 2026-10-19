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

package me.ahoo.cocache.core

import java.time.Duration
import java.time.Instant

/**
 * 运维接口（RFC 0003 §4.14），不属于用户 API：查看统计、清空本节点的本地副本、按 key 查看与强制失效。
 *
 * 按 key 的操作都以**编码后的 key**（存储 key 中 `{keyPrefix}{namespace}:` 之后的部分，即 `KeyEncoder` 的输出）为参数，
 * 因此适用于任意 key 类型。
 */
interface CacheAdmin {
    val name: String

    val metrics: CacheMetrics

    /**
     * 本地副本数（估计值）。
     */
    val localSize: Long

    /**
     * 失效写入 L1 失败、仍在重试中的 key 数（重试成功或放弃后归零）。
     */
    val pendingInvalidationCount: Int

    /**
     * 清空本节点的 L2（L1 是共享存储，不受影响）。正在进行的填充会因失效戳变化而被放弃。
     */
    fun clearLocal()

    /**
     * 查看一个 key 在本节点 L2 与 L1 中的状态，**不加载**：不回源、不申请 lease 或刷新令牌、不填充任何一层、不计入指标，
     * 也不影响 L2 的淘汰统计。值用缓存的 `Codec` 解码后以 `toString()` 展示（截断到 [KeyInspection.MAX_VALUE_LENGTH]）。
     *
     * @param encodedKey 编码后的 key（不含 `{keyPrefix}{namespace}:`）
     */
    fun inspect(encodedKey: String): KeyInspection

    /**
     * 按编码后的 key 走正常的失效路径（两次递增戳、淘汰 L2、删除 L1 并广播，全集群生效），幂等。
     * 失败处理与 `invalidate` 相同（默认进入重试队列）。`SetCache` 上等价于 `clear(key)`：L1 是唯一副本，会删除该集合。
     *
     * @param encodedKey 编码后的 key（不含 `{keyPrefix}{namespace}:`）
     */
    fun invalidateEncoded(encodedKey: String)
}

/**
 * 一个 key 的只读快照（[CacheAdmin.inspect]）。
 *
 * @param storageKey 完整的存储 key：`{keyPrefix}{namespace}:{encodedKey}`
 * @param local 本节点 L2 中的副本；`null` 表示没有（已过期但尚未被淘汰的副本照常返回，[LocalCopy.expired] 为 `true`）
 * @param remote L1 中的状态
 * @param invalidationPending 该 key 的失效写入 L1 失败、仍在重试中（此时未命中会跳过 L1 直接回源）
 */
data class KeyInspection(
    val cache: String,
    val storageKey: String,
    val local: LocalCopy?,
    val remote: RemoteCopy,
    val invalidationPending: Boolean,
) {
    /**
     * L2 副本。
     *
     * @param value 值的 `toString()`（截断）；负缓存与“未决的不存在”为 `null`
     * @param refreshAt 进入提前刷新窗口的时间；没有为 `null`
     */
    data class LocalCopy(
        val state: LocalState,
        val value: String?,
        val expiresAt: Instant,
        val refreshAt: Instant?,
        val expired: Boolean,
    )

    enum class LocalState {
        /**
         * 有值。
         */
        VALUE,

        /**
         * 负缓存（数据源确认不存在）。
         */
        ABSENT,

        /**
         * 没有数据源的缓存未命中时的“不存在”：只是没有答案。
         */
        UNRESOLVED,
    }

    /**
     * L1 状态。
     *
     * @param value 解码后值的 `toString()`（截断）；只有 [RemoteState.VALUE] 与 [RemoteState.SET] 有
     * @param ttl 剩余寿命（[RemoteState.LEASED] 时是 lease 的剩余寿命）；永不过期或没有内容时为 `null`
     * @param format 线格式版本（只有读到内容时有）
     * @param error [RemoteState.UNREADABLE] 与 [RemoteState.ERROR] 的原因
     */
    data class RemoteCopy(
        val state: RemoteState,
        val value: String? = null,
        val ttl: Duration? = null,
        val format: Int? = null,
        val error: String? = null,
    )

    enum class RemoteState {
        /**
         * 标量值。
         */
        VALUE,

        /**
         * 负缓存。
         */
        ABSENT,

        /**
         * 集合（`SetCache`）。
         */
        SET,

        /**
         * 某个节点持有 lease、正在回源。
         */
        LEASED,

        /**
         * 不存在。
         */
        MISS,

        /**
         * 有内容但无法解码：无法识别的格式、更高的格式版本、形状不符或 `Codec` 解码失败（读取时会按 §5.4 处理）。
         */
        UNREADABLE,

        /**
         * 读取 L1 失败。
         */
        ERROR,
    }

    companion object {
        /**
         * 展示值的最大字符数，超出部分截断。
         */
        const val MAX_VALUE_LENGTH = 256

        /**
         * 截断展示用的文本：`前缀…(共 n 个字符)`。
         */
        @JvmStatic
        fun abbreviate(text: String): String {
            if (text.length <= MAX_VALUE_LENGTH) {
                return text
            }
            return text.substring(0, MAX_VALUE_LENGTH) + "…(${text.length} chars)"
        }
    }
}
