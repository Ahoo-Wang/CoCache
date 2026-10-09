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

package me.ahoo.cache.converter

import org.springframework.expression.Expression
import org.springframework.expression.common.TemplateParserContext
import org.springframework.expression.spel.SpelCompilerMode
import org.springframework.expression.spel.SpelParserConfiguration
import org.springframework.expression.spel.standard.SpelExpressionParser

/**
 * 解析 SpEL 模板表达式（`#{...}`），启用混合编译模式：热点表达式编译为字节码，失败时回退解释执行。
 */
internal object SpelTemplates {
    private val parser = SpelExpressionParser(
        SpelParserConfiguration(SpelCompilerMode.MIXED, SpelTemplates::class.java.classLoader)
    )

    fun parse(expression: String): Expression {
        return parser.parseExpression(expression, TemplateParserContext.TEMPLATE_EXPRESSION)
    }
}
