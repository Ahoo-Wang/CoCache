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
import me.ahoo.cache.api.converter.KeyConverter
import me.ahoo.cache.converter.DefaultKeyConverterFactory
import me.ahoo.cache.converter.KeyConverterFactory
import me.ahoo.cache.spring.AbstractCacheFactory
import org.springframework.context.ApplicationContext

/**
 * 解析 `{cacheName}.KeyConverter` bean，缺省按元数据创建（keyPrefix/keyExpression 支持 Spring 占位符）。
 */
class SpringKeyConverterFactory(
    appContext: ApplicationContext
) : KeyConverterFactory, AbstractCacheFactory(appContext) {
    companion object {
        const val KEY_CONVERTER_SUFFIX = ".KeyConverter"
    }

    private val defaultFactory = DefaultKeyConverterFactory(appContext.environment::resolvePlaceholders)

    override val suffix: String = KEY_CONVERTER_SUFFIX

    override fun fallback(cacheMetadata: CoCacheMetadata): Any {
        return defaultFactory.create<Any>(cacheMetadata)
    }

    override fun <K> create(cacheMetadata: CoCacheMetadata): KeyConverter<K> {
        @Suppress("UNCHECKED_CAST")
        return resolve(cacheMetadata) as KeyConverter<K>
    }
}
