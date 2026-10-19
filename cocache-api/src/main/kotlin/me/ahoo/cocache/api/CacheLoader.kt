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

package me.ahoo.cocache.api

/**
 * 数据源：只负责加载，寿命由缓存策略决定。
 *
 * 加载总是在调用方线程上同步执行，事务、安全上下文、MDC 等线程上下文都可用。
 */
fun interface CacheLoader<K : Any, V : Any> {
    /**
     * 加载单个 key；`null` 表示数据源中不存在（写入负缓存）。
     */
    fun load(key: K): V?

    /**
     * 批量加载：结果中缺少的 key 视为不存在。默认逐个调用 [load]；数据源应覆盖为一次批量查询。
     */
    fun loadAll(keys: Collection<K>): Map<K, V> {
        val result = LinkedHashMap<K, V>()
        for (key in keys) {
            load(key)?.let { result[key] = it }
        }
        return result
    }
}
