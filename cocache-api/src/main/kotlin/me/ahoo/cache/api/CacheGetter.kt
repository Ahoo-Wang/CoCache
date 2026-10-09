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

package me.ahoo.cache.api

/**
 * Cache Getter .
 *
 * @author ahoo wang
 */
interface CacheGetter<K, V> {
    /**
     * 获取缓存条目（可能为负缓存）。`null` 表示缓存与数据源中均无记录。
     */
    fun getCache(key: K): CacheValue<V>?

    /**
     * 获取缓存值：未命中、负缓存或已过期均返回 `null`。
     */
    operator fun get(key: K): V? {
        return getCache(key)?.takeUnless { it.isExpired }?.value
    }

    /**
     * 获取缓存值的绝对到期时间：未命中、负缓存或已过期均返回 `null`。
     */
    fun getTtlAt(key: K): Long? {
        return getCache(key)?.takeUnless { it.isMissing || it.isExpired }?.ttlAt
    }
}
