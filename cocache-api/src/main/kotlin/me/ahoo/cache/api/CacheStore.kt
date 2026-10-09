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
 * 缓存存储层 SPI：按字符串 key 存取 [CacheValue] 的纯存储，不持有任何 TTL/负缓存策略。
 *
 * 策略（TTL、抖动、负缓存时长）由编排层决定并编码在 [CacheValue.ttlAt] 中。
 *
 * 实现约定：
 * - [setCache] 写入已过期条目时等价于 [evict]。
 * - [getCache] 可能返回已过期条目，调用方负责判断 [CacheValue.isExpired]。
 *
 * @author ahoo wang
 */
interface CacheStore<V> {
    fun getCache(key: String): CacheValue<V>?

    fun setCache(key: String, value: CacheValue<V>)

    fun evict(key: String)
}
