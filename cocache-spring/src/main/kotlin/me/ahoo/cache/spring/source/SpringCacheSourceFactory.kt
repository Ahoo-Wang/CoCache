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

package me.ahoo.cache.spring.source

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.source.CacheSourceFactory
import me.ahoo.cache.spring.AbstractCacheFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType
import kotlin.reflect.javaType

class SpringCacheSourceFactory(beanFactory: BeanFactory) : CacheSourceFactory,
    AbstractCacheFactory(beanFactory) {
    companion object {
        const val CACHE_SOURCE_SUFFIX = ".CacheSource"
    }

    override val suffix: String = CACHE_SOURCE_SUFFIX

    @OptIn(ExperimentalStdlibApi::class)
    override fun getBeanType(cacheMetadata: CoCacheMetadata): ResolvableType {
        return ResolvableType.forClassWithGenerics(
            CacheSource::class.java,
            ResolvableType.forType(cacheMetadata.keyType.javaType),
            ResolvableType.forType(cacheMetadata.valueType.javaType)
        )
    }

    override fun fallback(cacheMetadata: CoCacheMetadata): Any {
        return CacheSource.noOp<Any, Any>()
    }

    override fun <K, V> create(cacheMetadata: CoCacheMetadata): CacheSource<K, V> {
        @Suppress("UNCHECKED_CAST")
        return createBean(cacheMetadata) as CacheSource<K, V>
    }
}
