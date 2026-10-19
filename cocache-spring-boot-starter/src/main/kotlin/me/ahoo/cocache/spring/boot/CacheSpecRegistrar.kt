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
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.core.ResolvableType

/**
 * 把每个 [CacheSpec] / [SetCacheSpec] bean 注册为对应的 [Cache] / [SetCache] bean（RFC 0003 §4.12）。
 *
 * - 泛型取自工厂方法**声明的**返回类型，不实例化声明；无法解析时启动失败（如返回原始类型）。
 * - bean 名：声明 bean 名去掉 `Spec` 后缀（`userSpec` → `user`），没有该后缀时加 `Cache` 后缀。
 * - 缓存在首次注入时由 [CacheAssembler] 创建。
 */
internal class CacheSpecRegistrar : BeanFactoryPostProcessor {
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        val registry = beanFactory as? BeanDefinitionRegistry ?: return
        register(beanFactory, registry, CacheSpec::class.java, Cache::class.java)
        register(beanFactory, registry, SetCacheSpec::class.java, SetCache::class.java)
    }

    private fun register(
        beanFactory: ConfigurableListableBeanFactory,
        registry: BeanDefinitionRegistry,
        specType: Class<*>,
        cacheType: Class<*>,
    ) {
        for (specName in beanFactory.getBeanNamesForType(specType, true, false)) {
            val generics = beanFactory.getMergedBeanDefinition(specName).resolvableType.`as`(specType).generics
            check(generics.size == 2 && generics.none { it.resolve() == null }) {
                "Cache spec bean[$specName] must declare its generic types, e.g. `fun userSpec(): CacheSpec<String, User>`."
            }
            val cacheName = cacheBeanName(specName)
            // AOT 运行时：构建期生成的代码已注册了同一个 spec 的缓存 bean
            if (registry.containsBeanDefinition(cacheName) &&
                CacheBeanDefinitions.specBeanOf(registry.getBeanDefinition(cacheName)) == specName
            ) {
                continue
            }
            check(!registry.containsBeanDefinition(cacheName)) {
                "Cannot register cache bean[$cacheName] for spec bean[$specName]: the bean name is already in use."
            }
            val definition = CacheBeanDefinitions.specCacheDefinition(
                ResolvableType.forClassWithGenerics(cacheType, *generics),
                specName
            )
            registry.registerBeanDefinition(cacheName, definition)
        }
    }

    companion object {
        private const val SPEC_SUFFIX = "Spec"
        private const val CACHE_SUFFIX = "Cache"

        fun cacheBeanName(specBeanName: String): String {
            return if (specBeanName.endsWith(SPEC_SUFFIX) && specBeanName.length > SPEC_SUFFIX.length) {
                specBeanName.removeSuffix(SPEC_SUFFIX)
            } else {
                specBeanName + CACHE_SUFFIX
            }
        }
    }
}
