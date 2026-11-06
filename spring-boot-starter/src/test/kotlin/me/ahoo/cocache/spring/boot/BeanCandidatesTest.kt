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

import me.ahoo.cocache.api.AsyncCacheLoader
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.annotation.JoinCacheable
import me.ahoo.cocache.api.join.JoinKeyExtractor
import me.ahoo.cocache.api.join.JoinView
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.target.SimpleBeanTargetSource
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.core.ResolvableType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * 按泛型自动匹配 loader、codec 与 `JoinKeyExtractor`（[BeanCandidates]）：与按名称 `getBean` 的可见性一致，
 * 包含祖先容器（子容器同名 bean 遮蔽父容器的），作用域代理按目标的定义匹配、注入公开的代理。
 */
class BeanCandidatesTest {
    @CoCache(name = "sourced")
    interface SourcedCache : Cache<String, String>

    @CoCache(name = "records")
    interface RecordStore : Store<String, String>

    @JoinCacheable(firstCacheName = "sourced", joinCacheName = "sourced")
    interface AutoJoin : JoinView<String, String, String, String>

    @JoinCacheable(name = "namedJoin", firstCacheName = "sourced", joinCacheName = "sourced", joinKeyExtractor = "extractor")
    interface NamedJoin : JoinView<String, String, String, String>

    /**
     * 记录每次调用所在实例的编号：作用域代理（prototype）每次调用都创建新的目标，直接持有目标时编号不变。
     */
    class Recorder {
        val instances = AtomicInteger()
        val encodes: MutableList<Int> = CopyOnWriteArrayList()
        val extracts: MutableList<Int> = CopyOnWriteArrayList()
    }

    class RecordingCodec(private val recorder: Recorder) : Codec<String> {
        private val id = recorder.instances.incrementAndGet()

        override fun encode(value: String): ByteArray {
            recorder.encodes += id
            return value.toByteArray()
        }

        override fun decode(bytes: ByteArray): String = String(bytes)
    }

    @Configuration(proxyBeanMethods = false)
    class ParentComponents {
        @Bean
        fun recorder(): Recorder = Recorder()

        @Bean
        fun parentLoader(): CacheLoader<String, String> = CacheLoader { "parent-$it" }

        @Bean
        fun parentCodec(recorder: Recorder): Codec<String> = RecordingCodec(recorder)

        @Bean
        fun parentExtractor(): JoinKeyExtractor<String, String> = JoinKeyExtractor { "j-$it" }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [SourcedCache::class, RecordStore::class], joinCaches = [AutoJoin::class])
    class ChildCaches {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()
    }

    @Configuration(proxyBeanMethods = false)
    class ParentAsyncLoader {
        @Bean
        fun parentAsyncLoader(): AsyncCacheLoader<String, String> =
            AsyncCacheLoader { CompletableFuture.completedFuture("async-$it") }

        @Bean
        fun parentCodec(): Codec<String> = StringCodec
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [SourcedCache::class])
    class ChildCache {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()
    }

    /**
     * 父容器的 `loader`、`codec` 被子容器同名的 bean 遮蔽：按名称 `getBean` 得到的是子容器的，父容器的不算候选。
     */
    @Configuration(proxyBeanMethods = false)
    class ShadowedParent {
        @Bean
        fun recorder(): Recorder = Recorder()

        @Bean
        fun loader(): CacheLoader<String, String> = CacheLoader { "parent-$it" }

        @Bean
        fun codec(recorder: Recorder): Codec<String> = RecordingCodec(recorder)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [SourcedCache::class, RecordStore::class])
    class ShadowingChild {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun loader(): CacheLoader<String, String> = CacheLoader { "child-$it" }

        @Bean
        fun codec(): String = "not a codec"
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [SourcedCache::class])
    class SecondLoaderChild {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun childLoader(): CacheLoader<String, String> = CacheLoader { "child-$it" }
    }

    /**
     * prototype 作用域、接口代理的三种组件：自动匹配与按名称指定都应注入公开的代理。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [SourcedCache::class, RecordStore::class], joinCaches = [AutoJoin::class, NamedJoin::class])
    class ScopedComponents {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun recorder(): Recorder = Recorder()

        @Bean
        @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
        fun loader(recorder: Recorder): CacheLoader<String, String> {
            val id = recorder.instances.incrementAndGet()
            return CacheLoader { "$id:$it" }
        }

        @Bean
        @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
        fun codec(recorder: Recorder): Codec<String> = RecordingCodec(recorder)

        @Bean
        @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
        fun extractor(recorder: Recorder): JoinKeyExtractor<String, String> {
            val id = recorder.instances.incrementAndGet()
            return JoinKeyExtractor {
                recorder.extracts += id
                it
            }
        }
    }

    /**
     * 两个不同的作用域代理：规范化到各自的代理名之后仍是两个候选。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [SourcedCache::class])
    class TwoScopedLoaders {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun codec(): Codec<String> = StringCodec

        @Bean
        @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
        fun first(): CacheLoader<String, String> = CacheLoader { "first-$it" }

        @Bean
        @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
        fun second(): CacheLoader<String, String> = CacheLoader { "second-$it" }
    }

    /**
     * 名称恰好带 `scopedTarget.` 前缀的普通 bean：没有对应的代理（`codec` 不存在，`loader` 不是代理），按原名使用。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [SourcedCache::class])
    class PrefixedPlainBeans {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean("scopedTarget.loader")
        fun plainLoader(): CacheLoader<String, String> = CacheLoader { "plain-$it" }

        @Bean
        fun loader(): String = "not a proxy"

        @Bean("scopedTarget.codec")
        fun plainCodec(): Codec<String> = StringCodec
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.enabled=true")

    @Test
    fun matchesComponentsOfTheParentContext() {
        AnnotationConfigApplicationContext(ParentComponents::class.java).use { parent ->
            runner.withParent(parent).withUserConfiguration(ChildCaches::class.java).run { context ->
                assertThat(context).hasNotFailed()
                val sourced = context.getBean(SourcedCache::class.java)
                sourced["a"].assert().isEqualTo("parent-a")
                val encodes = parent.getBean(Recorder::class.java).encodes
                val before = encodes.size
                val records = context.getBean(RecordStore::class.java)
                records.put("k", "v")
                encodes.size.assert().isEqualTo(before + 1)
                requireNotNull(context.getBean(CacheRuntime::class.java).admin("records")).clearLocal()
                records["k"].assert().isEqualTo("v")
                context.getBean(AutoJoin::class.java)["a"]?.joinKey.assert().isEqualTo("j-parent-a")
            }
        }
    }

    @Test
    fun matchesAnAsyncLoaderOfTheParentContext() {
        AnnotationConfigApplicationContext(ParentAsyncLoader::class.java).use { parent ->
            runner.withParent(parent).withUserConfiguration(ChildCache::class.java).run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(SourcedCache::class.java)["a"].assert().isEqualTo("async-a")
            }
        }
    }

    @Test
    fun childBeansShadowParentBeansOfTheSameName() {
        AnnotationConfigApplicationContext(ShadowedParent::class.java).use { parent ->
            runner.withParent(parent).withUserConfiguration(ShadowingChild::class.java).run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(SourcedCache::class.java)["a"].assert().isEqualTo("child-a")
                val records = context.getBean(RecordStore::class.java)
                records.put("k", "v")
                requireNotNull(context.getBean(CacheRuntime::class.java).admin("records")).clearLocal()
                records["k"].assert().isEqualTo("v")
                // 父容器的 codec 被遮蔽：两个缓存都按值类型使用 Jackson
                parent.getBean(Recorder::class.java).encodes.assert().isEmpty()
            }
        }
    }

    @Test
    fun candidatesAcrossContextsAreAmbiguous() {
        AnnotationConfigApplicationContext(ParentComponents::class.java).use { parent ->
            runner.withParent(parent).withUserConfiguration(SecondLoaderChild::class.java).run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining("Multiple CacheLoader beans")
                    .hasStackTraceContaining("childLoader").hasStackTraceContaining("parentLoader")
            }
        }
    }

    @Test
    fun injectsScopedProxiesInsteadOfTheirTargets() {
        runner.withUserConfiguration(ScopedComponents::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val sourced = context.getBean(SourcedCache::class.java)
            val first = requireNotNull(sourced["a"]).substringBefore(':')
            val second = requireNotNull(sourced["b"]).substringBefore(':')
            first.assert().isNotEqualTo(second)

            val recorder = context.getBean(Recorder::class.java)
            val records = context.getBean(RecordStore::class.java)
            records.put("x", "1")
            records.put("y", "2")
            recorder.encodes.takeLast(2).distinct().assert().hasSize(2)

            context.getBean(AutoJoin::class.java)["c"].assert().isNotNull()
            context.getBean(AutoJoin::class.java)["d"].assert().isNotNull()
            recorder.extracts.distinct().assert().hasSize(2)
            // 按名称指定的作用域代理按目标的定义校验泛型
            context.getBean(NamedJoin::class.java)["e"].assert().isNotNull()
            recorder.extracts.distinct().assert().hasSize(3)
        }
    }

    @Test
    fun distinctScopedProxiesStayAmbiguous() {
        runner.withUserConfiguration(TwoScopedLoaders::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasStackTraceContaining("Multiple CacheLoader beans")
                .hasStackTraceContaining("[first, second]")
        }
    }

    @Test
    fun plainBeansWithTheTargetPrefixKeepTheirNames() {
        runner.withUserConfiguration(PrefixedPlainBeans::class.java).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(SourcedCache::class.java)["a"].assert().isEqualTo("plain-a")
        }
    }

    /**
     * 只认目标源指向这个目标的代理：其它 AOP 代理（单例目标源、指向别的 bean）不是它的作用域代理。
     */
    @Test
    fun onlyAProxyOfThatTargetReplacesIt() {
        val type = ResolvableType.forClassWithGenerics(CacheLoader::class.java, String::class.java, String::class.java)
        val beanFactory = DefaultListableBeanFactory()
        listOf("scopedTarget.single", "scopedTarget.other").forEach { name ->
            beanFactory.registerBeanDefinition(
                name,
                RootBeanDefinition(CacheLoader::class.java).apply {
                    setTargetType(type)
                    instanceSupplier = Supplier { CacheLoader<String, String> { it } }
                }
            )
        }
        beanFactory.registerSingleton("single", ProxyFactory(Runnable {}).proxy)
        val elsewhere = SimpleBeanTargetSource().apply {
            targetBeanName = "scopedTarget.single"
            setBeanFactory(beanFactory)
        }
        beanFactory.registerSingleton("other", ProxyFactory().apply { targetSource = elsewhere }.proxy)
        BeanCandidates.namesOf(beanFactory, type).assert().containsExactly("scopedTarget.single", "scopedTarget.other")
        BeanCandidates.isTypeMatch(beanFactory, "other", type).assert().isTrue()
        BeanCandidates.isTypeMatch(beanFactory, "single", type).assert().isFalse()
    }
}
