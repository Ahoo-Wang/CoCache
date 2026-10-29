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

import me.ahoo.cocache.api.Cache

/**
 * 只读的组合视图：先读主缓存（first），再用 [JoinKeyExtractor] 从主值中提取关联 key，读关联缓存（join）。
 *
 * - 不是 [Cache] 的子类型，没有写入：两个组件缓存各自拥有数据源、TTL 与失效，组合视图只负责读取。
 * - 不持有自己的副本，也不改变组件缓存的一致性语义：两次读取之间没有原子性，结果中的两个值可能来自不同时刻。
 * - 用户接口继承本接口并在 `@EnableCoCache(joinCaches = [...])` 中注册（见 `@JoinCacheable`）。
 *
 * @param K1 主缓存 key
 * @param V1 主缓存值
 * @param K2 关联缓存 key
 * @param V2 关联缓存值
 */
interface JoinCache<K1 : Any, V1 : Any, K2 : Any, V2 : Any> {
    /**
     * 组合视图名。
     */
    val name: String

    /**
     * 读取主值并组合关联值。主值不存在（含负缓存）时返回 `null`；
     * 关联 key 为 `null` 或关联值不存在时，[JoinValue.secondValue] 为 `null`。
     */
    operator fun get(key: K1): JoinValue<V1, K2, V2>?

    /**
     * 批量读取：主缓存一次 `getAll`，关联缓存按去重后的关联 key 一次 `getAll`（没有关联 key 时不读）。
     * 结果只包含主值存在的 key。
     */
    fun getAll(keys: Collection<K1>): Map<K1, JoinValue<V1, K2, V2>>

    /**
     * 只失效主缓存：关联缓存有独立的生命周期，由其写入方负责失效。
     */
    fun invalidate(key: K1)

    /**
     * 同时失效主缓存与关联缓存。
     */
    fun invalidate(firstKey: K1, joinKey: K2)
}
