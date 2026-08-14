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
import com.google.common.util.concurrent.Striped
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.NamedCache
import me.ahoo.cache.distributed.DistributedClientId
import me.ahoo.cache.getFirstTtlConfiguration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock

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

        /**
         * Striped 锁数量：固定且永不回收，从根上消除"锁对象回收"竞态。
         * 不同 key 哈希到同一 stripe 时会被串行化（仅影响吞吐、不影响正确性）。
         */
        private const val KEY_LOCK_STRIPES = 1024
    }

    override val clientSideCache = config.clientSideCache
    override val distributedCache = config.distributedCache
    override val keyFilter = config.keyFilter
    override val keyConverter = config.keyConverter
    override val cacheSource = config.cacheSource
    private val ttlConfiguration = getFirstTtlConfiguration(clientSideCache, distributedCache)
    override val ttl: Long = ttlConfiguration.ttl
    override val ttlAmplitude: Long = ttlConfiguration.ttlAmplitude
    private val keyLocks: Striped<Lock> = Striped.lock(KEY_LOCK_STRIPES)
    private val closed = AtomicBoolean(false)

    /**
     * 回源在途的 per-key 失效代际。仅在回源临界区内存在条目（登记于回源前、移除于临界区 finally），
     * 不随 key 数量累积。onEvicted 收到其它实例事件时自增，使在途回源在写回前后检测到失效。
     * 不支持同 key 重入回源（CacheSource 内部再次 getCache 相同 key）：内层 finally 会移除条目，使外层判定失效（退化为过度淘汰，不产生脏数据）。
     */
    private val loadGenerations = ConcurrentHashMap<String, Long>()

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
            return DefaultCacheValue.missingGuard(ttl, ttlAmplitude)
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
        val lock = keyLocks.get(cacheKey)
        lock.lock()
        try {
            getL2Cache(cacheKey)?.let {
                return it
            }

            val generation = loadGenerations.merge(cacheKey, 1L, Long::plus)!!
            try {
                //region L0:Cache Source
                /*
                 * This is a heavy-duty operation.
                 */
                cacheSource.loadCacheValue(key)?.let { cacheValue ->
                    if (isLoadInvalidated(cacheKey, generation)) {
                        logStaleLoadDiscarded(cacheKey)
                        return cacheValue
                    }
                    setCache(cacheKey, cacheValue)
                    if (isLoadInvalidated(cacheKey, generation)) {
                        logStaleLoadDiscarded(cacheKey)
                        evict(key)
                        return cacheValue
                    }
                    cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
                    return cacheValue
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
                if (isLoadInvalidated(cacheKey, generation)) {
                    logStaleLoadDiscarded(cacheKey)
                    return null
                }
                setCache(cacheKey, DefaultCacheValue.missingGuard(ttl, ttlAmplitude))
                if (isLoadInvalidated(cacheKey, generation)) {
                    logStaleLoadDiscarded(cacheKey)
                    evict(key)
                }
                return null
            } finally {
                loadGenerations.remove(cacheKey)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun isLoadInvalidated(cacheKey: String, generation: Long): Boolean {
        // 条目仅可能被本线程在持锁 finally 中移除（同 key 同 stripe 锁），故 null 视为已失效——保守方向
        return loadGenerations[cacheKey] != generation
    }

    private fun logStaleLoadDiscarded(cacheKey: String) {
        log.warn {
            "Cache Name[$cacheName] - ClientId[$clientId] - getCache[$cacheKey] " +
                "- Discard the load result,because it was invalidated by an eviction event during loading."
        }
    }

    private fun setCache(cacheKey: String, cacheValue: CacheValue<V>) {
        if (cacheValue.isExpired) {
            clientSideCache.evict(cacheKey)
            distributedCache.evict(cacheKey)
            return
        }
        clientSideCache.setCache(cacheKey, cacheValue)
        distributedCache.setCache(cacheKey, cacheValue)
    }

    override fun setCache(key: K, value: CacheValue<V>) {
        if (value.isExpired) {
            evict(key)
            return
        }
        val cacheKey = keyConverter.toStringKey(key)
        setCache(cacheKey, value)
        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
    }

    /** 失效两级缓存并广播事件。调用方必须先更新数据源、再调用 evict（发布方语义依赖该顺序）。 */
    override fun evict(key: K) {
        val cacheKey = keyConverter.toStringKey(key)
        clientSideCache.evict(cacheKey)
        distributedCache.evict(cacheKey)
        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
    }

    /**
     * 幂等（重复调用为无操作）且协作式：不中断在途回源。
     * clientSideCache 不需要关闭（Guava/Caffeine/Map 实现均无 close 语义）。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        log.info { "Cache Name[$cacheName] - ClientId[$clientId] - close." }
        runCatching {
            cacheEvictedEventBus.unregister(this)
        }.onFailure {
            log.warn(
                it
            ) { "Cache Name[$cacheName] - ClientId[$clientId] - Failed to unregister from the evicted event bus." }
        }
        runCatching {
            distributedCache.close()
        }.onFailure {
            log.warn(it) { "Cache Name[$cacheName] - ClientId[$clientId] - Failed to close the distributed cache." }
        }
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
        loadGenerations.computeIfPresent(cacheEvictedEvent.key) { _, generation ->
            generation + 1
        }
        clientSideCache.evict(cacheEvictedEvent.key)
    }

    override fun toString(): String {
        return "CoherentCache(cacheName='$cacheName', clientId='$clientId')"
    }
}
