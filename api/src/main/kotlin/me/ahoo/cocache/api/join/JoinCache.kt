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

package me.ahoo.cocache.api.join

/**
 * 两侧都是派生副本（`me.ahoo.cocache.api.Cache`）的组合视图：读取同 [JoinView]，另有失效。
 *
 * 不供用户实现，委托除外（见 `me.ahoo.cocache.api.ReadableCache`）。
 */
interface JoinCache<K1 : Any, V1 : Any, K2 : Any, V2 : Any> : JoinView<K1, V1, K2, V2> {
    /**
     * 只失效主缓存：关联缓存有独立的生命周期，由其写入方负责失效。
     */
    fun invalidate(key: K1)

    /**
     * 同时失效主缓存与关联缓存。
     */
    fun invalidate(firstKey: K1, joinKey: K2)
}
