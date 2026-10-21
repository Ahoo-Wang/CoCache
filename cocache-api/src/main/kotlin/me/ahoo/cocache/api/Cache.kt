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

package me.ahoo.cocache.api

import me.ahoo.cocache.api.spi.Digest
import java.time.Duration

/**
 * 两级一致性缓存：进程内 L2 → 共享 L1 → 数据源。
 *
 * 一致性目标见 RFC 0003 §1.2：陈旧度有上界（G1）、失效不被吞没（G2）、命中路径便宜（G3）。
 *
 * @param K 业务 key
 * @param V 缓存值
 */
interface Cache<K : Any, V : Any> {
    /**
     * 缓存名。
     */
    val name: String

    /**
     * 读穿透：L2 → L1 → 数据源。不存在（含负缓存）返回 `null`。
     */
    operator fun get(key: K): V?

    /**
     * 读穿透，未命中时用 [loader] 回源（代替缓存声明中的数据源）。
     *
     * 与 [get] 走同一条受 lease 与失效戳保护的路径：回源结果只能凭 lease 写回 L1，期间发生的失效不会被吞没。
     * 供 Spring `@Cacheable(sync = true)` 等“调用方提供加载逻辑”的场景使用，避免“读未命中 → 加载 → 无条件写入”的竞态。
     */
    fun get(key: K, loader: CacheLoader<K, V>): V?

    /**
     * 批量读穿透：L1 一次批量读取、数据源一次批量加载，并与并发的单 key 读取共享在途加载。
     * 结果只包含存在的 key。
     */
    fun getAll(keys: Collection<K>): Map<K, V>

    /**
     * 需要区分“确认不存在”（负缓存）时使用。
     */
    fun lookup(key: K): Lookup<V>

    /**
     * 写穿透到 L1 并广播失效，按策略决定到期时间；原子地使所有在途填充失效。
     *
     * 写入不填充本地 L2：下一次读取从 L1 填充，保证 L2 与 L1 的写入顺序一致。
     *
     * @throws CacheWriteException L1 写入失败（结果可能未知，操作幂等，可安全重试）
     */
    fun put(key: K, value: V)

    /**
     * 以指定时长写入，语义同 [put]。
     */
    fun put(key: K, value: V, ttl: Duration)

    /**
     * 批量写入，每个 key 的语义同 [put]：寿命逐个按 [put] 计算（各自独立抖动），不填充本地 L2。
     *
     * 内置引擎把全部 key 放进一次写入：先对全部 key 递增失效戳并淘汰 L2，再批量写入 L1 并广播一次，最后再递增并淘汰，
     * 因此任何在途填充都不会把旧值写回。L1 只保证每个 key 原子，不保证跨 key 原子（Cluster 按 slot 分组执行）。
     * [entries] 为空时不做任何事。
     *
     * 默认实现逐个调用 [put]，供第三方实现保持兼容。
     *
     * @throws CacheWriteException L1 写入失败（部分 key 可能已生效；操作幂等，可安全重试）
     */
    fun putAll(entries: Map<K, V>) {
        entries.forEach { (key, value) -> put(key, value) }
    }

    /**
     * 以同一时长批量写入，语义同 [putAll]。
     */
    fun putAll(entries: Map<K, V>, ttl: Duration) {
        entries.forEach { (key, value) -> put(key, value, ttl) }
    }

    /**
     * 失效两级缓存并广播。必须在数据源提交之后调用（事务内应登记到提交之后）。
     *
     * L1 写入失败时默认进入重试队列而不抛出（见 RFC 0003 §4.13）。
     */
    fun invalidate(key: K)

    /**
     * 批量失效，语义同 [invalidate]。
     */
    fun invalidateAll(keys: Collection<K>)

    /**
     * 条件读取：直接读 L1（本缓存的权威副本），确认调用方已有的值是否仍是 L1 中的那一份。
     *
     * - L1 中的值的摘要等于 [known] 时返回 [ConditionalGet.Unchanged]：后端在 L1 中比较摘要，不传输正文
     *   （Redis 后端只回复一个结果码，代价是 Redis 对载荷计算一次 SHA-1）。
     * - 否则返回 [ConditionalGet.Present]（值与新摘要）或 [ConditionalGet.Absent]（L1 中没有值：不存在、lease 占位、负缓存）。
     *   [known] 为 `null` 时就是一次绕过 L2、带摘要的读取，供 [putIfMatch] 的“读—改—写”使用。
     *
     * 不读也不写本地 L2，不回源，不申请 lease，不修改 L1：确认的是 L1 本身，L2 可能更旧（陈旧度仍受 `localMaxAge` 约束）。
     * L1 读取失败时总是抛出 [CacheReadException]（与 `readFailure` 无关：没有可降级的答案）。
     * 读不懂的内容（不是本协议写入的、更高版本写入的、集合、解码失败）按 `foreignFormat` 处理：未配置或 `FAIL` 时抛出
     * [CacheReadException]（`cause` 为 `ForeignFormatException`），`RECLAIM` 时视为没有值（[ConditionalGet.Absent]），不修改 L1。
     *
     * 内置引擎支持（`SetCache` 除外）；其它实现默认抛出 [UnsupportedOperationException]。
     *
     * @param known 调用方已有的值的摘要（上一次 [getIfChanged] 或 [putIfMatch] 返回的）；`null` 表示没有
     * @throws CacheReadException L1 读取失败，或读到读不懂的内容
     */
    fun getIfChanged(key: K, known: Digest?): ConditionalGet<V> = throw UnsupportedOperationException(
        "${javaClass.name} does not support getIfChanged()."
    )

    /**
     * 条件写入（按摘要比较后写入）：仅当 L1 中的当前内容符合 [expected] 时写入 [value]，按策略决定到期时间（同 [put]）。
     * 比较与写入在 L1 中原子完成（Redis 后端是一个 Lua 脚本），失效只在写入时广播。
     *
     * - [expected] 为 `null`：仅当 L1 中没有值（不存在、lease 占位、负缓存）时写入（“仅当不存在”）。
     * - 否则：仅当 L1 中的值的摘要等于 [expected] 时写入。
     * - 不符合时返回 [ConditionalPut.Conflict]（附当前摘要），不写入、不广播、不改变到期时间：调用方用 [getIfChanged] 重读后重试。
     *
     * 写入时与 [put] 相同：使在途填充失效、销毁令牌、不填充本地 L2（下一次读取从 L1 填充）。
     * L1 中是读不懂的内容时按 `foreignFormat` 处理：未配置或 `FAIL` 时抛出 [CacheWriteException]（`cause` 为
     * `ForeignFormatException`），不修改 L1、不广播；`RECLAIM` 时视为没有值（`expected = null` 时整体覆盖它）。
     *
     * 内置引擎支持（`SetCache` 除外：元素增删本身是原子的）；其它实现默认抛出 [UnsupportedOperationException]。
     *
     * @param expected L1 中应有的值的摘要（[getIfChanged] 返回的）；`null` 表示应当没有值
     * @return 写入时为 [ConditionalPut.Written]（附写入的值的摘要），否则为 [ConditionalPut.Conflict]
     * @throws CacheWriteException L1 写入失败（结果可能未知：重读后再决定是否重试），或 L1 中是读不懂的内容
     */
    fun putIfMatch(key: K, expected: Digest?, value: V): ConditionalPut = throw UnsupportedOperationException(
        "${javaClass.name} does not support putIfMatch()."
    )

    /**
     * 以指定时长条件写入，语义同 [putIfMatch]。
     */
    fun putIfMatch(
        key: K,
        expected: Digest?,
        value: V,
        ttl: Duration
    ): ConditionalPut = throw UnsupportedOperationException(
        "${javaClass.name} does not support putIfMatch()."
    )

    /**
     * 同一个缓存的异步视图（RFC 0004）：共享 L2、失效戳、在途加载与 lease，每次返回同一个对象。
     *
     * 内置引擎与接口式声明的代理都支持；其它实现默认抛出 [UnsupportedOperationException]。
     */
    fun async(): AsyncCache<K, V> = throw UnsupportedOperationException("${javaClass.name} does not support async().")
}
