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

package me.ahoo.cocache.api.spi

import java.time.Duration

/**
 * L1 协议（RFC 0003 §4.4）。只处理字节与令牌，不持有任何策略：TTL、刷新窗口、令牌都由引擎传入。
 *
 * 实现约定：
 * - 每个方法对单个 key 原子执行；批量方法对每个 key 原子（不要求跨 key 原子）。
 * - 每次状态转换都重设到期时间（`ttl == null` 为永不过期）；lease、负缓存的到期时间不得泄漏到新值上。
 * - `frame` 是 [InvalidationFrame] 编码的失效消息：[CoherenceBackend.atomicBroadcast] 为 `true` 时，
 *   实现必须在同一次原子操作中把它发布到本命名空间的失效通道。
 * - 失败时抛出异常；降级策略由引擎决定。
 */
interface RemoteStore : AutoCloseable {
    /**
     * 读取。key 不存在且 [lease] 非空时原子地写入 lease 占位并返回 `Miss(true)`；
     * 处于 lease 状态时返回 [RemoteRead.Leased]；为标量值且满足 [refresh] 条件时原子地写入刷新令牌。
     */
    fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?): RemoteRead

    /**
     * 批量读取：对每个 key 语义同 [read]，[leases] 中没有的 key 不申请 lease。结果包含全部 key。
     */
    fun readAll(keys: Collection<String>, leases: Map<String, LeaseRequest>): Map<String, RemoteRead>

    /**
     * 令牌保护的填充：仅当 L1 中仍是 [Fill.token]（lease 或刷新令牌）时写入。
     *
     * @return 是否写入
     */
    fun fill(fill: Fill): Boolean

    /**
     * 批量填充，结果与 [fills] 一一对应。
     */
    fun fillAll(fills: List<Fill>): List<Boolean>

    /**
     * 释放未使用的令牌：lease 状态且令牌匹配时删除 key；值上的刷新令牌匹配时只清除令牌。
     */
    fun release(key: String, token: Token)

    /**
     * 整体覆盖写入并广播；销毁任何令牌。
     */
    fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray)

    /**
     * 批量整体覆盖写入并广播：每个 key 的转换同 [put]（销毁令牌、重设到期时间），每个 key 原子，不要求跨 key 原子。
     * [frame] 携带 [puts] 的全部 key；实现可以把它拆成若干帧分别广播（如按 slot 分组），但每个 key 必须恰好随其写入广播一次。
     * key 重复时后者生效（引擎不会传入重复的 key）。
     *
     * 默认实现逐个调用 [put]，每个 key 只广播自己（帧无法按 key 拆分时原样使用 [frame]），供第三方实现保持兼容。
     */
    fun putAll(puts: List<Put>, frame: ByteArray) {
        val frames = framesPerKey(frame, puts.map { it.key })
        puts.forEachIndexed { index, put -> put(put.key, put.envelope, put.ttl, frames[index]) }
    }

    /**
     * 把携带多个 key 的帧拆成每个 key 一帧（同一发布者），避免 N 个 key 各广播一次全部 key（O(N²)）。
     * 帧畸形或其中的 key 与 [keys] 不一致（无法安全拆分）时，每个 key 原样使用 [frame]。
     */
    private fun framesPerKey(frame: ByteArray, keys: List<String>): List<ByteArray> {
        val decoded = try {
            InvalidationFrame.decode(frame)
        } catch (_: IllegalArgumentException) {
            return List(keys.size) { frame }
        }
        if (decoded.keys.toSet() != keys.toSet()) {
            return List(keys.size) { frame }
        }
        return keys.map { InvalidationFrame.encode(decoded.origin, listOf(it)) }
    }

    /**
     * 删除并广播。
     */
    fun invalidate(keys: Collection<String>, frame: ByteArray)

    /**
     * 条件回收：仅当当前内容的 [Digest] 等于 [observed] 时，原子地把 key 转为 [lease] 占位。
     *
     * @return 是否回收
     */
    fun reclaim(key: String, observed: Digest, lease: LeaseRequest): Boolean

    /**
     * 原子地加入集合成员：key 不存在、为负缓存或处于 lease 状态时先清除，再创建集合；清除刷新令牌；重设到期时间；广播。
     * 其它内容（不是本协议的集合）被整体覆盖；不能覆盖时用 [addMembersIfReadable]。
     */
    fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)

    /**
     * 原子地移除集合成员：仅当 key 为集合时生效，移空则删除 key，否则重设到期时间；广播。
     */
    fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray)

    /**
     * 同 [addMembers]，但 key 中是不能当作本协议集合的内容时，不修改 key、不广播，返回该内容的格式版本。这类内容是：
     * 不是本协议写入的内容（如 CoCache 5.x 留下的原生 Set / String / Hash，返回 `0`）、本协议的标量值、
     * 更高格式版本写入的集合（返回其版本）。key 不存在、为负缓存或处于 lease 状态时照常写入。
     *
     * 没有数据源的集合缓存（L1 是唯一副本）用它代替 [addMembers]：整体覆盖读不懂的内容就是删除唯一副本。
     *
     * 默认实现先 [read] 再 [addMembers]：检查与写入之间不原子，也认不出更高的格式版本。实现应当覆盖它，在一次原子操作中检查并写入。
     *
     * @return 写入时为 `null`；否则为阻止写入的内容的格式版本（`0` 表示不是本协议写入）
     */
    fun addMembersIfReadable(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int? {
        unreadableSetFormat(read(key, null, null))?.let { return it }
        addMembers(key, members, ttl, frame)
        return null
    }

    /**
     * 同 [removeMembers]，但 key 中是不能当作本协议集合的内容（见 [addMembersIfReadable]）时，不修改 key、不广播，返回该内容的格式版本。
     *
     * 默认实现先 [read] 再 [removeMembers]（不原子，认不出更高的格式版本）；实现应当覆盖它。
     *
     * @return 执行时为 `null`；否则为阻止执行的内容的格式版本
     */
    fun removeMembersIfReadable(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int? {
        unreadableSetFormat(read(key, null, null))?.let { return it }
        removeMembers(key, members, ttl, frame)
        return null
    }

    override fun close() = Unit
}

/**
 * [RemoteStore.addMembersIfReadable] 等默认实现的判定：命中且不是本协议的集合（格式低于 1，或是标量值）时返回其格式版本。
 * 负缓存、lease、不存在都可以写入。
 */
internal fun unreadableSetFormat(read: RemoteRead): Int? {
    if (read !is RemoteRead.Hit) {
        return null
    }
    return read.format.takeIf { it < 1 || read.envelope is Envelope.Scalar }
}
