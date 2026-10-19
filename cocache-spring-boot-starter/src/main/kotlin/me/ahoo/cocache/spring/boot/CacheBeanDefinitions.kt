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

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.env.Environment

/**
 * starter 注册的缓存 bean 定义的公共约定：用属性标记缓存名（或 spec bean 名），供校验与错误提示使用。
 */
internal object CacheBeanDefinitions {
    /**
     * 接口式声明的缓存名。
     */
    const val CACHE_NAME_ATTRIBUTE = "me.ahoo.cocache.cacheName"

    /**
     * spec 声明对应的 spec bean 名（缓存名在 spec 中）。
     */
    const val SPEC_BEAN_ATTRIBUTE = "me.ahoo.cocache.specBean"

    /**
     * 已声明（不论是否已创建）的缓存名。spec 声明的缓存名需要取出 spec bean（不可变数据，取出代价很小）。
     */
    fun declaredCacheNames(beanFactory: ConfigurableListableBeanFactory): Set<String> {
        val names = linkedSetOf<String>()
        for (beanName in beanFactory.beanDefinitionNames) {
            val definition = beanFactory.getBeanDefinition(beanName)
            (definition.getAttribute(CACHE_NAME_ATTRIBUTE) as? String)?.let { names += it }
            (definition.getAttribute(SPEC_BEAN_ATTRIBUTE) as? String)?.let { specBean ->
                when (val spec = beanFactory.getBean(specBean)) {
                    is CacheSpec<*, *> -> names += spec.name
                    is SetCacheSpec<*, *> -> names += spec.name
                }
            }
        }
        return names
    }

    /**
     * 取出创建缓存所需的 starter 组件；不存在时说明原因（CoCache 被禁用或没有后端），而不是抛出裸的 [NoSuchBeanDefinitionException]。
     */
    fun <T : Any> requireComponent(beanFactory: BeanFactory, type: Class<T>, cacheName: String): T {
        try {
            return beanFactory.getBean(type)
        } catch (e: NoSuchBeanDefinitionException) {
            val environment = beanFactory.getBeanProvider(Environment::class.java).ifAvailable
            val enabled = environment?.getProperty("${CoCacheProperties.PREFIX}.enabled", Boolean::class.javaObjectType, true) ?: true
            val reason = if (enabled) {
                "no ${CoherenceBackend::class.java.simpleName} is available: configure Spring Data Redis " +
                    "(spring.data.redis.*) or declare a CoherenceBackend bean"
            } else {
                "CoCache is disabled (${CoCacheProperties.PREFIX}.enabled=false): enable it or remove the cache declaration"
            }
            throw IllegalStateException("Cannot create cache[$cacheName] - $reason.", e)
        }
    }
}
