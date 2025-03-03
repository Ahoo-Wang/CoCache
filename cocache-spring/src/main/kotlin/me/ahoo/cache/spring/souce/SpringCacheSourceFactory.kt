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

package me.ahoo.cache.spring.souce

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.source.CacheSourceFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType

class SpringCacheSourceFactory(private val beanFactory: BeanFactory) : CacheSourceFactory {
    companion object {
        private val log = LoggerFactory.getLogger(SpringCacheSourceFactory::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <K, V> create(cacheMetadata: CoCacheMetadata): CacheSource<K, V> {
        val cacheSourceType = ResolvableType.forClassWithGenerics(
            CacheSource::class.java,
            String::class.java,
            cacheMetadata.valueType.java
        )
        val cacheSourceProvider = beanFactory.getBeanProvider<CacheSource<String, Any>>(cacheSourceType)
        return cacheSourceProvider.getIfAvailable {
            if (log.isWarnEnabled) {
                log.warn(
                    "CacheSource not found for {}, use CacheSource.noOp() instead.",
                    cacheMetadata
                )
            }
            CacheSource.noOp()
        } as CacheSource<K, V>
    }
}
