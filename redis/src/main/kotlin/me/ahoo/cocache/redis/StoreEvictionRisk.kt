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

package me.ahoo.cocache.redis

import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheRuntime
import java.time.Duration

/**
 * 权威副本（`Store`、`VersionedStore`、`SetStore`）的唯一副本会不会被 Redis 驱逐（RFC 0005 §4.1、§12 M11，S22）：内存满后被驱逐的 key
 * 无法从数据源恢复。starter 启动时、之后创建权威缓存时与周期重读时都按这一规则检查；不经 starter 使用 CoCache 的库直接调用
 * [check]（X2）。
 *
 * 规则：
 * - 各主节点的 `maxmemory-policy` 读自 `INFO memory`（[RedisBackend.maxmemoryPolicies]）；读不到时按 `assumedPolicy` 判断。
 * - 一律有风险（[Risk.fatal]），与 [Mode] 无关：任一主节点为 `allkeys-*`（任何 key 都可能被驱逐，[Kind.EVICTS_ANY_KEY]）；
 *   读不到策略又没有 `assumedPolicy`（[Kind.UNREADABLE_POLICY]）。
 * - [Mode] 只管剩下的一种：`volatile-*` 且有会过期的权威缓存（它们可能在到期前被驱逐，[Kind.EVICTS_EXPIRING_KEYS]）：
 *   [Mode.WARN] 告警（不致命），[Mode.FAIL] 致命，[Mode.OFF] 不检查。会过期指策略的 `ttl` 有限，或者本实例写入过按条目寿命
 *   （写入时给出的 `ttl`、spec 的 `ttlOf`，[CacheAdmin.writtenEntryTtl]，见 [authoritativeStores]）：后者在第一次这样写入之后才知道，
 *   启动时的检查看不到。`VersionedStore` 的墓碑总有有限的寿命，被驱逐只让迟到的旧版本提前可写，不计入这一条（按值的寿命判断）。
 *
 * 只在有权威缓存时读一次策略（一次 `INFO memory`，集群为每个主节点一次）。后端经 [me.ahoo.cocache.api.spi.CoherenceBackend.unwrap]
 * 取得：测试与观测用的包装（如 `HookedBackend`）不让检查被跳过。
 */
object StoreEvictionRisk {
    private const val NO_EVICTION = "noeviction"
    private const val ALL_KEYS_PREFIX = "allkeys-"
    private const val VOLATILE_PREFIX = "volatile-"

    /**
     * 读不到策略、按假定值判断时，策略的键。
     */
    const val ASSUMED = "assumed"

    /**
     * `volatile-*` 且有会过期的权威缓存时的处理方式。
     */
    enum class Mode {
        WARN,
        FAIL,
        OFF,
    }

    /**
     * 风险的种类。
     */
    enum class Kind {
        /**
         * 读不到 `maxmemory-policy`，也没有假定值。
         */
        UNREADABLE_POLICY,

        /**
         * `allkeys-*`：任何 key 都可能被驱逐。
         */
        EVICTS_ANY_KEY,

        /**
         * `volatile-*`：会过期的权威缓存可能在到期前被驱逐。
         */
        EVICTS_EXPIRING_KEYS,
    }

    /**
     * 一次检查的结论。
     */
    sealed interface Verdict {
        /**
         * 有风险且致命时抛出 [IllegalStateException]（消息同 [Risk.message]），否则返回自身。
         */
        fun requireSafe(): Verdict = this
    }

    /**
     * 不适用：后端不是 Redis（看穿包装之后），或者没有权威缓存。
     */
    data object NotApplicable : Verdict

    /**
     * 没有风险（或者 [Mode.OFF] 下只有 `volatile-*` 的风险）。
     *
     * @property policies 各主节点的策略（`host:port`，单机为 `redis`；按假定值时为 [ASSUMED]）
     */
    data class Safe(val policies: Map<String, String>) : Verdict

    /**
     * 有风险。
     *
     * @property caches 受影响的权威缓存名
     * @property policies 引起风险的主节点策略（读不到时为空）
     * @property fatal 应当让启动或创建失败（`allkeys-*`、读不到策略，或 [Mode.FAIL] 下的 `volatile-*`）；否则只告警
     */
    data class Risk(
        val kind: Kind,
        val caches: Set<String>,
        val policies: Map<String, String>,
        val fatal: Boolean,
        val message: String,
    ) : Verdict {
        override fun requireSafe(): Verdict {
            check(!fatal) { message }
            return this
        }
    }

    /**
     * 检查 [runtime] 中已创建的权威缓存（[authoritativeStores]），后端取 `runtime.backend.unwrap(RedisBackend)`：后端不是 Redis 时
     * [NotApplicable]。阻塞，至多一次 `INFO memory`（每个主节点）。
     */
    @JvmStatic
    @JvmOverloads
    fun check(runtime: CacheRuntime, mode: Mode = Mode.WARN, assumedPolicy: String? = null): Verdict {
        val backend = runtime.backend.unwrap(RedisBackend::class.java) ?: return NotApplicable
        return check(backend, authoritativeStores(runtime), mode, assumedPolicy)
    }

    /**
     * 检查 [stores]（权威缓存名 → 有限的寿命，`null` 为不会过期，见 [authoritativeStores]；可以包含尚未创建的声明），策略读自 [backend]。
     */
    @JvmStatic
    @JvmOverloads
    fun check(
        backend: RedisBackend,
        stores: Map<String, Duration?>,
        mode: Mode = Mode.WARN,
        assumedPolicy: String? = null,
    ): Verdict = assess(stores, mode, assumedPolicy) { backend.maxmemoryPolicies() }

    /**
     * 规则本身：[policies] 给出各主节点的策略（读不到时返回 `null`），只在有权威缓存时调用一次。周期重读的结果可以经它重新判断，
     * 不再访问 Redis。
     */
    @JvmStatic
    fun assess(
        stores: Map<String, Duration?>,
        mode: Mode,
        assumedPolicy: String?,
        policies: () -> Map<String, String>?,
    ): Verdict {
        requireValidPolicy(assumedPolicy)
        if (stores.isEmpty()) {
            return NotApplicable
        }
        val caches = stores.keys.toSortedSet()
        val read = policies() ?: assumedPolicy?.let { mapOf(ASSUMED to it) }
            ?: return Risk(
                Kind.UNREADABLE_POLICY,
                caches,
                emptyMap(),
                fatal = true,
                "Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches $caches keep their only copy " +
                    "in Redis. Grant the Redis user +info, or assume the policy of your deployment."
            )
        val evicting = read.filterValues { it.startsWith(ALL_KEYS_PREFIX) }
        if (evicting.isNotEmpty()) {
            return Risk(
                Kind.EVICTS_ANY_KEY,
                caches,
                evicting,
                fatal = true,
                "Authoritative caches $caches keep their only copy in Redis, but maxmemory-policy $evicting can evict them when " +
                    "memory is full. Use noeviction, or a volatile-* policy when they have no ttl."
            )
        }
        val volatile = read.filterValues { it.startsWith(VOLATILE_PREFIX) }
        val expiring = stores.filterValues { it != null }.keys.toSortedSet()
        if (mode == Mode.OFF || volatile.isEmpty() || expiring.isEmpty()) {
            return Safe(read)
        }
        return Risk(
            Kind.EVICTS_EXPIRING_KEYS,
            expiring,
            volatile,
            fatal = mode == Mode.FAIL,
            "Authoritative caches $expiring have a finite ttl (from their policy, or entries written with their own ttl), " +
                "so maxmemory-policy $volatile can evict them before they expire when memory is full. Use noeviction, " +
                "or remove the ttl."
        )
    }

    /**
     * [runtime] 中已创建的权威缓存：缓存名 → 有限的寿命，`null` 为不会过期。有限的寿命是策略的 `ttl`；策略永不过期时，是本实例写入过的
     * 按条目寿命（[CacheAdmin.writtenEntryTtl]：写入时给出的 `ttl`、spec 的 `ttlOf`），这样写出的 key 同样带过期时间。每次调用都按此刻的
     * 记录重算，不访问 Redis。
     */
    @JvmStatic
    fun authoritativeStores(runtime: CacheRuntime): Map<String, Duration?> {
        val stores = sortedMapOf<String, Duration?>()
        for (name in runtime.cacheNames) {
            val admin = runtime.admin(name) ?: continue
            if (authoritative(admin.apiType)) {
                stores[name] = runtime.policy(name)?.ttl ?: admin.writtenEntryTtl
            }
        }
        return stores
    }

    /**
     * 权威副本的 API 类型（`Store`、`VersionedStore`、`SetStore`）。
     */
    @JvmStatic
    fun authoritative(apiType: Class<*>): Boolean =
        apiType == Store::class.java || apiType == VersionedStore::class.java || apiType == SetStore::class.java

    /**
     * 假定的策略只能是 `noeviction`、`allkeys-*` 或 `volatile-*`（`null` 为不假定）。
     */
    @JvmStatic
    fun requireValidPolicy(policy: String?) {
        require(
            policy == null || policy == NO_EVICTION || policy.startsWith(ALL_KEYS_PREFIX) || policy.startsWith(VOLATILE_PREFIX)
        ) {
            "maxmemory-policy[$policy] must be $NO_EVICTION, $ALL_KEYS_PREFIX* or $VOLATILE_PREFIX*."
        }
    }
}
