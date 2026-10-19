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

import me.ahoo.cocache.api.join.JoinCache
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import java.util.function.Supplier

/**
 * 把 [EnableCoCache.joinCaches] 列出的组合视图接口注册为 bean：bean 名为组合视图名（默认接口简单类名），类型为接口本身。
 *
 * 组合视图在启动时（单例预实例化）由 [JoinCacheFactory] 创建，按名称取出主缓存与关联缓存的 bean。
 */
internal class JoinCacheRegistrar : ImportBeanDefinitionRegistrar {
    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        val attributes = importingClassMetadata.getAnnotationAttributes(EnableCoCache::class.java.name) ?: return

        @Suppress("UNCHECKED_CAST")
        val types = attributes["joinCaches"] as Array<Class<out JoinCache<*, *, *, *>>>
        for (type in types) {
            val joinCacheName = JoinCacheFactory.joinCacheNameOf(type)
            check(!registry.containsBeanDefinition(joinCacheName)) {
                "Cannot register join cache interface[${type.name}]: the bean name[$joinCacheName] is already in use."
            }
            val definition = RootBeanDefinition(type).apply {
                instanceSupplier = Supplier {
                    JoinCacheFactory(registry as ConfigurableListableBeanFactory).create(type)
                }
            }
            registry.registerBeanDefinition(joinCacheName, definition)
        }
    }
}
