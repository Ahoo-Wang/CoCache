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

package me.ahoo.cache.spring.client

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.client.ClientSideCacheFactory
import me.ahoo.cache.client.DefaultClientSideCacheFactory
import me.ahoo.cache.spring.AbstractCacheFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType
import kotlin.reflect.jvm.javaType

class SpringClientSideCacheFactory(beanFactory: BeanFactory) : ClientSideCacheFactory,
    AbstractCacheFactory(beanFactory) {
    companion object {
        const val CLIENT_SIDE_CACHE_SUFFIX = ".ClientSideCache"
    }

    override val suffix: String = CLIENT_SIDE_CACHE_SUFFIX

    override fun getBeanType(cacheMetadata: CoCacheMetadata): ResolvableType {
        return ResolvableType.forClassWithGenerics(
            ClientSideCache::class.java,
            ResolvableType.forType(cacheMetadata.valueType.javaType)
        )
    }

    override fun fallback(cacheMetadata: CoCacheMetadata): Any {
        return DefaultClientSideCacheFactory.create<Any>(cacheMetadata)
    }

    override fun <V> create(cacheMetadata: CoCacheMetadata): ClientSideCache<V> {
        @Suppress("UNCHECKED_CAST")
        return createBean(cacheMetadata) as ClientSideCache<V>
    }
}
