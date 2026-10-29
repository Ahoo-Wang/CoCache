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

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.annotation.JoinCacheable
import me.ahoo.cocache.api.join.JoinCache
import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.aot.hint.ExecutableMode
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.ResolvableType
import java.lang.reflect.Type

/**
 * 构建期（Spring AOT）为 starter 注册的缓存接口与组合视图生成运行时提示，使 AOT 处理过的应用能在 GraalVM 原生镜像中运行。
 *
 * 只读取注册器产生的 bean 定义（不创建缓存）。每个 `@EnableCoCache(caches = ...)` 接口与 `joinCaches` 接口：
 * - JDK 代理：接口列表与 [me.ahoo.cocache.core.proxy.CacheProxies] / [me.ahoo.cocache.core.proxy.JoinCacheProxies]
 *   创建代理时完全一致（只有接口本身）；
 * - 接口本身的公共方法反射：读取注解与泛型、校验抽象方法、以及 `InvocationHandler.invokeDefault` 调用 default 方法；
 * - 声明了 `keyExpression` 时，key 类型的公共方法与字段（SpEL 在其上求值）；`joinKeyExpression` 同理作用于主缓存的值类型；
 * - 没有指定 `codec` 且容器中没有匹配的 `Codec<V>` bean 时（即使用默认的 Jackson 编解码），值类型的 Jackson 绑定提示。
 *
 * 所有声明（含 spec 声明）的 key 类型：默认的 key 编码器首次遇到某个 key 类型时反射检查它是否覆盖了 `toString()`。
 * spec 声明的编解码与数据源由使用者自己构造，CoCache 无从推断，不为其值类型生成提示。
 */
internal class CoCacheAotProcessor : BeanFactoryInitializationAotProcessor {
    override fun processAheadOfTime(
        beanFactory: ConfigurableListableBeanFactory
    ): BeanFactoryInitializationAotContribution? {
        val hints = mutableListOf<(RuntimeHints) -> Unit>()
        for (beanName in beanFactory.beanDefinitionNames) {
            val definition = beanFactory.getBeanDefinition(beanName)
            CacheBeanDefinitions.cacheInterfaceOf(definition)?.let { hints += cacheHints(beanFactory, it) }
            CacheBeanDefinitions.joinInterfaceOf(definition)?.let { hints += joinCacheHints(it) }
            if (CacheBeanDefinitions.specBeanOf(definition) != null) {
                specCacheHints(beanFactory, beanName)?.let { hints += it }
            }
        }
        if (hints.isEmpty()) {
            return null
        }
        return BeanFactoryInitializationAotContribution { generationContext, _ ->
            hints.forEach { it(generationContext.runtimeHints) }
        }
    }

    private fun cacheHints(beanFactory: ConfigurableListableBeanFactory, type: Class<*>): (RuntimeHints) -> Unit {
        val annotation: CoCache? = type.getAnnotation(CoCache::class.java)
        // 没有具体类型参数的接口在运行时创建缓存时失败并给出原因，这里只注册代理
        val generics = AnnotatedCacheFactory.keyValueTypesOf(type)
        val expressionRoot = generics?.first?.takeIf { !annotation?.keyExpression.isNullOrBlank() }
        val bindingType = generics?.second?.takeIf { usesDefaultCodec(beanFactory, annotation, it) }
        return { hints ->
            registerInterface(hints, type)
            generics?.first?.resolve()?.let { registerKeyType(hints, it) }
            expressionRoot?.let { registerExpressionRoot(hints, it) }
            bindingType?.let { registerBinding(hints, it.type) }
        }
    }

    /**
     * spec 声明：bean 定义的类型带有 spec 的泛型（`Cache<K, V>`），只登记 key 类型。
     */
    private fun specCacheHints(
        beanFactory: ConfigurableListableBeanFactory,
        beanName: String
    ): ((RuntimeHints) -> Unit)? {
        val keyType = beanFactory.getMergedBeanDefinition(beanName).resolvableType.generics.firstOrNull()?.resolve()
            ?: return null
        return { hints -> registerKeyType(hints, keyType) }
    }

    private fun joinCacheHints(type: Class<*>): (RuntimeHints) -> Unit {
        val annotation: JoinCacheable? = type.getAnnotation(JoinCacheable::class.java)
        val generics = ResolvableType.forClass(type).`as`(JoinCache::class.java).generics
        // 主缓存的值类型是 joinKeyExpression 的求值对象
        val expressionRoot = generics.getOrNull(1)?.takeIf { !annotation?.joinKeyExpression.isNullOrBlank() }
        return { hints ->
            registerInterface(hints, type)
            expressionRoot?.let { registerExpressionRoot(hints, it) }
        }
    }

    /**
     * 与 [AnnotatedCacheFactory] 的选择一致：未指定 `codec`，且没有匹配 `Codec<V>` 的 bean（不提前初始化 bean）。
     */
    private fun usesDefaultCodec(
        beanFactory: ConfigurableListableBeanFactory,
        annotation: CoCache?,
        valueType: ResolvableType
    ): Boolean {
        if (!annotation?.codec.isNullOrBlank()) {
            return false
        }
        val codecType = ResolvableType.forClassWithGenerics(Codec::class.java, valueType)
        return beanFactory.getBeanNamesForType(codecType, true, false).isEmpty()
    }

    private fun registerInterface(hints: RuntimeHints, type: Class<*>) {
        hints.proxies().registerJdkProxy(type)
        hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS)
    }

    /**
     * 默认 key 编码器经 `getMethod("toString")` 检查 key 类型（[me.ahoo.cocache.core.KeyEncoder.ofToString]）。
     */
    private fun registerKeyType(hints: RuntimeHints, keyType: Class<*>) {
        hints.reflection().registerType(keyType) { it.withMethod("toString", emptyList(), ExecutableMode.INVOKE) }
    }

    private fun registerExpressionRoot(hints: RuntimeHints, rootType: ResolvableType) {
        rootType.resolve()?.let {
            hints.reflection().registerType(
                it,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.ACCESS_PUBLIC_FIELDS
            )
        }
    }

    private fun registerBinding(hints: RuntimeHints, type: Type) {
        BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), type)
    }
}
