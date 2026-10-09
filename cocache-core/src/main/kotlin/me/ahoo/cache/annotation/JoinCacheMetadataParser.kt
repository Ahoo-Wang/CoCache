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

import me.ahoo.cache.api.annotation.JoinCacheable
import me.ahoo.cache.api.join.JoinCache
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.jvmName

object JoinCacheMetadataParser {
    /**
     * 解析 [JoinCacheable] 注解定义的 JoinCache 接口。
     *
     * @param proxyInterface 必须是接口，且继承 [JoinCache]
     */
    fun parse(proxyInterface: KClass<out JoinCache<*, *, *, *>>): JoinCacheMetadata {
        require(proxyInterface.java.isInterface) {
            "${proxyInterface.jvmName} must be interface."
        }
        val annotation = proxyInterface.findAnnotation<JoinCacheable>() ?: JoinCacheable()
        val joinCacheType = proxyInterface.resolveSupertype(JoinCache::class)
        val joinKeyType = joinCacheType.typeArgument(2)
        if (annotation.joinKeyExpression.isNotBlank()) {
            require(joinKeyType.classifier == String::class) {
                "[${proxyInterface.jvmName}] JoinCacheable.joinKeyExpression must be blank when joinKeyType is not String."
            }
        }
        return JoinCacheMetadata(
            proxyInterface = proxyInterface,
            cacheName = annotation.name.ifBlank { requireNotNull(proxyInterface.simpleName) },
            firstCacheName = annotation.firstCacheName,
            joinCacheName = annotation.joinCacheName,
            joinKeyExpression = annotation.joinKeyExpression,
            firstKeyType = joinCacheType.typeArgument(0),
            firstValueType = joinCacheType.typeArgument(1),
            joinKeyType = joinKeyType,
            joinValueType = joinCacheType.typeArgument(3),
        )
    }
}

fun KClass<out JoinCache<*, *, *, *>>.toJoinCacheMetadata(): JoinCacheMetadata {
    return JoinCacheMetadataParser.parse(this)
}

inline fun <reified CACHE : JoinCache<*, *, *, *>> joinCacheMetadata(): JoinCacheMetadata {
    return CACHE::class.toJoinCacheMetadata()
}
