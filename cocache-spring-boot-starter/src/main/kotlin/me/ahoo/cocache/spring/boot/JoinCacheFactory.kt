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
import me.ahoo.cocache.api.annotation.JoinCacheable
import me.ahoo.cocache.api.join.JoinCache
import me.ahoo.cocache.api.join.JoinKeyExtractor
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.join.SimpleJoinCache
import me.ahoo.cocache.core.proxy.JoinCacheProxies
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.ResolvableType
import org.springframework.expression.common.LiteralExpression
import org.springframework.expression.common.TemplateParserContext
import org.springframework.expression.spel.standard.SpelExpressionParser

/**
 * 把组合视图接口（[JoinCacheable]）翻译为 [SimpleJoinCache]，再包上代理（[JoinCacheProxies]）。
 *
 * - 泛型取自接口继承的 `JoinCache<K1, V1, K2, V2>`，必须是具体类型。
 * - 主缓存与关联缓存只按名称解析（`firstCacheName` / `joinCacheName` 必填）：先按 bean 名，再按 spec 声明的缓存名；
 *   并校验其类型为 `Cache<K1, V1>` / `Cache<K2, V2>`。
 * - 关联 key：`joinKeyExpression`（SpEL 模板，以主值为根对象，要求 `K2` 为 `String`）、`joinKeyExtractor` 指定的 bean，
 *   两者都为空时按泛型 `JoinKeyExtractor<V1, K2>` 唯一匹配；找不到或有多个候选时启动失败。
 */
internal class JoinCacheFactory(private val beanFactory: ConfigurableListableBeanFactory) {
    fun <C : JoinCache<*, *, *, *>> create(type: Class<C>): C {
        val name = joinCacheNameOf(type)
        val annotation = checkNotNull(type.getAnnotation(JoinCacheable::class.java)) {
            "Join cache[$name] - interface[${type.name}] must be annotated with @JoinCacheable(firstCacheName = ..., joinCacheName = ...)."
        }
        val generics = ResolvableType.forClass(type).`as`(JoinCache::class.java).generics
        check(generics.none { it.resolve() == null }) {
            "Join cache[$name] - interface[${type.name}] must extend JoinCache with concrete type arguments."
        }
        val firstCache = cacheOf(name, "firstCacheName", annotation.firstCacheName, generics[0], generics[1])
        val joinCache = cacheOf(name, "joinCacheName", annotation.joinCacheName, generics[2], generics[3])
        val extractor = extractorOf(name, annotation, firstValueType = generics[1], joinKeyType = generics[2])
        return JoinCacheProxies.create(type, SimpleJoinCache(name, firstCache, joinCache, extractor))
    }

    @Suppress("UNCHECKED_CAST")
    private fun cacheOf(
        name: String,
        attribute: String,
        cacheName: String,
        keyType: ResolvableType,
        valueType: ResolvableType
    ): Cache<Any, Any> {
        check(cacheName.isNotBlank()) {
            "Join cache[$name] - @JoinCacheable.$attribute is required."
        }
        val beanName = cacheBeanNameOf(cacheName) ?: throw IllegalStateException(
            "Join cache[$name] - @JoinCacheable.$attribute = \"$cacheName\" does not match any cache; " +
                "declared caches: ${CacheBeanDefinitions.declaredCacheNames(beanFactory)}."
        )
        val expected = ResolvableType.forClassWithGenerics(Cache::class.java, keyType, valueType)
        check(beanFactory.isTypeMatch(beanName, expected)) {
            "Join cache[$name] - @JoinCacheable.$attribute = \"$cacheName\" refers to bean[$beanName] of type " +
                "[${beanFactory.getType(beanName)}], expected $expected."
        }
        // 销毁顺序：组合视图先于其引用的缓存
        beanFactory.registerDependentBean(beanName, name)
        return beanFactory.getBean(beanName) as Cache<Any, Any>
    }

    /**
     * 缓存名 → bean 名：接口式声明的 bean 名就是缓存名；spec 声明的 bean 名由 spec bean 名推导，缓存名在 spec 中。
     */
    private fun cacheBeanNameOf(cacheName: String): String? {
        // 先按缓存名精确匹配 spec 声明的缓存（其 bean 名可能不同于缓存名），再按 bean 名匹配接口式缓存
        val specCache = beanFactory.beanDefinitionNames.firstOrNull { beanName ->
            val specBean = beanFactory.getBeanDefinition(
                beanName
            ).getAttribute(CacheBeanDefinitions.SPEC_BEAN_ATTRIBUTE) as? String
            when (val spec = specBean?.let { beanFactory.getBean(it) }) {
                is CacheSpec<*, *> -> spec.name == cacheName
                is SetCacheSpec<*, *> -> spec.name == cacheName
                else -> false
            }
        }
        if (specCache != null) {
            return specCache
        }
        // 同名但不是缓存的 bean 交给类型校验给出明确的错误
        return cacheName.takeIf { beanFactory.containsBean(it) }
    }

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
            val parsed = PARSER.parseExpression(expression, TemplateParserContext())
            check(parsed !is LiteralExpression) {
                "Join cache[$name] - @JoinCacheable.joinKeyExpression[$expression] is a constant; use a template such as \"#{#root.userId}\"."
            }
            return JoinKeyExtractor { firstValue -> parsed.getValue(firstValue, String::class.java) }
        }
        val type = ResolvableType.forClassWithGenerics(JoinKeyExtractor::class.java, firstValueType, joinKeyType)
        if (beanName.isNotBlank()) {
            check(beanFactory.isTypeMatch(beanName, type)) {
                "Join cache[$name] - @JoinCacheable.joinKeyExtractor bean[$beanName] is not a $type."
            }
            return beanFactory.getBean(beanName, JoinKeyExtractor::class.java) as JoinKeyExtractor<Any, Any>
        }
        val candidates = beanFactory.getBeanNamesForType(type)
        check(candidates.size == 1) {
            "Join cache[$name] - expected exactly one $type bean, found ${candidates.toList()}; " +
                "specify @JoinCacheable(joinKeyExpression = \"...\") or @JoinCacheable(joinKeyExtractor = \"beanName\")."
        }
        return beanFactory.getBean(candidates.single()) as JoinKeyExtractor<Any, Any>
    }

    companion object {
        private val PARSER = SpelExpressionParser()

        /**
         * 组合视图名（也是 bean 名）：`@JoinCacheable(name)`，默认接口简单类名。
         */
        fun joinCacheNameOf(type: Class<*>): String {
            return type.getAnnotation(JoinCacheable::class.java)?.name?.takeIf { it.isNotBlank() } ?: type.simpleName
        }
    }
}
