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

import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.api.consistency.CacheEvictedEvent
import me.ahoo.cache.api.consistency.CacheEvictedSubscriber
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 确定性地覆盖 [DefaultCoherentCache] 中依赖时序的分支：过期副本回落，以及“写入之后、复核之前”到达的失效。
 *
 * 通过在存储层 setCache 返回前注入失效，模拟失效恰好落在写回窗口内的交错。
 */
internal class DefaultCoherentCacheInvariantTest {
    private val cacheName = "DefaultCoherentCacheInvariantTest"
    private val key = "key"

    /**
     * L2 存储：可在写入后执行钩子，模拟失效与写回交错。
     */
    private class HookedClientSideCache(
        val backing: MutableMap<String, CacheValue<String>> = ConcurrentHashMap(),
        private val delegate: ClientSideCache<String> = MapClientSideCache(backing)
    ) : ClientSideCache<String> by delegate {
        @Volatile
        var afterSet: (String) -> Unit = {}

        @Volatile
        var afterEvict: (String) -> Unit = {}

        override fun setCache(key: String, value: CacheValue<String>) {
            delegate.setCache(key, value)
            afterSet(key)
        }

        override fun evict(key: String) {
            delegate.evict(key)
            afterEvict(key)
        }
    }

    /**
     * L1 存储：允许直接放入已过期条目（InMemoryDistributedCache 会拒绝写入过期值）。
     */
    private class RawDistributedCache(
        val backing: MutableMap<String, CacheValue<String>> = ConcurrentHashMap()
    ) : DistributedCache<String> {
        override fun getCache(key: String): CacheValue<String>? = backing[key]
        override fun setCache(key: String, value: CacheValue<String>) {
            backing[key] = value
        }

        override fun evict(key: String) {
            backing.remove(key)
        }
    }

    private fun createCache(
        clientSideCache: ClientSideCache<String>,
        distributedCache: DistributedCache<String>,
        cacheSource: CacheSource<String, String>,
        eventBus: LocalCacheEvictedEventBus = LocalCacheEvictedEventBus()
    ): DefaultCoherentCache<String, String> {
        return DefaultCoherentCacheFactory(eventBus).create(
            CoherentCacheConfiguration(
                cacheName = cacheName,
                clientId = "self",
                keyConverter = ToStringKeyConverter(""),
                distributedCache = distributedCache,
                clientSideCache = clientSideCache,
                cacheSource = cacheSource
            )
        ) as DefaultCoherentCache<String, String>
    }

    @Test
    fun expiredClientSideEntryIsEvictedAndReloadedFromDistributed() {
        val clientSideCache = HookedClientSideCache()
        val distributedCache = InMemoryDistributedCache<String>()
        distributedCache.setCache(key, CacheValue.forever("fresh"))
        val cache = createCache(clientSideCache, distributedCache, CacheSource.noOp())
        // 注册时的 onReset 会清空 L2，因此在创建之后再放入过期条目
        clientSideCache.backing[key] = CacheValue.of("stale", TtlAt.at(-5))
        val evicted = AtomicInteger()
        clientSideCache.afterEvict = { evicted.incrementAndGet() }

        cache[key].assert().isEqualTo("fresh")
        evicted.get().assert().isOne()
        clientSideCache.backing[key]?.value.assert().isEqualTo("fresh")
    }

    @Test
    fun expiredDistributedEntryFallsThroughToSource() {
        val distributedCache = RawDistributedCache()
        distributedCache.backing[key] = CacheValue.of("stale", TtlAt.at(-5))
        val sourceCalls = AtomicInteger()
        val cache = createCache(
            clientSideCache = MapClientSideCache(),
            distributedCache = distributedCache,
            cacheSource = CacheSource {
                sourceCalls.incrementAndGet()
                CacheValue.forever("fresh")
            }
        )

        cache[key].assert().isEqualTo("fresh")
        sourceCalls.get().assert().isOne()
        distributedCache.backing[key]?.value.assert().isEqualTo("fresh")
    }

    @Test
    fun invalidationRightAfterClientSideFillUndoesTheFill() {
        val clientSideCache = HookedClientSideCache()
        val distributedCache = InMemoryDistributedCache<String>()
        distributedCache.setCache(key, CacheValue.forever("value"))
        val cache = createCache(clientSideCache, distributedCache, CacheSource.noOp())
        clientSideCache.afterSet = { cache.onEvicted(CacheEvictedEvent(cacheName, it, "remote")) }

        cache[key].assert().isEqualTo("value")

        clientSideCache.backing[key].assert().isNull()
        distributedCache.getCache(key)?.value.assert().isEqualTo("value")
    }

    @Test
    fun invalidationRightAfterWriteBackUndoesBothLevelsAndBroadcasts() {
        val clientSideCache = HookedClientSideCache()
        val distributedCache = InMemoryDistributedCache<String>()
        val eventBus = LocalCacheEvictedEventBus()
        val cache =
            createCache(clientSideCache, distributedCache, CacheSource { CacheValue.forever("loaded") }, eventBus)
        val published = CopyOnWriteArrayList<CacheEvictedEvent>()
        eventBus.register(object : CacheEvictedSubscriber {
            override val cacheName: String = this@DefaultCoherentCacheInvariantTest.cacheName
            override fun onEvicted(cacheEvictedEvent: CacheEvictedEvent) {
                published.add(cacheEvictedEvent)
            }

            override fun onReset() = Unit
        })
        clientSideCache.afterSet = { cache.onEvicted(CacheEvictedEvent(cacheName, it, "remote")) }

        cache.getCache(key).value.assert().isEqualTo("loaded")

        clientSideCache.backing[key].assert().isNull()
        distributedCache.getCache(key).assert().isNull()
        published.map { it.key to it.publisherId }.assert().contains(key to "self")
    }
}
