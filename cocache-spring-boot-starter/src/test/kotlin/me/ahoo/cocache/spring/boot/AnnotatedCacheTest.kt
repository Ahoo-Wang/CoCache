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
        fun stringCodec(): Codec<String> = StringCodec

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

    @Configuration(proxyBeanMethods = false)
    @EnableCoCache(caches = [PlainCache::class])
    class NameConflictConfig {
        @Bean
        fun plain(): Any = Any()
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoCacheNextAutoConfiguration::class.java))
        .withPropertyValues("cocache.next.enabled=true")

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
            .withPropertyValues("cocache.next.caches.UserCache.ttl=PT10S")
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
}
