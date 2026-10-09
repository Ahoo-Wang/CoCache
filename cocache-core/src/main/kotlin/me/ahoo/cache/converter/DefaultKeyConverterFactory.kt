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

package me.ahoo.cache.converter

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.api.converter.KeyConverter

/**
 * 按元数据创建 key 转换器：前缀缺省为 `cocache:{cacheName}:`；设置了 keyExpression 时使用 SpEL。
 *
 * @param resolvePlaceholders 解析 keyPrefix/keyExpression 中的占位符（如 Spring Environment）
 */
class DefaultKeyConverterFactory(
    private val resolvePlaceholders: (String) -> String = { it }
) : KeyConverterFactory {
    companion object {
        @JvmField
        val INSTANCE = DefaultKeyConverterFactory()
    }

    override fun <K> create(cacheMetadata: CoCacheMetadata): KeyConverter<K> {
        val keyPrefix = cacheMetadata.keyPrefix.ifBlank {
            "${CoCache.COCACHE}:${cacheMetadata.cacheName}:"
        }.let(resolvePlaceholders)
        if (cacheMetadata.keyExpression.isNotBlank()) {
            return ExpKeyConverter(keyPrefix, resolvePlaceholders(cacheMetadata.keyExpression))
        }
        return ToStringKeyConverter(keyPrefix)
    }
}
