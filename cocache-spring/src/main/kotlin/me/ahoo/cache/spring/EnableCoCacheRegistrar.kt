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
import me.ahoo.cache.annotation.JoinCacheMetadata
import me.ahoo.cache.annotation.toCoCacheMetadata
import me.ahoo.cache.annotation.toJoinCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.spring.join.JoinCacheProxyFactoryBean
import me.ahoo.cache.spring.proxy.CacheProxyFactoryBean
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import kotlin.reflect.KClass

class EnableCoCacheRegistrar : ImportBeanDefinitionRegistrar {
    companion object {
        private val log = LoggerFactory.getLogger(EnableCoCacheRegistrar::class.java)
        const val CACHE_METADATA_SUFFIX = ".CacheMetadata"
    }

    private fun BeanDefinitionRegistry.registerCacheMetadata(cacheMetadata: CoCacheMetadata) {
        val metadataName = "${cacheMetadata.cacheName}$CACHE_METADATA_SUFFIX"
        val metadataBeanDefinition = BeanDefinitionBuilder.genericBeanDefinition(CoCacheMetadata::class.java) {
            cacheMetadata
        }
        registerBeanDefinition(metadataName, metadataBeanDefinition.beanDefinition)
    }

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        val cacheMetadataList = resolveCacheMetadataList(importingClassMetadata)
        cacheMetadataList.forEach { cacheMetadata ->
            registry.registerCacheMetadata(cacheMetadata)
            val beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(CacheProxyFactoryBean::class.java)
            beanDefinitionBuilder.addConstructorArgValue(cacheMetadata)
            beanDefinitionBuilder.setPrimary(true)
            if (log.isInfoEnabled) {
                log.info("Register Cache proxy bean definition:{}.", cacheMetadata)
            }
            registry.registerBeanDefinition(cacheMetadata.cacheName, beanDefinitionBuilder.beanDefinition)
        }
        val joinCacheMetadataList = resolveJoinCacheMetadataList(importingClassMetadata)
        joinCacheMetadataList.forEach { cacheMetadata ->
            val beanDefinitionBuilder =
                BeanDefinitionBuilder.genericBeanDefinition(JoinCacheProxyFactoryBean::class.java)
            beanDefinitionBuilder.addConstructorArgValue(cacheMetadata)
            beanDefinitionBuilder.setPrimary(true)
            if (log.isInfoEnabled) {
                log.info("Register JoinCache proxy bean definition:{}.", cacheMetadata)
            }
            registry.registerBeanDefinition(cacheMetadata.cacheName, beanDefinitionBuilder.beanDefinition)
        }
    }

    private fun resolveCacheMetadataList(importingClassMetadata: AnnotationMetadata): List<CoCacheMetadata> {
        return getCacheTypes(importingClassMetadata)
            .filter {
                !JoinCache::class.java.isAssignableFrom(it)
            }.map {
                it.kotlin.toCoCacheMetadata()
            }
    }

    private fun getCacheTypes(importingClassMetadata: AnnotationMetadata): Array<Class<out Cache<*, *>>> {
        val enableCoCache = importingClassMetadata
            .getAnnotationAttributes(EnableCoCache::class.java.name) ?: return emptyArray()
        @Suppress("UNCHECKED_CAST")
        return enableCoCache[EnableCoCache::caches.name] as Array<Class<out Cache<*, *>>>
    }

    private fun resolveJoinCacheMetadataList(importingClassMetadata: AnnotationMetadata): List<JoinCacheMetadata> {
        return getCacheTypes(importingClassMetadata)
            .filter {
                JoinCache::class.java.isAssignableFrom(it)
            }.map {
                @Suppress("UNCHECKED_CAST")
                it.kotlin as KClass<JoinCache<*, *, *, *>>
            }
            .map {
                it.toJoinCacheMetadata()
            }
    }
}
