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
    fun getCache(key: K): CacheValue<V>?

    /**
     * Get the real cache value.
     *
     * @param key cache key
     * @return real cache value
     */
    operator fun get(key: K): V?

    /**
     * get cache expire at time.
     *
     * @param key cache key
     * @return when return null:cache not exist.
     */
    fun getTtlAt(key: K): Long?
}
