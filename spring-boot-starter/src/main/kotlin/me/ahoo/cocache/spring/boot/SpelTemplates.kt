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

import org.springframework.core.NativeDetector
import org.springframework.expression.Expression
import org.springframework.expression.common.LiteralExpression
import org.springframework.expression.common.TemplateParserContext
import org.springframework.expression.spel.SpelCompilerMode
import org.springframework.expression.spel.SpelParserConfiguration
import org.springframework.expression.spel.standard.SpelExpressionParser

/**
 * `@CoCache.keyExpression` 与 `@JoinCacheable.joinKeyExpression` 的 SpEL 模板（RFC 0005 §4.9）：每次调用求值一次，在命中路径上，
 * 因此用编译模式（[SpelCompilerMode.MIXED]）。先解释执行，求值一定次数之后编译成字节码；编译失败（含不可编译的表达式，如投影与选择）
 * 时一直解释执行，编译后的代码在运行时失败（如根对象换了类型）时退回解释执行并重新求值，结果都与解释执行相同。
 * 原生镜像不能在运行时定义类，那里一律解释执行。
 */
internal object SpelTemplates {
    private val CONTEXT = TemplateParserContext()

    /**
     * @param classLoader 编译出的类所用的类加载器（应能看到根对象的类型）；`null` 为默认类加载器
     */
    fun parse(expression: String, classLoader: ClassLoader?): Expression =
        SpelExpressionParser(SpelParserConfiguration(compilerMode(), classLoader)).parseExpression(expression, CONTEXT)

    /**
     * 注解属性 [attribute]（如 `@CoCache.keyExpression`）的模板：解析并拒绝常量。没有 `#{...}` 的模板对任何输入求值都相同
     * （所有 key 映射到同一个存储 key、所有值关联到同一个 key），两个属性以同样的 [IllegalArgumentException] 拒绝它。
     *
     * @param example 错误提示中的模板示例
     */
    fun parseTemplate(attribute: String, expression: String, example: String, classLoader: ClassLoader?): Expression {
        val parsed = parse(expression, classLoader)
        require(
            parsed !is LiteralExpression
        ) { "$attribute[$expression] is a constant; use a template such as \"$example\"." }
        return parsed
    }

    internal fun compilerMode(nativeImage: Boolean = NativeDetector.inNativeImage()): SpelCompilerMode =
        if (nativeImage) SpelCompilerMode.OFF else SpelCompilerMode.MIXED
}
