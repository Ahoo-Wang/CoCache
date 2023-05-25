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

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import me.ahoo.cache.CacheValue

/**
 * Guava Client Side Cache .
 *
 * @author ahoo wang
 */
class GuavaClientSideCache<V>(
    private val guavaCache: Cache<String, CacheValue<V>> = CacheBuilder.newBuilder().build()
) : ClientSideCache<V> {

    override fun getCache(key: String): CacheValue<V>? {
        return guavaCache.getIfPresent(key)
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        if (value.isExpired) {
            return
        }
        guavaCache.put(key, value)
    }

    override fun evict(key: String) {
        guavaCache.invalidate(key)
    }

    override fun clear() {
        guavaCache.invalidateAll()
    }
}
