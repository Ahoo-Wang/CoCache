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
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.cacheSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.storeSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 缺陷复现（#999）：经 Spring 别名引用 CoCache 的 CacheManager（`cacheManager = "cacheAlias"`）的操作曾被当作“其它 CacheManager”，
 * 绕过 `require-sync` 与缓存角色的启动检查，而调用实际进入同一个 CacheManager。别名与原名现在同样检查。
 */
class CacheManagerAliasCheckTest {
    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    class Caches {
        @Bean
        fun usersSpec(): CacheSpec<String, String> = cacheSpec("users") {
            codec = StringCodec
            externallySourced()
        }

        @Bean
        fun recordSpec(): StoreSpec<String, String> = storeSpec("record") { codec = StringCodec }
    }

    open class CanonicalUnsynced {
        @Cacheable(cacheNames = ["users"], cacheManager = "cocacheCacheManager")
        open fun load(key: String) = "value-$key"
    }

    open class AliasUnsynced {
        @Cacheable(cacheNames = ["users"], cacheManager = ALIAS)
        open fun load(key: String) = "value-$key"
    }

    open class AliasSynced {
        @Cacheable(cacheNames = ["users"], cacheManager = ALIAS, sync = true)
        open fun load(key: String) = "value-$key"
    }

    open class CanonicalAuthoritative {
        @Cacheable(cacheNames = ["record"], cacheManager = "cocacheCacheManager", sync = true)
        open fun load(key: String) = "value-$key"
    }

    open class AliasAuthoritative {
        @Cacheable(cacheNames = ["record"], cacheManager = ALIAS, sync = true)
        open fun load(key: String) = "value-$key"
    }

    private fun runner(type: Class<*>) = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(CoCacheAutoConfiguration::class.java, CoCacheSpringCacheAutoConfiguration::class.java)
        )
        .withPropertyValues("cocache.backend=memory", "cocache.spring-cache.require-sync=true")
        .withUserConfiguration(Caches::class.java)
        .withBean(type)
        .withInitializer { it.beanFactory.registerAlias("cocacheCacheManager", ALIAS) }

    /**
     * `require-sync=true` 时，经别名的 `@Cacheable(sync = false)` 与经原名的一样让启动失败。
     */
    @Test
    fun anUnsyncedCacheableThroughAnAliasFails() {
        listOf(CanonicalUnsynced::class.java, AliasUnsynced::class.java).forEach { type ->
            runner(type).run { context ->
                context.startupFailure.assert()
                    .withFailMessage { "${type.simpleName} started despite require-sync=true" }
                    .hasStackTraceContaining("without sync = true")
                    .hasStackTraceContaining("[users]")
            }
        }
    }

    /**
     * 指向权威 `Store` 的 Spring Cache 操作经别名同样在启动时失败，不等到第一次调用。
     */
    @Test
    fun anOperationOnAStoreThroughAnAliasFails() {
        listOf(CanonicalAuthoritative::class.java, AliasAuthoritative::class.java).forEach { type ->
            runner(type).run { context ->
                context.startupFailure.assert()
                    .withFailMessage { "${type.simpleName} started with a Spring Cache operation on a Store" }
                    .hasStackTraceContaining("not a Cache")
                    .hasStackTraceContaining("[record]")
            }
        }
    }

    /**
     * 对照：经别名的合法 `sync = true` 读取照常启动，写入的是同一个运行时的缓存。
     */
    @Test
    fun aSyncedCacheableThroughAnAliasWorks() {
        runner(AliasSynced::class.java).run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(ALIAS).assert().isSameAs(context.getBean("cocacheCacheManager"))
            context.getBean(AliasSynced::class.java).load("k").assert().isEqualTo("value-k")
            @Suppress("UNCHECKED_CAST")
            val users = context.getBean(CacheRuntime::class.java).cache("users") as Cache<String, String>
            users["k"].assert().isEqualTo("value-k")
        }
    }

    companion object {
        private const val ALIAS = "cacheAlias"
    }
}
