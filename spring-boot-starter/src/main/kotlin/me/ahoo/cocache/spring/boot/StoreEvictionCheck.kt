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

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.redis.RedisBackend
import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import java.time.Duration

/**
 * 启动时检查权威副本（`Store`、`VersionedStore`、`SetStore`）的唯一副本会不会被 Redis 驱逐（RFC 0005 §4.1、§12 M11）。
 * 内存满后被驱逐的 key 无法从数据源恢复。
 *
 * - 检查的权威缓存由 [stores] 给出：启动时已创建的，加上已声明但尚未创建的（延迟初始化的 bean），见 [declaredStores]。
 * - 各主节点的 `maxmemory-policy` 读自 `INFO memory`；读不到时按 [assumedPolicy]（`cocache.redis.assume-maxmemory-policy`）判断。
 * - 一律启动失败，与 [mode] 无关（S22）：`allkeys-*`（任何 key 都可能被驱逐，唯一副本无法恢复），以及读不到策略又没有配置 [assumedPolicy]。
 * - [mode] 只管剩下的一种：`volatile-*` 且有 `ttl` 有限的权威缓存（它们可能在到期前被驱逐）：`warn`（默认）告警，`fail` 启动失败，
 *   `off` 不检查。`VersionedStore` 的墓碑总有有限的寿命，被驱逐只让迟到的旧版本提前可写，不计入这一条。
 *
 * @param stores 权威缓存名 → 生效的 `ttl`（`null` 为永不过期）
 */
internal class StoreEvictionCheck(
    private val backend: CoherenceBackend,
    private val mode: CoCacheProperties.EvictionCheck,
    private val assumedPolicy: String?,
    private val stores: () -> Map<String, Duration?>,
) : SmartInitializingSingleton {

    init {
        require(assumedPolicy == null || assumedPolicy == NO_EVICTION || evicts(assumedPolicy)) {
            "${CoCacheProperties.PREFIX}.redis.assume-maxmemory-policy[$assumedPolicy] must be $NO_EVICTION, " +
                "$ALL_KEYS_PREFIX* or $VOLATILE_PREFIX*."
        }
    }

    override fun afterSingletonsInstantiated() {
        if (backend !is RedisBackend) {
            return
        }
        val caches = stores()
        if (caches.isEmpty()) {
            return
        }
        val policies = backend.maxmemoryPolicies() ?: assumedPolicy?.let { mapOf(ASSUMED to it) }
        checkNotNull(policies) {
            "Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches ${caches.keys} keep their only copy " +
                "in Redis. Grant the Redis user +info, or set ${CoCacheProperties.PREFIX}.redis.assume-maxmemory-policy to the " +
                "policy of your deployment."
        }
        val evicting = policies.filterValues { it.startsWith(ALL_KEYS_PREFIX) }
        check(evicting.isEmpty()) {
            "Authoritative caches ${caches.keys} keep their only copy in Redis, but maxmemory-policy $evicting can evict them when " +
                "memory is full. Use noeviction, or a volatile-* policy when they have no ttl."
        }
        val volatile = policies.filterValues { it.startsWith(VOLATILE_PREFIX) }
        val expiring = caches.filterValues { it != null }.keys
        if (mode == CoCacheProperties.EvictionCheck.OFF || volatile.isEmpty() || expiring.isEmpty()) {
            return
        }
        val message = "Authoritative caches $expiring have a finite ttl, so maxmemory-policy $volatile can evict them before " +
            "they expire when memory is full. Use noeviction, remove the ttl, or set $CHECK_PROPERTY=off to accept it."
        check(mode != CoCacheProperties.EvictionCheck.FAIL) { message }
        log.warn(message)
    }

    companion object {
        private const val NO_EVICTION = "noeviction"
        private const val ALL_KEYS_PREFIX = "allkeys-"
        private const val VOLATILE_PREFIX = "volatile-"
        private const val ASSUMED = "assumed"
        private const val CHECK_PROPERTY = "${CoCacheProperties.PREFIX}.redis.store-eviction-check"

        private fun evicts(policy: String): Boolean = policy.startsWith(ALL_KEYS_PREFIX) || policy.startsWith(VOLATILE_PREFIX)

        /**
         * 权威缓存名 → 生效的 `ttl`，按缓存名排序。已创建的取运行时中生效的策略（也覆盖不经 starter、直接由运行时创建的缓存）；
         * 已声明但尚未创建的（延迟初始化的 bean）按创建时同样的规则叠加定制器与配置文件（[CacheAssembler.resolve]），不创建缓存。
         */
        fun declaredStores(
            runtime: CacheRuntime,
            beanFactory: ConfigurableListableBeanFactory
        ): Map<String, Duration?> {
            val caches = sortedMapOf<String, Duration?>()
            for (name in runtime.cacheNames) {
                val admin = runtime.admin(name) ?: continue
                if (ApiTypes.authoritative(admin.apiType)) {
                    caches[name] = runtime.policy(name)?.ttl
                }
            }
            DeclaredCaches.of(
                beanFactory
            ).entries.filter { it.authoritative && it.cacheName !in caches }.forEach { entry ->
                val assembler = CacheBeanDefinitions.requireComponent(
                    beanFactory,
                    CacheAssembler::class.java,
                    entry.cacheName
                )
                caches[entry.cacheName] = assembler.resolve(specOf(beanFactory, entry)).policy.ttl
            }
            return caches
        }

        /**
         * 尚未创建的声明的 spec：spec 声明就是它的 spec；接口式声明按接口推导（[AnnotatedCacheFactory.specOf]，不创建缓存）。
         */
        private fun specOf(beanFactory: ConfigurableListableBeanFactory, entry: DeclaredCaches.Entry): Spec<*, *> {
            entry.spec?.let { return it }
            val type = requireNotNull(entry.cacheInterface)
            return CacheBeanDefinitions.requireComponent(
                beanFactory,
                AnnotatedCacheFactory::class.java,
                entry.cacheName
            )
                .specOf(type)
        }
    }
}

private val log = LogFactory.getLog(StoreEvictionCheck::class.java)
