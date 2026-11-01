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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.annotation.CaffeineCache
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.codec.jackson.JacksonCodec
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.LocalPolicy
import me.ahoo.cocache.core.proxy.CacheProxies
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.ResolvableType
import org.springframework.expression.Expression
import org.springframework.expression.common.LiteralExpression
import org.springframework.expression.common.TemplateParserContext
import org.springframework.expression.spel.standard.SpelExpressionParser
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * 把接口式声明（[CoCache]、[CaffeineCache]）翻译为 [CacheSpec] / [SetCacheSpec]，经 [CacheAssembler] 创建引擎，再包上代理。
 *
 * - 泛型取自接口继承的 `Cache<K, V>` / `SetCache<K, E>`，必须是具体类型。
 * - 数据源：`loader` 指定的 bean（`CacheLoader` 或 `AsyncCacheLoader`），否则按 `CacheLoader<K, V>`、`AsyncCacheLoader<K, V>`
 *   唯一匹配（两种各有一个候选时启动失败，需用 `loader` 指明；同时实现两者的 bean 按同步数据源使用），找不到则没有数据源。
 * - 编解码：`codec` 指定的 bean，否则按 `Codec<V>` 唯一匹配，找不到则按值类型使用 Jackson。
 * - key：`keyExpression` 为 SpEL 模板（以业务 key 为根对象），否则 `toString()`。
 * - 时长单位为秒；[CoCache.FOREVER] 只能用于 `ttl`（永不过期）与 `@CaffeineCache.expireAfterAccess`（不启用），
 *   `refreshWindow = 0` 表示关闭提前刷新。
 * - 用户接口中的抽象方法必须对应 [Cache] / [SetCache] 的方法（参数类型兼容），否则启动失败（代理按方法名分派）。
 *
 * 内部实现（starter 接线使用），不在兼容性承诺之内。
 */
class AnnotatedCacheFactory(
    private val beanFactory: ListableBeanFactory,
    private val assembler: CacheAssembler,
    private val mapper: ObjectMapper,
) {
    fun <C : Cache<*, *>> create(type: Class<C>): C {
        val cacheName = cacheNameOf(type)
        return withCacheName(cacheName) {
            val annotation = type.getAnnotation(CoCache::class.java)
            val isSet = SetCache::class.java.isAssignableFrom(type)
            CacheMethods.validate(type, isSet)
            val engine: Cache<*, *> = if (isSet) {
                assembler.create(setSpecOf(type, cacheName, annotation))
            } else {
                assembler.create(specOf(type, cacheName, annotation))
            }
            CacheProxies.create(type, engine)
        }
    }

    /**
     * 集合缓存接口翻译成的 spec（尚未叠加定制器与配置文件）。驱逐检查用它得到尚未创建的集合缓存的 `ttl`。
     */
    internal fun setSpecOf(type: Class<*>): SetCacheSpec<Any, Any> {
        val cacheName = cacheNameOf(type)
        return withCacheName(cacheName) { setSpecOf(type, cacheName, type.getAnnotation(CoCache::class.java)) }
    }

    private fun specOf(type: Class<*>, cacheName: String, annotation: CoCache?): CacheSpec<Any, Any> {
        val (keyType, valueType) = generics(type, Cache::class.java)
        val builder = CacheSpec.builder<Any, Any>(cacheName)
        annotation?.namespace?.takeIf { it.isNotBlank() }?.let { builder.namespace = it }
        annotation?.keyPrefix?.takeIf { it.isNotBlank() }?.let { builder.keyPrefix = it }
        builder.keyEncoder = keyEncoderOf(annotation)
        builder.codec = codecOf(annotation?.codec, valueType)
        when (val source = sourceOf(annotation?.loader, keyType, valueType)) {
            is CacheLoader<*, *> -> builder.loader = source as CacheLoader<Any, Any>
            is AsyncCacheLoader<*, *> -> builder.asyncLoader = source as AsyncCacheLoader<Any, Any>
        }
        builder.policy = policyOf(type, cacheName, annotation, CachePolicy.DEFAULT)
        return builder.build()
    }

    private fun setSpecOf(type: Class<*>, cacheName: String, annotation: CoCache?): SetCacheSpec<Any, Any> {
        // SetCache 没有数据源（只用于 L1 是唯一副本的集合），静默忽略 loader 会让使用者误以为未命中会回源
        check(annotation?.loader.isNullOrBlank()) {
            "@CoCache(loader = \"${annotation?.loader}\") is not supported on SetCache interface[${type.name}]: a SetCache has no data source."
        }
        val (_, elementType) = generics(type, SetCache::class.java)
        val builder = SetCacheSpec.builder<Any, Any>(cacheName)
        annotation?.namespace?.takeIf { it.isNotBlank() }?.let { builder.namespace = it }
        annotation?.keyPrefix?.takeIf { it.isNotBlank() }?.let { builder.keyPrefix = it }
        builder.keyEncoder = keyEncoderOf(annotation)
        builder.elementCodec = codecOf(annotation?.codec, elementType)
        builder.policy = policyOf(type, cacheName, annotation, SetCacheSpec.DEFAULT_POLICY)
        return builder.build()
    }

    private fun generics(type: Class<*>, cacheType: Class<*>): Pair<ResolvableType, ResolvableType> {
        return checkNotNull(genericsOf(type, cacheType)) {
            "Cache interface[${type.name}] must extend ${cacheType.simpleName} with concrete type arguments."
        }
    }

    private fun keyEncoderOf(annotation: CoCache?): KeyEncoder<Any> {
        val expression = annotation?.keyExpression?.takeIf { it.isNotBlank() } ?: return KeyEncoder.ofToString()
        val parsed: Expression = PARSER.parseExpression(expression, TemplateParserContext())
        // 没有 #{...} 的模板是常量：所有 key 都映射到同一个存储 key
        require(parsed !is LiteralExpression) {
            "@CoCache.keyExpression[$expression] is a constant; use a template such as \"#{#root.id}\"."
        }
        return KeyEncoder { key ->
            requireNotNull(
                parsed.getValue(key, String::class.java)
            ) { "keyExpression[$expression] evaluated to null for key[$key]." }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun codecOf(beanName: String?, valueType: ResolvableType): Codec<Any> {
        if (!beanName.isNullOrBlank()) {
            return beanFactory.getBean(beanName, Codec::class.java) as Codec<Any>
        }
        // 与数据源一致：多个候选时启动失败，而不是静默退回 Jackson
        val type = ResolvableType.forClassWithGenerics(Codec::class.java, valueType)
        val candidates = beanFactory.getBeanNamesForType(type)
        check(candidates.size <= 1) {
            "Multiple Codec beans match $type: ${candidates.toList()}; specify @CoCache(codec = \"beanName\")."
        }
        return candidates.firstOrNull()?.let { beanFactory.getBean(it) as Codec<Any> }
            ?: JacksonCodec(mapper, mapper.typeFactory.constructType(valueType.type))
    }

    /**
     * 数据源：[CacheLoader] 或 [AsyncCacheLoader]（RFC 0004 §3.4），没有时为 `null`。
     */
    private fun sourceOf(beanName: String?, keyType: ResolvableType, valueType: ResolvableType): Any? {
        if (!beanName.isNullOrBlank()) {
            val bean = beanFactory.getBean(beanName)
            check(bean is CacheLoader<*, *> || bean is AsyncCacheLoader<*, *>) {
                "@CoCache(loader = \"$beanName\") must name a CacheLoader or an AsyncCacheLoader bean, but it is ${bean.javaClass.name}."
            }
            return bean
        }
        val sync = candidatesOf(CacheLoader::class.java, keyType, valueType)
        // 同时实现两者的 bean 按同步数据源使用，不算作异步候选
        val async = candidatesOf(AsyncCacheLoader::class.java, keyType, valueType).filterNot { it in sync }
        check(sync.isEmpty() || async.isEmpty()) {
            "Both CacheLoader ${sync.toList()} and AsyncCacheLoader ${async.toList()} beans match Cache<$keyType, $valueType>; " +
                "specify @CoCache(loader = \"beanName\")."
        }
        return (sync.firstOrNull() ?: async.firstOrNull())?.let { beanFactory.getBean(it) }
    }

    private fun candidatesOf(loaderType: Class<*>, keyType: ResolvableType, valueType: ResolvableType): List<String> {
        val type = ResolvableType.forClassWithGenerics(loaderType, keyType, valueType)
        val candidates = beanFactory.getBeanNamesForType(type)
        check(candidates.size <= 1) {
            "Multiple ${loaderType.simpleName} beans match $type: ${candidates.toList()}; specify @CoCache(loader = \"beanName\")."
        }
        return candidates.toList()
    }

    private fun policyOf(type: Class<*>, cacheName: String, annotation: CoCache?, base: CachePolicy): CachePolicy {
        val builder = base.toBuilder()
        annotation?.let {
            when (it.ttl) {
                CoCache.UNSET -> Unit
                CoCache.FOREVER -> builder.ttl = null
                else -> builder.ttl = Duration.ofSeconds(it.ttl)
            }
            seconds(cacheName, "@CoCache.ttlJitter", it.ttlJitter)?.let { jitter -> builder.ttlJitter = jitter }
            seconds(cacheName, "@CoCache.absentTtl", it.absentTtl)?.let { absent -> builder.absentTtl = absent }
            seconds(cacheName, "@CoCache.localMaxAge", it.localMaxAge)?.let { maxAge -> builder.localMaxAge = maxAge }
            // 0 表示关闭提前刷新
            seconds(cacheName, "@CoCache.refreshWindow", it.refreshWindow)?.let { window ->
                builder.refreshWindow = window.takeUnless { window.isZero }
            }
        }
        type.getAnnotation(CaffeineCache::class.java)?.let { builder.local = localPolicyOf(it, base.local) }
        return builder.build()
    }

    private fun localPolicyOf(caffeine: CaffeineCache, base: LocalPolicy): LocalPolicy {
        val expireAfterAccess = when (caffeine.expireAfterAccess) {
            CoCache.UNSET -> base.expireAfterAccess
            CoCache.FOREVER -> null
            else -> Duration.ofSeconds(caffeine.expireAfterAccess)
        }
        return LocalPolicy(
            maximumSize = caffeine.maximumSize.takeIf { it != CoCache.UNSET } ?: base.maximumSize,
            initialCapacity = caffeine.initialCapacity.takeIf { it != CoCache.UNSET.toInt() } ?: base.initialCapacity,
            expireAfterAccess = expireAfterAccess,
        )
    }

    /**
     * 秒 → 时长；[CoCache.UNSET] 返回 `null`（交给默认值与配置文件）。[CoCache.FOREVER] 只对 `ttl` 有意义，用在其它属性上启动失败。
     */
    private fun seconds(cacheName: String, attribute: String, value: Long): Duration? {
        return when (value) {
            CoCache.UNSET -> null
            CoCache.FOREVER -> throw IllegalArgumentException(
                "Cache[$cacheName] - $attribute = FOREVER is not supported; FOREVER is only allowed for @CoCache.ttl and @CaffeineCache.expireAfterAccess."
            )
            else -> Duration.ofSeconds(value)
        }
    }

    companion object {
        private val PARSER = SpelExpressionParser()

        /**
         * 缓存接口的 key 类型与 value 类型（集合缓存为元素类型）；没有具体类型参数时返回 `null`。
         */
        internal fun keyValueTypesOf(type: Class<*>): Pair<ResolvableType, ResolvableType>? {
            val cacheType = if (SetCache::class.java.isAssignableFrom(type)) SetCache::class.java else Cache::class.java
            return genericsOf(type, cacheType)
        }

        private fun genericsOf(type: Class<*>, cacheType: Class<*>): Pair<ResolvableType, ResolvableType>? {
            val generics = ResolvableType.forClass(type).`as`(cacheType).generics
            if (generics.size != 2 || generics.any { it.resolve() == null }) {
                return null
            }
            return generics[0] to generics[1]
        }

        /**
         * 缓存名：`@CoCache(name)`，默认接口简单类名。
         */
        fun cacheNameOf(type: Class<*>): String {
            return type.getAnnotation(CoCache::class.java)?.name?.takeIf { it.isNotBlank() } ?: type.simpleName
        }
    }
}
