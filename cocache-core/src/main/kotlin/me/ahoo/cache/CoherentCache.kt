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
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.NamedCache
import me.ahoo.cache.api.annotation.MissingGuardCache
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.source.CacheSource
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
@Suppress("LongParameterList")
class CoherentCache<K, V>(
    val config: CoherentCacheConfiguration<K, V>,
    val cacheEvictedEventBus: CacheEvictedEventBus
) : ComputedCache<K, V>, DistributedClientId by config, NamedCache by config, CacheEvictedSubscriber {

    constructor(
        cacheName: String,
        clientId: String,
        keyConverter: KeyConverter<K>,
        clientSideCache: ClientSideCache<V> = MapClientSideCache(),
        distributedCache: DistributedCache<V>,
        cacheSource: CacheSource<K, V> = CacheSource.noOp(),
        keyFilter: KeyFilter = NoOpKeyFilter,
        missingGuardTtl: Long = MissingGuardCache.DEFAULT_TTL_SECONDS,
        missingGuardTtlAmplitude: Long = MissingGuardCache.DEFAULT_TTL_AMPLITUDE_SECONDS,
        cacheEvictedEventBus: CacheEvictedEventBus
    ) : this(
        CoherentCacheConfiguration(
            cacheName = cacheName,
            clientId = clientId,
            keyConverter = keyConverter,
            clientSideCache = clientSideCache,
            distributedCache = distributedCache,
            cacheSource = cacheSource,
            keyFilter = keyFilter,
            missingGuardTtl = missingGuardTtl,
            missingGuardTtlAmplitude = missingGuardTtlAmplitude
        ),
        cacheEvictedEventBus = cacheEvictedEventBus,
    )

    companion object {
        private val log = LoggerFactory.getLogger(CoherentCache::class.java)
    }

    val clientSideCache = config.clientSideCache
    val distributedCache = config.distributedCache
    val keyFilter = config.keyFilter
    val keyConverter = config.keyConverter
    val missingGuardTtl = config.missingGuardTtl
    val missingGuardTtlAmplitude = config.missingGuardTtlAmplitude
    val cacheSource = config.cacheSource

    @Suppress("ReturnCount")
    private fun getL2Cache(cacheKey: String): CacheValue<V>? {
        //region L2
        clientSideCache.getCache(cacheKey)?.let {
            if (it.isExpired.not()) {
                return it
            } else {
                clientSideCache.evict(cacheKey)
            }
        }

        //endregion
        if (keyFilter.notExist(cacheKey)) {
            return DefaultCacheValue.missingGuard(missingGuardTtl, missingGuardTtlAmplitude)
        }
        //region L1
        distributedCache.getCache(cacheKey)?.let {
            if (it.isExpired.not()) {
                if (log.isDebugEnabled) {
                    log.debug(
                        "Cache Name[{}] - ClientId[{}] - get[{}] - set Client Cache.",
                        cacheName,
                        clientId,
                        cacheKey
                    )
                }
                clientSideCache.setCache(cacheKey, it)
                return it
            }
        }
        //endregion
        return null
    }

    @Suppress("ReturnCount")
    override fun getCache(key: K): CacheValue<V>? {
        val cacheKey = keyConverter.toStringKey(key)
        getL2Cache(cacheKey)?.let {
            return it
        }

        /*
         *** Fix 缓存击穿 ***
         * 0. Db 存在该记录
         * 1. 并发获取缓存时导致的多次回源问题
         *** 应用级锁控制并发回源 ***
         */
        synchronized(this) {
            getL2Cache(cacheKey)?.let {
                return it
            }

            //region L0:Cache Source
            /*
             * This is a heavy-duty operation.
             */
            cacheSource.loadCacheValue(key)?.let {
                setCache(cacheKey, it)
                cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
                return it
            }

            //endregion
            if (log.isDebugEnabled) {
                log.debug(
                    "Cache Name[{}] - ClientId[{}] - getCache[{}] " +
                        "- Set missing guard,because no cache source was found.",
                    cacheName,
                    clientId,
                    cacheKey
                )
            }
            /*
             *** Fix 缓存穿透 ***
             * 0. Db 不存在该记录
             * 1. 穿透到 Db 回源
             **** 缓存空值 ***
             */
            setCache(cacheKey, DefaultCacheValue.missingGuard(missingGuardTtl, missingGuardTtlAmplitude))
            return null
        }
    }

    private fun setCache(cacheKey: String, cacheValue: CacheValue<V>) {
        clientSideCache.setCache(cacheKey, cacheValue)
        distributedCache.setCache(cacheKey, cacheValue)
    }

    override fun setCache(key: K, value: CacheValue<V>) {
        if (value.isExpired) {
            return
        }
        val cacheKey = keyConverter.toStringKey(key)
        setCache(cacheKey, value)
        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
    }

    override fun evict(key: K) {
        val cacheKey = keyConverter.toStringKey(key)
        clientSideCache.evict(cacheKey)
        distributedCache.evict(cacheKey)
        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
    }

    @Subscribe
    override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
        if (cacheEvictedEvent.cacheName != cacheName) {
            if (log.isDebugEnabled) {
                log.debug(
                    "Cache Name[{}] - ClientId[{}] - onEvicted " +
                        "- Ignore the CacheEvictedEvent:{}" +
                        ",because the cache name do not match:[{}]",
                    cacheName,
                    clientId,
                    cacheEvictedEvent,
                    cacheName,
                )
            }
            return
        }

        if (cacheEvictedEvent.publisherId == clientId) {
            if (log.isDebugEnabled) {
                log.debug(
                    "Cache Name[{}] - ClientId[{}] - onEvicted " +
                        "- Ignore the CacheEvictedEvent:{} " +
                        "because it is self-published.",
                    cacheName,
                    clientId,
                    cacheEvictedEvent,
                )
            }
            return
        }
        if (log.isDebugEnabled) {
            log.debug(
                "Cache Name[{}] - ClientId[{}] - onEvicted - CacheEvictedEvent:[{}]",
                cacheName,
                clientId,
                cacheEvictedEvent,
            )
        }
        clientSideCache.evict(cacheEvictedEvent.key)
    }

    override fun toString(): String {
        return "CoherentCache(cacheName='$cacheName', clientId='$clientId')"
    }
}
