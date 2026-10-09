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
 * Cache Setter .
 *
 * @author ahoo wang
 */
interface CacheSetter<K, V> {
    /**
     * 写入缓存条目；已过期的条目等价于 [evict]。
     */
    fun setCache(key: K, value: CacheValue<V>)

    /**
     * 以绝对到期时间写入；`null` 值写为负缓存。
     */
    operator fun set(key: K, ttlAt: Long, value: V) {
        setCache(key, CacheValue.of(value, ttlAt))
    }

    /**
     * 按缓存自身的 TTL 策略写入；`null` 值写为负缓存。
     */
    operator fun set(key: K, value: V)

    fun evict(key: K)
}
