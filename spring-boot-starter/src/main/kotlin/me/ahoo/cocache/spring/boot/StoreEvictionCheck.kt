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

import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.redis.StoreEvictionRisk
import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 权威副本（`Store`、`VersionedStore`、`SetStore`）的唯一副本会不会被 Redis 驱逐（RFC 0005 §4.1、§12 M11，S22）：规则在
 * `cocache-redis` 的 [StoreEvictionRisk]，这里只把它接到 starter 的三个时机上，并把配置项写进提示。
 *
 * - **启动**（[afterSingletonsInstantiated]）：检查 [stores] 给出的权威缓存（已创建的，加上已声明但尚未创建的，见 [declaredStores]）。
 *   致命的风险（`allkeys-*`、读不到策略又没有 `cocache.redis.assume-maxmemory-policy`，或 `fail` 下的 `volatile-*`）让启动失败，
 *   其余告警。
 * - **启动之后的创建**（X2）：经 `CoCacheFactory` 创建的权威缓存（延迟初始化的 bean、库在运行时创建的）在创建路径上按同一规则检查
 *   （[check]，由 [CacheAssembler] 在分层之后调用），致命时创建失败：创建监听抛出的异常只会被记录。直接经 `runtime.create` 创建、
 *   不经过 starter 的缓存只能在创建监听中检查（[created]），致命时也只告警。
 * - **周期重读**（可选，`cocache.redis.store-eviction-check-interval`）：按间隔重读各主节点的策略，检查已创建的权威缓存，
 *   风险变化时告警。启动之后改了 `maxmemory-policy` 的部署因此也会被发现。
 *
 * 最近一次的结论（[verdict]、[checkedAt]）只读，健康检查只读它、不访问 Redis，也不因它变为 `DOWN`。后端取
 * `runtime.backend.unwrap(RedisBackend)`：包装过的 Redis 后端（如测试的 `HookedBackend`）照样受检查。
 *
 * 内部实现（starter 接线使用），不在兼容性承诺之内。
 *
 * @param stores 启动时检查的权威缓存名 → 生效的 `ttl`（`null` 为永不过期）
 * @param interval 周期重读的间隔；`null` 为不重读
 */
class StoreEvictionCheck internal constructor(
    private val runtime: CacheRuntime,
    private val mode: CoCacheProperties.EvictionCheck,
    private val assumedPolicy: String?,
    private val interval: Duration? = null,
    private val stores: () -> Map<String, Duration?>,
) : SmartInitializingSingleton, DisposableBean {
    private val riskMode = StoreEvictionRisk.Mode.valueOf(mode.name)

    @Volatile
    private var started = false

    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    /**
     * 最近一次检查的结论；尚未检查时为 `null`。
     */
    @Volatile
    var verdict: StoreEvictionRisk.Verdict? = null
        private set

    /**
     * 最近一次检查的时刻；尚未检查时为 `null`。
     */
    @Volatile
    var checkedAt: Instant? = null
        private set

    init {
        try {
            StoreEvictionRisk.requireValidPolicy(assumedPolicy)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("${CoCacheProperties.PREFIX}.redis.assume-${e.message}", e)
        }
        runtime.addCreationListener { created(it) }
    }

    override fun afterSingletonsInstantiated() {
        enforce(evaluate(stores()))
        started = true
        interval?.let { schedule(it) }
    }

    /**
     * 启动之后经 starter 的装配层创建的权威缓存 [spec]（[CacheAssembler] 在分层之后、创建之前调用）：按同一规则检查这一个缓存，
     * 致命时抛出 [IllegalStateException]，创建随之失败。启动之前的创建由启动检查覆盖，这里什么也不做。
     */
    fun check(spec: Spec<*, *>) {
        if (!started || !ApiTypes.authoritative(ApiTypes.of(spec))) {
            return
        }
        enforce(evaluate(mapOf(spec.name to spec.policy.ttl)))
    }

    /**
     * 直接经 `runtime.create` 创建的权威缓存（不经过 starter 的装配层）：只能在创建之后检查，致命的风险也只告警。
     */
    private fun created(admin: CacheAdmin) {
        if (!started || admin.layered || !ApiTypes.authoritative(admin.apiType)) {
            return
        }
        val risk = evaluate(mapOf(admin.name to runtime.policy(admin.name)?.ttl)) as? StoreEvictionRisk.Risk ?: return
        log.warn(
            "${messageOf(risk)} The cache was created directly through CacheRuntime.create, so this can't stop it."
        )
    }

    private fun evaluate(caches: Map<String, Duration?>): StoreEvictionRisk.Verdict {
        val backend = runtime.backend.unwrap(RedisBackend::class.java)
        val result = if (backend == null) {
            StoreEvictionRisk.NotApplicable
        } else {
            StoreEvictionRisk.check(backend, caches, riskMode, assumedPolicy)
        }
        verdict = result
        checkedAt = Instant.now()
        return result
    }

    private fun enforce(verdict: StoreEvictionRisk.Verdict) {
        val risk = verdict as? StoreEvictionRisk.Risk ?: return
        val message = messageOf(risk)
        check(!risk.fatal) { message }
        log.warn(message)
    }

    private fun schedule(interval: Duration) {
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cocache-store-eviction-check").apply { isDaemon = true }
        }
        scheduler = executor
        executor.scheduleWithFixedDelay(::recheck, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * 周期重读：检查已创建的权威缓存（尚未创建的声明在创建时检查），结论有风险且与上一次不同时告警。任何异常都不能逃出去，
     * 否则定时任务从此不再执行。
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun recheck() {
        val previous = verdict
        try {
            val current = evaluate(StoreEvictionRisk.authoritativeStores(runtime))
            if (current is StoreEvictionRisk.Risk && current != previous) {
                log.warn(messageOf(current))
            }
        } catch (e: Exception) {
            log.warn("Store eviction re-check failed - retry in $interval.", e)
        }
    }

    override fun destroy() {
        scheduler?.shutdownNow()
    }

    /**
     * 规则给出的消息，加上 starter 的配置项。
     */
    private fun messageOf(risk: StoreEvictionRisk.Risk): String = when (risk.kind) {
        StoreEvictionRisk.Kind.UNREADABLE_POLICY ->
            "${risk.message} Set it with ${CoCacheProperties.PREFIX}.redis.assume-maxmemory-policy."
        StoreEvictionRisk.Kind.EVICTS_ANY_KEY -> risk.message
        StoreEvictionRisk.Kind.EVICTS_EXPIRING_KEYS -> "${risk.message} Or set $CHECK_PROPERTY=off to accept it."
    }

    companion object {
        private val log = LogFactory.getLog(StoreEvictionCheck::class.java)
        private const val CHECK_PROPERTY = "${CoCacheProperties.PREFIX}.redis.store-eviction-check"

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
