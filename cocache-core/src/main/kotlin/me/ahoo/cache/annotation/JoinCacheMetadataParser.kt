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

import me.ahoo.cache.annotation.CoCacheMetadataParser.getCacheGenericsType
import me.ahoo.cache.api.annotation.JoinCacheable
import me.ahoo.cache.api.join.JoinCache
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.jvmName

object JoinCacheMetadataParser {
    /**
     * 解析 JoinCacheable 注解定义的 JoinCache 接口
     *
     * @param proxyInterface 必须继承 `JoinCache` 接口，必须是接口
     */
    fun parse(proxyInterface: KClass<out JoinCache<*, *, *, *>>): JoinCacheMetadata {
        require(proxyInterface.java.isInterface) {
            "${proxyInterface.jvmName} must be interface."
        }
        val joinCacheAnnotation = proxyInterface.findAnnotation<JoinCacheable>() ?: JoinCacheable()
        // 获取继承的 JoinCache<K,V> 中 V 的具体类型
        val superCacheType = proxyInterface.supertypes.first {
            it.classifier == JoinCache::class
        }
        val firstKeyType = superCacheType.getCacheGenericsType(0)
        val firstValueType = superCacheType.getCacheGenericsType(1)
        val joinKeyType = superCacheType.getCacheGenericsType(2)
        val joinValueType = superCacheType.getCacheGenericsType(3)
        if (joinCacheAnnotation.joinKeyExpression.isNotBlank()) {
            require(joinKeyType.classifier == String::class) {
                "[${proxyInterface.jvmName}] JoinCacheable.joinKeyExpression must be blank when joinKeyType is not String."
            }
        }

        return JoinCacheMetadata(
            proxyInterface = proxyInterface,
            name = joinCacheAnnotation.name,
            firstCacheName = joinCacheAnnotation.firstCacheName,
            joinCacheName = joinCacheAnnotation.joinCacheName,
            joinKeyExpression = joinCacheAnnotation.joinKeyExpression,
            firstKeyType = firstKeyType,
            firstValueType = firstValueType,
            joinKeyType = joinKeyType,
            joinValueType = joinValueType,
        )
    }
}

fun KClass<out JoinCache<*, *, *, *>>.toJoinCacheMetadata(): JoinCacheMetadata {
    return JoinCacheMetadataParser.parse(this)
}

inline fun <reified CACHE : JoinCache<*, *, *, *>> joinCacheMetadata(): JoinCacheMetadata {
    return CACHE::class.toJoinCacheMetadata()
}
