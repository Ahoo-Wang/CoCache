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
import org.springframework.expression.common.TemplateParserContext
import org.springframework.expression.spel.standard.SpelExpressionParser
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * 把接口式声明（[CoCache]、[CaffeineCache]）翻译为 [CacheSpec] / [SetCacheSpec]，经 [CacheAssembler] 创建引擎，再包上代理。
 *
 * - 泛型取自接口继承的 `Cache<K, V>` / `SetCache<K, E>`，必须是具体类型。
 * - 数据源：`loader` 指定的 bean，否则按 `CacheLoader<K, V>` 唯一匹配，找不到则没有数据源。
 * - 编解码：`codec` 指定的 bean，否则按 `Codec<V>` 唯一匹配，找不到则按值类型使用 Jackson。
 * - key：`keyExpression` 为 SpEL 模板（以业务 key 为根对象），否则 `toString()`。
 */
class AnnotatedCacheFactory(
    private val beanFactory: ListableBeanFactory,
    private val assembler: CacheAssembler,
    private val mapper: ObjectMapper,
) {
    fun <C : Cache<*, *>> create(type: Class<C>): C {
        val annotation = type.getAnnotation(CoCache::class.java)
        val engine: Cache<*, *> = if (SetCache::class.java.isAssignableFrom(type)) {
            assembler.create(setSpecOf(type, annotation))
        } else {
            assembler.create(specOf(type, annotation))
        }
        return CacheProxies.create(type, engine)
    }

    private fun specOf(type: Class<*>, annotation: CoCache?): CacheSpec<Any, Any> {
        val (keyType, valueType) = generics(type, Cache::class.java)
        val builder = CacheSpec.builder<Any, Any>(cacheNameOf(type))
        annotation?.namespace?.takeIf { it.isNotBlank() }?.let { builder.namespace = it }
        annotation?.keyPrefix?.takeIf { it.isNotBlank() }?.let { builder.keyPrefix = it }
        builder.keyEncoder = keyEncoderOf(annotation)
        builder.codec = codecOf(annotation?.codec, valueType)
        builder.loader = loaderOf(annotation?.loader, keyType, valueType)
        builder.policy = policyOf(type, annotation, CachePolicy.DEFAULT)
        return builder.build()
    }

    private fun setSpecOf(type: Class<*>, annotation: CoCache?): SetCacheSpec<Any, Any> {
        val (_, elementType) = generics(type, SetCache::class.java)
        val builder = SetCacheSpec.builder<Any, Any>(cacheNameOf(type))
        annotation?.namespace?.takeIf { it.isNotBlank() }?.let { builder.namespace = it }
        annotation?.keyPrefix?.takeIf { it.isNotBlank() }?.let { builder.keyPrefix = it }
        builder.keyEncoder = keyEncoderOf(annotation)
        builder.elementCodec = codecOf(annotation?.codec, elementType)
        builder.policy = policyOf(type, annotation, SetCacheSpec.DEFAULT_POLICY)
        return builder.build()
    }

    private fun generics(type: Class<*>, cacheType: Class<*>): Pair<ResolvableType, ResolvableType> {
        val generics = ResolvableType.forClass(type).`as`(cacheType).generics
        check(generics.size == 2 && generics.none { it.resolve() == null }) {
            "Cache interface[${type.name}] must extend ${cacheType.simpleName} with concrete type arguments."
        }
        return generics[0] to generics[1]
    }

    private fun keyEncoderOf(annotation: CoCache?): KeyEncoder<Any> {
        val expression = annotation?.keyExpression?.takeIf { it.isNotBlank() } ?: return KeyEncoder { it.toString() }
        val parsed: Expression = PARSER.parseExpression(expression, TemplateParserContext())
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
        val provider = beanFactory.getBeanProvider<Codec<Any>>(
            ResolvableType.forClassWithGenerics(Codec::class.java, valueType)
        )
        return provider.ifUnique ?: JacksonCodec(mapper, mapper.typeFactory.constructType(valueType.type))
    }

    @Suppress("UNCHECKED_CAST")
    private fun loaderOf(
        beanName: String?,
        keyType: ResolvableType,
        valueType: ResolvableType
    ): CacheLoader<Any, Any>? {
        if (!beanName.isNullOrBlank()) {
            return beanFactory.getBean(beanName, CacheLoader::class.java) as CacheLoader<Any, Any>
        }
        val type = ResolvableType.forClassWithGenerics(CacheLoader::class.java, keyType, valueType)
        val candidates = beanFactory.getBeanNamesForType(type)
        check(candidates.size <= 1) {
            "Multiple CacheLoader beans match $type: ${candidates.toList()}; specify @CoCache(loader = \"beanName\")."
        }
        return candidates.firstOrNull()?.let { beanFactory.getBean(it) as CacheLoader<Any, Any> }
    }

    private fun policyOf(type: Class<*>, annotation: CoCache?, base: CachePolicy): CachePolicy {
        val builder = base.toBuilder()
        annotation?.let {
            seconds(it.ttl)?.let { ttl -> builder.ttl = if (it.ttl == CoCache.FOREVER) null else ttl }
            seconds(it.ttlJitter, allowZero = true)?.let { jitter -> builder.ttlJitter = jitter }
            seconds(it.absentTtl)?.let { absent -> builder.absentTtl = absent }
            seconds(it.localMaxAge)?.let { maxAge -> builder.localMaxAge = maxAge }
            seconds(it.refreshWindow)?.let { window -> builder.refreshWindow = window }
        }
        type.getAnnotation(CaffeineCache::class.java)?.let { caffeine ->
            builder.local = LocalPolicy(
                maximumSize = caffeine.maximumSize.takeIf { it != CoCache.UNSET } ?: base.local.maximumSize,
                initialCapacity = caffeine.initialCapacity.takeIf { it != CoCache.UNSET.toInt() } ?: base.local.initialCapacity,
                expireAfterAccess = seconds(caffeine.expireAfterAccess) ?: base.local.expireAfterAccess,
            )
        }
        return builder.build()
    }

    private fun seconds(value: Long, allowZero: Boolean = false): Duration? {
        return when {
            value == CoCache.UNSET -> null
            value == CoCache.FOREVER -> Duration.ofSeconds(Long.MAX_VALUE / 1000)
            value == 0L && allowZero -> Duration.ZERO
            else -> Duration.ofSeconds(value)
        }
    }

    companion object {
        private val PARSER = SpelExpressionParser()

        /**
         * 缓存名：`@CoCache(name)`，默认接口简单类名。
         */
        fun cacheNameOf(type: Class<*>): String {
            return type.getAnnotation(CoCache::class.java)?.name?.takeIf { it.isNotBlank() } ?: type.simpleName
        }
    }
}
