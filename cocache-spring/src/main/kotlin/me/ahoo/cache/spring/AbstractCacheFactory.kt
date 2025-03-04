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

package me.ahoo.cache.spring

import me.ahoo.cache.annotation.CoCacheMetadata
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType

abstract class AbstractCacheFactory(private val beanFactory: BeanFactory) {

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(AbstractCacheFactory::class.java)
    }

    abstract val suffix: String

    private fun getBeanName(cacheMetadata: CoCacheMetadata): String {
        return cacheMetadata.cacheName + suffix
    }

    abstract fun getBeanType(cacheMetadata: CoCacheMetadata): ResolvableType

    abstract fun fallback(cacheMetadata: CoCacheMetadata): Any

    fun createBean(cacheMetadata: CoCacheMetadata): Any {
        val beanName = getBeanName(cacheMetadata)
        if (beanFactory.containsBean(beanName)) {
            return beanFactory.getBean(beanName)
        }
        val beanType = getBeanType(cacheMetadata)
        val provider = beanFactory.getBeanProvider<Any>(beanType)
        return provider.getIfAvailable {
            if (log.isWarnEnabled) {
                log.warn(
                    "[${this.javaClass.simpleName}] Not found for {}, fallback.",
                    cacheMetadata
                )
            }
            fallback(cacheMetadata)
        }
    }
}
