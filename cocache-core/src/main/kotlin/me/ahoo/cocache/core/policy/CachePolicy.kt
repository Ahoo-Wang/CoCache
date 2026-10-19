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
 * @param ttlJitter 命中值寿命的随机抖动幅度（防止批量同时到期）
 * @param absentTtl 负缓存寿命（不抖动）
 * @param localMaxAge L2 副本的最长寿命：失效事件丢失时 L2 陈旧度的上界
 * @param degradedLocalMaxAge 未写入 L1 的填充（L1 故障、等待 lease 超时、fill 被拒绝）在 L2 中的最长寿命
 * @param refreshWindow 提前刷新窗口；`null` 表示关闭
 * @param invalidateFailure 失效写入 L1 失败时的处理方式
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
    val invalidateFailure: InvalidateFailureMode = InvalidateFailureMode.RETRY,
) {
    init {
        ttl?.let { requirePositive("ttl", it) }
        require(!ttlJitter.isNegative) { "ttlJitter[$ttlJitter] must not be negative." }
        requirePositive("absentTtl", absentTtl)
        requirePositive("localMaxAge", localMaxAge)
        requirePositive("degradedLocalMaxAge", degradedLocalMaxAge)
        refreshWindow?.let { window ->
            requirePositive("refreshWindow", window)
            // 窗口不小于寿命时，每个新副本都处于窗口内，命中会持续触发刷新
            require(ttl == null || window < ttl) { "refreshWindow[$window] must be less than ttl[$ttl]." }
            require(window < localMaxAge) { "refreshWindow[$window] must be less than localMaxAge[$localMaxAge]." }
        }
    }

    /**
     * 按策略计算命中值寿命（含抖动，至少 1 毫秒）；`null` 表示永不过期。
     */
    fun valueTtl(): Duration? {
        val base = ttl ?: return null
        val amplitude = ttlJitter.toMillis()
        if (amplitude == 0L) {
            return base
        }
        val millis = base.toMillis() + ThreadLocalRandom.current().nextLong(-amplitude, amplitude + 1)
        return Duration.ofMillis(millis.coerceAtLeast(1))
    }

    fun toBuilder(): Builder = Builder(this)

    override fun toString(): String {
        return "CachePolicy(ttl=$ttl, ttlJitter=$ttlJitter, absentTtl=$absentTtl, localMaxAge=$localMaxAge, " +
            "degradedLocalMaxAge=$degradedLocalMaxAge, refreshWindow=$refreshWindow, lease=$lease, load=$load, " +
            "local=$local, invalidateFailure=$invalidateFailure)"
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
        var invalidateFailure: InvalidateFailureMode = policy.invalidateFailure

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
            invalidateFailure: InvalidateFailureMode
        ) = apply { this.invalidateFailure = invalidateFailure }

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
data class LeasePolicy(
    val ttl: Duration = Duration.ofSeconds(10),
    val wait: Duration = Duration.ofMillis(200),
) {
    init {
        requirePositive("lease.ttl", ttl)
        require(!wait.isNegative) { "lease.wait[$wait] must not be negative." }
    }
}

/**
 * 回源并发上限（bulkhead）：只计 SingleFlight leader，批量回源占一个许可。
 *
 * @param maxConcurrent 每个缓存同时进行的回源数上限；`0` 表示不限制
 * @param acquireTimeout 等待许可的最长时间，超时抛出 [me.ahoo.cocache.core.SourceLoadRejectedException]
 */
data class LoadPolicy(
    val maxConcurrent: Int = 0,
    val acquireTimeout: Duration = Duration.ofSeconds(1),
) {
    init {
        require(maxConcurrent >= 0) { "load.maxConcurrent[$maxConcurrent] must not be negative." }
        require(!acquireTimeout.isNegative) { "load.acquireTimeout[$acquireTimeout] must not be negative." }
    }
}

/**
 * L2（Caffeine）容量。
 *
 * @param expireAfterAccess 空闲淘汰时长；`null` 表示不启用（启用后每次读取都写访问时间，热点高并发场景慎用）
 */
data class LocalPolicy(
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
 * 失效写入 L1 失败时的处理方式。
 */
enum class InvalidateFailureMode {
    /**
     * 进入有界重试队列，不抛出（默认）。失效通常在事务提交之后调用，抛出既不能回滚也不该回滚。
     */
    RETRY,

    /**
     * 抛出 [me.ahoo.cocache.api.CacheWriteException]。
     */
    THROW,
}

private fun requirePositive(name: String, duration: Duration) {
    require(!duration.isNegative && !duration.isZero) { "$name[$duration] must be positive." }
}
