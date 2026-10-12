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

package me.ahoo.cache.api.source

import me.ahoo.cache.api.CacheValue

/**
 * 数据源（L0）：L2、L1 均未命中时回源加载。
 *
 * 返回 `null` 表示数据源中不存在该 key，调用方将写入负缓存以防缓存穿透。
 *
 * @author ahoo wang
 */
fun interface CacheSource<K, V> {
    fun loadCacheValue(key: K): CacheValue<V>?

    /**
     * 批量回源：结果中缺少的 key 视为数据源中不存在，调用方将写入负缓存。
     *
     * 默认逐个调用 [loadCacheValue]；数据源应覆盖为一次批量查询（如 `WHERE id IN (...)`）。
     */
    fun loadCacheValues(keys: Collection<K>): Map<K, CacheValue<V>> {
        val result = LinkedHashMap<K, CacheValue<V>>()
        for (key in keys) {
            loadCacheValue(key)?.let { result[key] = it }
        }
        return result
    }

    companion object {
        private val NO_OP = CacheSource<Any?, Any?> { null }

        @JvmStatic
        fun <K, V> noOp(): CacheSource<K, V> {
            @Suppress("UNCHECKED_CAST")
            return NO_OP as CacheSource<K, V>
        }
    }
}
