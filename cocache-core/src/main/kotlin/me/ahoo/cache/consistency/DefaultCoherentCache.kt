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

import com.google.common.eventbus.Subscribe
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.NamedCache
import me.ahoo.cache.distributed.DistributedClientId
import me.ahoo.cache.getFirstTtlConfiguration
import java.util.concurrent.ConcurrentHashMap

/**
 * Coherent cache .
 *
 * @author ahoo wang
 */
@Suppress("LongParameterList")
class DefaultCoherentCache<K, V>(
    val config: CoherentCacheConfiguration<K, V>,
    override val cacheEvictedEventBus: CacheEvictedEventBus
) : CoherentCache<K, V>, DistributedClientId by config, NamedCache by config {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override val clientSideCache = config.clientSideCache
    override val distributedCache = config.distributedCache
    override val keyFilter = config.keyFilter
    override val keyConverter = config.keyConverter
    override val missingGuardTtl = config.missingGuardTtl
    override val missingGuardTtlAmplitude = config.missingGuardTtlAmplitude
    override val cacheSource = config.cacheSource
    override val ttl: Long = getFirstTtlConfiguration(clientSideCache, distributedCache).ttl
    override val ttlAmplitude: Long = getFirstTtlConfiguration(clientSideCache, distributedCache).ttlAmplitude
    private val keyLocks = ConcurrentHashMap<String, Any>()

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
                log.debug {
                    "Cache Name[$cacheName] - ClientId[$clientId] - get[$cacheKey] - set Client Cache."
                }
                clientSideCache.setCache(cacheKey, it)
                return it
            }
        }
        //endregion
        return null
    }

    private fun getLock(cacheKey: String): Any {
        return keyLocks.computeIfAbsent(cacheKey) {
            Object()
        }
    }

    private fun releaseLock(cacheKey: String) {
        keyLocks.remove(cacheKey)
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
         *** 细粒度锁控制并发回源 ***
         */
        val lock = getLock(cacheKey)
        synchronized(lock) {
            try {
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
                log.debug {
                    "Cache Name[$cacheName] - ClientId[$clientId] - getCache[$cacheKey] " +
                        "- Set missing guard,because no cache source was found."
                }
                /*
                 *** Fix 缓存穿透 ***
                 * 0. Db 不存在该记录
                 * 1. 穿透到 Db 回源
                 **** 缓存空值 ***
                 */
                setCache(cacheKey, DefaultCacheValue.missingGuard(missingGuardTtl, missingGuardTtlAmplitude))
                return null
            } finally {
                releaseLock(cacheKey)
            }
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
            log.debug {
                "Cache Name[$cacheName] - ClientId[$clientId] - onEvicted " +
                    "- Ignore the CacheEvictedEvent:$cacheEvictedEvent" +
                    ",because the cache name do not match:[$cacheName]"
            }
            return
        }

        if (cacheEvictedEvent.publisherId == clientId) {
            log.debug {
                "Cache Name[$cacheName] - ClientId[$clientId] - onEvicted " +
                    "- Ignore the CacheEvictedEvent:$cacheEvictedEvent" +
                    ",because it is self-published."
            }
            return
        }
        log.debug {
            "Cache Name[$cacheName] - ClientId[$clientId] - onEvicted - CacheEvictedEvent:[$cacheEvictedEvent]"
        }
        clientSideCache.evict(cacheEvictedEvent.key)
    }

    override fun toString(): String {
        return "CoherentCache(cacheName='$cacheName', clientId='$clientId')"
    }
}
