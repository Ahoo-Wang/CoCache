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

package me.ahoo.cocache.core

/**
 * [CoCacheFactory] 的装配层（RFC 0005 §4.8）：把声明变成生效声明。每种 spec 一个入口，默认原样返回。
 *
 * starter 以定制器与配置文件实现它；三层合并（`defaults` → 声明 → 定制器 → `overrides` → `caches.[name]`）、适用性与
 * `lock` / `constrain` 随 M6 在这里实现。实现返回的必须是同一个缓存的声明：名称不变，类型由签名保证
 * （定制器只能修改策略字段与不决定类型的字段，U16）。
 *
 * 不经 [CoCacheFactory]、直接经 [CacheRuntime.create] 创建的缓存不经过任何一层，标注为“未分层”（[CacheAdmin.layered]，S20）。
 */
interface CacheLayers {
    fun <K : Any, V : Any> apply(spec: CacheSpec<K, V>): CacheSpec<K, V> = spec

    fun <K : Any, V : Any> apply(spec: VersionedCacheSpec<K, V>): VersionedCacheSpec<K, V> = spec

    fun <K : Any, V : Any> apply(spec: StoreSpec<K, V>): StoreSpec<K, V> = spec

    fun <K : Any, V : Any> apply(spec: VersionedStoreSpec<K, V>): VersionedStoreSpec<K, V> = spec

    fun <K : Any, E : Any> apply(spec: SetStoreSpec<K, E>): SetStoreSpec<K, E> = spec

    companion object {
        /**
         * 不做任何修改的装配层：生效声明就是声明。
         */
        @JvmField
        val NONE: CacheLayers = object : CacheLayers {
            override fun toString(): String = "CacheLayers.NONE"
        }
    }
}
