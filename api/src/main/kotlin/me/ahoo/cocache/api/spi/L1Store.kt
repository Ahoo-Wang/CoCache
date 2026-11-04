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
 * L1 协议（RFC 0005 §4.3）：一个命名空间的存储，所有 key 都已包含命名空间前缀。只处理字节与令牌，不持有任何策略：
 * TTL、刷新窗口、令牌都由引擎传入。
 *
 * 全部操作都是挂起函数、都是抽象的：SPI 不提供任何非原子的默认实现。Java 实现者实现 [FutureL1Store]，经 [FutureL1Store.adapt] 得到本接口；
 * 装饰器继承 [ForwardingL1Store]。存储没有 `close`：连接等资源属于 [CoherenceBackend]，随它关闭。
 *
 * **原子性、到期时间与广播**
 * - 每个方法对单个 key 原子执行；批量方法对每个 key 原子（不要求跨 key 原子）。
 * - 每次状态转换都重设到期时间（`ttl == null` 为永不过期）；lease、负缓存的到期时间不得泄漏到新值上。
 * - `frame` 是 [InvalidationFrame] 编码的失效消息：[CoherenceBackend.atomicBroadcast] 为 `true` 时，实现必须在写入的同一次原子操作中
 *   把它发布到本命名空间的失效通道；为 `false` 时由引擎在写入成功之后经 [InvalidationChannel.publish] 发布。
 *
 * **取消与限时**
 * - 引擎从不取消本接口的调用（核心在 `NonCancellable` 中调用），实现用可取消的 `await()` 等待底层命令也是安全的。线程中断不到达存储。
 * - **故障不得以 `CancellationException` 报告**：在 JVM 上它就是协程的取消，引擎会把它当作调用方被取消（不降级、跟随者自行运行）。
 *   底层客户端取消的命令（如连接重置、关闭时被取消的命令）是结果未知的故障，以其它异常报告（可以把原取消异常作为 cause）。
 * - 调用何时结束只由实现决定：每个调用都必须自己限时（如命令超时），不得永远挂起。读取（[read]、[readAll]、
 *   [ConditionalL1Store.readIfChanged]）另可带这次调用的 `timeout`：`null` 为实现自己的命令超时；给出时以命令超时为上限，
 *   实现以两者中较短的一个限定这次调用（RFC 0005 round 3 D6）。它与引擎传入的其它时长一样是相对的。
 * - 超时要取消到底层命令：尚未写出的命令不再写出。
 * - 取得或交回令牌的命令（[read]、[readAll]、[fill]、[fillAll]、[release]、[reclaim]）失败时，除确定未执行外（见下面的“失败”），
 *   实现都尽力释放该令牌：异步发出，不等待（RFC 0005 round 3 K14）。超时、断线时被排空、脚本的错误回复、底层客户端的取消、
 *   解码失败都在此列。令牌不匹配时释放什么也不做，所以迟到、重复或重连之后才到达的释放都无害。
 * - 写入方法（[put]、[putAll]、[invalidate] 与能力面上的写入）返回或抛出时，它发出的全部命令都已结束：否则引擎补发的广播
 *   可能先于仍在执行的命令到达。
 *
 * **失败**：抛出异常，降级策略由引擎决定。失败分两类：
 * - 确定未执行：命令在写出之前就被拒绝（如断线时拒绝命令），或在发出之前同步失败。
 * - 结果未知：其余情形，包括超时、断线时被排空的命令，以及脚本的错误回复（除非能逐个脚本证明错误只可能发生在第一次写入之前）。
 *
 * 区分两类失败的异常类型随用户 API 提供（RFC 0005 M5）；在此之前实现按上述分类决定是否释放令牌。
 *
 * **能力**：条件读写、按版本有序的写入、集合成员的增删是可选能力，由 [conditional]、[versioned]、[sets] 访问。
 * - 不支持时返回 `null`，不抛出。
 * - 同一个存储在其生命周期内每次返回同样的结果（同一个对象，或总是 `null`），引擎可以只取一次。
 * - 访问器是抽象的、没有默认的 `null`：否则只包装基本操作的装饰器会静默剥掉能力。
 * - 能力面是独立的接口，不继承 [L1Store]：装饰器要拦截能力面上的操作，就覆盖对应的访问器（见 [ForwardingL1Store]），
 *   不会因为转发出去的能力面就是被装饰者本身而被绕过。
 *
 * 读不懂的内容（不是本协议写入的、更高格式版本写入的）从不当作没有值写入：SPI 没有“视为没有值”的开关，集合只有“检查并写入”。
 */
interface L1Store {
    /**
     * 读取。key 不存在且 [lease] 非空时原子地写入 lease 占位并返回 `Miss(true)`；处于 lease 状态时返回 [L1Read.Leased]；
     * 为标量值且满足 [refresh] 条件时原子地写入刷新令牌（[L1Read.Hit.refreshGranted]）。
     *
     * - 带摘要段的值（[Envelope.Scalar.digest] 非 `null`，权威标量，D4）从不授予刷新令牌；带版本的值照常授予。
     * - 保留版本栅栏的失效（[VersionedL1Store.evictKeepingVersion] 留下的状态 `E`）读作未命中，不是负缓存：[lease] 非空、
     *   其上没有令牌或令牌签发已超过 [LeaseRequest.ttl] 时，原地写入 lease 令牌（保留版本与到期时间）并返回 `Miss(true)`；
     *   令牌未过期时返回 [L1Read.Leased]（剩余时间为 [LeaseRequest.ttl] 减去已签发时长）；[lease] 为空时返回 `Miss(false)`。
     *
     * @param timeout 这次调用的时限：`null` 为实现的命令超时；给出时必须为正，以命令超时为上限（见类注释）。
     * 超时与其它失败一样释放取得的令牌。
     */
    suspend fun read(key: String, lease: LeaseRequest?, refresh: RefreshRequest?, timeout: Duration?): L1Read

    /**
     * 批量读取：对每个 key 语义同 [read]（不申请刷新令牌），[leases] 中没有的 key 不申请 lease。结果包含全部 key。
     *
     * @param timeout 整个调用的时限，同 [read]
     */
    suspend fun readAll(
        keys: Collection<String>,
        leases: Map<String, LeaseRequest>,
        timeout: Duration?
    ): Map<String, L1Read>

    /**
     * 令牌保护的填充：仅当 L1 中仍是 [Fill.token]（lease 或刷新令牌）时写入。支持版本的存储（[versioned] 非 `null`）同时保存 [Fill.version]，
     * 并按版本规则决定是否写入（RFC 0005 §4.7）：key 中带版本（持有刷新令牌的带版本值，或持有 lease 的 `E`）时，
     * - [Fill.version] 小于 key 中的版本，或为 [me.ahoo.cocache.api.Versioned.NO_VERSION]：不写入，在同一次原子操作中清零令牌
     *   （只触及令牌，保留内容与到期时间），返回 `Rejected(key 中的版本)`；
     * - 相等：写入（相当于续期），不广播，返回 `Filled(false)`；key 处于 `E` 时同下一条；
     * - 更大（或 key 处于 `E`）：写入并在同一次原子操作中广播失效，返回 `Filled(true)`。
     *
     * 版本规则广播的帧由存储构造：来源为 [Fill.token] 的 [Token.node]（令牌由引擎签发，就是引擎的节点），只含 [Fill.key]。
     * [CoherenceBackend.atomicBroadcast] 为 `false` 时不广播，由引擎在 `Filled(true)` 之后发布。
     *
     * @return 写入时为 [L1Fill.Filled]；令牌不匹配或版本规则拒绝时为 [L1Fill.Rejected]
     */
    suspend fun fill(fill: Fill): L1Fill

    /**
     * 批量填充：对每一项语义同 [fill]，结果与 [fills] 一一对应。
     */
    suspend fun fillAll(fills: List<Fill>): List<L1Fill>

    /**
     * 释放未使用的令牌：lease 状态且令牌匹配时删除 key；值上的刷新令牌、`E` 上的 lease 令牌匹配时只清除令牌
     * （保留内容与到期时间，从不删除 `E`）。
     */
    suspend fun release(key: String, token: Token)

    /**
     * 条件回收：仅当当前内容的指纹等于 [observed]（[ContentFingerprint.of] 读到的内容）时，原子地把 key 转为 [lease] 占位。
     * 读路径只经它回收损坏的内容，从不无条件删除。从不回收：lease 占位、带版本的内容与 `E`（lease 放不下版本）、
     * 带摘要段的内容（权威标量，D4）、更高格式的内容。
     *
     * @return 是否回收
     */
    suspend fun reclaim(key: String, observed: ContentFingerprint, lease: LeaseRequest): Boolean

    /**
     * 整体覆盖写入并广播；销毁任何令牌。支持版本的存储（[versioned] 非 `null`）同时保存 [Envelope.Scalar.version]。
     */
    suspend fun put(key: String, envelope: Envelope, ttl: Duration?, frame: ByteArray)

    /**
     * 批量整体覆盖写入并广播：每个 key 的转换同 [put]（销毁令牌、重设到期时间），每个 key 原子，不要求跨 key 原子。
     *
     * [frame] 携带 [puts] 的全部 key；实现可以把它拆成若干帧分别广播（如按 slot 分组），但每个 key 必须恰好随其写入广播一次。
     * key 重复时后者生效（引擎不会传入重复的 key）。没有逐个调用 [put] 的默认实现：那样每个 key 都会广播全部 key。
     */
    suspend fun putAll(puts: List<Put>, frame: ByteArray)

    /**
     * 删除并广播。
     */
    suspend fun invalidate(keys: Collection<String>, frame: ByteArray)

    /**
     * 条件读写的能力面；不支持时为 `null`。同一个存储每次返回同样的结果。
     */
    fun conditional(): ConditionalL1Store?

    /**
     * 按版本有序写入的能力面；不支持时为 `null`。同一个存储每次返回同样的结果。
     *
     * 非 `null` 时，本存储的 [fill]、[put]、[putAll] 同时保存版本（[Fill.version]、[Envelope.Scalar.version]），[read] 原样返回。
     */
    fun versioned(): VersionedL1Store?

    /**
     * 集合成员增删的能力面；不支持时为 `null`。同一个存储每次返回同样的结果。
     */
    fun sets(): SetL1Store?
}

/**
 * 条件读写（[L1Store.conditional]）：按载荷的 [Digest] 比较。约定同 [L1Store]（原子、重设到期时间、广播、限时与失败的分类）。
 *
 * 只认带摘要段、不带版本的标量值（[Envelope.Scalar.digest] 非 `null`，[Envelope.Scalar.version] 为
 * [me.ahoo.cocache.api.Versioned.NO_VERSION]）：比较的是写入时保存的摘要段，存储不重算。没有摘要段的值（不是权威缓存写入的）、
 * 带版本的内容（值、墓碑与 `E`）都是读不懂的内容，拒绝；lease 占位与不带版本的负缓存是“没有值”。实现应当只读头部比较
 * （Redis：`GETRANGE`），比较成本与载荷大小无关。
 */
interface ConditionalL1Store {
    /**
     * 条件读取（确认 L1 中的值是否仍是调用方已有的那一份）：key 中是带摘要段的标量值、且其摘要段等于 [known] 时
     * 返回 [L1ConditionalRead.Unchanged]（附格式版本），不返回正文；否则返回当前状态（值与格式版本、没有值、读不懂的内容）。
     * 返回的值带摘要段与写入标识（[Envelope.Scalar.digest]、[Envelope.Scalar.writer]），由引擎重算校验。
     * 不申请 lease 或刷新令牌，不修改 key。
     *
     * 实现应当在 L1 中比较摘要，摘要相同时只传输结果码与格式版本。
     *
     * @param timeout 这次调用的时限，同 [L1Store.read]
     */
    suspend fun readIfChanged(key: String, known: Digest?, timeout: Duration?): L1ConditionalRead

    /**
     * 条件写入（按摘要比较后写入）：仅当 key 的当前内容符合 [expected] 时，原子地整体覆盖写入 [envelope]
     * （转换同 [L1Store.put]：销毁任何令牌，按 [ttl] 重设到期时间，`null` 为永不过期；写入没有版本的值）并广播 [frame]；
     * 否则不修改 key、不广播。
     *
     * - [expected] 为 `null`：仅当 key 中没有值（不存在、lease 占位、不带版本的负缓存）时写入。
     * - 否则：仅当 key 中是带摘要段的标量值、且其摘要段等于 [expected] 时写入。
     * - 不符合时返回 [L1ConditionalWrite.Conflict]，附当前值的摘要段；没有值时为 `null`。
     * - key 中是不能当作带摘要段的标量值的内容（不是本协议写入的内容、本协议的集合、更高格式版本写入的内容、没有摘要段的值、
     *   带版本的内容，同 [L1ConditionalRead.Unreadable]）时返回 [L1ConditionalWrite.Unreadable]：读不懂的内容从不视为没有值。
     * - 写入的摘要段是 [envelope] 的 [Envelope.Scalar.digest]（由引擎计算），存储不重算。
     *
     * 比较与写入必须在一次原子操作中完成：“先读后写”会让两个并发的写入方都得到 [L1ConditionalWrite.Written]、丢失更新。
     */
    suspend fun putIfMatch(
        key: String,
        expected: Digest?,
        envelope: Envelope.Scalar,
        ttl: Duration?,
        frame: ByteArray
    ): L1ConditionalWrite
}

/**
 * 按版本有序的写入（[L1Store.versioned]）。约定同 [L1Store]。
 *
 * 提供本能力面的存储同时在 [L1Store.fill]、[L1Store.put]、[L1Store.putAll] 时保存版本，[L1Store.read] 原样返回：
 * 标量值带版本，[Envelope.Absent] 带版本即墓碑（带版本的负缓存）；[L1Store.fill] 按版本规则写入。
 * [evictKeepingVersion] 留下的状态 `E` 只有版本、没有内容：读作未命中，可以持有 lease，其上的填充按版本规则写入。
 */
interface VersionedL1Store {
    /**
     * 仅当 [version] 大于 key 中的版本时，原子地整体覆盖写入 [envelope] 并保存 [version]
     * （转换同 [L1Store.put]：销毁任何令牌，按 [ttl] 重设到期时间，`null` 为永不过期）、广播 [frame]；否则不修改 key。
     * 版本相等时仍在同一次原子操作中广播 [frame]（RFC 0005 §4.7）：这个版本可能是令牌保护的填充写入的，填充在 lease 上不广播，
     * 别的节点还可能持有更旧的副本。版本更小时不广播。
     *
     * - [envelope] 是标量值，或 [Envelope.Absent]（墓碑）；[Envelope.Scalar.version] 被忽略，保存的是 [version]。
     * - key 中的版本：本协议写入的、带版本的标量值、负缓存或 `E` 的版本。没有版本的值与负缓存（含带摘要段的值）、lease 占位、
     *   不存在都视为 [me.ahoo.cocache.api.Versioned.NO_VERSION]，总是写入。
     * - [version] 不大于 key 中的版本时返回 [L1VersionedWrite.Stale]（附 key 中的版本）；相等时同时广播了 [frame]。
     * - key 中是读不懂的内容（不是本协议写入的内容、本协议的集合、更高格式版本写入的内容）时返回 [L1VersionedWrite.Unreadable]。
     *
     * 比较与写入必须在一次原子操作中完成：“先读后写”会让迟到的旧版本覆盖新版本。
     */
    suspend fun putIfNewer(
        key: String,
        envelope: Envelope,
        version: Long,
        ttl: Duration?,
        frame: ByteArray
    ): L1VersionedWrite

    /**
     * 保留版本栅栏的失效（RFC 0005 §4.7，P1）：淘汰 key 的内容但保留它的版本，迟到的旧版本因此仍被拒绝。原子地：
     * - key 中带版本（值、墓碑或 `E`）：整体写为 `E(key 中的版本)`（没有内容，令牌为零，销毁任何 lease 或刷新令牌），
     *   按 [ttl] 重设到期时间（`null` 为永不过期），广播 [frame]，返回 [L1Eviction.Evicted]；
     * - key 中既没有版本也没有摘要段（没有版本的值与负缓存、lease 占位、不存在）：删除并广播，返回 [L1Eviction.Evicted]；
     * - key 中是带摘要段的内容（权威标量，D4）：不修改、不广播，返回 [L1Eviction.Authoritative]；
     * - key 中是读不懂的内容（不是本协议写入的内容、本协议的集合、更高格式版本写入的内容）：不修改、不广播，返回 [L1Eviction.Unreadable]。
     *
     * `E` 上的读取是未命中（可以取得 lease），填充的版本不小于 `E` 的版本才写入（见 [L1Store.fill]）。
     */
    suspend fun evictKeepingVersion(key: String, ttl: Duration?, frame: ByteArray): L1Eviction
}

/**
 * 集合成员的增删（[L1Store.sets]）：只有“检查并写入”，读不懂的内容不修改。约定同 [L1Store]。
 *
 * 不能当作本协议集合的内容：不是本协议写入的内容（格式 `0`）、本协议的标量值（其格式版本）、更高格式版本写入的集合（其格式版本）。
 * 检查与写入必须在一次原子操作中完成：没有数据源的集合缓存中 L1 是唯一副本，覆盖读不懂的内容就是删除唯一副本。
 */
interface SetL1Store {
    /**
     * 原子地检查并加入集合成员：key 不存在、为负缓存或处于 lease 状态时先清除，再创建集合；为本协议的集合时加入成员；
     * 清除刷新令牌，按 [ttl] 重设到期时间（`null` 为永不过期），广播 [frame]。
     *
     * @return 写入时为 `null`；key 中是不能当作本协议集合的内容时不修改 key、不广播，返回该内容的格式版本（`0` 表示不是本协议写入）
     */
    suspend fun addMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int?

    /**
     * 原子地检查并移除集合成员：key 为本协议的集合时移除，移空则删除 key，否则按 [ttl] 重设到期时间；key 不存在、为负缓存或
     * 处于 lease 状态时不修改 key；两种情形都广播 [frame]。
     *
     * @return 执行时为 `null`；key 中是不能当作本协议集合的内容时不修改 key、不广播，返回该内容的格式版本（`0` 表示不是本协议写入）
     */
    suspend fun removeMembers(key: String, members: Collection<ByteArray>, ttl: Duration?, frame: ByteArray): Int?
}
