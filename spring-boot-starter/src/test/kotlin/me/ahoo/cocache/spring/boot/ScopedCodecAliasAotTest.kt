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

import io.mockk.mockk
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.Store
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.scope.ScopedProxyUtils
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.aot.test.generate.TestGenerationContext
import org.springframework.beans.factory.config.BeanDefinitionHolder
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.core.ResolvableType
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * 缺陷复现（#998）：同级别名把作用域 Codec 的公开名重定向到别的 bean 之后，运行时（[BeanCandidates.namesOf]）正确地忽略被替换的
 * 隐藏目标，构建期（[BeanCandidates.definedNamesOf]）却仍按旧的代理定义把它算作候选，AOT 于是以为有自定义 Codec，漏掉值类型的
 * Jackson 绑定提示。构建期与运行时现在一致，且构建期从不创建目标。
 */
class ScopedCodecAliasAotTest {
    data class User(val name: String)

    interface Users : Store<String, User>

    class UserCodec : Codec<User> {
        override fun encode(value: User) = value.name.toByteArray()

        override fun decode(bytes: ByteArray) = User(String(bytes))
    }

    private val codecType = ResolvableType.forClassWithGenerics(Codec::class.java, User::class.java)

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun buildTimeCandidatesMatchRuntime(redirected: Boolean) {
        val (factory, created) = factory(redirected)
        val defined = BeanCandidates.definedNamesOf(factory, codecType)
        created.get().assert().isZero()
        val runtime = BeanCandidates.namesOf(factory, codecType)
        runtime.assert().isEqualTo(if (redirected) emptyList() else listOf("codec"))
        defined.assert().isEqualTo(runtime)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun theDefaultJacksonCodecGetsItsBindingHints(redirected: Boolean) {
        val (factory, created) = factory(redirected)
        val context = TestGenerationContext()
        requireNotNull(CoCacheAotProcessor().processAheadOfTime(factory)).applyTo(context, mockk(relaxed = true))
        created.get().assert().isZero()
        val canGetName = RuntimeHintsPredicates.reflection().onMethodInvocation(User::class.java, "getName")
            .test(context.runtimeHints)
        canGetName.assert()
            .withFailMessage { "redirected=$redirected: Jackson binding hint present=$canGetName" }
            .isEqualTo(redirected)
    }

    /**
     * 别名指向本级另一个指向同一目标的作用域代理：按名称解析到的代理确实指向这个目标，构建期与运行时都给出公开名。
     */
    @Test
    fun anAliasToAProxyOfTheSameTargetIsACandidate() {
        val factory = DefaultListableBeanFactory()
        val created = AtomicInteger()
        val target = RootBeanDefinition(UserCodec::class.java).apply {
            setTargetType(codecType)
            scope = "prototype"
            instanceSupplier = Supplier {
                created.incrementAndGet()
                UserCodec()
            }
        }
        val proxy = ScopedProxyUtils.createScopedProxy(BeanDefinitionHolder(target, "codec"), factory, false)
        factory.registerBeanDefinition("proxy", proxy.beanDefinition)
        factory.registerAlias("proxy", "codec")
        BeanCandidates.definedNamesOf(factory, codecType).assert().containsExactly("codec")
        created.get().assert().isZero()
        BeanCandidates.namesOf(factory, codecType).assert().containsExactly("codec")
    }

    private fun factory(redirected: Boolean): Pair<DefaultListableBeanFactory, AtomicInteger> {
        val factory = DefaultListableBeanFactory()
        val created = AtomicInteger()
        val target = RootBeanDefinition(UserCodec::class.java).apply {
            setTargetType(codecType)
            scope = "prototype"
            instanceSupplier = Supplier {
                created.incrementAndGet()
                UserCodec()
            }
        }
        val proxy = ScopedProxyUtils.createScopedProxy(BeanDefinitionHolder(target, "codec"), factory, false)
        factory.registerBeanDefinition("codec", proxy.beanDefinition)
        factory.registerBeanDefinition(
            "other",
            RootBeanDefinition(String::class.java).apply { instanceSupplier = Supplier { "replacement" } }
        )
        if (redirected) {
            factory.registerAlias("other", "codec")
        }
        factory.registerBeanDefinition("users", CacheBeanDefinitions.annotatedCacheDefinition(Users::class.java))
        return factory to created
    }
}
