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
import me.ahoo.cocache.api.annotation.CoCache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.expression.Expression
import org.springframework.expression.common.CompositeStringExpression
import org.springframework.expression.spel.SpelCompilerMode
import org.springframework.expression.spel.standard.SpelExpression
import tools.jackson.databind.json.JsonMapper

/**
 * key 模板的编译模式（RFC 0005 §4.9）：预热之后编译；根对象换了类型时退回解释执行；不可编译的表达式一直解释执行。结果都与解释执行相同。
 */
class SpelTemplatesTest {
    class UserKey(val id: String, val tags: List<String>)

    class OrderKey(val id: String)

    private fun Expression.evaluate(root: Any, times: Int = WARM_UP): String? {
        var value: String? = null
        repeat(times) { value = getValue(root, String::class.java) }
        return value
    }

    private fun SpelExpression.compiled(): Boolean =
        SpelExpression::class.java.getDeclaredField("compiledAst").apply { isAccessible = true }.get(this) != null

    /**
     * 外部来源、带 `keyExpression` 的接口：容器不是 `ConfigurableBeanFactory` 时用默认类加载器编译。
     */
    @CoCache(source = CoCache.Source.EXTERNAL, keyExpression = "user:#{#root.id}")
    interface UserCache : Cache<UserKey, String>

    @Test
    fun compilerModeIsOffInNativeImages() {
        SpelTemplates.compilerMode().assert().isEqualTo(SpelCompilerMode.MIXED)
        SpelTemplates.compilerMode(nativeImage = true).assert().isEqualTo(SpelCompilerMode.OFF)
    }

    @Test
    fun keyExpressionWithoutAConfigurableBeanFactory() {
        val runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
        runtime.use {
            val factory = AnnotatedCacheFactory(StaticListableBeanFactory(), CoCacheFactory(it)) {
                JsonMapper.builder().build()
            }
            val users = factory.create(UserCache::class.java)
            val key = UserKey("7", emptyList())
            repeat(WARM_UP) { users.put(key, "seven") }
            users[key].assert().isEqualTo("seven")
            requireNotNull(it.admin(users.name)).inspect("user:7").remote.value.assert().isEqualTo("seven")
        }
    }

    @Test
    fun compilesAfterWarmUp() {
        val expression = SpelTemplates.parse("#{#root.id}", javaClass.classLoader) as SpelExpression
        expression.evaluate(UserKey("1", emptyList()), times = 1).assert().isEqualTo("1")
        expression.compiled().assert().isFalse()
        expression.evaluate(UserKey("2", emptyList())).assert().isEqualTo("2")
        expression.compiled().assert().isTrue()
        expression.evaluate(UserKey("3", emptyList()), times = 1).assert().isEqualTo("3")
    }

    @Test
    fun revertsToInterpretedWhenTheRootTypeChanges() {
        val expression = SpelTemplates.parse("#{#root.id}", null) as SpelExpression
        expression.evaluate(UserKey("1", emptyList())).assert().isEqualTo("1")
        expression.compiled().assert().isTrue()
        expression.evaluate(OrderKey("o-1"), times = 1).assert().isEqualTo("o-1")
        expression.compiled().assert().isFalse()
    }

    @Test
    fun keepsInterpretingWhatCannotCompile() {
        val expression = SpelTemplates.parse("#{#root.tags.![toUpperCase()]}", null) as SpelExpression
        expression.evaluate(UserKey("1", listOf("a", "b"))).assert().isEqualTo("A,B")
        expression.compiled().assert().isFalse()
    }

    @Test
    fun templatesWithLiteralsCompileTheirParts() {
        val expression = SpelTemplates.parse("user:#{#root.id}:#{#root.tags.size()}", null)
        expression.assert().isInstanceOf(CompositeStringExpression::class.java)
        expression.evaluate(UserKey("1", listOf("a"))).assert().isEqualTo("user:1:1")
        val parts = (expression as CompositeStringExpression).expressions.filterIsInstance<SpelExpression>()
        parts.map { it.compiled() }.assert().containsExactly(true, true)
    }

    private companion object {
        /**
         * 多于 `MIXED` 模式编译之前的解释执行次数。
         */
        const val WARM_UP = 200
    }
}
