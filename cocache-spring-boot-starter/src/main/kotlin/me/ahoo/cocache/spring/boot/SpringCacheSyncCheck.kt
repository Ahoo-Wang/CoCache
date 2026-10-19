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

import org.apache.commons.logging.LogFactory
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.cache.interceptor.CacheOperationSource
import org.springframework.cache.interceptor.CacheableOperation
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Proxy

/**
 * 启动检查：指向 CoCache 缓存的 `@Cacheable` 是否都声明了 `sync = true`。
 *
 * 不带 `sync` 的 `@Cacheable` 由 Spring 执行“读未命中 → 调用方法 → `put`”，`put` 是无条件写入，
 * 不受 lease 与失效戳保护（并发的失效可能被旧值覆盖）；`sync = true` 经引擎的 `get(key, loader)` 回源。
 *
 * 通过 Spring 的 [CacheOperationSource]（`@EnableCaching` 注册）解析每个 bean 类型上的缓存操作，因此 `@CacheConfig`
 * 的类级缓存名同样生效；没有 `@EnableCaching`（没有 [CacheOperationSource]）时什么也不做。只按缓存名判断：
 * 没有写明缓存名（只用 `cacheResolver`）的操作无法判断，跳过。
 *
 * @param requireSync 为 `true` 时发现违规即启动失败，否则只记录告警
 * @param cacheNames CoCache 的缓存名（已创建的与已声明但尚未创建的）
 */
internal class SpringCacheSyncCheck(
    private val beanFactory: ConfigurableListableBeanFactory,
    private val requireSync: Boolean,
    private val cacheNames: () -> Set<String>,
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
        val found = scan(sources, names)
        violations = found
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

    /**
     * 显式指定了其它 CacheManager 或自定义 CacheResolver 的操作不一定经过 CoCache，不检查。
     */
    private fun targetsCoCache(operation: CacheableOperation): Boolean =
        operation.cacheResolver.isEmpty() && (operation.cacheManager.isEmpty() || operation.cacheManager == COCACHE_CACHE_MANAGER)

    private fun scan(sources: List<CacheOperationSource>, names: Set<String>): List<Violation> {
        val found = LinkedHashMap<String, Violation>()
        for (beanName in beanFactory.beanDefinitionNames) {
            val type = userClassOf(beanName) ?: continue
            for (source in sources) {
                if (!source.isCandidateClass(type)) {
                    continue
                }
                ReflectionUtils.doWithMethods(type) { method ->
                    val operations = source.getCacheOperations(method, type).orEmpty()
                    val unsynced = operations.filterIsInstance<CacheableOperation>()
                        .filter { !it.isSync && targetsCoCache(it) }
                        .flatMap { op -> op.cacheNames.filter { it in names } }
                        .distinct()
                    if (unsynced.isNotEmpty()) {
                        val id = "${type.name}#${method.name}"
                        found.merge(id, Violation(id, unsynced)) { old, new ->
                            Violation(id, (old.cacheNames + new.cacheNames).distinct())
                        }
                    }
                }
            }
        }
        return found.values.toList()
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

    private companion object {
        const val COCACHE_CACHE_MANAGER = "cocacheCacheManager"
    }
}

private val log = LogFactory.getLog(SpringCacheSyncCheck::class.java)
