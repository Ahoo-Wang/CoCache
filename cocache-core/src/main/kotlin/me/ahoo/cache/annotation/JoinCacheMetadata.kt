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

import kotlin.reflect.KClass

data class JoinCacheMetadata(
    override val proxyInterface: KClass<*>,
    override val name: String,
    val firstCacheName: String,
    val joinCacheName: String,
    val joinKeyExtractorName: String,
    val firstKeyType: KClass<*>,
    val firstValueType: KClass<*>,
    val joinKeyType: KClass<*>,
    val joinValueType: KClass<*>
) : ComputedNamedCache
