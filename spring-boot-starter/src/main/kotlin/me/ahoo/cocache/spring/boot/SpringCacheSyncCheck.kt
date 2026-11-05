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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.proxy.CacheProxies
import org.apache.commons.logging.LogFactory
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.cache.interceptor.CacheOperation
import org.springframework.cache.interceptor.CacheOperationSource
import org.springframework.cache.interceptor.CacheableOperation
import org.springframework.core.Ordered
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Proxy

/**
 * Spring Cache 的启动检查。
 *
 * **`sync = true`**：指向 CoCache 缓存的 `@Cacheable` 是否都声明了 `sync = true`。
 *
 * 不带 `sync` 的 `@Cacheable` 由 Spring 执行“读未命中 → 调用方法 → `put`”，`put` 是无条件写入，
 * 不受 lease 与失效戳保护（并发的失效可能被旧值覆盖）；`sync = true` 经引擎的 `get(key, loader)` 回源。
 *
 * 通过 Spring 的 [CacheOperationSource]（`@EnableCaching` 注册）解析每个 bean 类型上的缓存操作，因此 `@CacheConfig`
 * 的类级缓存名同样生效；没有 `@EnableCaching`（没有 [CacheOperationSource]）时什么也不做。只按缓存名判断：
 * 没有写明缓存名（只用 `cacheResolver`）的操作无法判断，跳过。
 *
 * **缓存通知与事务通知的嵌套**（RFC 0005 §4.9 S19）：`@EnableCaching` 与 `@EnableTransactionManagement` 的 order 默认都是
 * `LOWEST_PRECEDENCE`，order 相同时谁在外层取决于 advisor 的注册顺序。starter 的事务装饰器假定事务通知在外层
 * （`@CacheEvict` 登记到事务、`@CachePut` 推迟到提交之后、`@CacheEvict(beforeInvocation = true)` 立即失效并登记），
 * 因此两者 order 相同时告警，建议让事务通知的 order 更小。只看代理模式注册的 advisor；AspectJ 模式没有 advisor，不检查。
 * 与 `sync` 检查的前提相同：有 `@EnableCaching`、CoCache 的 CacheManager 生效、存在 CoCache 缓存。
 *
 * **只接入派生副本**（RFC 0005 §4.9）：Spring Cache 背后的缓存是派生副本 `Cache`，被注解的方法就是数据源。
 * 指向 `Store`、`VersionedStore`、`SetStore` 或 `VersionedCache` 的缓存操作（任何注解，不论 `sync`）启动失败：
 * CoCache 的 CacheManager 不暴露它们，否则要到第一次调用时才以“找不到缓存”失败。
 *
 * @param requireSync 为 `true` 时发现不带 `sync` 的 `@Cacheable` 即启动失败，否则只记录告警
 * @param cacheNames CoCache 的缓存名（已创建的与已声明但尚未创建的）
 * @param underivedNames 其中不是派生副本 `Cache` 的缓存名（[underivedCacheNames]）
 */
internal class SpringCacheSyncCheck(
    private val beanFactory: ConfigurableListableBeanFactory,
    private val requireSync: Boolean,
    private val cacheNames: () -> Set<String>,
    private val underivedNames: () -> Set<String>,
) : SmartInitializingSingleton {

    /**
     * 一处违规：[method] 为 `类名#方法名`。
     */
    data class Violation(val method: String, val cacheNames: List<String>)

    /**
     * 最近一次检查发现的违规（供测试与诊断查看）。
     */
    @Volatile
    var violations: List<Violation> = emptyList()
        private set

    /**
     * 最近一次检查发现缓存通知与事务通知的 order 相同时，那个 order；否则为 `null`（供测试与诊断查看）。
     */
    @Volatile
    var sameAdviceOrder: Int? = null
        private set

    override fun afterSingletonsInstantiated() {
        val sources = beanFactory.getBeanProvider(CacheOperationSource::class.java).orderedStream().toList()
        // 用户自己的 CacheManager 生效时（CoCache 的 CacheManager 退让），@Cacheable 不经过 CoCache
        if (sources.isEmpty() || !beanFactory.containsBean(COCACHE_CACHE_MANAGER)) {
            return
        }
        val names = cacheNames()
        if (names.isEmpty()) {
            return
        }
        checkAdviceOrder()
        val underived = underivedNames().intersect(names)
        val (found, misdirected) = scan(sources, names, underived)
        violations = found
        check(misdirected.isEmpty()) {
            "Spring Cache operations on CoCache caches that are not a Cache: " +
                misdirected.joinToString("; ") { "${it.method} -> ${it.cacheNames}" } + ". " +
                "Spring Cache only serves derived caches (Cache): a Store, VersionedStore, SetStore or VersionedCache is not " +
                "exposed by the CoCache CacheManager. Use the cache's own API, or declare the cache as a Cache."
        }
        if (found.isEmpty()) {
            return
        }
        val message = found.joinToString("; ") { "${it.method} -> ${it.cacheNames}" }
        check(!requireSync) {
            "@Cacheable without sync = true on CoCache caches: $message. " +
                "Set sync = true (lease-protected load), or set ${CoCacheProperties.PREFIX}.spring-cache.require-sync=false."
        }
        found.forEach {
            log.warn(
                "@Cacheable on ${it.method} uses CoCache cache ${it.cacheNames} without sync = true: Spring then loads and puts " +
                    "unconditionally, which is not lease-protected (a concurrent invalidation can be overwritten by a stale value). " +
                    "Use @Cacheable(sync = true)."
            )
        }
    }

    private fun checkAdviceOrder() {
        val cacheOrder = advisorOrder(CACHE_ADVISOR) ?: return
        val transactionOrder = advisorOrder(TRANSACTION_ADVISOR) ?: return
        if (cacheOrder != transactionOrder) {
            return
        }
        sameAdviceOrder = cacheOrder
        log.warn(
            "The Spring Cache advice and the transaction advice have the same order ($cacheOrder), so which one is outermost " +
                "depends on advisor registration order. CoCache's transactional caching (@CacheEvict registered with the " +
                "transaction, @CachePut deferred until commit) assumes the transaction advice is outermost: give it a lower order, " +
                "e.g. @EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)."
        )
    }

    /**
     * advisor 的 order：没有这个 bean（没有对应的 `@Enable...`，或 AspectJ 模式）时为 `null`。
     */
    private fun advisorOrder(beanName: String): Int? {
        if (!beanFactory.containsBean(beanName)) {
            return null
        }
        return (beanFactory.getBean(beanName) as? Ordered)?.order
    }

    /**
     * 显式指定了其它 CacheManager 或自定义 CacheResolver 的操作不一定经过 CoCache，不检查。
     */
    private fun targetsCoCache(operation: CacheOperation): Boolean =
        operation.cacheResolver.isEmpty() && (operation.cacheManager.isEmpty() || operation.cacheManager == COCACHE_CACHE_MANAGER)

    /**
     * 一次扫描得到两类结果：不带 `sync` 的 `@Cacheable`（指向派生副本），与指向 [underived] 的任何缓存操作。
     */
    private fun scan(
        sources: List<CacheOperationSource>,
        names: Set<String>,
        underived: Set<String>
    ): Pair<List<Violation>, List<Violation>> {
        val found = LinkedHashMap<String, Violation>()
        val misdirected = LinkedHashMap<String, Violation>()
        for (beanName in beanFactory.beanDefinitionNames) {
            val type = userClassOf(beanName) ?: continue
            for (source in sources) {
                if (!source.isCandidateClass(type)) {
                    continue
                }
                ReflectionUtils.doWithMethods(type) { method ->
                    val operations = source.getCacheOperations(method, type).orEmpty().filter { targetsCoCache(it) }
                    val id = "${type.name}#${method.name}"
                    val unsynced = operations.filterIsInstance<CacheableOperation>()
                        .filter { !it.isSync }
                        .flatMap { op -> op.cacheNames.filter { it in names && it !in underived } }
                        .distinct()
                    merge(found, id, unsynced)
                    merge(
                        misdirected,
                        id,
                        operations.flatMap { op -> op.cacheNames.filter { it in underived } }.distinct()
                    )
                }
            }
        }
        return found.values.toList() to misdirected.values.toList()
    }

    private fun merge(violations: MutableMap<String, Violation>, id: String, cacheNames: List<String>) {
        if (cacheNames.isEmpty()) {
            return
        }
        violations.merge(
            id,
            Violation(id, cacheNames)
        ) { old, new -> Violation(id, (old.cacheNames + new.cacheNames).distinct()) }
    }

    /**
     * bean 的用户类：不实例化 bean（FactoryBean 也不初始化）；CGLIB 代理取父类，JDK 代理取已创建单例的目标类。
     */
    private fun userClassOf(beanName: String): Class<*>? {
        if (beanFactory.getBeanDefinition(beanName).isAbstract) {
            return null
        }
        val type = beanFactory.getType(beanName, false) ?: return null
        if (!Proxy.isProxyClass(type)) {
            return ClassUtils.getUserClass(type)
        }
        if (!beanFactory.containsSingleton(beanName)) {
            return null
        }
        return AopProxyUtils.ultimateTargetClass(beanFactory.getBean(beanName))
    }

    companion object {
        private const val COCACHE_CACHE_MANAGER = "cocacheCacheManager"

        /**
         * `CacheManagementConfigUtils.CACHE_ADVISOR_BEAN_NAME`。
         */
        private const val CACHE_ADVISOR = "org.springframework.cache.config.internalCacheAdvisor"

        /**
         * `TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME`（spring-tx 是可选依赖，不引用它的类）。
         */
        private const val TRANSACTION_ADVISOR = "org.springframework.transaction.config.internalTransactionAdvisor"

        /**
         * 不是派生副本 `Cache` 的 CoCache 缓存名：已创建的按运行时中的缓存判断；已声明但尚未创建的（延迟初始化的 bean）
         * 按声明的接口类型或 spec 的类型判断，不创建缓存。
         */
        fun underivedCacheNames(runtime: CacheRuntime, beanFactory: ConfigurableListableBeanFactory): Set<String> {
            val names = sortedSetOf<String>()
            runtime.cacheNames.filterTo(names) { runtime.cache(it) !is Cache<*, *> }
            for (beanName in beanFactory.beanDefinitionNames) {
                val definition = beanFactory.getBeanDefinition(beanName)
                CacheBeanDefinitions.cacheInterfaceOf(definition)?.let { type ->
                    // 视图接口（如 `SuspendingCache`）背后的缓存仍是 `Cache`
                    if (CacheProxies.apiTypeOf(type) != Cache::class.java) {
                        names += AnnotatedCacheFactory.cacheNameOf(type)
                    }
                }
                CacheBeanDefinitions.specBeanOf(definition)?.let { specBean ->
                    val spec = beanFactory.getBean(specBean)
                    if (spec !is CacheSpec<*, *>) {
                        names += CacheBeanDefinitions.specNameOf(spec)
                    }
                }
            }
            return names
        }
    }
}

private val log = LogFactory.getLog(SpringCacheSyncCheck::class.java)
