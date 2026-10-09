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

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CaffeineCache
import me.ahoo.cache.api.client.ClientSideCache
import java.time.Duration

/**
 * 基于 Caffeine 的 L2 缓存：有界（[CaffeineCache.DEFAULT_MAXIMUM_SIZE]）。
 *
 * 不使用条目级 `Expiry`：它会让每次读取都写节点元数据，热点 key 在多线程下无法扩展。
 * 过期条目在读取时由编排层判断并淘汰，内存由 `maximumSize` 约束。
 * 可选的 `expireAfterAccess` 同样会在每次读取时写访问时间，热点高并发场景慎用。
 *
 * @author ahoo wang
 */
class CaffeineClientSideCache<V>(
    private val caffeineCache: Cache<String, CacheValue<V>>
) : ClientSideCache<V> {

    override fun getCache(key: String): CacheValue<V>? {
        return caffeineCache.getIfPresent(key)
    }

    override fun setCache(key: String, value: CacheValue<V>) {
        if (value.isExpired) {
            evict(key)
            return
        }
        caffeineCache.put(key, value)
    }

    override val size: Long
        get() = caffeineCache.estimatedSize()

    override fun evict(key: String) {
        caffeineCache.invalidate(key)
    }

    override fun clear() {
        caffeineCache.invalidateAll()
    }

    companion object {
        /**
         * @param expireAfterAccess 空闲淘汰时长，`null` 表示不启用
         */
        @JvmStatic
        @JvmOverloads
        fun <V> build(
            maximumSize: Long = CaffeineCache.DEFAULT_MAXIMUM_SIZE,
            initialCapacity: Int = CaffeineCache.UNSET,
            expireAfterAccess: Duration? = null
        ): CaffeineClientSideCache<V> {
            require(maximumSize > 0) { "maximumSize[$maximumSize] must be positive." }
            val builder = Caffeine.newBuilder().maximumSize(maximumSize)
            if (initialCapacity != CaffeineCache.UNSET) {
                builder.initialCapacity(initialCapacity)
            }
            expireAfterAccess?.let { builder.expireAfterAccess(it) }
            return CaffeineClientSideCache(builder.build())
        }

        @JvmStatic
        fun <V> CaffeineCache.toClientSideCache(): CaffeineClientSideCache<V> {
            return build(
                maximumSize = maximumSize,
                initialCapacity = initialCapacity,
                expireAfterAccess = if (expireAfterAccess > 0) {
                    Duration.of(
                        expireAfterAccess,
                        expireUnit.toChronoUnit()
                    )
                } else {
                    null
                }
            )
        }
    }
}
