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

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.api.Store
import me.ahoo.cocache.api.join.JoinValue
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.spring.boot.CoCacheAotTest.AsyncTokenStore
import me.ahoo.cocache.spring.boot.CoCacheAotTest.CounterCache
import me.ahoo.cocache.spring.boot.CoCacheAotTest.Ext
import me.ahoo.cocache.spring.boot.CoCacheAotTest.ExtJoinCache
import me.ahoo.cocache.spring.boot.CoCacheAotTest.ProductJoinCache
import me.ahoo.cocache.spring.boot.CoCacheAotTest.Profile
import me.ahoo.cocache.spring.boot.CoCacheAotTest.ProfileStore
import me.ahoo.cocache.spring.boot.CoCacheAotTest.SuspendingTagStore
import me.ahoo.cocache.spring.boot.CoCacheAotTest.Tag
import me.ahoo.cocache.spring.boot.CoCacheAotTest.TagCache
import me.ahoo.cocache.spring.boot.CoCacheAotTest.Token
import me.ahoo.cocache.spring.boot.CoCacheAotTest.TokenCache
import me.ahoo.cocache.spring.boot.CoCacheAotTest.User
import me.ahoo.cocache.spring.boot.CoCacheAotTest.UserCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.ResolvableType
import org.springframework.core.test.tools.CompileWithForkedClassLoader
import org.springframework.core.test.tools.TestCompiler

/**
 * Spring AOT 运行时：编译构建期生成的代码，在没有注册器（`ImportBeanDefinitionRegistrar` 不再运行）的新容器中重建全部缓存 bean。
 *
 * 生成的代码访问 Spring Boot 的非公开类型，需要在独立的类加载器中运行；JaCoCo 不统计该类加载器加载的类，覆盖率由 [CoCacheAotTest] 等提供。
 */
@CompileWithForkedClassLoader
class CoCacheAotRuntimeTest {
    @Test
    fun generatedInitializerRecreatesCachesWithoutRegistrars() {
        val (generationContext, initializerName) = CoCacheAotTest.processAheadOfTime()
        TestCompiler.forSystem().with(generationContext).compile { compiled ->
            val context = GenericApplicationContext()
            CoCacheAotTest.PROPERTIES.applyTo(context)
            @Suppress("UNCHECKED_CAST")
            val initializer = compiled.getInstance(
                ApplicationContextInitializer::class.java,
                initializerName.reflectionName()
            ) as ApplicationContextInitializer<GenericApplicationContext>
            initializer.initialize(context)
            context.refresh()
            context.use {
                val users = it.getBean(UserCache::class.java)
                users.nameOf("a").assert().isEqualTo("name-a")
                it.getBean("UserCache").assert().isSameAs(users)
                it.getBean(ExtJoinCache::class.java).userOf("e").assert().isEqualTo(User("u-e", "name-u-e"))
                it.getBean(ExtJoinCache::class.java)["e"].assert()
                    .isEqualTo(JoinValue(Ext("e", "u-e"), "u-e", User("u-e", "name-u-e")))
                @Suppress("UNCHECKED_CAST")
                val products = it.getBean("product") as Cache<String, String>
                products["x"].assert().isEqualTo("product-x")
                it.getBean(ProductJoinCache::class.java)["x"]?.secondValue.assert()
                    .isEqualTo(User("product-x", "name-product-x"))
                it.getBean(TokenCache::class.java).put("t", Token("v"))
                it.getBean(TokenCache::class.java)["t"].assert().isEqualTo(Token("v"))
                it.getBean(TagCache::class.java).add("k", listOf(Tag("a")))
                it.getBean(TagCache::class.java)["k"].assert().isEqualTo(setOf(Tag("a")))
                val suspendingTags = it.getBean(SuspendingTagStore::class.java)
                runBlocking {
                    suspendingTags.add("k", listOf(Tag("b")))
                    suspendingTags.get("k").assert().isEqualTo(setOf(Tag("b")))
                }
                val asyncTokens = it.getBean(AsyncTokenStore::class.java)
                asyncTokens.put("t", Token("w")).join()
                asyncTokens.get("t").join().assert().isEqualTo(Token("w"))
                @Suppress("UNCHECKED_CAST")
                (it.getBean("label") as SetStore<String, String>).add("k", listOf("x"))
                assertBeanResolution(it)
                assertDeclarations(it)
            }
        }
    }

    /**
     * spec 缓存只注册一次：`CacheSpecRegistrar` 在运行时跳过生成代码已注册的 bean；声明的缓存名都可见。
     */
    private fun assertDeclarations(context: GenericApplicationContext) {
        val stringCacheType = ResolvableType.forClassWithGenerics(
            Cache::class.java,
            String::class.java,
            String::class.java
        )
        context.getBeanNamesForType(stringCacheType).assert().containsExactly("product")
        DeclaredCaches.of(context.beanFactory).names.assert()
            .containsExactlyInAnyOrder(
                "UserCache",
                "ExtCache",
                "TokenCache",
                "TagCache",
                "SuspendingTagStore",
                "AsyncTokenStore",
                "ProfileStore",
                "CounterCache",
                "products",
                "labels",
                "orders",
                "ledger"
            )
    }

    /**
     * FactoryBean 提供的 spec、继承的类型参数与作用域代理的数据源在 AOT 运行时同样生效。
     */
    private fun assertBeanResolution(context: GenericApplicationContext) {
        @Suppress("UNCHECKED_CAST")
        val ledger = context.getBean("ledger") as Store<String, String>
        ledger.put("k", "v")
        ledger["k"].assert().isEqualTo("v")
        // 继承的类型参数：经 L1 读回的值按 List<Profile> 解码
        val profiles = context.getBean(ProfileStore::class.java)
        profiles.put("p", listOf(Profile("n")))
        requireNotNull(context.getBean(CacheRuntime::class.java).admin("ProfileStore")).clearLocal()
        profiles["p"].assert().isEqualTo(listOf(Profile("n")))
        // 作用域代理：每次回源取得新的目标
        val counters = context.getBean(CounterCache::class.java)
        requireNotNull(counters["a"]).assert().isNotEqualTo(requireNotNull(counters["b"]))
    }
}
