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

package me.ahoo.cache.spring.converter

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.converter.ExpKeyConverter
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.converter.KeyConverterFactory
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.spring.AbstractCacheFactory
import org.springframework.context.ApplicationContext
import org.springframework.core.ResolvableType
import kotlin.reflect.jvm.javaType

class SpringKeyConverterFactory(private val appContext: ApplicationContext) : KeyConverterFactory,
    AbstractCacheFactory(appContext) {

    companion object {
        const val KEY_CONVERTER_SUFFIX = ".KeyConverter"
    }

    override val suffix: String = KEY_CONVERTER_SUFFIX

    override fun getBeanType(cacheMetadata: CoCacheMetadata): ResolvableType {
        return ResolvableType.forClassWithGenerics(
            KeyConverter::class.java,
            ResolvableType.forType(cacheMetadata.keyType.javaType)
        )
    }

    override fun getBeanProvider(cacheMetadata: CoCacheMetadata, fallback: () -> Any): Any {
        if (cacheMetadata.keyType.classifier == String::class) {
            return fallback()
        }
        return super.getBeanProvider(cacheMetadata, fallback)
    }

    override fun fallback(cacheMetadata: CoCacheMetadata): Any {
        val cacheKeyPrefix = if (cacheMetadata.keyPrefix.isNotBlank()) {
            appContext.environment.resolvePlaceholders(cacheMetadata.keyPrefix)
        } else {
            "${CoCache.COCACHE}:${cacheMetadata.cacheName}:"
        }

        if (cacheMetadata.keyExpression.isNotBlank()) {
            val keyExp = appContext.environment.resolvePlaceholders(cacheMetadata.keyExpression)
            return ExpKeyConverter<Any>(cacheKeyPrefix, keyExp)
        }
        return ToStringKeyConverter<Any>(cacheKeyPrefix)
    }

    override fun <K> create(cacheMetadata: CoCacheMetadata): KeyConverter<K> {
        @Suppress("UNCHECKED_CAST")
        return createBean(cacheMetadata) as KeyConverter<K>
    }
}
