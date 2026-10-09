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

package me.ahoo.cache.join

import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.annotation.JoinCacheable
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinValue
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.foreverSource
import me.ahoo.cache.inMemoryCoherentCache
import me.ahoo.cache.test.CacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.*

internal class SimpleJoinCacheTest : CacheSpec<String, JoinValue<Order, String, OrderAddress>>() {

    private lateinit var orderCache: CoherentCache<String, Order>
    private lateinit var orderAddressCache: CoherentCache<String, OrderAddress>
    private val joinCache: JoinCache<String, Order, String, OrderAddress>
        get() = cache as JoinCache<String, Order, String, OrderAddress>

    override fun createCache(): Cache<String, JoinValue<Order, String, OrderAddress>> {
        orderCache = inMemoryCoherentCache()
        orderAddressCache = inMemoryCoherentCache()
        return SimpleJoinCache(orderCache, orderAddressCache) { it.id }
    }

    override fun createCacheEntry(): Pair<String, JoinValue<Order, String, OrderAddress>> {
        val orderId = UUID.randomUUID().toString()
        return orderId to JoinValue(Order(orderId), orderId, OrderAddress(orderId))
    }

    @Test
    fun getWithoutSecondValueKeepsFirstValue() {
        val orderId = UUID.randomUUID().toString()
        orderCache[orderId] = Order(orderId)

        val actual = requireNotNull(cache[orderId])

        actual.firstValue.assert().isEqualTo(Order(orderId))
        actual.joinKey.assert().isEqualTo(orderId)
        actual.secondValue.assert().isNull()
    }

    @Test
    fun getWithMissingSecondValueUsesEarlierTtlAt() {
        val orderId = UUID.randomUUID().toString()
        orderCache.setCache(orderId, CacheValue.forever(Order(orderId)))
        val secondMissing = CacheValue.missing<OrderAddress>(TtlAt.at(5))
        orderAddressCache.setCache(orderId, secondMissing)

        val actual = requireNotNull(cache.getCache(orderId))

        actual.ttlAt.assert().isEqualTo(secondMissing.ttlAt)
        actual.value?.secondValue.assert().isNull()
    }

    @Test
    fun getWithMissingFirstValueIsMissing() {
        val orderId = UUID.randomUUID().toString()
        val missing = CacheValue.missing<Order>(TtlAt.at(5))
        orderCache.setCache(orderId, missing)

        val actual = requireNotNull(cache.getCache(orderId))

        actual.isMissing.assert().isTrue()
        actual.ttlAt.assert().isEqualTo(missing.ttlAt)
    }

    @Test
    fun getLoadsComponentsThroughTheirSources() {
        val orderId = UUID.randomUUID().toString()
        val cache = SimpleJoinCache(
            inMemoryCoherentCache(foreverSource(orderId to Order(orderId))),
            inMemoryCoherentCache(foreverSource(orderId to OrderAddress(orderId)))
        ) { it.id }

        cache[orderId].assert().isEqualTo(JoinValue(Order(orderId), orderId, OrderAddress(orderId)))
    }

    @Test
    fun setWithoutSecondValueDoesNotPopulateJoinCache() {
        val orderId = UUID.randomUUID().toString()
        cache.setCache(orderId, CacheValue.forever(JoinValue(Order(orderId), orderId, null)))

        orderCache[orderId].assert().isEqualTo(Order(orderId))
        orderAddressCache.getCache(orderId)?.isMissing.assert().isTrue()
    }

    @Test
    fun evictOnlyEvictsFirstCache() {
        val (key, value) = createCacheEntry()
        cache[key] = value

        cache.evict(key)

        orderCache[key].assert().isNull()
        orderAddressCache[key].assert().isEqualTo(value.secondValue)
    }

    @Test
    fun evictBoth() {
        val (key, value) = createCacheEntry()
        cache[key] = value

        joinCache.evict(key, value.joinKey)

        orderCache[key].assert().isNull()
        orderAddressCache[value.joinKey].assert().isNull()
    }
}

/**
 * 直接返回预置条目的缓存（可包含已过期条目），用于覆盖过期分支。
 */
private class FixedCache<V>(private val cacheValue: CacheValue<V>?) : Cache<String, V> {
    var written: CacheValue<V>? = null
    override fun getCache(key: String): CacheValue<V>? = cacheValue
    override fun setCache(key: String, value: CacheValue<V>) {
        written = value
    }

    override fun set(key: String, value: V) {
        written = CacheValue.forever(value)
    }

    override fun evict(key: String) = Unit
}

internal class SimpleJoinCacheExpiryTest {
    private val orderId = UUID.randomUUID().toString()

    @Test
    fun expiredFirstValueIsMiss() {
        val cache = SimpleJoinCache(
            FixedCache(CacheValue.of(Order(orderId), TtlAt.at(-5))),
            FixedCache<OrderAddress>(null)
        ) { it.id }
        cache.getCache(orderId).assert().isNull()
    }

    @Test
    fun expiredSecondValueIsTreatedAsAbsent() {
        val firstTtlAt = TtlAt.at(60)
        val cache = SimpleJoinCache(
            FixedCache(CacheValue.of(Order(orderId), firstTtlAt)),
            FixedCache(CacheValue.of(OrderAddress(orderId), TtlAt.at(-5)))
        ) { it.id }

        val actual = requireNotNull(cache.getCache(orderId))

        actual.value?.secondValue.assert().isNull()
        actual.ttlAt.assert().isEqualTo(firstTtlAt)
    }

    @Test
    fun absentSecondValueKeepsFirstTtlAt() {
        val firstTtlAt = TtlAt.at(60)
        val cache = SimpleJoinCache(
            FixedCache(CacheValue.of(Order(orderId), firstTtlAt)),
            FixedCache<OrderAddress>(null)
        ) { it.id }

        val actual = requireNotNull(cache.getCache(orderId))

        actual.value?.secondValue.assert().isNull()
        actual.ttlAt.assert().isEqualTo(firstTtlAt)
    }

    @Test
    fun setWithoutSecondValueOnlyWritesFirst() {
        val first = FixedCache<Order>(null)
        val second = FixedCache<OrderAddress>(null)
        val cache = SimpleJoinCache(first, second) { it.id }

        cache[orderId] = JoinValue(Order(orderId), orderId, null)

        first.written?.value.assert().isEqualTo(Order(orderId))
        second.written.assert().isNull()
    }
}

data class Order(val id: String)

data class OrderAddress(val orderId: String)

@JoinCacheable(firstCacheName = "OrderAddress", joinCacheName = "Order")
interface MockJoinCache : JoinCache<String, OrderAddress, String, Order>
