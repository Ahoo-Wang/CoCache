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

package me.ahoo.cache.api.annotation

import java.lang.annotation.Inherited

/**
 * 用于标记缓存接口，用于自动注入缓存代理。
 *
 * - 缓存接口必须是接口。
 * - 缓存接口必须继承 [me.ahoo.cache.api.Cache]。
 *
 * @see me.ahoo.cache.api.Cache
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Inherited
@MustBeDocumented
annotation class CoCache(
    val name: String = "",
    val keyPrefix: String = "",
    /**
     * Spel Expression
     */
    val keyExpression: String = ""
) {
    companion object {
        const val COCACHE = "cocache"
    }
}
