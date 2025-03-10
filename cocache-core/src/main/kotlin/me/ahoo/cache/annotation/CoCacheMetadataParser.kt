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

package me.ahoo.cache.annotation

import me.ahoo.cache.ComputedCache
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.jvmName

object CoCacheMetadataParser {
    /**
     * 解析 CoCache 注解定义的 Cache 接口
     *
     * @param cacheType 必须继承 `Cache` 接口，必须是接口
     */
    fun parse(cacheType: KClass<out Cache<*, *>>): CoCacheMetadata {
        require(cacheType.java.isInterface) {
            "${cacheType.jvmName} must be interface."
        }
        val coCacheAnnotation = cacheType.findAnnotation<CoCache>() ?: CoCache()
        // 获取继承的 Cache<K,V> 中 V 的具体类型
        val superCacheType = cacheType.supertypes.first {
            it.classifier == Cache::class || it.classifier == ComputedCache::class
        }
        val keyType = superCacheType.arguments.first().type?.classifier as? KClass<*>
        requireNotNull(keyType)
        val valueType = superCacheType.arguments.last().type?.classifier as? KClass<*>
        requireNotNull(valueType)

        return CoCacheMetadata(
            proxyInterface = cacheType,
            name = coCacheAnnotation.name,
            keyPrefix = coCacheAnnotation.keyPrefix,
            keyExpression = coCacheAnnotation.keyExpression,
            keyType = keyType,
            valueType = valueType
        )
    }
}

fun KClass<out Cache<*, *>>.toCoCacheMetadata(): CoCacheMetadata {
    return CoCacheMetadataParser.parse(this)
}

inline fun <reified CACHE : Cache<*, *>> coCacheMetadata(): CoCacheMetadata {
    return CACHE::class.toCoCacheMetadata()
}
