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
import me.ahoo.cocache.api.join.JoinCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.core.ResolvableType
import org.springframework.core.env.Environment

/**
 * starter 注册的缓存 bean 定义的公共约定。
 *
 * 缓存 bean 一律由本类的静态工厂方法创建，bean 定义只携带可序列化的第一个构造参数：接口类型或 spec bean 名。
 * 这样 Spring AOT 能为它们生成代码：运行时由生成的代码注册 bean 定义，不再需要注册器（lambda 实例提供者 AOT 无法生成）。
 * 校验与错误提示也从这个构造参数识别缓存，而不是 bean 定义属性：AOT 生成的 bean 定义不保留属性。
 */
internal object CacheBeanDefinitions {
    /**
     * 已声明（不论是否已创建）的缓存名。spec 声明的缓存名需要取出 spec bean（不可变数据，取出代价很小）。
     */
    fun declaredCacheNames(beanFactory: ConfigurableListableBeanFactory): Set<String> {
        val names = linkedSetOf<String>()
        for (beanName in beanFactory.beanDefinitionNames) {
            val definition = beanFactory.getBeanDefinition(beanName)
            cacheInterfaceOf(definition)?.let { names += AnnotatedCacheFactory.cacheNameOf(it) }
            specBeanOf(definition)?.let { specBean -> names += specNameOf(beanFactory.getBean(specBean)) }
        }
        return names
    }

    /**
     * 接口式声明的缓存接口；不是接口式声明的缓存 bean 定义时返回 `null`。
     */
    fun cacheInterfaceOf(definition: BeanDefinition): Class<*>? {
        return (argumentOf(definition) as? Class<*>)?.takeIf { Cache::class.java.isAssignableFrom(it) }
    }

    /**
     * 组合视图接口；不是组合视图的 bean 定义时返回 `null`。
     */
    fun joinInterfaceOf(definition: BeanDefinition): Class<*>? {
        return (argumentOf(definition) as? Class<*>)?.takeIf { JoinCache::class.java.isAssignableFrom(it) }
    }

    /**
     * spec 声明对应的 spec bean 名；不是 spec 声明的缓存 bean 定义时返回 `null`。
     */
    fun specBeanOf(definition: BeanDefinition): String? = argumentOf(definition) as? String

    /**
     * spec bean 的缓存名：[SetCacheSpec] 或 [CacheSpec]。
     */
    fun specNameOf(spec: Any): String = if (spec is SetCacheSpec<*, *>) spec.name else (spec as CacheSpec<*, *>).name

    private fun argumentOf(definition: BeanDefinition): Any? {
        if (definition.beanClassName != CacheBeanDefinitions::class.java.name) {
            return null
        }
        return definition.constructorArgumentValues.getIndexedArgumentValue(0, null)?.value
    }

    /**
     * 接口式声明（`@EnableCoCache(caches = ...)`）的 bean 定义：由 [annotatedCache] 创建，类型为接口本身。
     */
    fun annotatedCacheDefinition(type: Class<*>): RootBeanDefinition {
        return factoryMethodDefinition(ANNOTATED_CACHE_METHOD, ResolvableType.forClass(type), type)
    }

    /**
     * 组合视图（`@EnableCoCache(joinCaches = ...)`）的 bean 定义：由 [joinCache] 创建，类型为接口本身。
     */
    fun joinCacheDefinition(type: Class<*>): RootBeanDefinition {
        return factoryMethodDefinition(JOIN_CACHE_METHOD, ResolvableType.forClass(type), type)
    }

    /**
     * spec 声明的 bean 定义：由 [specCache] 创建，类型带上 spec 的泛型（如 `Cache<String, User>`），依赖 spec bean。
     */
    fun specCacheDefinition(cacheType: ResolvableType, specName: String): RootBeanDefinition {
        return factoryMethodDefinition(SPEC_CACHE_METHOD, cacheType, specName).apply {
            setDependsOn(specName)
        }
    }

    private fun factoryMethodDefinition(method: String, targetType: ResolvableType, argument: Any): RootBeanDefinition {
        return RootBeanDefinition(CacheBeanDefinitions::class.java).apply {
            setTargetType(targetType)
            factoryMethodName = method
            constructorArgumentValues.addIndexedArgumentValue(0, argument)
            // 第二个参数（BeanFactory）按类型自动注入
            autowireMode = AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR
        }
    }

    /**
     * 接口式声明的工厂方法（bean 定义的工厂方法，不是给使用者调用的 API）。
     */
    @JvmStatic
    fun annotatedCache(type: Class<*>, beanFactory: ConfigurableListableBeanFactory): Any {
        @Suppress("UNCHECKED_CAST")
        val cacheType = type as Class<out Cache<*, *>>
        val cacheName = AnnotatedCacheFactory.cacheNameOf(type)
        return requireComponent(beanFactory, AnnotatedCacheFactory::class.java, cacheName).create(cacheType)
    }

    /**
     * 组合视图的工厂方法（bean 定义的工厂方法，不是给使用者调用的 API）。
     */
    @JvmStatic
    fun joinCache(type: Class<*>, beanFactory: ConfigurableListableBeanFactory): Any {
        @Suppress("UNCHECKED_CAST")
        return JoinCacheFactory(beanFactory).create(type as Class<out JoinCache<*, *, *, *>>)
    }

    /**
     * spec 声明的工厂方法（bean 定义的工厂方法，不是给使用者调用的 API）：spec bean 是 [SetCacheSpec] 时创建集合缓存。
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun specCache(specName: String, beanFactory: ConfigurableListableBeanFactory): Any {
        val spec = beanFactory.getBean(specName)
        val assembler = requireComponent(beanFactory, CacheAssembler::class.java, specNameOf(spec))
        return if (spec is SetCacheSpec<*, *>) {
            assembler.create(spec as SetCacheSpec<Any, Any>)
        } else {
            assembler.create(spec as CacheSpec<Any, Any>)
        }
    }

    private const val ANNOTATED_CACHE_METHOD = "annotatedCache"
    private const val JOIN_CACHE_METHOD = "joinCache"
    private const val SPEC_CACHE_METHOD = "specCache"

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
                    "(spring.data.redis.*), set cocache.backend=memory for local development and tests, or declare a CoherenceBackend bean"
            } else {
                "CoCache is disabled (${CoCacheProperties.PREFIX}.enabled=false): enable it or remove the cache declaration"
            }
            throw IllegalStateException("Cannot create cache[$cacheName] - $reason.", e)
        }
    }
}
