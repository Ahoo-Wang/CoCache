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
import me.ahoo.cache.annotation.toCoCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.spring.proxy.CacheProxyFactoryBean
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata

class EnableCoCacheRegistrar : ImportBeanDefinitionRegistrar {
    companion object {
        private val log = LoggerFactory.getLogger(EnableCoCacheRegistrar::class.java)
    }

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        val cacheMetadataList = resolveCacheMetadataList(importingClassMetadata)
        cacheMetadataList.forEach { cacheMetadata ->
            val beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(CacheProxyFactoryBean::class.java)
            beanDefinitionBuilder.addConstructorArgValue(cacheMetadata)
            beanDefinitionBuilder.setPrimary(true)
            if (log.isInfoEnabled) {
                log.info("Register cache proxy bean definition:{}.", cacheMetadata)
            }
            registry.registerBeanDefinition(cacheMetadata.cacheName, beanDefinitionBuilder.beanDefinition)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveCacheMetadataList(importingClassMetadata: AnnotationMetadata): List<CoCacheMetadata> {
        val enableCoCache = importingClassMetadata
            .getAnnotationAttributes(EnableCoCache::class.java.name) ?: return emptyList()
        val caches = enableCoCache[EnableCoCache::caches.name] as Array<Class<Cache<*, *>>>
        return caches.filter {
            !it.isAssignableFrom(JoinCache::class.java)
        }.map { it.kotlin.toCoCacheMetadata() }
    }
}
