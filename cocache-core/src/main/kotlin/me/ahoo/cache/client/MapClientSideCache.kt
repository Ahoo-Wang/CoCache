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

package me.ahoo.cache.client

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.client.ClientSideCache
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于 Map 的 L2 缓存：无界、过期条目仅在读取时淘汰。仅适用于测试或 key 集合有限的场景。
 *
 * @author ahoo wang
 */
class MapClientSideCache<V>(
    private val cacheMap: MutableMap<String, CacheValue<V>> = ConcurrentHashMap()
) : ClientSideCache<V> {
    override fun getCache(key: String): CacheValue<V>? {
        return cacheMap[key]
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        if (value.isExpired) {
            evict(key)
            return
        }
        cacheMap[key] = value
    }

    override val size: Long
        get() = cacheMap.size.toLong()

    override fun evict(key: String) {
        cacheMap.remove(key)
    }

    override fun clear() {
        cacheMap.clear()
    }
}
