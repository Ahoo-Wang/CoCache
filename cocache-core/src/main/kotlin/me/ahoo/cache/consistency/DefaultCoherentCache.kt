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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedEventBus
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.cache.concurrent.SingleFlight
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 两级一致性缓存的默认实现。
 *
 * 读路径：L2 → [keyFilter][CoherentCacheConfiguration.keyFilter] → 按 key 合并的（L1 → 数据源）。
 *
 * 一致性不变量：
 * - 所有失效（本地 [evict]/[setCache]、远端事件、通道重置）都先递增 [InvalidationStamps] 再淘汰副本；
 *   所有写回（L1→L2 填充、回源写回）都在取戳之后、写入前后各复核一次。
 * - 通道重置（[onReset]）清空 L2：至多一次投递的事件通道丢失的失效以此兜底。
 * - 回源成功不广播事件：L1 为空时，其它实例 L2 中的副本必然已过期或已被失效。
 */
class DefaultCoherentCache<K, V>(
    override val configuration: CoherentCacheConfiguration<K, V>,
    private val cacheEvictedEventBus: CacheEvictedEventBus
) : CoherentCache<K, V>, CacheEvictedSubscriber {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val clientSideCache = configuration.clientSideCache
    private val distributedCache = configuration.distributedCache
    private val keyConverter = configuration.keyConverter
    private val keyFilter = configuration.keyFilter
    private val cacheSource = configuration.cacheSource
    private val ttlPolicy = configuration.ttlPolicy
    private val stamps = InvalidationStamps()
    private val loads = SingleFlight<String, CacheValue<V>>()
    private val closed = AtomicBoolean(false)

    override val cacheName: String = configuration.cacheName

    override fun getCache(key: K): CacheValue<V> {
        val cacheKey = keyConverter.toStringKey(key)
        clientSideCache.getCache(cacheKey)?.let {
            if (!it.isExpired) {
                return it
            }
            clientSideCache.evict(cacheKey)
        }
        if (keyFilter.notExist(cacheKey)) {
            return ttlPolicy.missing()
        }
        return loads.execute(cacheKey) {
            loadThrough(key, cacheKey)
        }
    }

    /**
     * 由 [SingleFlight] leader 执行：同一 key 在本实例内同一时刻只有一个线程访问 L1 与数据源。
     */
    private fun loadThrough(key: K, cacheKey: String): CacheValue<V> {
        val stamp = stamps.current(cacheKey)
        distributedCache.getCache(cacheKey)?.let {
            if (!it.isExpired) {
                fillClientSide(cacheKey, it, stamp)
                return it
            }
        }
        val loaded = cacheSource.loadCacheValue(key) ?: ttlPolicy.missing()
        if (loaded.isExpired) {
            return loaded
        }
        writeBack(cacheKey, loaded, stamp)
        return loaded
    }

    private fun fillClientSide(cacheKey: String, cacheValue: CacheValue<V>, stamp: Long) {
        if (!stamps.isValid(cacheKey, stamp)) {
            return
        }
        clientSideCache.setCache(cacheKey, cacheValue)
        if (!stamps.isValid(cacheKey, stamp)) {
            clientSideCache.evict(cacheKey)
        }
    }

    private fun writeBack(cacheKey: String, cacheValue: CacheValue<V>, stamp: Long) {
        if (!stamps.isValid(cacheKey, stamp)) {
            logStaleLoadDiscarded(cacheKey)
            return
        }
        distributedCache.setCache(cacheKey, cacheValue)
        clientSideCache.setCache(cacheKey, cacheValue)
        if (!stamps.isValid(cacheKey, stamp)) {
            // 写入与失效交错：写入的值可能覆盖了失效方刚刚的淘汰，必须撤销并广播
            logStaleLoadDiscarded(cacheKey)
            invalidate(cacheKey)
        }
    }

    private fun logStaleLoadDiscarded(cacheKey: String) {
        log.warn {
            "Cache Name[$cacheName] - ClientId[$clientId] - key[$cacheKey] " +
                "- Discard the loaded value, because it was invalidated during loading."
        }
    }

    override fun set(key: K, value: V) {
        setCache(key, ttlPolicy.toCacheValue(value))
    }

    override fun setCache(key: K, value: CacheValue<V>) {
        if (value.isExpired) {
            evict(key)
            return
        }
        val cacheKey = keyConverter.toStringKey(key)
        stamps.invalidate(cacheKey)
        distributedCache.setCache(cacheKey, value)
        clientSideCache.setCache(cacheKey, value)
        publish(cacheKey)
    }

    /**
     * 失效两级缓存并广播。调用方必须先更新数据源、再调用 evict。
     */
    override fun evict(key: K) {
        invalidate(keyConverter.toStringKey(key))
    }

    private fun invalidate(cacheKey: String) {
        stamps.invalidate(cacheKey)
        clientSideCache.evict(cacheKey)
        distributedCache.evict(cacheKey)
        publish(cacheKey)
    }

    private fun publish(cacheKey: String) {
        cacheEvictedEventBus.publish(CacheEvictedEvent(cacheName, cacheKey, clientId))
    }

    override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
        if (cacheEvictedEvent.cacheName != cacheName || cacheEvictedEvent.publisherId == clientId) {
            return
        }
        log.debug {
            "Cache Name[$cacheName] - ClientId[$clientId] - onEvicted - CacheEvictedEvent:[$cacheEvictedEvent]"
        }
        stamps.invalidate(cacheEvictedEvent.key)
        clientSideCache.evict(cacheEvictedEvent.key)
    }

    override fun onReset() {
        log.info { "Cache Name[$cacheName] - ClientId[$clientId] - onReset - clear client side cache." }
        stamps.invalidateAll()
        clientSideCache.clear()
    }

    /**
     * 幂等；不中断在途回源。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        log.info { "Cache Name[$cacheName] - ClientId[$clientId] - close." }
        runCatching {
            cacheEvictedEventBus.unregister(this)
        }.onFailure {
            log.warn(it) { "Cache Name[$cacheName] - Failed to unregister from the evicted event bus." }
        }
        runCatching {
            distributedCache.close()
        }.onFailure {
            log.warn(it) { "Cache Name[$cacheName] - Failed to close the distributed cache." }
        }
    }

    override fun toString(): String {
        return "DefaultCoherentCache(cacheName='$cacheName', clientId='$clientId')"
    }
}
