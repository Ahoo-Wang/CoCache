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
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.core.ResolvableType

/** 同级别名覆盖公开作用域代理后，其内部 target 不应继续成为自动匹配候选（#899）。 */
class ScopedProxyAliasTest {
    @CoCache(name = "automatic")
    interface Automatic : Cache<String, String>

    @CoCache(name = "named", loader = "loader")
    interface Named : Cache<String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [Automatic::class])
    class AutomaticConfig

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [Named::class])
    class NamedConfig

    @Configuration(proxyBeanMethods = false)
    class ScopedComponents {
        @Bean
        @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
        fun loader(): CacheLoader<String, String> = CacheLoader { "old-$it" }

        @Bean
        fun replacement(): CacheLoader<String, String> = CacheLoader { "new-$it" }

        @Bean
        fun codec(): Codec<String> = StringCodec
    }

    @Configuration(proxyBeanMethods = false)
    class PlainComponents {
        @Bean
        fun loader(): CacheLoader<String, String> = CacheLoader { "old-$it" }

        @Bean
        fun replacement(): CacheLoader<String, String> = CacheLoader { "new-$it" }

        @Bean
        fun codec(): Codec<String> = StringCodec
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.backend=memory")
        .withAllowBeanDefinitionOverriding(true)
        .withInitializer { context ->
            // 在配置类完成 bean 定义登记之后，按 Spring 允许的方式把公开名称重定向。
            context.addBeanFactoryPostProcessor { factory -> factory.registerAlias("replacement", "loader") }
        }

    @Test
    fun aMaskedScopedTargetMustNotCreateAnExtraLoaderCandidate() {
        runner.withUserConfiguration(ScopedComponents::class.java, AutomaticConfig::class.java).run { context ->
            context.startupFailure.assert().withFailMessage {
                "The masked scoped target remained a candidate: " + context.startupFailure
            }.isNull()
            context.getBean(Automatic::class.java)["k"].assert().isEqualTo("new-k")
        }
    }

    @Test
    fun anExplicitLoaderNameUsesTheReplacement() {
        runner.withUserConfiguration(ScopedComponents::class.java, NamedConfig::class.java).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean("loader").assert().isSameAs(context.getBean("replacement"))
            context.getBean(Named::class.java)["k"].assert().isEqualTo("new-k")
        }
    }

    @Test
    fun aMaskedOrdinaryBeanDoesNotRemainACandidate() {
        runner.withUserConfiguration(PlainComponents::class.java, AutomaticConfig::class.java).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(Automatic::class.java)["k"].assert().isEqualTo("new-k")
        }
    }

    /**
     * 只有单例、没有定义的同名前缀 bean 无从判断是否是隐藏目标，按原名使用（与 Spring 按类型注入相同：没有定义的单例总是候选）。
     */
    @Test
    fun aPrefixedSingletonWithoutADefinitionRemainsACandidate() {
        val factory = DefaultListableBeanFactory().apply {
            registerSingleton("scopedTarget.loader", CacheLoader<String, String> { it })
        }
        val type = ResolvableType.forClass(CacheLoader::class.java)
        BeanCandidates.namesOf(factory, type).assert().containsExactly("scopedTarget.loader")
        BeanCandidates.definedNamesOf(factory, type).assert().containsExactly("scopedTarget.loader")
    }

    @Test
    fun springResolvesTheScopedProxyNameToTheReplacement() {
        runner.withUserConfiguration(ScopedComponents::class.java).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean("loader").assert().isSameAs(context.getBean("replacement"))
        }
    }
}
