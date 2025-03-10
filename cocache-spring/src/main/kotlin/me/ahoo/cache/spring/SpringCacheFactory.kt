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

import me.ahoo.cache.CacheFactory
import me.ahoo.cache.api.Cache
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException

class SpringCacheFactory(private val beanFactory: ListableBeanFactory) : CacheFactory {
    override val caches: Map<String, Cache<*, *>>
        get() {
            return beanFactory.getBeansOfType(Cache::class.java)
        }

    @Suppress("UNCHECKED_CAST", "SwallowedException")
    override fun <CACHE : Cache<*, *>> getCache(cacheName: String, cacheType: Class<*>): CACHE? {
        return try {
            beanFactory.getBean(cacheName, cacheType) as CACHE
        } catch (error: NoSuchBeanDefinitionException) {
            null
        }
    }
}
