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

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.LeasePolicy
import me.ahoo.cocache.core.policy.LoadPolicy
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.redis.RedisBackendOptions
import me.ahoo.cocache.redis.RedisChannelMode
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.boot.convert.DurationUnit
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * CoCache 配置（`cocache.*`）。
 *
 * 策略来源的优先级（后者覆盖前者）：内置默认值 → 声明（spec）→ [me.ahoo.cocache.spring.boot.CacheSpecCustomizer]
 * → `cocache.defaults.*` → `cocache.caches.[name].*`。
 *
 * 构造函数绑定：默认值用 [DefaultValue] 声明而不用 Kotlin 默认参数。参数全有默认值时 Kotlin 会额外生成无参构造函数，
 * 配置处理器与（没有 kotlin-reflect 时的）绑定器都会因此退回 JavaBean 方式，只读属性全部丢失；
 * [DefaultValue] 同时不依赖 kotlin-reflect，并把默认值写入配置元数据。
 */
@ConfigurationProperties(prefix = CoCacheProperties.PREFIX)
data class CoCacheProperties @ConstructorBinding constructor(
    /**
     * 是否启用 CoCache 自动配置。
     */
    @DefaultValue("true")
    val enabled: Boolean,
    /**
     * 作用于所有缓存的策略覆盖（优先级低于 `cocache.caches.[name].*`）。
     */
    @DefaultValue
    val defaults: PolicyProperties,
    /**
     * 按缓存名覆盖策略（缓存名不区分大小写）；出现未声明的缓存名时启动失败。
     */
    @DefaultValue
    val caches: Map<String, PolicyProperties>,
    /**
     * Redis 后端（L1 与失效通道）。
     */
    @DefaultValue
    val redis: Redis,
) {
    /**
     * 按缓存名合并配置文件中的策略。
     */
    fun policyFor(cacheName: String, declared: CachePolicy): CachePolicy {
        val withDefaults = defaults.applyTo(declared)
        return cacheProperties(cacheName)?.applyTo(withDefaults) ?: withDefaults
    }

    /**
     * 按缓存名查找 `cocache.caches.[name]`：先精确匹配，再忽略大小写匹配（环境变量绑定的 key 是小写）。
     */
    fun cacheProperties(cacheName: String): PolicyProperties? {
        return caches[cacheName] ?: caches.entries.firstOrNull { it.key.equals(cacheName, ignoreCase = true) }?.value
    }

    /**
     * Redis 后端选项。时长未写单位时按毫秒解析（与 `spring.data.redis.timeout` 一致）。
     */
    data class Redis @ConstructorBinding constructor(
        /**
         * 单条命令（脚本）的等待上限（未写单位按毫秒）。
         */
        @DefaultValue("2s")
        val commandTimeout: Duration,
        /**
         * 订阅连接的心跳周期：PING 失败或超时即重置所有订阅者（未写单位按毫秒）。
         */
        @DefaultValue("5s")
        val heartbeat: Duration,
        /**
         * 单个失效脚本处理的 key 数上限。
         */
        @DefaultValue("512")
        val invalidateBatchSize: Int,
        /**
         * CoCache 自建 Redis 连接的连接名（`CLIENT SETNAME`）。
         */
        val clientName: String?,
        /**
         * 失效通道模式：`pubsub`（默认，PUBLISH/SUBSCRIBE）或 `sharded`（SPUBLISH/SSUBSCRIBE，Redis 7+）。
         * 两者互不相通，全集群必须一致。
         */
        @DefaultValue("pubsub")
        val channelMode: RedisChannelMode,
    ) {
        fun toOptions(): RedisBackendOptions = RedisBackendOptions(
            commandTimeout,
            heartbeat,
            invalidateBatchSize,
            clientName = clientName,
            channelMode = channelMode,
        )
    }

    /**
     * 策略的可覆盖字段：未设置（`null`）的字段沿用下层的值。时长未写单位时按秒解析（与注解一致），也可以写 `500ms`、`2m`、`PT2M`。
     */
    data class PolicyProperties @ConstructorBinding constructor(
        /**
         * 命中值寿命（未写单位按秒；内置默认 1h）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val ttl: Duration?,
        /**
         * 为 `true` 时命中值永不过期（覆盖 ttl）。
         */
        val ttlForever: Boolean?,
        /**
         * 命中值寿命的随机抖动幅度，实际幅度不超过 ttl 的一半（未写单位按秒；内置默认 1m）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val ttlJitter: Duration?,
        /**
         * 负缓存（数据源返回 null）的寿命（未写单位按秒；内置默认 1m）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val absentTtl: Duration?,
        /**
         * L2 副本的最长寿命：失效事件丢失时 L2 陈旧度的上界（未写单位按秒；内置默认 5m）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val localMaxAge: Duration?,
        /**
         * 未写入 L1 的填充（L1 故障等）在 L2 中的最长寿命（未写单位按秒；内置默认 10s）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val degradedLocalMaxAge: Duration?,
        /**
         * 提前刷新窗口：剩余寿命小于该值时命中触发后台刷新；必须小于 ttl - ttl-jitter 与 local-max-age（未写单位按秒；内置默认关闭）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val refreshWindow: Duration?,
        /**
         * lease 自动回收时长（节点崩溃的兜底），应大于数据源的 p99 耗时（未写单位按秒；内置默认 10s）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val leaseTtl: Duration?,
        /**
         * 其它节点持有 lease 时最多等待的时长；超时后自行回源但不写 L1（未写单位按秒；内置默认 200ms）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val leaseWait: Duration?,
        /**
         * 每个缓存同时进行的回源数上限（bulkhead）；`0` 表示不限制（内置默认 0）。
         */
        val loadMaxConcurrent: Int?,
        /**
         * 等待回源许可的最长时间，超时抛出 `SourceLoadRejectedException`（未写单位按秒；内置默认 1s）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val loadAcquireTimeout: Duration?,
        /**
         * L2（Caffeine）的最大条目数（内置默认 10000）。
         */
        val localMaximumSize: Long?,
        /**
         * 失效写入 L1 失败时的处理方式：`retry`（默认，有界重试队列）或 `throw`。
         */
        val invalidateFailure: InvalidateFailureMode?,
        /**
         * 读取 L1 失败时的处理方式：`degrade`（默认，降级回源）或 `throw`（对应 5.x 的 `cocache.redis.strict-failure=true`）。
         */
        val readFailure: ReadFailureMode?,
    ) {
        fun applyTo(policy: CachePolicy): CachePolicy {
            val builder = policy.toBuilder()
            applyLifetimes(builder)
            applyLease(builder, policy)
            applyLoad(builder, policy)
            localMaximumSize?.let { builder.local = policy.local.copy(maximumSize = it) }
            invalidateFailure?.let { builder.invalidateFailure = it }
            readFailure?.let { builder.readFailure = it }
            return builder.build()
        }

        private fun applyLifetimes(builder: CachePolicy.Builder) {
            when {
                ttlForever == true -> builder.ttl = null
                ttl != null -> builder.ttl = ttl
            }
            ttlJitter?.let { builder.ttlJitter = it }
            absentTtl?.let { builder.absentTtl = it }
            localMaxAge?.let { builder.localMaxAge = it }
            degradedLocalMaxAge?.let { builder.degradedLocalMaxAge = it }
            refreshWindow?.let { builder.refreshWindow = it }
        }

        private fun applyLease(builder: CachePolicy.Builder, policy: CachePolicy) {
            if (leaseTtl != null || leaseWait != null) {
                builder.lease = LeasePolicy(leaseTtl ?: policy.lease.ttl, leaseWait ?: policy.lease.wait)
            }
        }

        private fun applyLoad(builder: CachePolicy.Builder, policy: CachePolicy) {
            if (loadMaxConcurrent != null || loadAcquireTimeout != null) {
                builder.load = LoadPolicy(loadMaxConcurrent ?: policy.load.maxConcurrent, loadAcquireTimeout ?: policy.load.acquireTimeout)
            }
        }
    }

    companion object {
        const val PREFIX = "cocache"
    }
}
