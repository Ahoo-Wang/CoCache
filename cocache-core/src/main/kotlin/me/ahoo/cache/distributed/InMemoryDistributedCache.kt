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

package me.ahoo.cache.distributed

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.distributed.SetElementsStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内的 [DistributedCache]：用于测试，或在同一进程内模拟共享的 L1。
 *
 * @author ahoo wang
 */
class InMemoryDistributedCache<V> : DistributedCache<V>, SetElementsStore<Any?> {
    private val cacheMap: ConcurrentHashMap<String, CacheValue<V>> = ConcurrentHashMap()

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

    override fun evict(key: String) {
        cacheMap.remove(key)
    }

    /**
     * 元素增删仅当 `V` 为 `Set` 时可用；[fillCache] / [evictIfUnchanged] 适用于任意 `V`。
     */
    override fun addElements(key: String, elements: Collection<Any?>, ttlAt: Long) {
        cacheMap.compute(key) { _, current ->
            val members = LinkedHashSet(presentMembers(current).orEmpty())
            members.addAll(elements)
            toSetValue(members, ttlAt)
        }
    }

    override fun removeElements(key: String, elements: Collection<Any?>, ttlAt: Long) {
        cacheMap.computeIfPresent(key) { _, current ->
            if (current.isExpired) {
                return@computeIfPresent null
            }
            val present = presentMembers(current) ?: return@computeIfPresent current
            val members = LinkedHashSet(present)
            members.removeAll(elements.toSet())
            if (members.isEmpty()) null else toSetValue(members, ttlAt)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun fillCache(key: String, value: CacheValue<Set<Any?>>): Boolean {
        if (value.isExpired) {
            return false
        }
        val filled = value as CacheValue<V>
        var written = false
        cacheMap.compute(key) { _, current ->
            if (current == null || current.isMissing || current.isExpired) {
                written = true
                filled
            } else {
                current
            }
        }
        return written
    }

    override fun evictIfUnchanged(key: String, value: CacheValue<Set<Any?>>) {
        cacheMap.computeIfPresent(key) { _, current ->
            val unchanged = if (value.isMissing) current.isMissing else current.value == value.value
            if (unchanged) null else current
        }
    }

    /**
     * 未过期命中值的成员；负缓存或已过期为 `null`。
     */
    private fun presentMembers(cacheValue: CacheValue<V>?): Set<*>? {
        if (cacheValue == null || cacheValue.isMissing || cacheValue.isExpired) {
            return null
        }
        return cacheValue.value as Set<*>
    }

    @Suppress("UNCHECKED_CAST")
    private fun toSetValue(members: Set<Any?>, ttlAt: Long): CacheValue<V> {
        return CacheValue.of(members as V, ttlAt)
    }
}
