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
import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.api.annotation.JoinCacheable
import me.ahoo.cocache.api.join.JoinCache
import me.ahoo.cocache.api.join.JoinKeyExtractor
import me.ahoo.cocache.api.join.JoinView
import me.ahoo.cocache.core.join.SimpleJoinCache
import me.ahoo.cocache.core.join.SimpleJoinView
import me.ahoo.cocache.core.proxy.JoinCacheProxies
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.ResolvableType
import org.springframework.expression.common.LiteralExpression

/**
 * 把组合视图接口（[JoinCacheable]）翻译为 [SimpleJoinView] / [SimpleJoinCache]，再包上代理（[JoinCacheProxies]）。
 *
 * - 接口继承 [JoinCache]（两侧都是 `Cache`，另有失效）时创建 [SimpleJoinCache]，只继承 [JoinView]（只读，两侧是任何缓存）时创建
 *   [SimpleJoinView]（RFC 0005 §3.1）。泛型取自接口继承的那个类型，必须是具体类型。
 * - 主缓存与关联缓存只按名称解析（`firstCacheName` / `joinCacheName` 必填）：先按 spec 声明的缓存名（其 bean 名可能不同），再按 bean 名（接口式缓存的 bean 名即缓存名）；
 *   包含祖先容器，从本容器起逐级查找，可见性与按名称 `getBean` 一致（子容器的同名 bean 遮蔽祖先容器的）；并校验其类型（[JoinCache] 为 `Cache<K1, V1>` / `Cache<K2, V2>`，[JoinView] 为 `ReadableCache<K1, V1>` / `ReadableCache<K2, V2>`）。
 * - 关联 key：`joinKeyExpression`（SpEL 模板，以主值为根对象，要求 `K2` 为 `String`）、`joinKeyExtractor` 指定的 bean，
 *   两者都为空时按泛型 `JoinKeyExtractor<V1, K2>` 唯一匹配；找不到或有多个候选时启动失败。
 */
internal class JoinCacheFactory(private val beanFactory: ConfigurableListableBeanFactory) {
    @Suppress("UNCHECKED_CAST")
    fun <C : JoinView<*, *, *, *>> create(type: Class<C>): C {
        val name = joinCacheNameOf(type)
        val annotation = checkNotNull(type.getAnnotation(JoinCacheable::class.java)) {
            "Join cache[$name] - interface[${type.name}] must be annotated with @JoinCacheable(firstCacheName = ..., joinCacheName = ...)."
        }
        val writable = JoinCache::class.java.isAssignableFrom(type)
        val viewType = if (writable) JoinCache::class.java else JoinView::class.java
        val generics = ResolvableType.forClass(type).`as`(viewType).generics
        check(generics.none { it.resolve() == null }) {
            "Join cache[$name] - interface[${type.name}] must extend ${viewType.simpleName} with concrete type arguments."
        }
        val cacheType = if (writable) Cache::class.java else ReadableCache::class.java
        val firstCache = cacheOf(name, "firstCacheName", annotation.firstCacheName, cacheType, generics[0], generics[1])
        val joinCache = cacheOf(name, "joinCacheName", annotation.joinCacheName, cacheType, generics[2], generics[3])
        val extractor = extractorOf(name, annotation, firstValueType = generics[1], joinKeyType = generics[2])
        val target = if (writable) {
            SimpleJoinCache(name, firstCache as Cache<Any, Any>, joinCache as Cache<Any, Any>, extractor)
        } else {
            SimpleJoinView(name, firstCache, joinCache, extractor)
        }
        return JoinCacheProxies.create(type, target)
    }

    @Suppress("UNCHECKED_CAST", "LongParameterList")
    private fun cacheOf(
        name: String,
        attribute: String,
        cacheName: String,
        cacheType: Class<*>,
        keyType: ResolvableType,
        valueType: ResolvableType
    ): ReadableCache<Any, Any> {
        check(cacheName.isNotBlank()) {
            "Join cache[$name] - @JoinCacheable.$attribute is required."
        }
        val beanName = cacheBeanNameOf(cacheName) ?: throw IllegalStateException(
            "Join cache[$name] - @JoinCacheable.$attribute = \"$cacheName\" does not match any cache; " +
                "declared caches: ${levels().flatMap { CacheBeanDefinitions.declaredCacheNames(it) }.toSortedSet()}."
        )
        val expected = ResolvableType.forClassWithGenerics(cacheType, keyType, valueType)
        check(beanFactory.isTypeMatch(beanName, expected)) {
            "Join cache[$name] - @JoinCacheable.$attribute = \"$cacheName\" refers to bean[$beanName] of type " +
                "[${beanFactory.getType(beanName)}], expected $expected."
        }
        // 销毁顺序：组合视图先于其引用的缓存
        beanFactory.registerDependentBean(beanName, name)
        return beanFactory.getBean(beanName) as ReadableCache<Any, Any>
    }

    /**
     * 缓存名 → bean 名：接口式声明的 bean 名就是缓存名；spec 声明的 bean 名由 spec bean 名推导，缓存名在 spec 中。
     *
     * 从本容器起逐级向祖先容器查找，每一级先按缓存名精确匹配这一级 spec 声明的缓存，再按 bean 名匹配这一级的 bean：
     * 近的容器优先，子容器的同名 bean（不论类型）遮蔽祖先容器的，祖先容器中被遮蔽的 spec 缓存不按缓存名解析（与按名称 `getBean` 一致）。
     * 曾只查找本容器的 spec 缓存名，祖先容器的 spec 缓存只能按 bean 名引用（#875）。
     */
    private fun cacheBeanNameOf(cacheName: String): String? {
        val descendants = mutableListOf<ConfigurableListableBeanFactory>()
        for (level in levels()) {
            val specCache = level.beanDefinitionNames.firstOrNull { beanName ->
                descendants.none { it.containsLocalBean(beanName) } && specCacheNameOf(level, beanName) == cacheName
            }
            if (specCache != null) {
                return specCache
            }
            // 同名但不是缓存的 bean 交给类型校验给出明确的错误
            if (level.containsLocalBean(cacheName)) {
                return cacheName
            }
            descendants += level
        }
        // 读不到 bean 定义的祖先容器（不是 ConfigurableListableBeanFactory）只按 bean 名
        return cacheName.takeIf { beanFactory.containsBean(it) }
    }

    /**
     * [level] 中定义的 bean [beanName] 是 spec 声明的缓存时，它的缓存名；spec bean 在同一个容器中。
     */
    private fun specCacheNameOf(level: ConfigurableListableBeanFactory, beanName: String): String? {
        val specBean = CacheBeanDefinitions.specBeanOf(level.getBeanDefinition(beanName)) ?: return null
        return CacheBeanDefinitions.specNameOf(level.getBean(specBean))
    }

    /**
     * 本容器与能读取 bean 定义的祖先容器，由近及远。
     */
    private fun levels(): Sequence<ConfigurableListableBeanFactory> =
        generateSequence(beanFactory) { it.parentBeanFactory as? ConfigurableListableBeanFactory }

    @Suppress("UNCHECKED_CAST")
    private fun extractorOf(
        name: String,
        annotation: JoinCacheable,
        firstValueType: ResolvableType,
        joinKeyType: ResolvableType
    ): JoinKeyExtractor<Any, Any> {
        val expression = annotation.joinKeyExpression
        val beanName = annotation.joinKeyExtractor
        check(expression.isBlank() || beanName.isBlank()) {
            "Join cache[$name] - specify either @JoinCacheable.joinKeyExpression or @JoinCacheable.joinKeyExtractor, not both."
        }
        if (expression.isNotBlank()) {
            check(joinKeyType.resolve() == String::class.java) {
                "Join cache[$name] - @JoinCacheable.joinKeyExpression requires the join key type to be String, but it is $joinKeyType; " +
                    "use @JoinCacheable.joinKeyExtractor instead."
            }
            val parsed = SpelTemplates.parse(expression, beanFactory.beanClassLoader)
            check(parsed !is LiteralExpression) {
                "Join cache[$name] - @JoinCacheable.joinKeyExpression[$expression] is a constant; use a template such as \"#{#root.userId}\"."
            }
            return JoinKeyExtractor { firstValue -> parsed.getValue(firstValue, String::class.java) }
        }
        val type = ResolvableType.forClassWithGenerics(JoinKeyExtractor::class.java, firstValueType, joinKeyType)
        if (beanName.isNotBlank()) {
            check(BeanCandidates.isTypeMatch(beanFactory, beanName, type)) {
                "Join cache[$name] - @JoinCacheable.joinKeyExtractor bean[$beanName] is not a $type."
            }
            return beanFactory.getBean(beanName, JoinKeyExtractor::class.java) as JoinKeyExtractor<Any, Any>
        }
        val candidates = BeanCandidates.namesOf(beanFactory, type)
        check(candidates.size == 1) {
            "Join cache[$name] - expected exactly one $type bean, found $candidates; " +
                "specify @JoinCacheable(joinKeyExpression = \"...\") or @JoinCacheable(joinKeyExtractor = \"beanName\")."
        }
        return beanFactory.getBean(candidates.single()) as JoinKeyExtractor<Any, Any>
    }

    companion object {
        /**
         * 组合视图名（也是 bean 名）：`@JoinCacheable(name)`，默认接口简单类名。
         */
        fun joinCacheNameOf(type: Class<*>): String {
            return type.getAnnotation(JoinCacheable::class.java)?.name?.takeIf { it.isNotBlank() } ?: type.simpleName
        }
    }
}
