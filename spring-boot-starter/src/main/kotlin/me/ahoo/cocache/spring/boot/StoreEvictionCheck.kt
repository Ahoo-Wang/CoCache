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

import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.VersionedStore
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.VersionedStoreSpec
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
 * - 各主节点的 `maxmemory-policy` 读自 `INFO memory`；读不到时按 [assumedPolicy]（`cocache.redis.assume-maxmemory-policy`）判断，
 *   没有配置则按 [mode] 告警或启动失败，不再静默跳过。
 * - `allkeys-*`：任何 key 都可能被驱逐，按 [mode] 告警或启动失败（一律启动失败的规则见 RFC 0005 §4.1 S22，随 M6 实施）。
 * - `volatile-*`：只驱逐设置了过期时间的 key，`ttl` 有限的权威缓存可能在到期前被驱逐。只告警（`fail` 也一样）：这些数据本来就会过期。
 *   `VersionedStore` 的墓碑总有有限的寿命，被驱逐只让迟到的旧版本提前可写，不计入这一条。
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
        if (mode == CoCacheProperties.EvictionCheck.OFF || backend !is RedisBackend) {
            return
        }
        val caches = stores()
        if (caches.isEmpty()) {
            return
        }
        val policies = backend.maxmemoryPolicies() ?: assumedPolicy?.let { mapOf(ASSUMED to it) }
        if (policies == null) {
            report(
                "Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches ${caches.keys} keep their only copy " +
                    "in Redis. " +
                    "Grant the Redis user +info, set ${CoCacheProperties.PREFIX}.redis.assume-maxmemory-policy to the policy of " +
                    "your deployment, or set $CHECK_PROPERTY=off to skip the check."
            )
            return
        }
        val volatile = policies.filterValues { it.startsWith(VOLATILE_PREFIX) }
        val expiring = caches.filterValues { it != null }.keys
        if (volatile.isNotEmpty() && expiring.isNotEmpty()) {
            log.warn(
                "Authoritative caches $expiring have a finite ttl, so maxmemory-policy $volatile can evict them before they expire when " +
                    "memory is full. Use noeviction, or remove the ttl."
            )
        }
        val evicting = policies.filterValues { it.startsWith(ALL_KEYS_PREFIX) }
        if (evicting.isNotEmpty()) {
            report(
                "Authoritative caches ${caches.keys} keep their only copy in Redis, but maxmemory-policy $evicting can evict them when " +
                    "memory is full. Use noeviction, or a volatile-* policy when they have no ttl, or set " +
                    "$CHECK_PROPERTY=off to accept the risk."
            )
        }
    }

    private fun report(message: String) {
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
                val cache = runtime.cache(name)
                if (cache is Store<*, *> || cache is VersionedStore<*, *> || cache is SetStore<*, *>) {
                    caches[name] = runtime.policy(name)?.ttl
                }
            }
            for (beanName in beanFactory.beanDefinitionNames) {
                val spec = uncreatedStoreSpecOf(beanFactory, beanName, caches.keys) ?: continue
                val assembler = CacheBeanDefinitions.requireComponent(
                    beanFactory,
                    CacheAssembler::class.java,
                    spec.name
                )
                caches[spec.name] = assembler.resolve(spec).policy.ttl
            }
            return caches
        }

        private fun authoritative(spec: Spec<*, *>): Boolean =
            spec is StoreSpec<*, *> || spec is VersionedStoreSpec<*, *> || spec is SetStoreSpec<*, *>

        /**
         * 尚未创建的权威缓存声明（接口式声明或 spec bean）；不是权威类型的声明、或已经创建（在 [created] 中）时返回 `null`。
         */
        private fun uncreatedStoreSpecOf(
            beanFactory: ConfigurableListableBeanFactory,
            beanName: String,
            created: Set<String>
        ): Spec<*, *>? {
            val definition = beanFactory.getBeanDefinition(beanName)
            val type = CacheBeanDefinitions.cacheInterfaceOf(definition)
            if (type != null) {
                val cacheName = AnnotatedCacheFactory.cacheNameOf(type)
                val authoritativeType = listOf(Store::class.java, VersionedStore::class.java, SetStore::class.java)
                    .any { it.isAssignableFrom(type) }
                if (!authoritativeType || cacheName in created) {
                    return null
                }
                return CacheBeanDefinitions.requireComponent(beanFactory, AnnotatedCacheFactory::class.java, cacheName)
                    .specOf(type)
            }
            val specBean = CacheBeanDefinitions.specBeanOf(definition) ?: return null
            val spec = beanFactory.getBean(specBean) as Spec<*, *>
            return spec.takeIf { authoritative(it) && it.name !in created }
        }
    }
}

private val log = LogFactory.getLog(StoreEvictionCheck::class.java)
