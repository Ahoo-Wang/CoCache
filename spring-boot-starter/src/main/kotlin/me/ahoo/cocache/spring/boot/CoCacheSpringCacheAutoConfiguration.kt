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

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.spring.SpringSupport
import me.ahoo.cocache.spring.cache.CoCacheSpringCacheManager
import me.ahoo.cocache.spring.cache.DegradingCacheErrorHandler
import me.ahoo.cocache.spring.tx.TransactionAwareSpringCacheManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cache.CacheManager
import org.springframework.cache.interceptor.CacheAspectSupport
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.SimpleCacheErrorHandler
import org.springframework.context.annotation.Bean

/**
 * CoCache 的 Spring Cache 接线（`cocache.enabled`，默认启用）：CacheManager、`@Cacheable` 的启动检查与可选的降级错误处理器
 * （RFC 0005 §4.9）。bean 名与拆分之前相同；应用只想关掉 Spring Cache 接线时 `exclude` 这个类，CoCache 本身照常工作。
 *
 * 在 [CoCacheAutoConfiguration] 之后（要它的运行时），先于 Spring Boot 的 `CacheAutoConfiguration`：后者的
 * `@ConditionalOnMissingBean(CacheManager)` 应看到 CoCache 的 CacheManager。
 */
@AutoConfiguration(
    after = [CoCacheAutoConfiguration::class],
    beforeName = ["org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration"]
)
@ConditionalOnClass(CacheManager::class)
@ConditionalOnProperty(
    prefix = CoCacheProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class CoCacheSpringCacheAutoConfiguration {

    /**
     * Spring Cache 的 CacheManager。按名称查找尚未创建的缓存时，创建以这个名称声明的派生副本 bean（D8）：
     * 延迟初始化下，只经 `@Cacheable` 使用的缓存没有注入点，从不创建，否则第一次调用以“找不到缓存”失败。
     * 声明在第一次查不到时才收集（此时 bean 定义已经确定），之后复用。
     */
    @Bean
    @ConditionalOnMissingBean(CacheManager::class)
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheCacheManager(
        runtime: CacheRuntime,
        beanFactory: ConfigurableListableBeanFactory,
        cacheAspects: ObjectProvider<CacheAspectSupport>,
    ): CacheManager {
        val declared by lazy { CacheBeanDefinitions.derivedCacheBeans(beanFactory) }
        val manager = CoCacheSpringCacheManager(runtime) { name -> declared[name]?.let { beanFactory.getBean(it) } }
        // 有 spring-tx 时经 CoCache 的事务装饰器：evict 提交与回滚都失效，put / clear 推迟到提交之后（RFC 0005 §4.9）
        if (!SpringSupport.transactionsPresent(beanFactory.beanClassLoader)) {
            return manager
        }
        // 事务结束之后的失败交给缓存通知的 CacheErrorHandler（S18）。失败时才取得：此时缓存通知已经配置完成，
        // 也看得到 degrade-on-error 换上的处理器
        return TransactionAwareSpringCacheManager(manager) { cacheErrorHandler(cacheAspects) }
    }

    /**
     * `cocache.spring-cache.degrade-on-error=true`：缓存通知（[CacheAspectSupport]）改用 [DegradingCacheErrorHandler]，
     * 原来的处理器（`CachingConfigurer.errorHandler()` 给出的，或 Spring 默认的 [SimpleCacheErrorHandler]）处理其它缓存，
     * 以及 CoCache 缓存的失效与清空失败。
     *
     * 在全部单例创建之后替换：缓存通知的处理器由 `CachingConfigurer` 延迟解析，提前取得会提前创建它。
     * 已经是 [DegradingCacheErrorHandler] 的（应用自己配置了）不再包装。
     */
    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    @ConditionalOnProperty(prefix = SPRING_CACHE_PREFIX, name = ["degrade-on-error"], havingValue = "true")
    fun cocacheDegradingCacheErrorHandler(
        cacheAspects: ObjectProvider<CacheAspectSupport>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        cacheAspects.orderedStream().forEach { aspect ->
            val current = aspect.errorHandler
            if (current !is DegradingCacheErrorHandler) {
                aspect.errorHandler = DegradingCacheErrorHandler(current)
            }
        }
    }

    /**
     * 启动时检查指向 CoCache 缓存的 `@Cacheable` 是否声明了 `sync = true`（`cocache.spring-cache.require-sync`），
     * 指向不是派生副本 `Cache` 的缓存的操作一律启动失败。没有 `@EnableCaching`（没有 `CacheOperationSource`）时什么也不做。
     */
    @Bean
    @ConditionalOnBean(CacheRuntime::class)
    fun cocacheSpringCacheSyncCheck(
        runtime: CacheRuntime,
        properties: CoCacheProperties,
        beanFactory: ConfigurableListableBeanFactory,
    ): SmartInitializingSingleton = SpringCacheSyncCheck(
        beanFactory,
        properties.springCache.requireSync,
        { runtime.cacheNames + CacheBeanDefinitions.declaredCacheNames(beanFactory) },
        { SpringCacheSyncCheck.underivedCacheNames(runtime, beanFactory) },
    )

    companion object {
        private const val SPRING_CACHE_PREFIX = "${CoCacheProperties.PREFIX}.spring-cache"

        /**
         * 缓存通知的 [CacheErrorHandler]；没有唯一的缓存通知（没有 `@EnableCaching`）时是 Spring 默认的 [SimpleCacheErrorHandler]。
         */
        internal fun cacheErrorHandler(cacheAspects: ObjectProvider<CacheAspectSupport>): CacheErrorHandler =
            cacheAspects.getIfUnique()?.errorHandler ?: SpringSupport.SIMPLE_ERROR_HANDLER
    }
}
