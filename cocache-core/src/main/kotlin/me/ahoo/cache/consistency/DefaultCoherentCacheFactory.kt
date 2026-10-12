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

package me.ahoo.cache.consistency

import me.ahoo.cache.api.consistency.CacheEvictedEventBus

/**
 * 创建 [DefaultCoherentCache] 并注册到失效事件通道。
 *
 * @param recordClientSideHits 是否统计 L2 命中，见 [CoherentCacheStats.clientSideHitsRecorded]
 */
class DefaultCoherentCacheFactory @JvmOverloads constructor(
    private val cacheEvictedEventBus: CacheEvictedEventBus,
    private val recordClientSideHits: Boolean = false
) : CoherentCacheFactory {
    override fun <K, V> create(configuration: CoherentCacheConfiguration<K, V>): CoherentCache<K, V> {
        return DefaultCoherentCache(configuration, cacheEvictedEventBus, recordClientSideHits).also {
            cacheEvictedEventBus.register(it)
        }
    }
}
