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
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.annotation.JoinCacheable
import me.ahoo.cocache.api.join.JoinCache
import me.ahoo.cocache.api.join.JoinKeyExtractor
import me.ahoo.cocache.api.join.JoinValue
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.join.SimpleJoinCache
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.proxy.JoinCacheProxies
import me.ahoo.cocache.core.setCacheSpec
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
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
     * 主缓存是 spec 声明的集合缓存（缓存名 `tags`）。
     */
    @JoinCacheable(firstCacheName = "tags", joinCacheName = "UserCache")
    interface TagJoinCache : JoinCache<String, Set<String>, String, User>

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
        fun tagSpec(): SetCacheSpec<String, String> = setCacheSpec("tags") { elementCodec = StringCodec }

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

            // 集合缓存未命中是空集合：没有关联
            val tags = context.getBean(TagJoinCache::class.java)
            tags["t1"].assert().isEqualTo(JoinValue<Set<String>, String, User>(emptySet(), null, null))
            @Suppress("UNCHECKED_CAST")
            val tagCache = context.getBean("tag") as SetCache<String, String>
            tagCache.add("t1", listOf("u3"))
            tags["t1"].assert().isEqualTo(JoinValue(setOf("u3"), "u3", User("u3", "name-u3")))
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
}
