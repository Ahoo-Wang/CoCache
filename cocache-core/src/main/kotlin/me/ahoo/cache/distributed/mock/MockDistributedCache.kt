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
package me.ahoo.cache.distributed.mock

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.distributed.DistributedCache
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock Distributed Caching .
 *
 * @author ahoo wang
 */
class MockDistributedCache<V>(
    override val ttl: Long = CoCache.DEFAULT_TTL,
    override val ttlAmplitude: Long = CoCache.DEFAULT_TTL_AMPLITUDE
) : DistributedCache<V> {
    private val cacheMap: ConcurrentHashMap<String, CacheValue<V>> = ConcurrentHashMap()

    override fun getCache(key: String): CacheValue<V>? {
        return cacheMap[key]
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        if (value.isExpired) {
            return
        }
        cacheMap[key] = value
    }

    override fun evict(key: String) {
        cacheMap.remove(key)
    }

    override fun close() {
        cacheMap.clear()
    }
}
