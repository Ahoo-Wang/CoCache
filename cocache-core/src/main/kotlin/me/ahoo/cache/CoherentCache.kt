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
package me.ahoo.cache

import com.google.common.eventbus.Subscribe
import me.ahoo.cache.CacheValue.Companion.missingGuard
import me.ahoo.cache.client.ClientSideCache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CacheEvictedEvent
import me.ahoo.cache.consistency.CacheEvictedEventBus
import me.ahoo.cache.consistency.CacheEvictedSubscriber
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedClientId
import me.ahoo.cache.filter.NoOpKeyFilter
import org.slf4j.LoggerFactory

/**
 * Coherent cache .
 *
 * @author ahoo wang
 */
class CoherentCache<K, V>(
    override val cacheName: String,
    override val clientId: String,
    val keyConverter: KeyConverter<K>,
    val distributedCaching: DistributedCache<V>,
    val clientSideCaching: ClientSideCache<V> = MapClientSideCache(),
    private val cacheEvictedEventBus: CacheEvictedEventBus,
    private val cacheSource: CacheSource<K, V> = CacheSource.noOp(),
    private val keyFilter: KeyFilter = NoOpKeyFilter
) : Cache<K, V>, DistributedClientId, CacheEvictedSubscriber {
    companion object {
        private val log = LoggerFactory.getLogger(CoherentCache::class.java)
    }

    private fun getL2Cache(cacheKey: String): CacheValue<V>? {
        //region L2
        var cacheValue = clientSideCaching.getCache(cacheKey)
        if (null != cacheValue) {
            return cacheValue
        }
        //endregion
        if (keyFilter.notExist(cacheKey)) {
            return missingGuard()
        }
        //region L1
        cacheValue = distributedCaching.getCache(cacheKey)
        if (null != cacheValue) {
            clientSideCaching.setCache(cacheKey, cacheValue)
            return cacheValue
        }
        //endregion
        return null
    }

    override fun getCache(key: K): CacheValue<V>? {
        val cacheKey = keyConverter.asKey(key)
        var cacheValue = getL2Cache(cacheKey)
        if (null != cacheValue) {
            return if (cacheValue.isMissingGuard) {
                null
            } else {
                cacheValue
            }
        }

        /*
         *** Fix 缓存击穿 ***
         * 0. Db 存在该记录
         * 1. 并发获取缓存时导致的多次回源问题
         *** 应用级锁控制并发回源 ***
         */
        synchronized(this) {
            cacheValue = getL2Cache(cacheKey)
            if (null != cacheValue) {
                return if (cacheValue!!.isMissingGuard) {
                    null
                } else {
                    cacheValue
                }
            }
            //region L0:Cache Source
            /*
             * This is a heavy-duty operation.
             */
            val sourceCache = cacheSource.load(key)
            if (null != sourceCache) {
                setCache(cacheKey, sourceCache)
                return sourceCache
            }
            //endregion
            /*
             *** Fix 缓存穿透 ***
             * 0. Db 不存在该记录
             * 1. 穿透到 Db 回源
             **** 缓存空值 ***
             */
            setCache(cacheKey, missingGuard())
            return null
        }
    }

    private fun setCache(cacheKey: String, cacheValue: CacheValue<V>) {
        clientSideCaching.setCache(cacheKey, cacheValue)
        distributedCaching.setCache(cacheKey, cacheValue)
    }

    override fun setCache(key: K, value: CacheValue<V>) {
        val cacheKey = keyConverter.asKey(key)
        setCache(cacheKey, value)
        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
    }

    override fun evict(key: K) {
        val cacheKey = keyConverter.asKey(key)
        clientSideCaching.evict(cacheKey)
        distributedCaching.evict(cacheKey)
        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
    }

    @Subscribe
    override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
        if (cacheEvictedEvent.cacheName != cacheName) {
            if (log.isDebugEnabled) {
                log.debug(
                    "Cache Name[{}] - ClientId[{}] - onEvicted - " +
                        "Ignore the CacheEvictedEvent:{}" +
                        ",because the cache name do not match:[{}]",
                    cacheName,
                    clientId,
                    cacheEvictedEvent,
                    cacheName
                )
            }
            return
        }

        if (cacheEvictedEvent.publisherId == clientId) {
            if (log.isDebugEnabled) {
                log.debug(
                    "Cache Name[{}] - ClientId[{}] - onEvicted - " +
                        "Ignore the CacheEvictedEvent:{} " +
                        "because it is self-published.",
                    cacheName,
                    clientId,
                    cacheEvictedEvent
                )
            }
            return
        }
        if (log.isDebugEnabled) {
            log.debug(
                "Cache Name[{}] - ClientId[{}] - onEvicted - CacheEvictedEvent:[{}]",
                cacheName,
                clientId,
                cacheEvictedEvent
            )
        }
        clientSideCaching.evict(cacheEvictedEvent.key)
    }
}
