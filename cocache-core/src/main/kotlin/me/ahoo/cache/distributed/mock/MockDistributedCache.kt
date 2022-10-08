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

import me.ahoo.cache.CacheValue
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.consistency.InvalidateEvent
import me.ahoo.cache.consistency.InvalidateEventBus
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.Nonnull

/**
 * Mock Distributed Caching .
 *
 * @author ahoo wang
 */
class MockDistributedCache<V>(private val invalidateEventBus: InvalidateEventBus) : DistributedCache<V> {
    private val cacheMap: ConcurrentHashMap<String, CacheValue<V>?> = ConcurrentHashMap()

    override fun get(@Nonnull key: String): V? {
        val cacheValue = getCache(key) ?: return null
        if (cacheValue.isExpired) {
            evict(key)
            return null
        }
        return cacheValue.value
    }

    override fun getCache(key: String): CacheValue<V>? {
        return cacheMap[key]
    }

    override fun setCache(@Nonnull key: String, @Nonnull value: CacheValue<V>?) {
        cacheMap[key] = value!!
        invalidateEventBus.publish(InvalidateEvent(key, clientId))
    }

    override fun evict(@Nonnull key: String) {
        cacheMap.remove(key)
        invalidateEventBus.publish(InvalidateEvent(key, clientId))
    }

    @Throws(Exception::class)
    override fun close() {
        cacheMap.clear()
    }

    override val clientId: String
        get() = invalidateEventBus.clientId
}
