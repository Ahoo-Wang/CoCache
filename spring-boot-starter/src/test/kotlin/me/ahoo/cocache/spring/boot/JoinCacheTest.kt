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
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.annotation.JoinCacheable
import me.ahoo.cocache.api.join.JoinCache
import me.ahoo.cocache.api.join.JoinKeyExtractor
import me.ahoo.cocache.api.join.JoinValue
import me.ahoo.cocache.api.join.JoinView
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.join.SimpleJoinCache
import me.ahoo.cocache.core.join.SimpleJoinView
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.proxy.JoinCacheProxies
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class JoinCacheTest {
    data class User(val id: String, val name: String)

    data class Ext(val id: String, val userId: String?)

    interface UserCache : Cache<String, User>

    interface ExtCache : Cache<String, Ext>

    interface CountCache : Cache<Long, String>

    @JoinCacheable(firstCacheName = "ExtCache", joinCacheName = "UserCache", joinKeyExpression = "#{#root.userId}")
    interface ExpressionJoinCache : JoinCache<String, Ext, String, User> {
        fun userOf(id: String): User? = get(id)?.secondValue
    }

    @JoinCacheable(
        name = "beanJoin",
        firstCacheName = "ExtCache",
        joinCacheName = "UserCache",
        joinKeyExtractor = "extUserId"
    )
    interface BeanJoinCache : JoinCache<String, Ext, String, User>

    /**
     * 引用 spec 声明的缓存（缓存名 `orders`，bean 名 `order`），关联 key 提取器按泛型匹配。
     */
    @JoinCacheable(firstCacheName = "orders", joinCacheName = "UserCache")
    interface OrderJoinCache : JoinCache<String, String, String, User>

    @JoinCacheable(name = "orderByBeanName", firstCacheName = "order", joinCacheName = "UserCache")
    interface OrderByBeanNameJoinCache : JoinCache<String, String, String, User>

    /**
     * 主缓存是 spec 声明的权威集合（缓存名 `tags`）：两侧不都是 `Cache`，只能是只读的 [JoinView]（RFC 0005 §3.1）。
     */
    @JoinCacheable(firstCacheName = "tags", joinCacheName = "UserCache")
    interface TagJoinCache : JoinView<String, Set<String>, String, User>

    /**
     * 两侧都是权威副本（`Store`）的只读组合视图。
     */
    @CoCache(name = "accounts")
    interface AccountStore : Store<String, String>

    @CoCache(name = "owners")
    interface OwnerStore : Store<String, User>

    @JoinCacheable(firstCacheName = "accounts", joinCacheName = "owners", joinKeyExpression = "#{#root}")
    interface AccountOwnerView : JoinView<String, String, String, User>

    /**
     * 声明为 [JoinCache] 时两侧必须是 `Cache`：引用 `Store` 时启动失败。
     */
    @JoinCacheable(firstCacheName = "accounts", joinCacheName = "owners", joinKeyExpression = "#{#root}")
    interface AccountOwnerJoinCache : JoinCache<String, String, String, User>

    @JoinCacheable(firstCacheName = "UserCache", joinCacheName = "UserCache")
    interface AmbiguousJoinCache : JoinCache<String, User, String, User>

    @JoinCacheable(firstCacheName = "ExtCache", joinCacheName = "CountCache")
    interface NoExtractorJoinCache : JoinCache<String, Ext, Long, String>

    @JoinCacheable(firstCacheName = "ExtCache", joinCacheName = "CountCache", joinKeyExpression = "#{#root.userId}")
    interface NonStringKeyJoinCache : JoinCache<String, Ext, Long, String>

    @JoinCacheable(
        firstCacheName = "ExtCache",
        joinCacheName = "UserCache",
        joinKeyExpression = "#{userId}",
        joinKeyExtractor = "extUserId"
    )
    interface BothExtractorsJoinCache : JoinCache<String, Ext, String, User>

    @JoinCacheable(firstCacheName = "ExtCache", joinCacheName = "UserCache", joinKeyExpression = "userId")
    interface ConstantExpressionJoinCache : JoinCache<String, Ext, String, User>

    @JoinCacheable(firstCacheName = "ExtCache", joinCacheName = "UserCache", joinKeyExtractor = "tagUserId")
    interface WrongExtractorJoinCache : JoinCache<String, Ext, String, User>

    @JoinCacheable(firstCacheName = "MissingCache", joinCacheName = "UserCache", joinKeyExpression = "#{userId}")
    interface MissingCacheJoinCache : JoinCache<String, Ext, String, User>

    interface UnannotatedJoinCache : JoinCache<String, Ext, String, User>

    @JoinCacheable(joinCacheName = "UserCache", joinKeyExpression = "#{userId}")
    interface BlankFirstNameJoinCache : JoinCache<String, Ext, String, User>

    @JoinCacheable(firstCacheName = "ExtCache", joinCacheName = "", joinKeyExpression = "#{userId}")
    interface BlankJoinNameJoinCache : JoinCache<String, Ext, String, User>

    @JoinCacheable(firstCacheName = "UserCache", joinCacheName = "UserCache", joinKeyExpression = "#{userId}")
    interface MismatchedJoinCache : JoinCache<String, Ext, String, User>

    @JoinCacheable(firstCacheName = "extUserId", joinCacheName = "UserCache", joinKeyExpression = "#{userId}")
    interface NotACacheJoinCache : JoinCache<String, Ext, String, User>

    @JoinCacheable(firstCacheName = "ExtCache", joinCacheName = "UserCache")
    interface GenericJoinCache<V : Any> : JoinCache<String, V, String, User>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(
        caches = [UserCache::class, ExtCache::class, CountCache::class],
        joinCaches = [
            ExpressionJoinCache::class,
            BeanJoinCache::class,
            OrderJoinCache::class,
            OrderByBeanNameJoinCache::class,
            TagJoinCache::class,
        ]
    )
    class AppConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun stringCodec(): Codec<String> = StringCodec

        @Bean
        fun userLoader(): CacheLoader<String, User> = CacheLoader { id ->
            if (id.startsWith("ghost")) null else User(id, "name-$id")
        }

        @Bean
        fun extLoader(): CacheLoader<String, Ext> = CacheLoader { id ->
            id.takeUnless { it.startsWith("none") }?.let { Ext(it, if (it.startsWith("solo")) null else "u-$it") }
        }

        @Bean
        fun countLoader(): CacheLoader<Long, String> = CacheLoader { "c-$it" }

        @Bean
        fun orderSpec(): CacheSpec<String, String> = cacheSpec("orders") {
            codec = StringCodec
            loader = CacheLoader { it.substringBefore('#') }
        }

        @Bean
        fun tagSpec(): SetStoreSpec<String, String> = setStoreSpec("tags") { elementCodec = StringCodec }

        @Bean
        fun tagUserId(): JoinKeyExtractor<Set<String>, String> = JoinKeyExtractor { it.firstOrNull() }

        @Bean
        fun extUserId(): JoinKeyExtractor<Ext, String> = JoinKeyExtractor { "bean-${it.userId}" }

        @Bean
        fun orderUserId(): JoinKeyExtractor<String, String> = JoinKeyExtractor { it }

        @Bean
        fun firstUserExtractor(): JoinKeyExtractor<User, String> = JoinKeyExtractor { it.id }

        @Bean
        fun secondUserExtractor(): JoinKeyExtractor<User, String> = JoinKeyExtractor { it.name }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [UserCache::class, ExtCache::class], joinCaches = [MissingCacheJoinCache::class])
    class MissingCacheConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userLoader(): CacheLoader<String, User> = CacheLoader { User(it, "name-$it") }

        @Bean
        fun extLoader(): CacheLoader<String, Ext> = CacheLoader { Ext(it, null) }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [AccountStore::class, OwnerStore::class], joinCaches = [AccountOwnerView::class])
    class StoreViewConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun stringCodec(): Codec<String> = StringCodec
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [AccountStore::class, OwnerStore::class], joinCaches = [AccountOwnerJoinCache::class])
    class StoreJoinCacheConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun stringCodec(): Codec<String> = StringCodec
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(joinCaches = [ExpressionJoinCache::class])
    class NameConflictConfig {
        @Bean
        fun expressionJoinCache(): Any = Any()

        @Bean(name = ["ExpressionJoinCache"])
        fun conflicting(): Any = Any()
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.enabled=true")

    @Test
    fun registersJoinCachesAsBeans() {
        runner.withUserConfiguration(AppConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val join = context.getBean(ExpressionJoinCache::class.java)
            context.getBean("ExpressionJoinCache").assert().isSameAs(join)
            join.name.assert().isEqualTo("ExpressionJoinCache")
            join["e1"].assert().isEqualTo(JoinValue(Ext("e1", "u-e1"), "u-e1", User("u-e1", "name-u-e1")))
            join.userOf("e2").assert().isEqualTo(User("u-e2", "name-u-e2"))
            // 表达式结果为 null：没有关联
            join["solo1"].assert().isEqualTo(JoinValue<Ext, String, User>(Ext("solo1", null), null, null))
            join["none1"].assert().isNull()
            join.getAll(listOf("e1", "solo1", "none1")).keys.assert().containsExactly("e1", "solo1")

            val target = JoinCacheProxies.targetOf(join) as SimpleJoinCache<*, *, *, *>
            target.firstCache.assert().isSameAs(context.getBean(ExtCache::class.java))
            target.joinCache.assert().isSameAs(context.getBean(UserCache::class.java))

            val beanJoin = context.getBean("beanJoin") as BeanJoinCache
            beanJoin["e1"].assert().isEqualTo(
                JoinValue(Ext("e1", "u-e1"), "bean-u-e1", User("bean-u-e1", "name-bean-u-e1"))
            )

            val orders = context.getBean(OrderJoinCache::class.java)
            orders["u1#a"].assert().isEqualTo(JoinValue("u1", "u1", User("u1", "name-u1")))
            orders["ghost#a"].assert().isEqualTo(JoinValue<String, String, User>("ghost", "ghost", null))
            val ordersTarget = JoinCacheProxies.targetOf(orders) as SimpleJoinCache<*, *, *, *>
            ordersTarget.firstCache.assert().isSameAs(context.getBean("order"))
            context.getBean(
                OrderByBeanNameJoinCache::class.java
            )["u2"]?.secondValue.assert().isEqualTo(User("u2", "name-u2"))

            // 集合缓存中不存在的集合不是主值（`get` 返回的空集合只是“不存在”）：单条与批量读取都没有组合结果（#870）
            val tags = context.getBean(TagJoinCache::class.java)
            tags["t1"].assert().isNull()
            tags.getAll(listOf("t1")).assert().isEmpty()
            @Suppress("UNCHECKED_CAST")
            val tagCache = context.getBean("tag") as SetStore<String, String>
            tagCache.add("t1", listOf("u3"))
            tags["t1"].assert().isEqualTo(JoinValue(setOf("u3"), "u3", User("u3", "name-u3")))
        }
    }

    /**
     * 两侧都是 `Store` 的 [JoinView]：读取组合两侧，代理背后是只读的 [SimpleJoinView]（没有失效）。
     */
    @Test
    fun joinViewCombinesTwoStores() {
        runner.withUserConfiguration(StoreViewConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(AccountStore::class.java).put("a1", "o1")
            context.getBean(OwnerStore::class.java).put("o1", User("o1", "owner"))
            val view = context.getBean(AccountOwnerView::class.java)
            view["a1"].assert().isEqualTo(JoinValue("o1", "o1", User("o1", "owner")))
            view["missing"].assert().isNull()
            view.getAll(listOf("a1", "missing")).keys.assert().containsExactly("a1")
            val target = JoinCacheProxies.targetOf(view)
            target.assert().isInstanceOf(SimpleJoinView::class.java)
            (target is JoinCache<*, *, *, *>).assert().isFalse()
        }
    }

    /**
     * [JoinCache] 有失效，两侧必须是 `Cache`：引用 `Store` 时启动失败，指出期望的类型。
     */
    @Test
    fun joinCacheOverStoresFailsStartup() {
        runner.withUserConfiguration(StoreJoinCacheConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasStackTraceContaining("expected me.ahoo.cocache.api.Cache<")
        }
    }

    @Test
    fun joinCacheNameDefaultsToSimpleName() {
        JoinCacheFactory.joinCacheNameOf(ExpressionJoinCache::class.java).assert().isEqualTo("ExpressionJoinCache")
        JoinCacheFactory.joinCacheNameOf(BeanJoinCache::class.java).assert().isEqualTo("beanJoin")
        JoinCacheFactory.joinCacheNameOf(UnannotatedJoinCache::class.java).assert().isEqualTo("UnannotatedJoinCache")
    }

    private fun failureOf(type: Class<out JoinCache<*, *, *, *>>): Throwable? {
        var failure: Throwable? = null
        runner.withUserConfiguration(AppConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            failure = runCatching { JoinCacheFactory(beanFactoryOf(context)).create(type) }.exceptionOrNull()
        }
        return failure
    }

    private fun beanFactoryOf(context: AssertableApplicationContext): ConfigurableListableBeanFactory {
        return context.autowireCapableBeanFactory as ConfigurableListableBeanFactory
    }

    @Test
    fun extractorResolutionFailuresAreExplicit() {
        failureOf(AmbiguousJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Join cache[AmbiguousJoinCache] - expected exactly one")
            .hasMessageContaining("firstUserExtractor")
            .hasMessageContaining("secondUserExtractor")
        failureOf(NoExtractorJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("expected exactly one")
            .hasMessageContaining("found []")
        failureOf(NonStringKeyJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("requires the join key type to be String")
        failureOf(BothExtractorsJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not both")
        // 没有 #{...} 的模板是常量：所有值都会关联到同一个 key
        failureOf(ConstantExpressionJoinCache::class.java).assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("is a constant")
        // 按名称指定的提取器类型不符：启动时失败，而不是首次读取时 ClassCastException
        failureOf(WrongExtractorJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("bean[tagUserId] is not a")
    }

    @Test
    fun cacheResolutionFailuresAreExplicit() {
        failureOf(MissingCacheJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("@JoinCacheable.firstCacheName = \"MissingCache\" does not match any cache")
            .hasMessageContaining("UserCache")
            .hasMessageContaining("orders")
        failureOf(UnannotatedJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("must be annotated with @JoinCacheable")
        failureOf(BlankFirstNameJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("@JoinCacheable.firstCacheName is required")
        failureOf(BlankJoinNameJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("@JoinCacheable.joinCacheName is required")
        failureOf(MismatchedJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("refers to bean[UserCache]")
            .hasMessageContaining("expected")
        failureOf(NotACacheJoinCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("refers to bean[extUserId]")

        @Suppress("UNCHECKED_CAST")
        val generic = GenericJoinCache::class.java as Class<JoinCache<*, *, *, *>>
        failureOf(generic).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("concrete type arguments")
    }

    @Test
    fun missingCacheFailsStartup() {
        runner.withUserConfiguration(MissingCacheConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasStackTraceContaining(
                "Join cache[MissingCacheJoinCache] - @JoinCacheable.firstCacheName = \"MissingCache\" does not match any cache"
            )
        }
    }

    @Test
    fun registrarIgnoresMetadataWithoutEnableCoCache() {
        val registry = org.springframework.beans.factory.support.DefaultListableBeanFactory()
        JoinCacheRegistrar().registerBeanDefinitions(
            org.springframework.core.type.AnnotationMetadata.introspect(JoinCacheTest::class.java),
            registry
        )
        registry.beanDefinitionCount.assert().isEqualTo(0)
    }

    @Test
    fun beanNameConflictFailsStartup() {
        runner.withUserConfiguration(NameConflictConfig::class.java).run { context ->
            assertThat(context).hasFailed()
        }
    }

    /**
     * spec 声明的缓存：缓存名 `products`，bean 名 `records`；缓存名 `backup-products`，bean 名 `backup`。
     */
    @Configuration(proxyBeanMethods = false)
    class ProductSpec {
        @Bean
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun recordsSpec(): StoreSpec<String, String> = storeSpec("products") { codec = StringCodec }

        @Bean
        fun backupSpec(): StoreSpec<String, String> = storeSpec("backup-products") { codec = StringCodec }
    }

    @JoinCacheable(firstCacheName = "products", joinCacheName = "products", joinKeyExpression = "#{#root}")
    interface ByCacheName : JoinView<String, String, String, String>

    @JoinCacheable(firstCacheName = "records", joinCacheName = "records", joinKeyExpression = "#{#root}")
    interface ByBeanName : JoinView<String, String, String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(joinCaches = [ByCacheName::class])
    class JoinByCacheName

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(joinCaches = [ByBeanName::class])
    class JoinByBeanName

    @JoinCacheable(firstCacheName = "selected", joinCacheName = "selected", joinKeyExpression = "#{#root}")
    interface BySelected : JoinView<String, String, String, String>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(joinCaches = [BySelected::class])
    class JoinBySelected

    /**
     * 子容器同名的 bean（不论类型）遮蔽父容器的缓存 bean `records`。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(joinCaches = [ByCacheName::class])
    class ShadowingRecords {
        @Bean
        fun records(): Any = Any()
    }

    /**
     * 父容器声明 [ProductSpec]（`records` 中 `k` → `k`，`backup` 中 `k` → `backup`），子容器声明 [child]，[customize] 调整子容器。
     */
    private fun withParentProducts(
        child: Class<*>,
        customize: (ApplicationContextRunner) -> ApplicationContextRunner = { it },
        assertion: (AssertableApplicationContext) -> Unit
    ) {
        runner.withUserConfiguration(ProductSpec::class.java).run { parent ->
            assertThat(parent).hasNotFailed()
            storeOf(parent, "records").put("k", "k")
            storeOf(parent, "backup").put("k", "backup")
            customize(ApplicationContextRunner().withParent(parent).withUserConfiguration(child)).run(assertion)
        }
    }

    /**
     * 子容器注册别名 [alias] → [target]（公开的 `registerAlias`，目标可以在父容器中）。
     */
    private fun aliasing(target: String, alias: String): (ApplicationContextRunner) -> ApplicationContextRunner {
        return { runner -> runner.withInitializer { it.beanFactory.registerAlias(target, alias) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun storeOf(context: ApplicationContext, name: String): Store<String, String> {
        return context.getBean(name) as Store<String, String>
    }

    /**
     * 对照：同一容器中按 spec 的缓存名解析。
     */
    @Test
    fun aLocalSpecCacheResolvesByItsCacheName() {
        runner.withUserConfiguration(ProductSpec::class.java, JoinByCacheName::class.java).run { context ->
            assertThat(context).hasNotFailed()
            @Suppress("UNCHECKED_CAST")
            (context.getBean("records") as Store<String, String>).put("k", "k")
            context.getBean(ByCacheName::class.java)["k"]?.secondValue.assert().isEqualTo("k")
        }
    }

    /**
     * 缺陷复现（#875）：子容器的组合视图按缓存名引用父容器 spec 声明的缓存（曾报 `does not match any cache`）。
     */
    @Test
    fun aParentSpecCacheResolvesByItsCacheName() {
        withParentProducts(JoinByCacheName::class.java) { child ->
            assertThat(child).hasNotFailed()
            child.getBean(ByCacheName::class.java)["k"]?.secondValue.assert().isEqualTo("k")
        }
    }

    /**
     * 对照：按 bean 名引用父容器的缓存。
     */
    @Test
    fun aParentSpecCacheResolvesByItsBeanName() {
        withParentProducts(JoinByBeanName::class.java) { child ->
            assertThat(child).hasNotFailed()
            child.getBean(ByBeanName::class.java)["k"]?.secondValue.assert().isEqualTo("k")
        }
    }

    /**
     * 子容器同名的 bean 遮蔽父容器的缓存 bean：父容器的 spec 缓存名随之不可见，与按名称 `getBean` 一致。
     */
    @Test
    fun aShadowedParentSpecCacheDoesNotResolve() {
        withParentProducts(ShadowingRecords::class.java) { child ->
            assertThat(child).hasFailed()
            child.startupFailure.assert().hasStackTraceContaining(
                "@JoinCacheable.firstCacheName = \"products\" does not match any cache"
            )
        }
    }

    /**
     * 对照：子容器的别名指向父容器的缓存 bean，没有同名的 spec 缓存名时按别名解析。
     */
    @Test
    fun aChildAliasToAParentCacheResolves() {
        withParentProducts(JoinBySelected::class.java, aliasing("backup", "selected")) { child ->
            assertThat(child).hasNotFailed()
            child.getBean("selected").assert().isSameAs(requireNotNull(child.parent).getBean("backup"))
            child.getBean(BySelected::class.java)["k"]?.firstValue.assert().isEqualTo("backup")
        }
    }

    /**
     * 缺陷复现（#887）：子容器的别名 `products` 指向父容器的 `backup`，按名称 `getBean("products")` 得到 `backup`；
     * 组合视图引用 `products` 也必须得到它，而不是继续查找到父容器、按缓存名选中 `records`（曾静默关联另一个缓存）。
     */
    @Test
    fun aChildAliasTakesPrecedenceOverAParentSpecCacheName() {
        withParentProducts(JoinByCacheName::class.java, aliasing("backup", "products")) { child ->
            assertThat(child).hasNotFailed()
            child.getBean("products").assert().isSameAs(requireNotNull(child.parent).getBean("backup"))
            child.getBean(ByCacheName::class.java)["k"]?.firstValue.assert().isEqualTo("backup")
        }
    }

    /**
     * 子容器的别名 `records` 与同名的 bean 一样遮蔽父容器的缓存 bean `records`：父容器的 spec 缓存名 `products` 随之不可见
     * （曾按缓存名选中 `records`，按名称取到的却是别名的目标 `backup`）。
     */
    @Test
    fun aChildAliasHidesTheParentSpecCacheOfThatBeanName() {
        withParentProducts(JoinByCacheName::class.java, aliasing("backup", "records")) { child ->
            assertThat(child).hasFailed()
            child.startupFailure.assert().hasStackTraceContaining(
                "@JoinCacheable.firstCacheName = \"products\" does not match any cache"
            )
        }
    }

    /**
     * 允许覆盖定义时，别名 `records` 可以与缓存 bean `records` 同在一个容器，按名称得到的是别名的目标：
     * 这个 spec 缓存不按缓存名 `products` 解析，与 Spring 按类型查找跳过这样的定义相同。
     */
    @Test
    fun aSpecCacheWhoseBeanNameIsAlsoAnAliasDoesNotResolve() {
        runner.withAllowBeanDefinitionOverriding(true).withUserConfiguration(ProductSpec::class.java).run { parent ->
            assertThat(parent).hasNotFailed()
            parent.beanFactory.registerAlias("backup", "records")
            parent.getBean("records").assert().isSameAs(parent.getBean("backup"))
            val childRunner = ApplicationContextRunner().withParent(parent)
            childRunner.withUserConfiguration(JoinByCacheName::class.java).run { child ->
                assertThat(child).hasFailed()
                child.startupFailure.assert().hasStackTraceContaining(
                    "@JoinCacheable.firstCacheName = \"products\" does not match any cache"
                )
            }
        }
    }
}
