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

package me.ahoo.cache.join

import me.ahoo.cache.api.join.JoinKeyExtractor
import org.springframework.expression.Expression
import org.springframework.expression.common.TemplateParserContext
import org.springframework.expression.spel.standard.SpelExpressionParser

class ExpJoinKeyExtractor<V1>(expression: String) : JoinKeyExtractor<V1, String> {
    private val expression: Expression = SpelExpressionParser()
        .parseExpression(expression, TemplateParserContext.TEMPLATE_EXPRESSION)

    override fun extract(firstValue: V1): String {
        return requireNotNull(expression.getValue(firstValue, String::class.java))
    }
}