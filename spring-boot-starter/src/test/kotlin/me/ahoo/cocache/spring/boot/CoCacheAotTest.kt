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

import com.github.benmanes.caffeine.cache.Caffeine
import me.ahoo.cocache.api.AsyncStore
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheLoader
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.SuspendingSetStore
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.annotation.JoinCacheable
import me.ahoo.cocache.api.join.JoinCache
import me.ahoo.cocache.api.join.JoinKeyExtractor
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.setStoreSpec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.aot.test.generate.TestGenerationContext
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.context.aot.ApplicationContextAotGenerator
import org.springframework.core.ResolvableType
import org.springframework.javapoet.ClassName
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Spring AOT 构建期：为原生镜像登记代理、反射、key 类型与 Jackson 绑定提示。生成代码的运行见 [CoCacheAotRuntimeTest]。
 */
class CoCacheAotTest {
    data class User(val id: String, val name: String)

    data class Ext(val id: String, val userId: String)

    data class Token(val value: String)

    data class Tag(val name: String)

    data class OrderKey(val id: String)

    data class Profile(val nickname: String)

    @CoCache(keyExpression = "#{#root.toUpperCase()}")
    interface UserCache : Cache<String, User> {
        fun nameOf(id: String): String? = get(id)?.name
    }

    /**
     * 按泛型匹配到 `Codec<Ext>` bean：不使用 Jackson。
     */
    interface ExtCache : Cache<String, Ext>

    @CoCache(codec = "tokenCodec", source = CoCache.Source.EXTERNAL)
    interface TokenCache : Cache<String, Token>

    interface TagCache : SetStore<String, Tag>

    /**
     * 视图接口：代理转发给缓存的挂起与异步视图。
     */
    interface SuspendingTagStore : SuspendingSetStore<String, Tag>

    @CoCache(codec = "tokenCodec")
    interface AsyncTokenStore : AsyncStore<String, Token>

    @JoinCacheable(firstCacheName = "ExtCache", joinCacheName = "UserCache", joinKeyExpression = "#{#root.userId}")
    interface ExtJoinCache : JoinCache<String, Ext, String, User> {
        fun userOf(id: String): User? = get(id)?.secondValue
    }

    /**
     * 主缓存是 spec 声明的缓存（缓存名 `products`，bean 名 `product`），关联 key 由提取器 bean 计算。
     */
    @JoinCacheable(firstCacheName = "products", joinCacheName = "UserCache")
    interface ProductJoinCache : JoinCache<String, String, String, User>

    interface GenericCache<V : Any> : Cache<String, V>

    /**
     * 经中间泛型接口继承的类型参数：绑定提示按实际的嵌套类型（`List<Profile>`）登记。
     */
    interface BaseStore<V : Any> : Store<String, V>

    interface ProfileStore : BaseStore<List<Profile>>

    /**
     * 数据源是作用域代理（prototype）：AOT 生成的代理定义带有目标的泛型，代理与目标都匹配，换名去重之后只有一个候选。
     */
    interface CounterCache : Cache<String, Long>

    interface UnannotatedJoinCache : JoinCache<String, Ext, String, User>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(
        caches = [
            UserCache::class,
            ExtCache::class,
            TokenCache::class,
            TagCache::class,
            SuspendingTagStore::class,
            AsyncTokenStore::class,
            ProfileStore::class,
            CounterCache::class,
        ],
        joinCaches = [ExtJoinCache::class, ProductJoinCache::class]
    )
    class AppConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userLoader(): CacheLoader<String, User> = CacheLoader { id -> User(id, "name-$id") }

        @Bean
        fun extLoader(): CacheLoader<String, Ext> = CacheLoader { id -> Ext(id, "u-$id") }

        @Bean
        fun extCodec(): Codec<Ext> = object : Codec<Ext> {
            override fun encode(value: Ext): ByteArray = "${value.id}|${value.userId}".toByteArray()

            override fun decode(bytes: ByteArray): Ext = String(bytes).split('|').let { Ext(it[0], it[1]) }
        }

        @Bean
        fun tokenCodec(): Codec<*> = object : Codec<Token> {
            override fun encode(value: Token): ByteArray = value.value.toByteArray()

            override fun decode(bytes: ByteArray): Token = Token(String(bytes))
        }

        @Bean
        fun productSpec(): CacheSpec<String, String> = cacheSpec("products") {
            codec = StringCodec
            loader = CacheLoader { "product-$it" }
        }

        @Bean
        fun labelSpec(): SetStoreSpec<String, String> = setStoreSpec("labels") { elementCodec = StringCodec }

        @Bean
        fun orderSpec(): CacheSpec<OrderKey, String> = cacheSpec("orders") {
            codec = StringCodec
            externallySourced()
        }

        @Bean
        fun productUserId(): JoinKeyExtractor<String, String> = JoinKeyExtractor { it }

        /**
         * `FactoryBean` 提供的 spec：泛型取自产品类型。
         */
        @Bean
        fun ledgerSpec(): FactoryBean<StoreSpec<String, String>> = object : FactoryBean<StoreSpec<String, String>> {
            override fun getObject(): StoreSpec<String, String> = storeSpec("ledger") { codec = StringCodec }

            override fun getObjectType(): Class<*> = StoreSpec::class.java
        }

        @Bean
        @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
        fun counterLoader(): CacheLoader<String, Long> {
            val id = COUNTER_INSTANCES.incrementAndGet()
            return CacheLoader { id }
        }
    }

    @Test
    fun registersHintsForDeclaredInterfaces() {
        val hints = processAheadOfTime().first.runtimeHints
        val reflection = RuntimeHintsPredicates.reflection()
        listOf(UserCache::class.java, ExtCache::class.java, TokenCache::class.java, TagCache::class.java)
            .plus(listOf(SuspendingTagStore::class.java, AsyncTokenStore::class.java))
            .plus(listOf(ExtJoinCache::class.java, ProductJoinCache::class.java))
            .forEach { type ->
                hints.assert().matches(RuntimeHintsPredicates.proxies().forInterfaces(type))
                hints.assert().matches(reflection.onType(type).withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
            }
        // 五种用户 API 类型与它们的异步、挂起视图，以及组合视图的公共方法（与声明无关的固定提示）
        CoCacheRuntimeHints.API_TYPES.forEach { type ->
            hints.assert().matches(reflection.onType(type).withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
        }
        // default 方法经 InvocationHandler.invokeDefault 调用
        hints.assert().matches(reflection.onMethodInvocation(UserCache::class.java, "nameOf"))
        hints.assert().matches(reflection.onMethodInvocation(ExtJoinCache::class.java, "userOf"))
        // keyExpression 在 key（String）上求值，joinKeyExpression 在主缓存的值（Ext）上求值
        hints.assert().matches(reflection.onMethodInvocation(String::class.java.getMethod("toUpperCase")))
        hints.assert().matches(reflection.onMethodInvocation(Ext::class.java, "getUserId"))
        // 默认 Jackson 编解码：值类型与集合元素类型的绑定提示；自定义编解码的值类型不登记
        hints.assert().matches(reflection.onMethodInvocation(User::class.java, "getName"))
        hints.assert().matches(reflection.onMethodInvocation(Tag::class.java, "getName"))
        hints.assert().matches(reflection.onType(Token::class.java).negate())
        // 继承的类型参数按实际的嵌套类型登记：List<Profile> 的 Profile
        hints.assert().matches(reflection.onMethodInvocation(Profile::class.java, "getNickname"))
        // 默认 key 编码器反射检查 key 类型的 toString()：接口式与 spec 声明都登记
        hints.assert().matches(reflection.onMethodInvocation(OrderKey::class.java, "toString"))
        // 固定提示（aot.factories 中的 RuntimeHintsRegistrar）
        listOf(Cache::class.java, SetStore::class.java, JoinCache::class.java).forEach {
            hints.assert().matches(reflection.onType(it).withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS))
        }
    }

    @Test
    fun registersTheCaffeineClassesCoCacheBuilds() {
        val hints = RuntimeHints()
        CoCacheRuntimeHints().registerHints(hints, javaClass.classLoader)
        CoCacheRuntimeHints.CAFFEINE_CLASSES.forEach {
            hints.assert().matches(
                RuntimeHintsPredicates.reflection().onType(Class.forName(it))
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
            )
        }
        // 与 L2 的两种构建方式一致（AbstractEngine.buildLocal）：只按条数淘汰，或再加空闲淘汰
        val bounded = Caffeine.newBuilder().maximumSize(1).build<String, String>()
        val idle = Caffeine.newBuilder().maximumSize(1).expireAfterAccess(Duration.ofSeconds(1)).build<String, String>()
        CoCacheRuntimeHints.CAFFEINE_CLASSES.assert()
            .contains(bounded.asMap().javaClass.name, idle.asMap().javaClass.name)
    }

    @Test
    fun registersOnlyProxyHintsWhenTypesCannotBeInferred() {
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerBeanDefinition(
            "generic",
            CacheBeanDefinitions.annotatedCacheDefinition(GenericCache::class.java)
        )
        beanFactory.registerBeanDefinition(
            "join",
            CacheBeanDefinitions.joinCacheDefinition(UnannotatedJoinCache::class.java)
        )
        val contribution = requireNotNull(CoCacheAotProcessor().processAheadOfTime(beanFactory))
        val generationContext = TestGenerationContext()
        contribution.applyTo(generationContext, io.mockk.mockk(relaxed = true))
        val hints: RuntimeHints = generationContext.runtimeHints
        hints.assert().matches(RuntimeHintsPredicates.proxies().forInterfaces(GenericCache::class.java))
        hints.assert().matches(RuntimeHintsPredicates.proxies().forInterfaces(UnannotatedJoinCache::class.java))
        hints.assert().matches(RuntimeHintsPredicates.reflection().onType(Ext::class.java).negate())
        hints.reflection().typeHints().count().assert().isEqualTo(2L)
    }

    /**
     * AOT 运行时：生成的代码已注册 spec 对应的缓存 bean，`CacheSpecRegistrar` 再次运行时跳过它，而不是因重名启动失败。
     */
    @Test
    fun specRegistrarSkipsCachesAlreadyRegisteredForTheSameSpec() {
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerBeanDefinition(
            "productSpec",
            RootBeanDefinition(CacheSpec::class.java).apply {
                setTargetType(
                    ResolvableType.forClassWithGenerics(CacheSpec::class.java, String::class.java, String::class.java)
                )
            }
        )
        CacheSpecRegistrar().postProcessBeanFactory(beanFactory)
        val registered = beanFactory.getBeanDefinition("product")
        CacheBeanDefinitions.specBeanOf(registered).assert().isEqualTo("productSpec")

        CacheSpecRegistrar().postProcessBeanFactory(beanFactory)
        beanFactory.getBeanDefinition("product").assert().isSameAs(registered)
    }

    @Test
    fun contributesNothingWithoutCacheDefinitions() {
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerBeanDefinition("other", RootBeanDefinition(String::class.java))
        CoCacheAotProcessor().processAheadOfTime(beanFactory).assert().isNull()
    }

    companion object {
        val PROPERTIES: TestPropertyValues = TestPropertyValues.of("cocache.enabled=true")

        /**
         * 作用域代理的目标创建次数（每次调用 prototype 目标都会创建）。
         */
        val COUNTER_INSTANCES = AtomicLong()

        /**
         * 构建期：对 [AppConfig] + 自动配置执行 AOT 处理，返回生成内容与生成的 `ApplicationContextInitializer` 类名。
         */
        fun processAheadOfTime(): Pair<TestGenerationContext, ClassName> {
            val context = AnnotationConfigApplicationContext()
            PROPERTIES.applyTo(context)
            context.register(AppConfig::class.java, CoCacheAutoConfiguration::class.java)
            val generationContext = TestGenerationContext(AppConfig::class.java)
            val initializerName = ApplicationContextAotGenerator().processAheadOfTime(context, generationContext)
            generationContext.writeGeneratedContent()
            return generationContext to initializerName
        }
    }
}
