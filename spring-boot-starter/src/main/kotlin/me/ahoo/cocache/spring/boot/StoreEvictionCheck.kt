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
import java.util.concurrent.ConcurrentHashMap
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
 * - **启动之后的创建**（X2）：经 starter 的装配层（[CacheAssembler]，即 starter 的 `CoCacheFactory`）创建的权威缓存（延迟初始化的
 *   bean、库经 starter 的工厂在运行时创建的）在创建路径上按同一规则检查（[check]，在分层之后调用），致命时创建失败：创建监听抛出的
 *   异常只会被记录。其余的创建（直接经 `runtime.create`，或库自建的 `CoCacheFactory(runtime)`）不经过 [check]，只能在创建监听中
 *   检查（[created]），致命时也只告警。
 * - **周期重读**（可选，`cocache.redis.store-eviction-check-interval`）：按间隔重读各主节点的策略，检查已创建的权威缓存，
 *   风险变化时告警。启动之后改了 `maxmemory-policy` 的部署因此也会被发现。
 *
 * 最近一次的结论（[verdict]、[checkedAt]）总是对全部权威缓存的结论：启动时含已声明但尚未创建的，之后是已创建的加上正在创建的。
 * 创建时只按这一个缓存决定失败或告警，快照仍按全部缓存重算（同一次读到的策略），不会被一个无风险的缓存覆盖成“安全”。健康检查读
 * [current]：启动之后才写入按条目寿命的权威缓存（`CacheAdmin.writtenEntryTtl`）在那里按最近一次读到的策略计入，不访问 Redis；
 * 健康检查也不因结论变为 `DOWN`。后端取 `runtime.backend.unwrap(RedisBackend)`：包装过的 Redis 后端（如测试的 `HookedBackend`）
 * 照样受检查；后端不是 Redis 时什么也不检查，也不求权威缓存（不解析尚未创建的声明）。
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
     * 已在创建路径上检查过（[check]）、尚未创建的缓存名：创建监听（[created]）据此跳过它们，只对其余的创建告警。检查致命时
     * 立即移除；检查通过、运行时却拒绝了这次创建（如重名）时会留下这个名称，之后同名的缓存若不经 starter 的工厂创建，就少一次告警。
     */
    private val checkedOnCreation: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    /**
     * 最近一次读到的各主节点策略（读不到时为 `null`）与是否读过：[current] 据此重新判断，不访问 Redis。
     */
    @Volatile
    private var policies: Map<String, String>? = null

    @Volatile
    private var policiesRead = false

    /**
     * 最近一次结论所依据的权威缓存：缓存名 → 有限的寿命（`null` 为不会过期）。
     */
    @Volatile
    private var assessed: Map<String, Duration?> = emptyMap()

    init {
        try {
            StoreEvictionRisk.requireValidPolicy(assumedPolicy)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("${CoCacheProperties.PREFIX}.redis.assume-${e.message}", e)
        }
        runtime.addCreationListener { created(it) }
    }

    override fun afterSingletonsInstantiated() {
        val backend = redisBackend()
        if (backend == null) {
            record(StoreEvictionRisk.NotApplicable)
        } else {
            enforce(record(assess(backend, stores())))
        }
        started = true
        if (backend != null) {
            interval?.let { schedule(it) }
        }
    }

    /**
     * 启动之后经 starter 的装配层创建的权威缓存 [spec]（[CacheAssembler] 在分层之后、创建之前调用）：按同一规则检查这一个缓存，
     * 致命时抛出 [IllegalStateException]，创建随之失败。启动之前的创建由启动检查覆盖，这里什么也不做。
     */
    fun check(spec: Spec<*, *>) {
        if (!started || !ApiTypes.authoritative(ApiTypes.of(spec))) {
            return
        }
        val backend = redisBackend() ?: return
        checkedOnCreation += spec.name
        try {
            enforce(evaluateCreation(backend, spec.name, spec.policy.ttl))
        } catch (e: IllegalStateException) {
            // 创建随之失败，不会有创建监听来取走它
            checkedOnCreation -= spec.name
            throw e
        }
    }

    /**
     * 没有在创建路径上检查过的权威缓存（直接经 `runtime.create`，或库自建的 `CoCacheFactory(runtime)`，都不经过 starter 的装配层）：
     * 只能在创建之后检查，致命的风险也只告警。
     */
    private fun created(admin: CacheAdmin) {
        if (!started || !ApiTypes.authoritative(admin.apiType) || checkedOnCreation.remove(admin.name)) {
            return
        }
        val backend = redisBackend() ?: return
        val risk = evaluateCreation(backend, admin.name, runtime.policy(admin.name)?.ttl) as? StoreEvictionRisk.Risk ?: return
        log.warn(
            "${messageOf(risk)} The cache was not created through the starter's CoCacheFactory (but directly through " +
                "CacheRuntime.create or a library's own CoCacheFactory), so this can't stop it."
        )
    }

    private fun redisBackend(): RedisBackend? = runtime.backend.unwrap(RedisBackend::class.java)

    /**
     * 健康检查读取的结论。此刻已创建的权威缓存比最近一次结论所依据的多出会过期的（启动之后写入了按条目寿命，
     * `CacheAdmin.writtenEntryTtl`）时，按最近一次读到的策略重新判断并记下，不访问 Redis；结论变为有风险时告警一次。这样写入按条目寿命的
     * 权威缓存不必等下一次创建或周期重读才被计入。尚未读过策略（后端不是 Redis，或还没有权威缓存）时返回 [verdict]。
     */
    fun current(): StoreEvictionRisk.Verdict? {
        if (!policiesRead) {
            return verdict
        }
        val stores = assessed + StoreEvictionRisk.authoritativeStores(runtime)
        if (stores == assessed) {
            return verdict
        }
        val previous = verdict
        assessed = stores
        val current = record(StoreEvictionRisk.assess(stores, riskMode, assumedPolicy) { policies })
        if (current is StoreEvictionRisk.Risk && current != previous) {
            log.warn(messageOf(current))
        }
        return current
    }

    /**
     * 按 [stores] 判断，策略读自 [backend]（至多一次，记下供 [current] 复用）。
     */
    private fun assess(backend: RedisBackend, stores: Map<String, Duration?>): StoreEvictionRisk.Verdict {
        assessed = stores
        return StoreEvictionRisk.assess(stores, riskMode, assumedPolicy) { read(backend) }
    }

    private fun read(backend: RedisBackend): Map<String, String>? = backend.maxmemoryPolicies().also {
        policies = it
        policiesRead = true
    }

    /**
     * 创建一个权威缓存时：返回这一个缓存的结论（决定失败或告警）；快照按已创建的全部权威缓存加上它重算，用同一次读到的策略。
     */
    private fun evaluateCreation(backend: RedisBackend, name: String, ttl: Duration?): StoreEvictionRisk.Verdict {
        val read by lazy(LazyThreadSafetyMode.NONE) { read(backend) }
        val single = StoreEvictionRisk.assess(mapOf(name to ttl), riskMode, assumedPolicy) { read }
        val all = StoreEvictionRisk.authoritativeStores(runtime) + (name to ttl)
        assessed = all
        record(StoreEvictionRisk.assess(all, riskMode, assumedPolicy) { read })
        return single
    }

    private fun record(result: StoreEvictionRisk.Verdict): StoreEvictionRisk.Verdict {
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
            val backend = redisBackend()
            val current = record(
                if (backend == null) {
                    StoreEvictionRisk.NotApplicable
                } else {
                    assess(backend, StoreEvictionRisk.authoritativeStores(runtime))
                }
            )
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
            val caches = StoreEvictionRisk.authoritativeStores(runtime).toSortedMap()
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
