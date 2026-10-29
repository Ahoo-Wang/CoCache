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
 * @param refreshWindow 提前刷新窗口；`null` 表示关闭
 * @param invalidateFailure 失效写入 L1 失败时的处理方式；`null`（默认）按缓存有没有数据源决定：有数据源时
 *   [InvalidateFailureMode.RETRY]，没有数据源时（L1 是唯一副本）[InvalidateFailureMode.THROW]
 * @param readFailure 读取 L1 失败时的处理方式
 * @param foreignFormat 读到本缓存读不懂的 L1 内容（格式 0：5.x 或其它程序写入的值、原生 Set / Hash 等其它 Redis 类型；
 *   以及同版本内容解码失败）时的处理方式；`null`（默认）按这次读取有没有数据源决定：有数据源时 [ForeignFormatMode.RECLAIM]，
 *   没有数据源时（`SetCache`、没有 loader 的 `Cache`）[ForeignFormatMode.FAIL]
 */
class CachePolicy(
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
    val readFailure: ReadFailureMode = ReadFailureMode.DEGRADE,
    val foreignFormat: ForeignFormatMode? = null,
) {
    /**
     * 6.4 及以前的构造函数（没有 [foreignFormat]），只为已编译的调用方保留二进制兼容。
     */
    @Deprecated("Binary compatibility with 6.4 and earlier.", level = DeprecationLevel.HIDDEN)
    @Suppress("LongParameterList")
    constructor(
        ttl: Duration? = DEFAULT_TTL,
        ttlJitter: Duration = DEFAULT_TTL_JITTER,
        absentTtl: Duration = DEFAULT_ABSENT_TTL,
        localMaxAge: Duration = DEFAULT_LOCAL_MAX_AGE,
        degradedLocalMaxAge: Duration = DEFAULT_DEGRADED_LOCAL_MAX_AGE,
        refreshWindow: Duration? = null,
        lease: LeasePolicy = LeasePolicy(),
        load: LoadPolicy = LoadPolicy(),
        local: LocalPolicy = LocalPolicy(),
        invalidateFailure: InvalidateFailureMode? = null,
        readFailure: ReadFailureMode = ReadFailureMode.DEGRADE,
    ) : this(
        ttl, ttlJitter, absentTtl, localMaxAge, degradedLocalMaxAge, refreshWindow, lease, load, local, invalidateFailure, readFailure, null
    )

    init {
        ttl?.let { requirePositive("ttl", it) }
        require(
            !ttlJitter.isNegative && ttlJitter <= MAX_DURATION
        ) { "ttlJitter[$ttlJitter] must be within [0, $MAX_DURATION]." }
        requirePositive("absentTtl", absentTtl)
        requirePositive("localMaxAge", localMaxAge)
        requirePositive("degradedLocalMaxAge", degradedLocalMaxAge)
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
     * 实际生效的抖动幅度：不超过寿命的一半，否则短寿命的值会大量以 1 毫秒寿命写入。
     */
    private fun effectiveJitter(ttl: Duration): Duration = minOf(ttlJitter, ttl.dividedBy(2))

    fun toBuilder(): Builder = Builder(this)

    override fun toString(): String {
        return "CachePolicy(ttl=$ttl, ttlJitter=$ttlJitter, absentTtl=$absentTtl, localMaxAge=$localMaxAge, " +
            "degradedLocalMaxAge=$degradedLocalMaxAge, refreshWindow=$refreshWindow, lease=$lease, load=$load, " +
            "local=$local, invalidateFailure=$invalidateFailure, readFailure=$readFailure, foreignFormat=$foreignFormat)"
    }

    /**
     * Java 友好的构建器。
     */
    class Builder internal constructor(policy: CachePolicy) {
        var ttl: Duration? = policy.ttl
        var ttlJitter: Duration = policy.ttlJitter
        var absentTtl: Duration = policy.absentTtl
        var localMaxAge: Duration = policy.localMaxAge
        var degradedLocalMaxAge: Duration = policy.degradedLocalMaxAge
        var refreshWindow: Duration? = policy.refreshWindow
        var lease: LeasePolicy = policy.lease
        var load: LoadPolicy = policy.load
        var local: LocalPolicy = policy.local
        var invalidateFailure: InvalidateFailureMode? = policy.invalidateFailure
        var readFailure: ReadFailureMode = policy.readFailure
        var foreignFormat: ForeignFormatMode? = policy.foreignFormat

        fun ttl(ttl: Duration?) = apply { this.ttl = ttl }
        fun ttlJitter(ttlJitter: Duration) = apply { this.ttlJitter = ttlJitter }
        fun absentTtl(absentTtl: Duration) = apply { this.absentTtl = absentTtl }
        fun localMaxAge(localMaxAge: Duration) = apply { this.localMaxAge = localMaxAge }
        fun degradedLocalMaxAge(
            degradedLocalMaxAge: Duration
        ) = apply { this.degradedLocalMaxAge = degradedLocalMaxAge }
        fun refreshWindow(refreshWindow: Duration?) = apply { this.refreshWindow = refreshWindow }
        fun lease(lease: LeasePolicy) = apply { this.lease = lease }
        fun load(load: LoadPolicy) = apply { this.load = load }
        fun local(local: LocalPolicy) = apply { this.local = local }
        fun invalidateFailure(
            invalidateFailure: InvalidateFailureMode?
        ) = apply { this.invalidateFailure = invalidateFailure }

        fun readFailure(readFailure: ReadFailureMode) = apply { this.readFailure = readFailure }
        fun foreignFormat(foreignFormat: ForeignFormatMode?) = apply { this.foreignFormat = foreignFormat }

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
            foreignFormat = foreignFormat,
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

        @JvmField
        val DEFAULT: CachePolicy = CachePolicy()

        @JvmStatic
        fun builder(): Builder = Builder(DEFAULT)
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
 * @param maxConcurrent 每个缓存同时进行的回源数上限；`0` 表示不限制
 * @param acquireTimeout 等待许可的最长时间，超时抛出 [me.ahoo.cocache.core.SourceLoadRejectedException]
 * @param asyncTimeout 异步数据源（`AsyncCacheLoader`）一次加载的最长时间，超时以
 *   [me.ahoo.cocache.core.SourceLoadTimeoutException] 失败并释放 lease 与回源许可（RFC 0004 §5.2.1）；数据源返回的 future 不会被取消，
 *   它可能仍在执行（此时不再计入并发上限），迟到的结果被丢弃。由 JDK 的延迟线程计时（`completeOnTimeout`）。不作用于同步数据源
 */
data class LoadPolicy @JvmOverloads constructor(
    val maxConcurrent: Int = 0,
    val acquireTimeout: Duration = Duration.ofSeconds(1),
    val asyncTimeout: Duration = DEFAULT_ASYNC_TIMEOUT,
) {
    init {
        require(maxConcurrent >= 0) { "load.maxConcurrent[$maxConcurrent] must not be negative." }
        require(
            !acquireTimeout.isNegative && acquireTimeout <= MAX_DURATION
        ) { "load.acquireTimeout[$acquireTimeout] must be within [0, $MAX_DURATION]." }
        requirePositive("load.asyncTimeout", asyncTimeout)
    }

    companion object {
        @JvmField
        val DEFAULT_ASYNC_TIMEOUT: Duration = Duration.ofSeconds(30)
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
 * 失效写入 L1 失败时的处理方式。未配置时按缓存有没有数据源决定（见 [CachePolicy.invalidateFailure]）。
 */
enum class InvalidateFailureMode {
    /**
     * 进入有界重试队列，不抛出。失效通常在事务提交之后调用，抛出既不能回滚也不该回滚。
     *
     * 有数据源的缓存的默认值：删除总是安全的，之后回源即可。没有数据源时（L1 是唯一副本）延迟的重试会删掉
     * 失败之后其它写入方写入的值（丢更新），显式配置即接受这个代价。
     */
    RETRY,

    /**
     * 抛出 [me.ahoo.cocache.api.CacheWriteException]，不登记重试。
     *
     * 没有数据源的缓存的默认值：L1 是唯一副本，调用方得知失败后自行决定（如重试 `invalidate`，或改为写入新值）。
     */
    THROW,
}

/**
 * 读取 L1 失败时的处理方式（RFC 0003 §4.13）。
 */
enum class ReadFailureMode {
    /**
     * 降级为直接回源，结果只以降级限龄进入 L2，不写 L1（默认）。L1 故障时由回源并发上限保护数据源。
     */
    DEGRADE,

    /**
     * 抛出 [me.ahoo.cocache.api.CacheReadException]。
     * 只作用于读取；填充、释放令牌等尽力而为的操作仍然降级。
     */
    THROW,
}

/**
 * 读到本缓存读不懂的 L1 内容时的处理方式。读不懂的内容包括：
 * - 格式 0：不是本协议写入的内容，例如 CoCache 5.x 写入的 JSON / 字符串 / 原生 Set / Hash，或其它程序写入的值；
 * - 同版本内容解码失败（`Codec` 抛出，例如值类型不兼容地改变）。
 *
 * 更高格式版本（滚动升级期间）不受影响：既不回收也不写回（[me.ahoo.cocache.api.Cache] 回源，`SetCache` 抛出）。
 */
enum class ForeignFormatMode {
    /**
     * 抛出 [me.ahoo.cocache.api.CacheReadException]（`cause` 为 [me.ahoo.cocache.core.ForeignFormatException]），
     * 计入 `unknownFormats`，不修改 L1、不写 L2。与 [ReadFailureMode] 无关：降级模式下同样抛出。
     *
     * 没有数据源的读取的默认值：L1 是唯一副本，回收就是删除，调用方会把“不存在”当作真实结果（例如授权数据消失后放行）。
     */
    FAIL,

    /**
     * 条件回收（比较摘要后转为 lease）并回源，与 6.4 及以前的行为相同。`SetCache` 没有数据源，按不存在处理（读到空集合），
     * 不回收、不删除，下一次 `put` / `add` 整体覆盖。
     *
     * 有数据源的读取的默认值：数据源是权威，回收后重新加载即可。
     */
    RECLAIM,
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
