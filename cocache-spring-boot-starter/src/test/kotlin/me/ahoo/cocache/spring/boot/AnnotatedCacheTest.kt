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
import me.ahoo.cocache.api.SetCache
import me.ahoo.cocache.api.annotation.CaffeineCache
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.RemoteRead
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

class AnnotatedCacheTest {
    data class User(val id: String, val name: String)

    @CoCache(
        keyPrefix = "app:",
        keyExpression = "#{'u-' + toString()}",
        ttl = 120,
        ttlJitter = 0,
        absentTtl = 30,
        localMaxAge = 60
    )
    @CaffeineCache(maximumSize = 100, initialCapacity = 8, expireAfterAccess = 300)
    interface UserCache : Cache<String, User> {
        fun nameOf(id: String): String? = get(id)?.name
    }

    @CoCache(name = "plain", ttl = CoCache.FOREVER)
    interface PlainCache : Cache<String, String>

    interface UndeclaredCache : Cache<Int, String>

    @CoCache(loader = "namedLoader", codec = "namedCodec")
    interface NamedCache : Cache<Long, String>

    interface IndexCache : SetCache<String, String>

    @CoCache(
        name = "tags",
        namespace = "tag-ns",
        keyPrefix = "t:",
        keyExpression = "#{toUpperCase()}",
        ttl = 600,
        ttlJitter = 0,
        codec = "namedCodec"
    )
    @CaffeineCache
    interface TagCache : SetCache<String, String>

    interface GenericCache<V : Any> : Cache<String, V>

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(
        caches = [UserCache::class, PlainCache::class, UndeclaredCache::class, NamedCache::class, IndexCache::class, TagCache::class]
    )
    class AppConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun userLoader(): CacheLoader<String, User> = CacheLoader { User(it, "name-$it") }

        @Bean
        fun namedLoader(): CacheLoader<Long, String> = CacheLoader { "n-$it" }

        @Bean
        fun namedCodec(): Codec<String> = StringCodec
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [PlainCache::class])
    class AmbiguousLoaderConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun codec(): Codec<String> = StringCodec

        @Bean
        fun first(): CacheLoader<String, String> = CacheLoader { it }

        @Bean
        fun second(): CacheLoader<String, String> = CacheLoader { it }
    }

    @CoCache(name = "asyncSourced")
    interface AsyncSourcedCache : Cache<String, String>

    @CoCache(name = "asyncNamed", loader = "namedAsyncLoader")
    interface AsyncNamedCache : Cache<String, Long>

    @CoCache(name = "notLoader", loader = "notALoader")
    interface NotALoaderCache : Cache<String, Long>

    /**
     * 只有异步数据源（按类型唯一匹配）与按名称指定的异步数据源（RFC 0004 §3.4）。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [AsyncSourcedCache::class, AsyncNamedCache::class])
    class AsyncLoaderConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun codec(): Codec<String> = StringCodec

        @Bean
        fun longCodec(): Codec<Long> = object : Codec<Long> {
            override fun encode(value: Long): ByteArray = value.toString().toByteArray()
            override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
        }

        @Bean
        fun asyncLoader(): AsyncCacheLoader<String, String> =
            AsyncCacheLoader { java.util.concurrent.CompletableFuture.completedFuture("async-$it") }

        @Bean
        fun namedAsyncLoader(): AsyncCacheLoader<String, Long> =
            AsyncCacheLoader { java.util.concurrent.CompletableFuture.completedFuture(it.length.toLong()) }
    }

    /**
     * 同步与异步数据源各有一个候选：歧义，启动失败。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [PlainCache::class])
    class SyncAndAsyncLoaderConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun codec(): Codec<String> = StringCodec

        @Bean
        fun syncLoader(): CacheLoader<String, String> = CacheLoader { it }

        @Bean
        fun asyncLoader(): AsyncCacheLoader<String, String> =
            AsyncCacheLoader { java.util.concurrent.CompletableFuture.completedFuture(it) }
    }

    /**
     * 同时实现两者的数据源按同步数据源使用。
     */
    class DualLoader : CacheLoader<String, String>, AsyncCacheLoader<String, String> {
        override fun load(key: String): String = "sync-$key"

        override fun loadAsync(key: String) = java.util.concurrent.CompletableFuture.completedFuture("async-$key")
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [PlainCache::class])
    class DualLoaderConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun codec(): Codec<String> = StringCodec

        @Bean
        fun dualLoader(): DualLoader = DualLoader()
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [NotALoaderCache::class])
    class NotALoaderConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun notALoader(): StringBuilder = StringBuilder("plain")
    }

    @CoCache(ttl = 120, refreshWindow = 0)
    @CaffeineCache(expireAfterAccess = CoCache.FOREVER)
    interface NoRefreshCache : Cache<String, String>

    @CoCache(absentTtl = CoCache.FOREVER)
    interface ForeverAbsentCache : Cache<String, String>

    @CoCache(refreshWindow = CoCache.FOREVER)
    interface ForeverRefreshCache : Cache<String, String>

    @CoCache(ttl = 10, refreshWindow = 30)
    interface InvalidPolicyCache : Cache<String, String>

    @CoCache(loader = "namedLoader")
    interface LoaderSetCache : SetCache<String, String>

    @CoCache(keyExpression = "id")
    interface ConstantKeyCache : Cache<String, String>

    interface OverloadCache : Cache<String, String> {
        // 与 Cache 方法同名同参数个数，但参数类型不兼容：代理按名分派会 ClassCastException
        fun get(key: String, fallback: String): String?
    }

    interface UnknownAbstractCache : Cache<String, String> {
        fun lookupAll(keys: List<String>): Map<String, String>
    }

    interface BadReturnCache : Cache<String, String> {
        fun lookup(key: Int): Int

        fun invalidate(key: Long): Boolean
    }

    interface SetOnlyMethodCache : Cache<String, String> {
        fun clear(key: String)
    }

    interface RedeclaredCache : Cache<String, String> {
        override fun get(key: String): String?

        override fun get(key: String, loader: CacheLoader<String, String>): String?

        override fun put(key: String, value: String, ttl: Duration)
    }

    interface RedeclaredSetCache : SetCache<String, String> {
        override fun clear(key: String)
    }

    /**
     * 记录翻译后的声明策略（定制器在配置文件之前执行）。
     */
    class PolicyRecorder : CacheSpecCustomizer {
        val policies = java.util.concurrent.ConcurrentHashMap<String, me.ahoo.cocache.core.policy.CachePolicy>()

        override fun customize(spec: me.ahoo.cocache.core.CacheSpec.Builder<*, *>) {
            policies[spec.name] = spec.policy
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [NoRefreshCache::class, RedeclaredCache::class, RedeclaredSetCache::class])
    class TranslationConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun codec(): Codec<String> = StringCodec

        @Bean
        fun recorder(): PolicyRecorder = PolicyRecorder()

        @Bean
        fun namedLoader(): CacheLoader<String, String> = CacheLoader { "n-$it" }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [PlainCache::class])
    class AmbiguousCodecConfig {
        @Bean(destroyMethod = "close")
        fun backend(): CoherenceBackend = InMemoryBackend()

        @Bean
        fun firstCodec(): Codec<String> = StringCodec

        @Bean
        fun secondCodec(): Codec<String> = StringCodec
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [PlainCache::class])
    class NoBackendConfig {
        @Bean
        fun codec(): Codec<String> = StringCodec
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [PlainCache::class])
    class NameConflictConfig {
        @Bean
        fun plain(): Any = Any()
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
        .withPropertyValues("cocache.enabled=true")

    @Test
    fun registersInterfacesAsBeans() {
        runner.withUserConfiguration(AppConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val users = context.getBean(UserCache::class.java)
            context.getBean("UserCache").assert().isSameAs(users)
            users["1"].assert().isEqualTo(User("1", "name-1"))
            users.nameOf("2").assert().isEqualTo("name-2")
            users.name.assert().isEqualTo("UserCache")

            val backend = context.getBean(CoherenceBackend::class.java)
            val stored = backend.store("UserCache").read("app:UserCache:u-1", null, null) as RemoteRead.Hit
            requireNotNull(stored.ttl).assert().isLessThanOrEqualTo(Duration.ofSeconds(120))

            val runtime = context.getBean(CacheRuntime::class.java)
            runtime.cacheNames.assert().contains("UserCache", "plain", "UndeclaredCache", "NamedCache", "IndexCache")

            val plain = context.getBean(PlainCache::class.java)
            plain["missing"].assert().isNull()
            plain.put("k", "v")
            (backend.store("plain").read("cocache:plain:k", null, null) as RemoteRead.Hit).ttl.assert().isNull()

            context.getBean(UndeclaredCache::class.java)[1].assert().isNull()
            context.getBean(NamedCache::class.java)[7L].assert().isEqualTo("n-7")

            val index = context.getBean(IndexCache::class.java)
            index.add("k", listOf("a"))
            index["k"].assert().containsExactly("a")
            CacheProxies.targetOf(index).assert().isSameAs(runtime.cache("IndexCache"))
        }
    }

    @Test
    fun annotatedSetCacheUsesNamespacePrefixAndKeyExpression() {
        runner.withUserConfiguration(AppConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val tags = context.getBean("tags") as TagCache
            tags.add("k", listOf("a"))
            tags["k"].assert().containsExactly("a")
            val stored = context.getBean(
                CoherenceBackend::class.java
            ).store("tag-ns").read("t:tag-ns:K", null, null) as RemoteRead.Hit
            requireNotNull(stored.ttl).assert().isLessThanOrEqualTo(Duration.ofSeconds(600))
        }
    }

    @Test
    fun propertiesOverrideAnnotations() {
        runner.withUserConfiguration(AppConfig::class.java)
            .withPropertyValues("cocache.caches.UserCache.ttl=PT10S")
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(UserCache::class.java)["1"]
                val stored = context.getBean(
                    CoherenceBackend::class.java
                ).store("UserCache").read("app:UserCache:u-1", null, null) as RemoteRead.Hit
                requireNotNull(stored.ttl).assert().isLessThanOrEqualTo(Duration.ofSeconds(10))
            }
    }

    @Test
    fun asyncLoadersAreResolvedByTypeAndName() {
        runner.withUserConfiguration(AsyncLoaderConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val sourced = context.getBean(AsyncSourcedCache::class.java)
            sourced["k"].assert().isEqualTo("async-k")
            sourced.async().get("j").join().assert().isEqualTo("async-j")
            context.getBean(AsyncNamedCache::class.java).async().get("four").join().assert().isEqualTo(4L)
        }
    }

    @Test
    fun syncAndAsyncLoaderCandidatesAreAmbiguous() {
        runner.withUserConfiguration(SyncAndAsyncLoaderConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasStackTraceContaining("Both CacheLoader")
        }
    }

    @Test
    fun loaderImplementingBothIsUsedAsSync() {
        runner.withUserConfiguration(DualLoaderConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(PlainCache::class.java)["k"].assert().isEqualTo("sync-k")
        }
    }

    @Test
    fun namedLoaderMustBeALoader() {
        runner.withUserConfiguration(NotALoaderConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasStackTraceContaining(
                "must name a CacheLoader or an AsyncCacheLoader bean"
            )
        }
    }

    @Test
    fun ambiguousLoaderFailsStartup() {
        runner.withUserConfiguration(AmbiguousLoaderConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasStackTraceContaining("Multiple CacheLoader beans")
        }
    }

    @Test
    fun unresolvedGenericsFail() {
        runner.withUserConfiguration(AppConfig::class.java).run { context ->
            val factory = context.getBean(AnnotatedCacheFactory::class.java)

            @Suppress("UNCHECKED_CAST")
            val type = GenericCache::class.java as Class<Cache<*, *>>
            runCatching {
                factory.create(
                    type
                )
            }.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("concrete type arguments")
        }
    }

    @Test
    fun beanNameConflictFailsStartup() {
        runner.withUserConfiguration(NameConflictConfig::class.java).run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun cacheNameDefaultsToSimpleName() {
        AnnotatedCacheFactory.cacheNameOf(UserCache::class.java).assert().isEqualTo("UserCache")
        AnnotatedCacheFactory.cacheNameOf(PlainCache::class.java).assert().isEqualTo("plain")
    }

    private fun <C : Cache<*, *>> createFailure(type: Class<C>, vararg properties: String): Throwable? {
        var failure: Throwable? = null
        runner.withUserConfiguration(TranslationConfig::class.java).withPropertyValues(*properties).run { context ->
            assertThat(context).hasNotFailed()
            failure = runCatching { context.getBean(AnnotatedCacheFactory::class.java).create(type) }.exceptionOrNull()
        }
        return failure
    }

    @Test
    fun foreverIsOnlyAllowedForTtlAndExpireAfterAccess() {
        runner.withUserConfiguration(TranslationConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val policy = requireNotNull(context.getBean(PolicyRecorder::class.java).policies["NoRefreshCache"])
            policy.local.expireAfterAccess.assert().isNull()
            policy.refreshWindow.assert().isNull()
            policy.ttl.assert().isEqualTo(Duration.ofSeconds(120))
        }
        createFailure(ForeverAbsentCache::class.java).assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cache[ForeverAbsentCache] - @CoCache.absentTtl = FOREVER is not supported")
        createFailure(ForeverRefreshCache::class.java).assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cache[ForeverRefreshCache] - @CoCache.refreshWindow = FOREVER")
    }

    @Test
    fun policyErrorsNameTheCache() {
        createFailure(InvalidPolicyCache::class.java).assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("Cache[InvalidPolicyCache] - refreshWindow")
        // 配置文件导致的策略错误同样带缓存名
        runner.withUserConfiguration(TranslationConfig::class.java)
            .withPropertyValues("cocache.caches.NoRefreshCache.absent-ttl=0")
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining("Cache[NoRefreshCache] - absentTtl")
            }
    }

    @Test
    fun constantKeyExpressionFails() {
        // 没有 #{...} 的模板是常量：所有 key 都会映射到同一个条目
        createFailure(ConstantKeyCache::class.java).assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("is a constant")
    }

    @Test
    fun loaderOnSetCacheFails() {
        createFailure(LoaderSetCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not supported on SetCache")
    }

    @Test
    fun ambiguousCodecFailsStartup() {
        runner.withUserConfiguration(AmbiguousCodecConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasStackTraceContaining("Multiple Codec beans")
        }
    }

    @Test
    fun incompatibleAbstractMethodsFailAtCreation() {
        createFailure(OverloadCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("String get(String, String)")
        createFailure(UnknownAbstractCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("lookupAll")
        createFailure(BadReturnCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("int lookup(int)")
            .hasMessageContaining("boolean invalidate(long)")
        createFailure(SetOnlyMethodCache::class.java).assert().isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("void clear(String)")
    }

    @Test
    fun redeclaredCacheMethodsAreDispatched() {
        runner.withUserConfiguration(TranslationConfig::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val cache = context.getBean(RedeclaredCache::class.java)
            cache["a"].assert().isEqualTo("n-a")
            cache.put("a", "v", Duration.ofSeconds(30))
            cache["a"].assert().isEqualTo("v")
            cache.get("b", CacheLoader { "loaded-$it" }).assert().isEqualTo("loaded-b")
            val set = context.getBean(RedeclaredSetCache::class.java)
            set.add("k", listOf("x"))
            set.clear("k")
        }
    }

    @Test
    fun missingBackendExplainsTheCause() {
        runner.withUserConfiguration(NoBackendConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            context.startupFailure.assert().hasStackTraceContaining(
                "Cannot create cache[plain] - no CoherenceBackend is available"
            )
        }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoCacheAutoConfiguration::class.java))
            .withUserConfiguration(AppConfig::class.java)
            .withPropertyValues("cocache.enabled=false")
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure.assert().hasStackTraceContaining("CoCache is disabled (cocache.enabled=false)")
            }
    }
}
