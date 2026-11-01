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

import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.redis.RedisBackend
import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import java.time.Duration

/**
 * 启动时检查 `SetCache` 的唯一副本会不会被 Redis 驱逐（RFC 0005 §4.1、§12 M11）。内存满后被驱逐的集合无法从数据源恢复。
 *
 * - 检查的集合缓存由 [setCaches] 给出：启动时已创建的，加上已声明但尚未创建的（延迟初始化的 bean），见 [declaredSetCaches]。
 * - 各主节点的 `maxmemory-policy` 读自 `INFO memory`；读不到时按 [assumedPolicy]（`cocache.redis.assume-maxmemory-policy`）判断，
 *   没有配置则按 [mode] 告警或启动失败，不再静默跳过。
 * - `allkeys-*`：任何 key 都可能被驱逐，按 [mode] 告警或启动失败。
 * - `volatile-*`：只驱逐设置了过期时间的 key，`ttl` 有限的集合缓存可能在到期前被驱逐。只告警（`fail` 也一样）：这些集合本来就会过期。
 *
 * @param setCaches 集合缓存名 → 生效的 `ttl`（`null` 为永不过期）
 */
internal class SetCacheEvictionCheck(
    private val backend: CoherenceBackend,
    private val mode: CoCacheProperties.EvictionCheck,
    private val assumedPolicy: String?,
    private val setCaches: () -> Map<String, Duration?>,
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
        val caches = setCaches()
        if (caches.isEmpty()) {
            return
        }
        val policies = backend.maxmemoryPolicies() ?: assumedPolicy?.let { mapOf(ASSUMED to it) }
        if (policies == null) {
            report(
                "Cannot read maxmemory-policy (INFO memory) to check that SetCache ${caches.keys} keeps its only copy in Redis. " +
                    "Grant the Redis user +info, set ${CoCacheProperties.PREFIX}.redis.assume-maxmemory-policy to the policy of " +
                    "your deployment, or set $CHECK_PROPERTY=off to skip the check."
            )
            return
        }
        val volatile = policies.filterValues { it.startsWith(VOLATILE_PREFIX) }
        val expiring = caches.filterValues { it != null }.keys
        if (volatile.isNotEmpty() && expiring.isNotEmpty()) {
            log.warn(
                "SetCache $expiring has a finite ttl, so maxmemory-policy $volatile can evict it before it expires when " +
                    "memory is full. Use noeviction, or remove the ttl."
            )
        }
        val evicting = policies.filterValues { it.startsWith(ALL_KEYS_PREFIX) }
        if (evicting.isNotEmpty()) {
            report(
                "SetCache ${caches.keys} keeps its only copy in Redis, but maxmemory-policy $evicting can evict it when " +
                    "memory is full. Use noeviction, or a volatile-* policy for SetCaches without a ttl, or set " +
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
        private const val CHECK_PROPERTY = "${CoCacheProperties.PREFIX}.redis.set-cache-eviction-check"

        private fun evicts(policy: String): Boolean = policy.startsWith(ALL_KEYS_PREFIX) || policy.startsWith(VOLATILE_PREFIX)

        /**
         * 集合缓存名 → 生效的 `ttl`，按缓存名排序。已创建的取运行时中生效的策略（也覆盖不经 starter、直接由运行时创建的集合缓存）；
         * 已声明但尚未创建的（延迟初始化的 bean）按创建时同样的规则叠加定制器与配置文件（[CacheAssembler.resolve]），不创建缓存。
         */
        fun declaredSetCaches(
            runtime: CacheRuntime,
            beanFactory: ConfigurableListableBeanFactory
        ): Map<String, Duration?> {
            val caches = sortedMapOf<String, Duration?>()
            for (name in runtime.cacheNames) {
                if (runtime.cache(name) is SetCache<*, *>) {
                    caches[name] = runtime.policy(name)?.ttl
                }
            }
            for (beanName in beanFactory.beanDefinitionNames) {
                val spec = uncreatedSetSpecOf(beanFactory, beanName, caches.keys) ?: continue
                val assembler = CacheBeanDefinitions.requireComponent(
                    beanFactory,
                    CacheAssembler::class.java,
                    spec.name
                )
                caches[spec.name] = assembler.resolve(spec).policy.ttl
            }
            return caches
        }

        /**
         * 尚未创建的集合缓存声明（接口式声明或 [SetCacheSpec] bean）；不是集合缓存的声明、或已经创建（在 [created] 中）时返回 `null`。
         */
        @Suppress("UNCHECKED_CAST")
        private fun uncreatedSetSpecOf(
            beanFactory: ConfigurableListableBeanFactory,
            beanName: String,
            created: Set<String>
        ): SetCacheSpec<Any, Any>? {
            val definition = beanFactory.getBeanDefinition(beanName)
            val type = CacheBeanDefinitions.cacheInterfaceOf(definition)
            if (type != null) {
                val cacheName = AnnotatedCacheFactory.cacheNameOf(type)
                if (!SetCache::class.java.isAssignableFrom(type) || cacheName in created) {
                    return null
                }
                return CacheBeanDefinitions.requireComponent(beanFactory, AnnotatedCacheFactory::class.java, cacheName)
                    .setSpecOf(type)
            }
            val specBean = CacheBeanDefinitions.specBeanOf(definition) ?: return null
            val spec = beanFactory.getBean(specBean) as? SetCacheSpec<*, *> ?: return null
            return (spec as SetCacheSpec<Any, Any>).takeUnless { it.name in created }
        }
    }
}

private val log = LogFactory.getLog(SetCacheEvictionCheck::class.java)
