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
import com.github.benmanes.caffeine.cache.Expiry
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.annotation.CaffeineCache
import me.ahoo.cache.api.client.ClientSideCache
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 基于 Caffeine 的 L2 缓存：有界，且每个条目按自身 [CacheValue.ttlAt] 主动到期。
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
            val builder = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(TtlAtExpiry<V>(expireAfterAccess))
            if (initialCapacity != CaffeineCache.UNSET) {
                builder.initialCapacity(initialCapacity)
            }
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

/**
 * 条目在 [CacheValue.ttlAt] 到期；启用空闲淘汰时取两者较早者。
 */
internal class TtlAtExpiry<V>(expireAfterAccess: Duration?) : Expiry<String, CacheValue<V>> {
    private val expireAfterAccessNanos: Long? = expireAfterAccess?.toNanos()

    private fun remainingNanos(value: CacheValue<V>): Long {
        if (value.isForever) {
            return Long.MAX_VALUE
        }
        val remainingMillis = TimeUnit.SECONDS.toMillis(value.ttlAt) - System.currentTimeMillis()
        return TimeUnit.MILLISECONDS.toNanos(remainingMillis.coerceAtLeast(0))
    }

    private fun capByAccess(nanos: Long): Long {
        return expireAfterAccessNanos?.let { minOf(it, nanos) } ?: nanos
    }

    override fun expireAfterCreate(key: String, value: CacheValue<V>, currentTime: Long): Long {
        return capByAccess(remainingNanos(value))
    }

    override fun expireAfterUpdate(key: String, value: CacheValue<V>, currentTime: Long, currentDuration: Long): Long {
        return capByAccess(remainingNanos(value))
    }

    override fun expireAfterRead(key: String, value: CacheValue<V>, currentTime: Long, currentDuration: Long): Long {
        if (expireAfterAccessNanos == null) {
            return currentDuration
        }
        return capByAccess(remainingNanos(value))
    }
}
