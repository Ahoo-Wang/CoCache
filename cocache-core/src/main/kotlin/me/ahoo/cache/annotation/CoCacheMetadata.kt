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

package me.ahoo.cache.annotation

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.NamedCache
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * [me.ahoo.cache.api.annotation.CoCache] 接口的解析结果。
 */
data class CoCacheMetadata(
    val proxyInterface: KClass<*>,
    override val cacheName: String,
    val keyPrefix: String,
    val keyExpression: String,
    val ttlPolicy: TtlPolicy,
    val keyType: KType,
    val valueType: KType
) : NamedCache
