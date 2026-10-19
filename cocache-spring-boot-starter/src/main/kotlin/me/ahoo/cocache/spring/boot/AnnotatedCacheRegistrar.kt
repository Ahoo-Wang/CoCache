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

package me.ahoo.cocache.spring.boot

import me.ahoo.cocache.api.Cache
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import java.util.function.Supplier

/**
 * 把 [EnableCoCache] 列出的接口注册为 bean；缓存在首次注入时由 [AnnotatedCacheFactory] 创建。
 */
class AnnotatedCacheRegistrar : ImportBeanDefinitionRegistrar {
    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        val attributes = importingClassMetadata.getAnnotationAttributes(EnableCoCache::class.java.name) ?: return

        @Suppress("UNCHECKED_CAST")
        val types = attributes["caches"] as Array<Class<out Cache<*, *>>>
        for (type in types) {
            val cacheName = AnnotatedCacheFactory.cacheNameOf(type)
            check(!registry.containsBeanDefinition(cacheName)) {
                "Cannot register cache interface[${type.name}]: the bean name[$cacheName] is already in use."
            }
            val definition = RootBeanDefinition(type).apply {
                setAttribute(CacheBeanDefinitions.CACHE_NAME_ATTRIBUTE, cacheName)
                instanceSupplier = Supplier {
                    CacheBeanDefinitions.requireComponent(
                        registry as BeanFactory,
                        AnnotatedCacheFactory::class.java,
                        cacheName
                    ).create(type)
                }
            }
            registry.registerBeanDefinition(cacheName, definition)
        }
    }
}
