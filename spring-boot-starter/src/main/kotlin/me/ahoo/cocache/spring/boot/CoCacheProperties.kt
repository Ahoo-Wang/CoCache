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
    /**
     * 一致性后端：`redis`（默认，Spring Data Redis 的连接信息）或 `memory`（进程内 `InMemoryBackend`，
     * 不需要 Redis，只用于本地开发与测试：没有跨实例的一致性）。已声明 `CoherenceBackend` bean 时两者都退让。
     */
    @DefaultValue("redis")
    val backend: Backend,
    /**
     * Spring Cache 集成。
     */
    @DefaultValue
    val springCache: SpringCache,
    /**
     * 提前刷新（refresh-ahead）回源所用 `java.util.concurrent.Executor` 的 bean 名（只按名称解析）；
     * 未设置时使用 CoCache 内置的有界守护线程池（队列满时跳过刷新并计数）。bean 不存在或不是 `Executor` 时启动失败。
     * 该执行器由容器管理，运行时关闭时不会关闭它。
     */
    val refreshExecutor: String?,
    /**
     * 异步视图（`cache.async()`，RFC 0004）续接所用 `java.util.concurrent.Executor` 的 bean 名（只按名称解析）；
     * 只承载短小的非阻塞工作。未设置时运行时自建 `ForkJoinPool`（并行度为 CPU 数，`cocache-async-*`）。
     * bean 不存在或不是 `Executor` 时启动失败；该执行器由容器管理，运行时关闭时不会关闭它。
     */
    val asyncExecutor: String? = null,
    /**
     * 异步视图上同步数据源所用 `java.util.concurrent.Executor` 的 bean 名（只按名称解析），不应排队。
     * 未设置时每个任务一个虚拟线程。
     * bean 不存在或不是 `Executor` 时启动失败；该执行器由容器管理，运行时关闭时不会关闭它。
     */
    val blockingExecutor: String? = null,
) {
    /**
     * 按缓存名合并配置文件中的策略（`CacheSpec`；集合缓存见 [setPolicyFor]）：先 `cocache.defaults.*`，再 `cocache.caches.[name].*`。
     * 带版本的缓存照常提前刷新（RFC 0005 §4.7），`cocache.defaults.refresh-window` 同样作用于它们。
     */
    fun policyFor(cacheName: String, declared: CachePolicy): CachePolicy {
        val withDefaults = defaults.applyTo(declared)
        return cacheProperties(cacheName)?.applyTo(withDefaults) ?: withDefaults
    }

    /**
     * 权威副本（`Store`、`VersionedStore`、`SetStore`）的策略：`cocache.defaults.ttl` / `ttl-forever` / `refresh-window` 不作用于它们
     * （L1 是唯一副本，全局寿命会让数据过期丢失；没有数据源，提前刷新不适用），只有 `cocache.caches.[name].*` 能改变它们。
     * 其它默认项照常生效。分层的语义随 RFC 0005 M6 重写。
     */
    fun authoritativePolicyFor(cacheName: String, declared: CachePolicy): CachePolicy {
        // 权威副本没有数据源：全局的寿命与提前刷新都不适用（提前刷新还会让校验因无关配置失败）
        val withDefaults = defaults.copy(
            ttl = null,
            ttlForever = null,
            refreshWindow = null
        ).applyTo(declared)
        return cacheProperties(cacheName)?.applyTo(withDefaults) ?: withDefaults
    }

    /**
     * 配置文件中的 key 前缀：`cocache.caches.[name].key-prefix` 优先，其次 `cocache.defaults.key-prefix`；都没有时为 `null`
     * （沿用声明与定制器的值）。与策略字段同一规则：配置文件覆盖声明（含注解或 spec 中显式写出的 `keyPrefix`）。
     */
    fun keyPrefixFor(cacheName: String): String? = cacheProperties(cacheName)?.keyPrefix ?: defaults.keyPrefix

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
        /**
         * 有权威副本（`Store`、`VersionedStore`、`SetStore`，含尚未创建的声明）时启动检查各主节点的 `maxmemory-policy`（读取 `INFO memory`）：
         * `allkeys-*` 会驱逐唯一副本，`volatile-*` 会驱逐 `ttl` 有限的权威缓存（只告警）。`warn`（默认）记录告警，`fail` 启动失败，`off` 不检查。
         * 读不到策略且没有配置 `assume-maxmemory-policy` 时同样按此处理。
         */
        @DefaultValue("warn")
        val storeEvictionCheck: EvictionCheck,
        /**
         * 读不到 `maxmemory-policy`（Redis 用户没有 `INFO` 权限、托管服务禁用了它）时，驱逐检查按这个值判断，
         * 如 `noeviction`、`volatile-lru`；必须是 `noeviction`、`allkeys-*` 或 `volatile-*`。读得到时以实际的值为准。
         */
        val assumeMaxmemoryPolicy: String? = null,
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
     * 权威副本驱逐检查的处理方式。
     */
    enum class EvictionCheck {
        WARN,
        FAIL,
        OFF,
    }

    /**
     * 一致性后端类型。
     */
    enum class Backend {
        /**
         * Redis（`RedisBackend`），由 Spring Data Redis 的连接信息构造。
         */
        REDIS,

        /**
         * 进程内内存后端（`InMemoryBackend`）：单进程，没有跨实例一致性，只用于本地开发与测试。
         */
        MEMORY,
    }

    /**
     * Spring Cache 集成（`cocache.spring-cache.*`）。
     */
    data class SpringCache @ConstructorBinding constructor(
        /**
         * 指向 CoCache 缓存的 `@Cacheable` 没有 `sync = true` 时启动失败（默认 `false`：只记录告警）。
         * 不带 `sync` 的 `@Cacheable` 由 Spring 执行“读未命中 → 调用方法 → put”，不受 lease 与失效戳保护。
         */
        @DefaultValue("false")
        val requireSync: Boolean,
        /**
         * 缓存通知改用 `DegradingCacheErrorHandler`（默认 `false`）：CoCache 缓存的读写失败只记录告警，不抛给调用方。
         * 不带 `sync` 的 `@Cacheable` 在 L1 故障时因此按未命中执行方法（带 `sync = true` 的本来就降级回源），
         * `@CachePut` 的写入失败不再让调用失败；失效与清空失败、其它缓存的失败仍交给原来的处理器。
         */
        @DefaultValue("false")
        val degradeOnError: Boolean,
    )

    /**
     * 策略的可覆盖字段：未设置（`null`）的字段沿用下层的值。时长未写单位时按秒解析（与注解一致），也可以写 `500ms`、`2m`、`PT2M`。
     */
    data class PolicyProperties @ConstructorBinding constructor(
        /**
         * 命中值寿命（未写单位按秒；内置默认 1h）。`cocache.defaults.ttl` 不作用于集合缓存（其 L1 是唯一副本）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val ttl: Duration?,
        /**
         * 为 `true` 时命中值永不过期（覆盖 ttl）。`cocache.defaults.ttl-forever` 不作用于集合缓存。
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
         * 提前刷新窗口：剩余寿命小于该值时命中触发后台刷新；必须小于 ttl - ttl-jitter 与 local-max-age（未写单位按秒；内置默认关闭；0 表示关闭）。
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
         * 失效写入 L1 失败时的处理方式：`retry`（有界重试队列）或 `throw`。未设置时按缓存有没有数据源决定：有数据源 `retry`，
         * 没有数据源 `throw`（L1 是唯一副本，延迟的重试会删掉失败之后写入的值）。
         */
        val invalidateFailure: InvalidateFailureMode?,
        /**
         * 读取 L1 失败时的处理方式：`degrade`（降级回源）或 `throw`。
         * 未设置时按这次读取有没有数据源决定：有数据源 `degrade`，没有数据源 `throw`（没有可降级的答案）。没有数据源时显式设置
         * `degrade`：读到不存在，不缓存（如撤销表：Redis 故障即视为未撤销）。
         */
        val readFailure: ReadFailureMode?,
        /**
         * L1 key 前缀（内置默认 `cocache:`），覆盖声明与定制器中的值；可以使用占位符，如 `${spring.application.name}:`。
         * 存储 key 为 `{keyPrefix}{namespace}:{key}`，失效频道为 `{keyPrefix}inv:{namespace}`：前缀不同、namespace 相同的应用
         * 既不共用 key，也收不到彼此的失效帧。
         */
        val keyPrefix: String?,
        /**
         * 一次加载的上限（未写单位按秒；内置默认 30s），超过它以 `SourceLoadTimeoutException` 失败：异步数据源（`AsyncCacheLoader`）的一次加载
         * （释放 lease），以及等待共享加载的读取；在途登记超过它的加载被注销，之后的读取开始新的加载。同步数据源本身无法被外部结束，应自带超时。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        val loadTimeout: Duration? = null,
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
            // 0 表示关闭（与注解一致），用于在配置文件中关掉声明里的提前刷新
            refreshWindow?.let { builder.refreshWindow = it.takeUnless(Duration::isZero) }
        }

        private fun applyLease(builder: CachePolicy.Builder, policy: CachePolicy) {
            if (leaseTtl != null || leaseWait != null) {
                builder.lease = LeasePolicy(leaseTtl ?: policy.lease.ttl, leaseWait ?: policy.lease.wait)
            }
        }

        private fun applyLoad(builder: CachePolicy.Builder, policy: CachePolicy) {
            // 只覆盖设置了的字段：其余沿用下层（含 timeout），不能用默认值重建
            if (loadMaxConcurrent != null || loadAcquireTimeout != null || loadTimeout != null) {
                builder.load = policy.load.copy(
                    maxConcurrent = loadMaxConcurrent ?: policy.load.maxConcurrent,
                    acquireTimeout = loadAcquireTimeout ?: policy.load.acquireTimeout,
                    timeout = loadTimeout ?: policy.load.timeout,
                )
            }
        }
    }

    companion object {
        const val PREFIX = "cocache"
    }
}
