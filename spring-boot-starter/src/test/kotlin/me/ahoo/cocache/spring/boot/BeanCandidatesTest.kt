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
import org.springframework.beans.factory.support.StaticListableBeanFactory
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

    @JoinCacheable(
        name = "namedJoin",
        firstCacheName = "sourced",
        joinCacheName = "sourced",
        joinKeyExtractor = "extractor"
    )
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
        fun codec(): Any = Any()
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
        fun loader(): Any = Any()

        @Bean("scopedTarget.codec")
        fun plainCodec(): Codec<String> = StringCodec
    }

    /**
     * 父容器的 `loader` 是作用域代理（prototype、接口代理）：候选只能在目标 `scopedTarget.loader` 的定义上匹配到。
     */
    @Configuration(proxyBeanMethods = false)
    class ScopedParent {
        @Bean
        @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
        fun loader(): CacheLoader<String, String> = CacheLoader { "parent-$it" }

        @Bean
        fun codec(): Codec<String> = StringCodec
    }

    @Configuration(proxyBeanMethods = false)
    class MatchingLoaderChild {
        @Bean
        fun loader(): CacheLoader<String, String> = CacheLoader { "child-$it" }
    }

    @Configuration(proxyBeanMethods = false)
    class OtherTypeLoaderChild {
        @Bean
        fun loader(): Any = Any()
    }

    /**
     * 父容器的 `legacyLoader` 与 `integerLoader` 类型不同：子容器把名称 `legacyLoader` 别名到另一种类型的 loader 时，
     * 按名称 `getBean("legacyLoader")` 得到的是别名的目标，父容器自己的 `legacyLoader` 不可见。
     */
    @Configuration(proxyBeanMethods = false)
    class AliasedParent {
        @Bean
        fun legacyLoader(): CacheLoader<String, String> = CacheLoader { "parent-$it" }

        @Bean
        fun integerLoader(): CacheLoader<Int, Int> = CacheLoader { it + 1 }

        @Bean
        fun codec(): Codec<String> = StringCodec
    }

    @Configuration(proxyBeanMethods = false)
    class LocalIntegerLoader {
        @Bean
        fun localIntegerLoader(): CacheLoader<Int, Int> = CacheLoader { it + 2 }
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.enabled=true")

    private val stringLoader = ResolvableType.forClassWithGenerics(
        CacheLoader::class.java,
        String::class.java,
        String::class.java
    )

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

    /**
     * 缺陷复现（#865）：子容器同名的 `loader` 遮蔽父容器的作用域代理 `loader`，父容器内部的目标 `scopedTarget.loader` 也随之不可见，
     * 不是第二个候选（曾报 `Multiple CacheLoader beans … [loader, scopedTarget.loader]`）。
     */
    @Test
    fun aSameNameChildBeanShadowsTheParentsScopedProxy() {
        AnnotationConfigApplicationContext(ScopedParent::class.java).use { parent ->
            val child = runner.withParent(parent)
            child.withUserConfiguration(ChildCache::class.java, MatchingLoaderChild::class.java).run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(SourcedCache::class.java)["a"].assert().isEqualTo("child-a")
            }
        }
    }

    /**
     * 缺陷复现（#865）：遮蔽与类型无关。子容器的 `loader` 不是 loader 时，父容器的 `loader` 被遮蔽，没有数据源；
     * 父容器内部的目标不能作为候选注入（曾经直接持有它，绕过作用域）。
     */
    @Test
    fun aSameNameChildBeanOfAnotherTypeHidesTheParentsScopedTarget() {
        AnnotationConfigApplicationContext(ScopedParent::class.java).use { parent ->
            val child = runner.withParent(parent)
            child.withUserConfiguration(ChildCache::class.java, OtherTypeLoaderChild::class.java).run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining("no data source")
            }
        }
    }

    /**
     * 遮蔽对每一级祖先容器都成立：中间容器的同名 bean（不论类型）遮蔽更远的容器的候选；不是层级容器的 bean 工厂只看它自己。
     */
    @Test
    fun shadowingAppliesAtEveryLevel() {
        val type = ResolvableType.forClassWithGenerics(CacheLoader::class.java, String::class.java, String::class.java)
        val grandparent = DefaultListableBeanFactory()
        listOf("first", "second").forEach { name ->
            grandparent.registerBeanDefinition(
                name,
                RootBeanDefinition(CacheLoader::class.java).apply {
                    setTargetType(type)
                    instanceSupplier = Supplier { CacheLoader<String, String> { it } }
                }
            )
        }
        val parent = DefaultListableBeanFactory(grandparent).apply { registerSingleton("first", Any()) }
        val child = DefaultListableBeanFactory(parent)
        BeanCandidates.namesOf(child, type).assert().containsExactly("second")
        BeanCandidates.namesOf(grandparent, type).assert().containsExactly("first", "second")
        val standalone = StaticListableBeanFactory(mapOf("plain" to CacheLoader<String, String> { it }))
        val rawLoader = ResolvableType.forClass(CacheLoader::class.java)
        BeanCandidates.namesOf(standalone, rawLoader).assert().containsExactly("plain")
    }

    /**
     * 对照：没有被遮蔽的父容器作用域代理照常注入（按公开的代理名）。
     */
    @Test
    fun anUnshadowedScopedProxyOfTheParentIsInjected() {
        AnnotationConfigApplicationContext(ScopedParent::class.java).use { parent ->
            runner.withParent(parent).withUserConfiguration(ChildCache::class.java).run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(SourcedCache::class.java)["a"].assert().isEqualTo("parent-a")
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
     * 对照：没有别名时，子容器与父容器的两个 String loader 名称不同，是两个候选。
     */
    @Test
    fun twoLoadersAcrossContextsAreAmbiguousWithoutAnAlias() {
        AnnotationConfigApplicationContext(AliasedParent::class.java).use { parent ->
            runner.withParent(parent).withUserConfiguration(SecondLoaderChild::class.java).run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining("Multiple CacheLoader beans")
                    .hasStackTraceContaining("childLoader").hasStackTraceContaining("legacyLoader")
            }
        }
    }

    /**
     * 对照：子容器的别名 `legacyLoader` 指向子容器自己的 Int loader，父容器的 String loader `legacyLoader` 被遮蔽。
     */
    @Test
    fun aChildAliasToALocalLoaderOfAnotherTypeHidesTheParentCandidate() {
        AnnotationConfigApplicationContext(AliasedParent::class.java).use { parent ->
            runner.withParent(parent)
                .withUserConfiguration(SecondLoaderChild::class.java, LocalIntegerLoader::class.java)
                .withInitializer { it.beanFactory.registerAlias("localIntegerLoader", "legacyLoader") }
                .run { context ->
                    assertThat(context).hasNotFailed()
                    context.getBean(SourcedCache::class.java)["k"].assert().isEqualTo("child-k")
                }
        }
    }

    /**
     * 缺陷复现（#887）：子容器的别名 `legacyLoader` 指向父容器的 Int loader，按名称得到的已不是 String loader，
     * 父容器的 `legacyLoader` 同样被遮蔽（曾因 `containsLocalBean` 对这样的别名为 `false` 而报 `Multiple … [childLoader, legacyLoader]`）。
     */
    @Test
    fun aChildAliasToAParentLoaderOfAnotherTypeHidesTheParentCandidate() {
        AnnotationConfigApplicationContext(AliasedParent::class.java).use { parent ->
            runner.withParent(parent)
                .withUserConfiguration(SecondLoaderChild::class.java)
                .withInitializer {
                    it.beanFactory.registerAlias("integerLoader", "legacyLoader")
                    it.beanFactory.getBean("legacyLoader").assert().isSameAs(parent.getBean("integerLoader"))
                    it.beanFactory.isTypeMatch("legacyLoader", stringLoader).assert().isFalse()
                }
                .run { context ->
                    assertThat(context).hasNotFailed()
                    context.getBean(SourcedCache::class.java)["k"].assert().isEqualTo("child-k")
                }
        }
    }

    /**
     * 缺陷复现（#887）：别名与同名的 bean 一样遮蔽祖先容器的候选，即使别名的目标在祖先容器中，对每一级都成立；
     * 结果中的每个名称在本容器按名称解析都是那个候选。
     */
    @Test
    fun aliasesShadowAtEveryLevel() {
        val integerLoader = ResolvableType.forClassWithGenerics(
            CacheLoader::class.java,
            Int::class.javaObjectType,
            Int::class.javaObjectType
        )
        val parent = DefaultListableBeanFactory().apply {
            registerLoader("legacy", stringLoader)
            registerLoader("current", stringLoader)
            registerLoader("integer", integerLoader)
        }
        BeanCandidates.namesOf(parent, stringLoader).assert().containsExactly("legacy", "current")
        // 别名指向同类型的另一个候选：两个名称是同一个 bean，不是两个候选（曾报 [legacy, current]）
        val renamed = DefaultListableBeanFactory(parent).apply { registerAlias("current", "legacy") }
        BeanCandidates.namesOf(renamed, stringLoader).assert().containsExactly("current")
        // 别名指向另一种类型：按名称已不匹配，不是候选
        val redirected = DefaultListableBeanFactory(parent).apply { registerAlias("integer", "legacy") }
        BeanCandidates.namesOf(redirected, stringLoader).assert().containsExactly("current")
        redirected.isTypeMatch("legacy", stringLoader).assert().isFalse()
        // 中间容器的别名同样遮蔽更远的容器
        val grandchild = DefaultListableBeanFactory(redirected)
        BeanCandidates.namesOf(grandchild, stringLoader).assert().containsExactly("current")
        // `&` 前缀（取 FactoryBean 本身）不改变归属；不是别名表的 bean 工厂没有别名
        BeanCandidates.declares(redirected, "&legacy").assert().isTrue()
        BeanCandidates.declares(redirected, "current").assert().isFalse()
        BeanCandidates.isAlias(StaticListableBeanFactory(), "legacy").assert().isFalse()
    }

    /**
     * 构建期的同一规则（D9）：遮蔽与作用域代理换名都与运行时相同，但不创建任何 bean；作用域代理按定义（`targetBeanName`）判断。
     */
    @Test
    fun definedNamesFollowTheRuntimeRulesWithoutCreatingBeans() {
        val parent = DefaultListableBeanFactory().apply {
            registerLoader("legacy", stringLoader)
            registerLoader("current", stringLoader)
            registerBeanDefinition("other", RootBeanDefinition(String::class.java))
        }
        // 别名指向父容器的另一个 bean（#887）
        val redirected = DefaultListableBeanFactory(parent).apply { registerAlias("other", "legacy") }
        BeanCandidates.definedNamesOf(redirected, stringLoader).assert().containsExactly("current")
        BeanCandidates.definedNamesOf(redirected, stringLoader).assert()
            .isEqualTo(BeanCandidates.namesOf(redirected, stringLoader))

        val created = AtomicInteger()
        val scoped = DefaultListableBeanFactory().apply {
            registerBeanDefinition(
                "scopedTarget.loader",
                RootBeanDefinition(CacheLoader::class.java).apply {
                    setTargetType(stringLoader)
                    instanceSupplier = Supplier {
                        created.incrementAndGet()
                        CacheLoader<Any, Any> { it }
                    }
                }
            )
            registerBeanDefinition(
                "loader",
                RootBeanDefinition(org.springframework.aop.scope.ScopedProxyFactoryBean::class.java).apply {
                    propertyValues.add("targetBeanName", "scopedTarget.loader")
                }
            )
            // 只是名称带有前缀的普通 bean：没有指向它的代理定义
            registerLoader("scopedTarget.plain", stringLoader)
            registerBeanDefinition("plain", RootBeanDefinition(String::class.java))
        }
        BeanCandidates.definedNamesOf(scoped, stringLoader).assert().containsExactly("loader", "scopedTarget.plain")
        created.get().assert().isZero()
        // 不是可列出定义的 bean 工厂：不按定义换名
        val static = StaticListableBeanFactory(mapOf("scopedTarget.x" to CacheLoader<String, String> { it }))
        BeanCandidates.definedNamesOf(static, ResolvableType.forClass(CacheLoader::class.java))
            .assert().containsExactly("scopedTarget.x")
    }

    private fun DefaultListableBeanFactory.registerLoader(name: String, type: ResolvableType) {
        registerBeanDefinition(
            name,
            RootBeanDefinition(CacheLoader::class.java).apply {
                setTargetType(type)
                instanceSupplier = Supplier { CacheLoader<Any, Any> { it } }
            }
        )
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
