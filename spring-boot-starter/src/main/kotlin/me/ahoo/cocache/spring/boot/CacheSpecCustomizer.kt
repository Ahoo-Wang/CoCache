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

import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.VersionedCacheSpec
import me.ahoo.cocache.core.VersionedStoreSpec

/**
 * 在创建缓存之前修改声明，用于注解或 spec 无法表达的设置，或批量修改多个缓存。多个定制器按 Spring 的顺序依次应用。
 * 五种 spec 各有一个重载（RFC 0005 §3.1），默认什么也不做。
 */
interface CacheSpecCustomizer {
    /**
     * 是否作用于该缓存，默认全部。
     */
    fun supports(cacheName: String): Boolean = true

    fun customize(spec: CacheSpec.Builder<*, *>) = Unit

    fun customize(spec: VersionedCacheSpec.Builder<*, *>) = Unit

    fun customize(spec: StoreSpec.Builder<*, *>) = Unit

    fun customize(spec: VersionedStoreSpec.Builder<*, *>) = Unit

    fun customize(spec: SetStoreSpec.Builder<*, *>) = Unit
}
