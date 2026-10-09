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

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.jvmName

object CoCacheMetadataParser {
    /**
     * 解析 [CoCache] 注解定义的缓存接口。
     *
     * @param proxyInterface 必须是接口，且继承 [Cache]
     */
    fun parse(proxyInterface: KClass<out Cache<*, *>>): CoCacheMetadata {
        require(proxyInterface.java.isInterface) {
            "${proxyInterface.jvmName} must be interface."
        }
        val annotation = proxyInterface.findAnnotation<CoCache>() ?: CoCache()
        val cacheType = proxyInterface.resolveSupertype(Cache::class)
        return CoCacheMetadata(
            proxyInterface = proxyInterface,
            cacheName = annotation.name.ifBlank { requireNotNull(proxyInterface.simpleName) },
            keyPrefix = annotation.keyPrefix,
            keyExpression = annotation.keyExpression,
            ttlPolicy = TtlPolicy(
                ttl = annotation.ttl,
                ttlAmplitude = annotation.ttlAmplitude,
                missingTtl = annotation.missingTtl
            ),
            keyType = cacheType.typeArgument(0),
            valueType = cacheType.typeArgument(1)
        )
    }
}

/**
 * 解析 [supertype] 在 [this] 上的参数化类型，类型参数必须是具体类型。
 */
internal fun KClass<*>.resolveSupertype(supertype: KClass<*>): KType {
    return requireNotNull(allSupertypes.firstOrNull { it.classifier == supertype }) {
        "$jvmName must extend ${supertype.jvmName}."
    }
}

internal fun KType.typeArgument(index: Int): KType {
    return requireNotNull(arguments[index].type) {
        "Type argument[$index] of [$this] must be a concrete type."
    }
}

fun KClass<out Cache<*, *>>.toCoCacheMetadata(): CoCacheMetadata {
    return CoCacheMetadataParser.parse(this)
}

inline fun <reified CACHE : Cache<*, *>> coCacheMetadata(): CoCacheMetadata {
    return CACHE::class.toCoCacheMetadata()
}
