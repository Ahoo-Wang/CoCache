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

import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * [TtlPolicy.clientSideTtl]：L2 副本的到期时间有上界，失效事件丢失时 L2 的陈旧度仍有界（G1）。
 */
internal class DefaultCoherentCacheClientSideTtlTest {
    private val key = "key"

    private fun createCache(
        clientSideTtl: Long,
        distributedCache: DistributedCache<String> = InMemoryDistributedCache(),
        cacheSource: CacheSource<String, String> = CacheSource.noOp()
    ): CoherentCache<String, String> {
        return DefaultCoherentCacheFactory(NoOpCacheEvictedEventBus).create(
            CoherentCacheConfiguration(
                cacheName = "DefaultCoherentCacheClientSideTtlTest",
                clientId = "client-$clientSideTtl-${System.identityHashCode(distributedCache)}",
                keyConverter = ToStringKeyConverter(""),
                clientSideCache = MapClientSideCache(),
                distributedCache = distributedCache,
                cacheSource = cacheSource,
                ttlPolicy = TtlPolicy(clientSideTtl = clientSideTtl)
            )
        )
    }

    private fun CoherentCache<String, String>.clientSideTtlAt(): Long {
        return requireNotNull(configuration.clientSideCache.getCache(key)).ttlAt
    }

    private fun CoherentCache<String, String>.distributedTtlAt(): Long {
        return requireNotNull(configuration.distributedCache.getCache(key)).ttlAt
    }

    @Test
    fun writeBackCapsOnlyClientSide() {
        val cache = createCache(30, cacheSource = { CacheValue.forever("value") })
        val now = TtlAt.currentTime()

        requireNotNull(cache.getCache(key)).ttlAt.assert().isEqualTo(TtlAt.FOREVER)

        cache.distributedTtlAt().assert().isEqualTo(TtlAt.FOREVER)
        cache.clientSideTtlAt().assert().isBetween(now + 30, now + 31)
    }

    @Test
    fun fillFromDistributedCapsClientSide() {
        val distributedCache = InMemoryDistributedCache<String>()
        distributedCache.setCache(key, CacheValue.forever("value"))
        val cache = createCache(30, distributedCache)
        val now = TtlAt.currentTime()

        cache[key].assert().isEqualTo("value")

        cache.clientSideTtlAt().assert().isBetween(now + 30, now + 31)
    }

    @Test
    fun setCacheCapsOnlyClientSide() {
        val cache = createCache(30)
        val now = TtlAt.currentTime()

        cache.setCache(key, CacheValue.forever("value"))

        cache.distributedTtlAt().assert().isEqualTo(TtlAt.FOREVER)
        cache.clientSideTtlAt().assert().isBetween(now + 30, now + 31)
    }

    @Test
    fun unlimitedKeepsDistributedTtlAt() {
        val cache = createCache(0)

        cache.setCache(key, CacheValue.forever("value"))

        cache.clientSideTtlAt().assert().isEqualTo(TtlAt.FOREVER)
    }

    /**
     * 实例 B 的写入事件没有到达实例 A（NoOp 通道模拟丢失）：A 的 L2 旧值在 clientSideTtl 后过期，从 L1 读到新值。
     */
    @Test
    fun staleClientSideHealsWithoutEvictionEvent() {
        val distributedCache = InMemoryDistributedCache<String>()
        val instanceA = createCache(1, distributedCache)
        val instanceB = createCache(1, distributedCache)
        instanceB.setCache(key, CacheValue.forever("v1"))
        instanceA[key].assert().isEqualTo("v1")

        instanceB.setCache(key, CacheValue.forever("v2"))
        instanceA[key].assert().isEqualTo("v1")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (instanceA[key] != "v2" && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        instanceA[key].assert().isEqualTo("v2")
    }
}
