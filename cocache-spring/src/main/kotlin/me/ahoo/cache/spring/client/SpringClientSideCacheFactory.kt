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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType

class SpringClientSideCacheFactory(private val beanFactory: BeanFactory) : ClientSideCacheFactory {
    companion object {
        private val log = LoggerFactory.getLogger(SpringClientSideCacheFactory::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V> create(cacheMetadata: CoCacheMetadata): ClientSideCache<V> {
        val clientSideCacheType = ResolvableType.forClassWithGenerics(
            ClientSideCache::class.java,
            cacheMetadata.valueType.java
        )
        val clientSideCacheProvider = beanFactory.getBeanProvider<ClientSideCache<Any>>(clientSideCacheType)
        return clientSideCacheProvider.getIfAvailable {
            if (log.isWarnEnabled) {
                log.warn(
                    "ClientSideCache not found for {}, use DefaultClientSideCacheFactory instead.",
                    cacheMetadata
                )
            }
            DefaultClientSideCacheFactory.create(cacheMetadata)
        } as ClientSideCache<V>
    }
}
