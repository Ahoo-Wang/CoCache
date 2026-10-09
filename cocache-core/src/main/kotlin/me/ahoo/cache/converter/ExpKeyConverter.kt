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

import me.ahoo.cache.api.converter.KeyConverter
import org.springframework.expression.Expression

/**
 * 基于 SpEL 模板表达式的 key 转换器。
 *
 * @author ahoo wang
 */
class ExpKeyConverter<K>(private val keyPrefix: String, expression: String) : KeyConverter<K> {
    private val expression: Expression = SpelTemplates.parse(expression)

    override fun toStringKey(sourceKey: K): String {
        return keyPrefix + expression.getValue(sourceKey, String::class.java)
    }

    override fun toString(): String {
        return "ExpKeyConverter(keyPrefix='$keyPrefix', expression=${expression.expressionString})"
    }
}
