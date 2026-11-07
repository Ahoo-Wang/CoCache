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

package me.ahoo.cocache.core.policy

import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/**
 * 缓存策略（RFC 0003 §4.10）：寿命、限龄、提前刷新、lease、回源并发与本地容量。不可变。
 *
 * @param ttl 命中值寿命；`null` 表示永不过期（一致性完全依赖显式失效）
 * @param ttlJitter 命中值寿命的随机抖动幅度（防止批量同时到期）；实际幅度不超过 `ttl / 2`
 * @param absentTtl 负缓存寿命（不抖动）
 * @param localMaxAge L2 副本的最长寿命：失效事件丢失时 L2 陈旧度的上界
 * @param degradedLocalMaxAge 未写入 L1 的填充（L1 故障、等待 lease 超时、fill 被拒绝）在 L2 中的最长寿命
 * @param refreshWindow 提前刷新窗口；`null` 表示关闭。只作用于有数据源的派生缓存
 * @param invalidateFailure 派生缓存（`Cache`）的失效写入 L1 失败时的处理方式；`null`（默认）为 [InvalidateFailureMode.RETRY]：
 *   删除副本在任何时刻都安全。权威副本的删除总是同步抛出，不适用本字段
 * @param readFailure 带数据源的读取（声明的数据源或调用级 loader）在 L1 读失败时的处理方式；`null`（默认）为 [ReadFailureMode.DEGRADE]。
 *   不带 loader 的读取与权威副本的读取不适用本字段：它们的 L1 读失败是 `Lookup.Unknown`，由声明的 `unknownRead` 决定（RFC 0005 §4.1）
 * @param readTimeout 每次 L1 读取（`read`、`readAll`、`readIfChanged`）的时限（RFC 0005 §4.3，D6）；`null`（默认）为后端的命令超时，
 *   给出时以命令超时为上限（由存储钳制）。超时按 L1 读失败处理（带数据源的读取按 [readFailure]，没有数据源的读取为 `Unknown`），
 *   取得的令牌照常释放。供不能降级回源、又要限制 L1 卡顿时每次读取多等多久的缓存使用；写入与运维读取不受它限制
 * @param ttlCeiling 按调用给出的寿命（回源与写入中 `ttlOf` 的结果，`put`、`putAll`、`putIfNewer`、`invalidateIfNewer` 显式给出的寿命）
 *   的上限，只由 `cocache.overrides` 的 `ttl` 设定（RFC 0005 §4.8，S8）：超过它的寿命被钳制，每个缓存第一次钳制时告警，
 *   计入 `cocache.ttl.clamped`；`null`（默认）不钳制。只作用于 `Cache`：其它类型的 spec 带着它时构建失败（带版本的类型的寿命是版本栅栏的寿命，
 *   权威类型的寿命就是数据的寿命）
 * @param tombstoneTtl 带版本的墓碑（`invalidateIfNewer`、`deleteIfNewer`、带版本的数据源返回的 `Versioned(null, w)`）与
 *   保留版本的失效 `E` 的寿命（不抖动）；`null` 表示与值相同（[valueTtl]）。它要覆盖重复投递的时间窗口：到期之后迟到的旧版本可以再次写入
 *
 * 读不懂的 L1 内容（格式号低于本节点格式的、不是本协议写入的、同格式解码失败的）不由策略决定（RFC 0005 §4.7）：
 * 有数据源的读取条件回收后回源，没有数据源的读取失败；更高格式的内容既不回收也不写回。
 *
 * 构造器不公开：每个策略都从一个类型的默认值（[DefaultPolicies]）出发构建，`DefaultPolicies.STORE.toBuilder()` 或
 * [CachePolicy.builder]`(DefaultPolicies.STORE)`。没有“不写起点”的写法：构造器的默认参数是派生副本的默认值（`ttl` 1 小时），
 * 曾让 `storeSpec { policy = CachePolicy(localMaxAge = …) }` 静默地给唯一副本 1 小时的寿命（S23）。
 */
class CachePolicy internal constructor(
    val ttl: Duration? = DEFAULT_TTL,
    val ttlJitter: Duration = DEFAULT_TTL_JITTER,
    val absentTtl: Duration = DEFAULT_ABSENT_TTL,
    val localMaxAge: Duration = DEFAULT_LOCAL_MAX_AGE,
    val degradedLocalMaxAge: Duration = DEFAULT_DEGRADED_LOCAL_MAX_AGE,
    val refreshWindow: Duration? = null,
    val lease: LeasePolicy = LeasePolicy(),
    val load: LoadPolicy = LoadPolicy(),
    val local: LocalPolicy = LocalPolicy(),
    val invalidateFailure: InvalidateFailureMode? = null,
    val readFailure: ReadFailureMode? = null,
    val readTimeout: Duration? = null,
    val tombstoneTtl: Duration? = null,
    val ttlCeiling: Duration? = null,
) {
    init {
        tombstoneTtl?.let { requirePositive("tombstoneTtl", it) }
        ttlCeiling?.let { requirePositive("ttlCeiling", it) }
        ttl?.let { requirePositive("ttl", it) }
        require(
            !ttlJitter.isNegative && ttlJitter <= MAX_DURATION
        ) { "ttlJitter[$ttlJitter] must be within [0, $MAX_DURATION]." }
        requirePositive("absentTtl", absentTtl)
        requirePositive("localMaxAge", localMaxAge)
        requirePositive("degradedLocalMaxAge", degradedLocalMaxAge)
        readTimeout?.let { requirePositive("readTimeout", it) }
        refreshWindow?.let { window ->
            requirePositive("refreshWindow", window)
            // 窗口不小于（抖动后的最短）寿命时，新副本一出生就处于窗口内，命中会持续触发刷新
            ttl?.let { valueTtl ->
                require(window < valueTtl - effectiveJitter(valueTtl)) {
                    "refreshWindow[$window] must be less than ttl[$valueTtl] - ttlJitter[${effectiveJitter(valueTtl)}]."
                }
            }
            require(window < localMaxAge) { "refreshWindow[$window] must be less than localMaxAge[$localMaxAge]." }
        }
    }

    /**
     * 按策略计算命中值寿命（含抖动，至少 1 毫秒）；`null` 表示永不过期。
     */
    fun valueTtl(): Duration? {
        val base = ttl ?: return null
        val amplitude = effectiveJitter(base).toMillis()
        if (amplitude == 0L) {
            return base
        }
        val millis = base.toMillis() + ThreadLocalRandom.current().nextLong(-amplitude, amplitude + 1)
        return Duration.ofMillis(millis.coerceAtLeast(1))
    }

    /**
     * 墓碑与 `E` 的寿命：[tombstoneTtl]，未设置时同 [valueTtl]；`null` 表示永不过期。
     */
    fun tombstoneTtl(): Duration? = tombstoneTtl ?: valueTtl()

    /**
     * 实际生效的抖动幅度：不超过寿命的一半，否则短寿命的值会大量以 1 毫秒寿命写入。
     */
    private fun effectiveJitter(ttl: Duration): Duration = minOf(ttlJitter, ttl.dividedBy(2))

    fun toBuilder(): Builder = Builder(this)

    override fun toString(): String {
        return "CachePolicy(ttl=$ttl, ttlJitter=$ttlJitter, absentTtl=$absentTtl, localMaxAge=$localMaxAge, " +
            "degradedLocalMaxAge=$degradedLocalMaxAge, refreshWindow=$refreshWindow, lease=$lease, load=$load, " +
            "local=$local, invalidateFailure=$invalidateFailure, readFailure=$readFailure, readTimeout=$readTimeout, " +
            "tombstoneTtl=$tombstoneTtl, ttlCeiling=$ttlCeiling)"
    }

    /**
     * Java 友好的构建器。记录写出了哪些字段（字段三态，RFC 0005 §4.8，U11）：spec 的 `policy { … }` 只把这些字段当作声明写出的，
     * 其余字段留给 `cocache.defaults`。[ttlCeiling] 由 `overrides` 设定，不算声明的字段。
     */
    class Builder internal constructor(policy: CachePolicy) {
        private val writes = linkedSetOf<PolicyField<*>>()

        /**
         * 经本构建器写出的字段，按写出的顺序。
         */
        val written: Set<PolicyField<*>>
            get() = writes

        var ttl: Duration? = policy.ttl
            set(value) {
                field = value
                writes += PolicyField.TTL
            }
        var ttlJitter: Duration = policy.ttlJitter
            set(value) {
                field = value
                writes += PolicyField.TTL_JITTER
            }
        var absentTtl: Duration = policy.absentTtl
            set(value) {
                field = value
                writes += PolicyField.ABSENT_TTL
            }
        var localMaxAge: Duration = policy.localMaxAge
            set(value) {
                field = value
                writes += PolicyField.LOCAL_MAX_AGE
            }
        var degradedLocalMaxAge: Duration = policy.degradedLocalMaxAge
            set(value) {
                field = value
                writes += PolicyField.DEGRADED_LOCAL_MAX_AGE
            }
        var refreshWindow: Duration? = policy.refreshWindow
            set(value) {
                field = value
                writes += PolicyField.REFRESH_WINDOW
            }

        /**
         * 子对象（[lease]、[load]、[local]）只把取值改变了的字段记为写出：整体赋值时没有改变的字段仍留给下层。
         */
        var lease: LeasePolicy = policy.lease
            set(value) {
                if (value.ttl != field.ttl) writes += PolicyField.LEASE_TTL
                if (value.wait != field.wait) writes += PolicyField.LEASE_WAIT
                field = value
            }
        var load: LoadPolicy = policy.load
            set(value) {
                if (value.maxConcurrent != field.maxConcurrent) writes += PolicyField.LOAD_MAX_CONCURRENT
                if (value.acquireTimeout != field.acquireTimeout) writes += PolicyField.LOAD_ACQUIRE_TIMEOUT
                if (value.timeout != field.timeout) writes += PolicyField.LOAD_TIMEOUT
                field = value
            }
        var local: LocalPolicy = policy.local
            set(value) {
                if (value.maximumSize != field.maximumSize) writes += PolicyField.LOCAL_MAXIMUM_SIZE
                field = value
            }

        /**
         * 回源并发上限（[LoadPolicy.maxConcurrent]）。赋值即记为写出，含哨兵 `maxConcurrent = unlimited()`：整体赋值 [load]
         * 只记录值有变化的字段，与当前值相同的“不限”经它写不出来。
         */
        var maxConcurrent: Int?
            get() = load.maxConcurrent
            set(value) {
                load = load.copy(maxConcurrent = value)
                writes += PolicyField.LOAD_MAX_CONCURRENT
            }

        /**
         * L2 条数上限（[LocalPolicy.maximumSize]）。赋值即记为写出，与当前值相同也算：整体赋值 [local] 只记录值有变化的字段，
         * 显式写出的默认值（注解 `@CaffeineCache(maximumSize = 10000)`）经它才记为写出。
         */
        var localMaximumSize: Long
            get() = local.maximumSize
            set(value) {
                local = local.copy(maximumSize = value)
                writes += PolicyField.LOCAL_MAXIMUM_SIZE
            }
        var invalidateFailure: InvalidateFailureMode? = policy.invalidateFailure
            set(value) {
                field = value
                writes += PolicyField.INVALIDATE_FAILURE
            }
        var readFailure: ReadFailureMode? = policy.readFailure
            set(value) {
                field = value
                writes += PolicyField.READ_FAILURE
            }
        var readTimeout: Duration? = policy.readTimeout
            set(value) {
                field = value
                writes += PolicyField.READ_TIMEOUT
            }
        var tombstoneTtl: Duration? = policy.tombstoneTtl
            set(value) {
                field = value
                writes += PolicyField.TOMBSTONE_TTL
            }
        var ttlCeiling: Duration? = policy.ttlCeiling

        /**
         * 显式的“永不过期”：`ttl = forever()`（与 `null` 相同，写出时更清楚）。
         */
        @Suppress("FunctionOnlyReturningConstant") // DSL 中写出意图的哨兵：`ttl = forever()`
        fun forever(): Duration? = null

        /**
         * 显式的“关闭”：`refreshWindow = off()`。
         */
        @Suppress("FunctionOnlyReturningConstant") // DSL 中写出意图的哨兵：`ttl = forever()`
        fun off(): Duration? = null

        /**
         * 显式的“不限”：`maxConcurrent = unlimited()`（经 [maxConcurrent] 赋值才记为写出）。
         */
        @Suppress("FunctionOnlyReturningConstant") // DSL 中写出意图的哨兵：`ttl = forever()`
        fun unlimited(): Int? = null

        fun ttl(ttl: Duration?) = apply { this.ttl = ttl }

        /**
         * 类型化的寿命（Java：`ttl(Ttl.FOREVER)`、`ttl(Ttl.of(duration))`）：哨兵有自己的类型，写不成别的字段的哨兵
         * （`ttl = off()` 编译得过，含义却是永不过期）。
         */
        fun ttl(ttl: Ttl) = apply { this.ttl = ttl.toDuration() }
        fun ttlJitter(ttlJitter: Duration) = apply { this.ttlJitter = ttlJitter }
        fun absentTtl(absentTtl: Duration) = apply { this.absentTtl = absentTtl }
        fun localMaxAge(localMaxAge: Duration) = apply { this.localMaxAge = localMaxAge }
        fun degradedLocalMaxAge(
            degradedLocalMaxAge: Duration
        ) = apply { this.degradedLocalMaxAge = degradedLocalMaxAge }
        fun refreshWindow(refreshWindow: Duration?) = apply { this.refreshWindow = refreshWindow }

        /**
         * 类型化的刷新窗口（Java：`refreshWindow(RefreshWindow.OFF)`）。
         */
        fun refreshWindow(refreshWindow: RefreshWindow) = apply { this.refreshWindow = refreshWindow.toDuration() }
        fun lease(lease: LeasePolicy) = apply { this.lease = lease }
        fun load(load: LoadPolicy) = apply { this.load = load }
        fun local(local: LocalPolicy) = apply { this.local = local }
        fun maxConcurrent(maxConcurrent: Int?) = apply { this.maxConcurrent = maxConcurrent }

        /**
         * 类型化的回源并发上限（Java：`maxConcurrent(Concurrency.UNLIMITED)`），同 [maxConcurrent] 的赋值，记为写出。
         */
        fun maxConcurrent(maxConcurrent: Concurrency) = apply { this.maxConcurrent = maxConcurrent.toPermits() }
        fun localMaximumSize(localMaximumSize: Long) = apply { this.localMaximumSize = localMaximumSize }
        fun invalidateFailure(
            invalidateFailure: InvalidateFailureMode?
        ) = apply { this.invalidateFailure = invalidateFailure }

        fun readFailure(readFailure: ReadFailureMode?) = apply { this.readFailure = readFailure }
        fun readTimeout(readTimeout: Duration?) = apply { this.readTimeout = readTimeout }
        fun tombstoneTtl(tombstoneTtl: Duration?) = apply { this.tombstoneTtl = tombstoneTtl }

        /**
         * 类型化的墓碑寿命（Java：`tombstoneTtl(Ttl.FOREVER)`）。
         */
        fun tombstoneTtl(tombstoneTtl: Ttl) = apply { this.tombstoneTtl = tombstoneTtl.toDuration() }
        fun ttlCeiling(ttlCeiling: Duration?) = apply { this.ttlCeiling = ttlCeiling }

        fun build(): CachePolicy = CachePolicy(
            ttl = ttl,
            ttlJitter = ttlJitter,
            absentTtl = absentTtl,
            localMaxAge = localMaxAge,
            degradedLocalMaxAge = degradedLocalMaxAge,
            refreshWindow = refreshWindow,
            lease = lease,
            load = load,
            local = local,
            invalidateFailure = invalidateFailure,
            readFailure = readFailure,
            readTimeout = readTimeout,
            tombstoneTtl = tombstoneTtl,
            ttlCeiling = ttlCeiling,
        )
    }

    companion object {
        @JvmField
        val DEFAULT_TTL: Duration = Duration.ofHours(1)

        @JvmField
        val DEFAULT_TTL_JITTER: Duration = Duration.ofMinutes(1)

        @JvmField
        val DEFAULT_ABSENT_TTL: Duration = Duration.ofMinutes(1)

        @JvmField
        val DEFAULT_LOCAL_MAX_AGE: Duration = Duration.ofMinutes(5)

        @JvmField
        val DEFAULT_DEGRADED_LOCAL_MAX_AGE: Duration = Duration.ofSeconds(10)

        /**
         * 以 [base] 为起点的构建器（同 `base.toBuilder()`）：起点通常是缓存类型的默认值，如 `CachePolicy.builder(DefaultPolicies.STORE)`。
         * 没有无参的形式：权威副本从派生副本的默认值起步会得到 1 小时的 `ttl`（S23）。
         */
        @JvmStatic
        fun builder(base: CachePolicy): Builder = Builder(base)
    }
}

/**
 * lease 策略。
 *
 * @param ttl lease 自动回收时长（节点崩溃的兜底），应大于 loader 的 p99 耗时
 * @param wait 其它节点持有 lease 时最多等待的时长；超时后自行回源但不写 L1
 */
data class LeasePolicy @JvmOverloads constructor(
    val ttl: Duration = Duration.ofSeconds(10),
    val wait: Duration = Duration.ofMillis(200),
) {
    init {
        requirePositive("lease.ttl", ttl)
        require(!wait.isNegative && wait <= MAX_DURATION) { "lease.wait[$wait] must be within [0, $MAX_DURATION]." }
    }
}

/**
 * 回源并发上限（bulkhead）：只计 SingleFlight leader，批量回源占一个许可。
 *
 * @param maxConcurrent 每个缓存同时进行的回源数上限；`null`（默认）表示不限制，没有隐式的 `0` 哨兵（RFC 0005 §4.8）
 * @param acquireTimeout 等待许可的最长时间，超时抛出 [me.ahoo.cocache.core.SourceLoadRejectedException]
 * @param timeout 一次加载的上限（RFC 0005 §4.4），超过它以 [me.ahoo.cocache.core.SourceLoadTimeoutException] 失败：
 *   - 异步数据源（`AsyncCacheLoader`）的一次加载：失败并释放 lease 与回源许可；数据源返回的 future 不会被取消，
 *     它可能仍在执行（此时不再计入并发上限），迟到的结果被丢弃。由 JDK 的延迟线程计时（`completeOnTimeout`）；
 *   - 跟随者等待共享的加载（截止时刻是这次加载登记时加上它，全部跟随者共用，同步与异步的跟随者都是）；
 *   - 加载在 SingleFlight 中登记超过它时被注销（不被完成）：新的调用方开始新的加载，不再合并到卡住的同步 loader 后面，
 *     迟到的结果只交给原 leader，写回仍受令牌与戳保护。
 *
 *   同步数据源本身无法被外部结束，应自带超时。
 */
data class LoadPolicy @JvmOverloads constructor(
    val maxConcurrent: Int? = null,
    val acquireTimeout: Duration = Duration.ofSeconds(1),
    val timeout: Duration = DEFAULT_TIMEOUT,
) {
    init {
        maxConcurrent?.let { require(it > 0) { "load.maxConcurrent[$it] must be positive; null means unlimited." } }
        require(
            !acquireTimeout.isNegative && acquireTimeout <= MAX_DURATION
        ) { "load.acquireTimeout[$acquireTimeout] must be within [0, $MAX_DURATION]." }
        requirePositive("load.timeout", timeout)
    }

    companion object {
        @JvmField
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

/**
 * L2（Caffeine）容量。
 *
 * @param expireAfterAccess 空闲淘汰时长；`null` 表示不启用（启用后每次读取都写访问时间，热点高并发场景慎用）
 */
data class LocalPolicy @JvmOverloads constructor(
    val maximumSize: Long = 10_000,
    val initialCapacity: Int? = null,
    val expireAfterAccess: Duration? = null,
) {
    init {
        require(maximumSize > 0) { "local.maximumSize[$maximumSize] must be positive." }
        initialCapacity?.let { require(it >= 0) { "local.initialCapacity[$it] must not be negative." } }
        expireAfterAccess?.let { requirePositive("local.expireAfterAccess", it) }
    }
}

/**
 * 派生缓存（`Cache`）的失效写入 L1 失败时的处理方式（见 [CachePolicy.invalidateFailure]）。
 */
enum class InvalidateFailureMode {
    /**
     * 进入有界重试队列，不抛出（默认）。失效通常在事务提交之后调用，抛出既不能回滚也不该回滚；删除副本在任何时刻都安全，之后回源即可。
     */
    RETRY,

    /**
     * 抛出 [me.ahoo.cocache.api.CacheWriteException]，不登记重试。
     */
    THROW,
}

/**
 * 带数据源的读取在 L1 读失败时的处理方式（RFC 0003 §4.13，见 [CachePolicy.readFailure]）。
 */
enum class ReadFailureMode {
    /**
     * 降级为直接回源，结果只以降级限龄进入 L2，不写 L1。L1 故障时由回源并发上限保护数据源（默认）。
     */
    DEGRADE,

    /**
     * 读取的结果为 `Lookup.Unknown`：`get` 抛出 [me.ahoo.cocache.api.CacheReadException]，`lookup` 返回它。
     * 只作用于读取；填充、释放令牌等尽力而为的操作仍然降级。
     */
    THROW,
}

/**
 * 没有数据源的读取（权威副本的读取，外部来源的缓存上不带 loader 的读取）在 L1 读失败时（`Lookup.Unknown`）返回什么（RFC 0005 §4.1，S16）。
 *
 * 只作用于不带 loader 的 `get` / `getAll` / `lookup` / `lookupAll` 及其异步、挂起视图；不作用于 `getIfChanged`、`getVersioned`
 * 与条件写入，也不作用于带 loader 的读取（它们由 [ReadFailureMode] 决定）。映射发生在调用方返回之前：共享的加载结果仍是
 * `Unknown`，带 loader 的调用方不会接受它。两种取值都计入 `cocache.reads.unknown`。
 */
enum class UnknownReadMode {
    /**
     * `get` / `getAll` 抛出 [me.ahoo.cocache.api.CacheReadException]，`lookup` 返回 `Lookup.Unknown`（默认，fail closed）。
     */
    THROW,

    /**
     * 当作不存在：`get` 返回 `null`，`getAll` 中缺席，`lookup` 返回 `Lookup.Absent`；不写入任何一层，下一次读取重新访问 L1。
     * 适用于把“读不到”当作安全答案的数据（如撤销表：Redis 故障即视为未撤销）。不能用于 `SetStore`。
     */
    ABSENT,
}

/**
 * 策略时长的上限（100 年）：保证“当前时间 + 时长”的毫秒运算不会溢出。
 */
internal val MAX_DURATION: Duration = Duration.ofDays(36_500)

private fun requirePositive(name: String, duration: Duration) {
    require(!duration.isNegative && !duration.isZero && duration <= MAX_DURATION) {
        "$name[$duration] must be positive and at most $MAX_DURATION."
    }
}

/**
 * 把（可能来自用户 `ttlOf` 的）寿命收敛到 [1 毫秒, [MAX_DURATION]]。
 */
internal fun Duration.boundedTtl(): Duration = when {
    this > MAX_DURATION -> MAX_DURATION
    this < Duration.ofMillis(1) -> Duration.ofMillis(1)
    else -> this
}
