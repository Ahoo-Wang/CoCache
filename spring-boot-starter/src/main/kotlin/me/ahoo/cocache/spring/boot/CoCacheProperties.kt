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

import me.ahoo.cocache.core.policy.InvalidateFailureMode
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cocache.redis.RedisBackendOptions
import me.ahoo.cocache.redis.RedisChannelMode
import me.ahoo.cocache.spring.tx.FillInTransaction
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * CoCache 配置（`cocache.*`）。策略按字段分层（RFC 0005 §4.8），由低到高：
 *
 * 1. 类型默认值（`DefaultPolicies`）；
 * 2. `cocache.defaults.cache.*` / `cocache.defaults.store.*`：全局默认值，**低于**声明，只作用于声明中没有写出的字段；
 * 3. 声明（注解或 spec）；
 * 4. [CacheSpecCustomizer]；
 * 5. `cocache.overrides.cache.*` / `cocache.overrides.store.*`：全局覆盖，**只收紧**：陈旧界限取 min（`ttl` 另钳制按调用给出的寿命），
 *    `read-failure` 取更严的值，其余字段不允许；
 * 6. `cocache.caches.[name].*`：按缓存设定。
 *
 * `cache` 一节作用于派生副本（`Cache`、`VersionedCache`），`store` 一节作用于权威副本（`Store`、`VersionedStore`、`SetStore`）。
 * 全局层跳过不适用、该层不允许与被锁定的字段（启动日志与端点列出）；`cocache.caches.[name]` 出现不适用的字段或未知的缓存名时
 * 按 [cachesValidation] 处理。时长必须带单位，哨兵为 `forever`、`off`、`unlimited`（[ConfigValues]）。
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
     * 所有缓存的 key 前缀，不参与分层：配置优先于声明（库以 `lock` 锁定了 `KEY_PREFIX` 的缓存除外）。
     * `cocache.caches.[name].key-prefix` 优先于它。
     */
    val keyPrefix: String?,
    /**
     * 全局默认值，低于声明（`cache`、`store` 两节）。
     */
    @DefaultValue
    val defaults: Layer,
    /**
     * 全局覆盖，高于声明，只收紧（`cache`、`store` 两节）。
     */
    @DefaultValue
    val overrides: Layer,
    /**
     * 按缓存设定（缓存名不区分大小写）。
     */
    @DefaultValue
    val caches: Map<String, PolicyProperties>,
    /**
     * `cocache.caches` 中的未知缓存名，以及设定了不适用于该缓存的字段的条目：`fail`（默认）启动失败，`warn` 只告警。
     * 指向未分层的缓存（直接经 `CacheRuntime.create` 创建）的条目总是告警，与此无关（它可能在启动之后才创建）。
     */
    @DefaultValue("fail")
    val cachesValidation: Validation,
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
    /**
     * 回滚时失效写事务内的填充（RFC 0005 §4.9 机制 1，默认开启；需要 spring-tx）：非只读的实际事务内，同步读取的数据源产生、
     * 写入 L1 或 L2 的 key，在事务回滚、提交结果未知或回滚到 savepoint 时失效。代价只在回滚时出现。
     */
    @DefaultValue("true")
    val invalidateFillsOnRollback: Boolean = true,
    /**
     * 写事务内的私有回源（RFC 0005 §4.9 机制 2；需要 spring-tx）：`registered-keys`（默认，本事务已登记失效的 key 的回源不写 L1 与 L2、
     * 不与其它调用方共享）、`private`（写事务内的全部回源）或 `allow`（不门控）。目前作用于所有缓存。
     */
    @DefaultValue("registered-keys")
    val fillInTransaction: FillInTransaction = FillInTransaction.REGISTERED_KEYS,
) {
    init {
        // 绑定时就解析每个值：不带单位的时长、写错的哨兵在启动时失败，而不是在第一个用到它的缓存创建时
        defaults.entries("$PREFIX.defaults")
        overrides.entries("$PREFIX.overrides")
        caches.forEach { (name, properties) -> properties.entries("$PREFIX.caches.$name") }
    }

    /**
     * 按缓存名查找 `cocache.caches.[name]`：先精确匹配，再忽略大小写匹配（环境变量绑定的 key 是小写）。
     * 返回配置中的名称与条目。
     */
    fun cacheProperties(cacheName: String): Pair<String, PolicyProperties>? {
        caches[cacheName]?.let { return cacheName to it }
        return caches.entries.firstOrNull { it.key.equals(cacheName, ignoreCase = true) }?.toPair()
    }

    /**
     * 全局层的两节：`cache` 作用于派生副本，`store` 作用于权威副本。
     */
    data class Layer @ConstructorBinding constructor(
        @DefaultValue
        val cache: PolicyProperties,
        @DefaultValue
        val store: PolicyProperties,
    ) {
        fun entries(path: String): List<Entry> = cache.entries("$path.cache") + store.entries("$path.store")
    }

    /**
     * 一个配置条目：完整的键、它的字段与解析后的值。
     */
    data class Entry(val key: String, val field: PolicyField<*>, val value: Any)

    /**
     * 一层中的策略字段，未设置（`null`）的字段不参与这一层。时长必须带单位（`500ms`、`5s`、`2m`、`PT2M`）。
     * [unknownRead]、[keyPrefix] 与 [unlock] 只在 `cocache.caches.[name]` 中有意义，出现在全局层时跳过。
     */
    data class PolicyProperties @ConstructorBinding constructor(
        /**
         * 命中值寿命，或 `forever`。`defaults.store.ttl` 与 `overrides.store.ttl` 不允许（权威副本的寿命即语义）。
         */
        val ttl: String?,
        /**
         * 寿命的随机抖动幅度（实际幅度不超过 `ttl / 2`）。
         */
        val ttlJitter: String?,
        /**
         * 负缓存寿命。
         */
        val absentTtl: String?,
        /**
         * L2 副本的最长寿命：失效丢失时 L2 陈旧度的上界。
         */
        val localMaxAge: String?,
        /**
         * 未写入 L1 的填充在 L2 中的最长寿命。
         */
        val degradedLocalMaxAge: String?,
        /**
         * 带版本的墓碑与 `E` 的寿命，或 `forever`（只用于 `VersionedCache`、`VersionedStore`）。
         */
        val tombstoneTtl: String?,
        /**
         * 提前刷新窗口，`off`（或 `0s`）关闭。
         */
        val refreshWindow: String?,
        /**
         * lease 自动回收时长（节点崩溃的兜底）。
         */
        val leaseTtl: String?,
        /**
         * 其它节点持有 lease 时最多等待的时长。
         */
        val leaseWait: String?,
        /**
         * 每个缓存同时进行的回源数上限，或 `unlimited`。
         */
        val loadMaxConcurrent: String?,
        /**
         * 等待回源许可的最长时间。
         */
        val loadAcquireTimeout: String?,
        /**
         * 一次加载的上限，超过它以 `SourceLoadTimeoutException` 失败。
         */
        val loadTimeout: String?,
        /**
         * 每次 L1 读取的时限（以 `cocache.redis.command-timeout` 为上限）。
         */
        val readTimeout: String?,
        /**
         * L2 容量上限。
         */
        val localMaximumSize: Long?,
        /**
         * 派生副本的失效写入 L1 失败时：`retry`（默认）或 `throw`。
         */
        val invalidateFailure: InvalidateFailureMode?,
        /**
         * 带 loader 的读取在 L1 读失败时：`degrade`（默认）或 `throw`。
         */
        val readFailure: ReadFailureMode?,
        /**
         * 不带 loader 的读取在 L1 读失败时：`throw`（默认）或 `absent`。只能写在 `cocache.caches.[name]` 中。
         */
        val unknownRead: UnknownReadMode?,
        /**
         * 这个缓存的 key 前缀，优先于 `cocache.key-prefix`。只能写在 `cocache.caches.[name]` 中。
         */
        val keyPrefix: String?,
        /**
         * 破窗开关：有意覆盖库锁定的字段（配置键，如 `ttl`），启动时告警。只能写在 `cocache.caches.[name]` 中。
         */
        @DefaultValue
        val unlock: List<String>,
    ) {
        /**
         * 这一层写出的策略条目（按配置文档的字段顺序），解析失败时抛出带完整键的 [IllegalArgumentException]。
         *
         * @param path 这一层的前缀，如 `cocache.defaults.cache`
         */
        fun entries(path: String): List<Entry> = buildList {
            fun <T : Any> parsed(field: PolicyField<T>, raw: String?, parse: (String, String) -> T) {
                raw?.let { add(Entry("$path.${field.key}", field, parse("$path.${field.key}", it))) }
            }

            fun <T : Any> plain(field: PolicyField<T>, value: T?) {
                value?.let { add(Entry("$path.${field.key}", field, it)) }
            }
            parsed(PolicyField.TTL, ttl, ConfigValues::ttl)
            parsed(PolicyField.TTL_JITTER, ttlJitter, ConfigValues::duration)
            parsed(PolicyField.ABSENT_TTL, absentTtl, ConfigValues::positiveDuration)
            parsed(PolicyField.LOCAL_MAX_AGE, localMaxAge, ConfigValues::positiveDuration)
            parsed(PolicyField.DEGRADED_LOCAL_MAX_AGE, degradedLocalMaxAge, ConfigValues::positiveDuration)
            parsed(PolicyField.TOMBSTONE_TTL, tombstoneTtl, ConfigValues::ttl)
            parsed(PolicyField.REFRESH_WINDOW, refreshWindow, ConfigValues::refreshWindow)
            parsed(PolicyField.LEASE_TTL, leaseTtl, ConfigValues::positiveDuration)
            parsed(PolicyField.LEASE_WAIT, leaseWait, ConfigValues::duration)
            parsed(PolicyField.LOAD_MAX_CONCURRENT, loadMaxConcurrent, ConfigValues::concurrency)
            parsed(PolicyField.LOAD_ACQUIRE_TIMEOUT, loadAcquireTimeout, ConfigValues::duration)
            parsed(PolicyField.LOAD_TIMEOUT, loadTimeout, ConfigValues::positiveDuration)
            parsed(PolicyField.READ_TIMEOUT, readTimeout, ConfigValues::positiveDuration)
            localMaximumSize?.let {
                require(it > 0) { "$path.${PolicyField.LOCAL_MAXIMUM_SIZE.key}: $it must be positive." }
            }
            plain(PolicyField.LOCAL_MAXIMUM_SIZE, localMaximumSize)
            plain(PolicyField.INVALIDATE_FAILURE, invalidateFailure)
            plain(PolicyField.READ_FAILURE, readFailure)
            plain(PolicyField.UNKNOWN_READ, unknownRead)
        }
    }

    /**
     * `cocache.caches` 的校验方式。
     */
    enum class Validation {
        FAIL,
        WARN,
    }

    /**
     * Redis 后端选项。时长必须带单位。
     */
    data class Redis @ConstructorBinding constructor(
        /**
         * 单条命令（脚本）的等待上限。
         */
        @DefaultValue("2s")
        val commandTimeout: String,
        /**
         * 订阅连接的心跳周期：PING 失败或超时即重置所有订阅者。
         */
        @DefaultValue("5s")
        val heartbeat: String,
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
         * 有权威副本（`Store`、`VersionedStore`、`SetStore`，含尚未创建的声明）时启动检查各主节点的 `maxmemory-policy`（读取 `INFO memory`）。
         * 一律启动失败的情形与本项无关：存在权威缓存时的 `allkeys-*`，以及读不到策略又没有配置 [assumeMaxmemoryPolicy]（S22）。
         * 本项只管剩下的一种：`volatile-*` 且存在 `ttl` 有限的权威缓存，`warn`（默认）记录告警，`fail` 启动失败，`off` 不检查。
         */
        @DefaultValue("warn")
        val storeEvictionCheck: EvictionCheck,
        /**
         * 读不到 `maxmemory-policy`（Redis 用户没有 `INFO` 权限、托管服务禁用了它）时，驱逐检查按这个值判断，
         * 如 `noeviction`、`volatile-lru`；必须是 `noeviction`、`allkeys-*` 或 `volatile-*`。读得到时以实际的值为准。
         */
        val assumeMaxmemoryPolicy: String? = null,
    ) {
        init {
            ConfigValues.positiveDuration("$PREFIX.redis.command-timeout", commandTimeout)
            ConfigValues.positiveDuration("$PREFIX.redis.heartbeat", heartbeat)
        }

        fun toOptions(): RedisBackendOptions = RedisBackendOptions(
            ConfigValues.positiveDuration("$PREFIX.redis.command-timeout", commandTimeout),
            ConfigValues.positiveDuration("$PREFIX.redis.heartbeat", heartbeat),
            invalidateBatchSize,
            clientName = clientName,
            channelMode = channelMode,
        )
    }

    /**
     * 权威副本驱逐检查（`volatile-*` 且有 `ttl` 有限的权威缓存时）的处理方式。
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

    companion object {
        const val PREFIX = "cocache"
    }
}
