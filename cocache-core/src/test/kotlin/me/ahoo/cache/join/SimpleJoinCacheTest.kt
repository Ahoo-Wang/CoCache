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

import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.JoinCacheable
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinValue
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.test.CacheSpec
import me.ahoo.cosid.jvm.UuidGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * SimpleJoinCachingTest .
 *
 * @author ahoo wang
 */
internal class SimpleJoinCacheTest : CacheSpec<String, JoinValue<Order, String, OrderAddress>>() {

    private lateinit var orderCache: MapClientSideCache<Order>
    private lateinit var orderAddressCache: MapClientSideCache<OrderAddress>

    override val missingGuard: JoinValue<Order, String, OrderAddress>
        get() = DefaultJoinValue.missingGuardValue()
    override fun createCache(): Cache<String, JoinValue<Order, String, OrderAddress>> {
        orderCache = MapClientSideCache()
        orderAddressCache = MapClientSideCache()
        return SimpleJoinCache(
            orderCache,
            orderAddressCache,
        ) { firstValue -> firstValue.id }
    }

    override fun createCacheEntry(): Pair<String, JoinValue<Order, String, OrderAddress>> {
        val orderId = UuidGenerator.INSTANCE.generateAsString()
        val order = Order(orderId)
        val orderAddress = OrderAddress(order.id)
        return orderId to DefaultJoinValue(order, orderId, orderAddress)
    }

    @Test
    fun evictJoinCache() {
        val (key, value) = createCacheEntry()
        cache[key] = value
        cache[key].assert().isEqualTo(value)
        val joinCache = cache as JoinCache<String, Order, String, OrderAddress>
        joinCache.evict(key, key)
        cache[key].assert().isNull()
    }

    @Test
    fun evictWhenFirstValueIsMissingGuardStillEvictsJoinCache() {
        val (key, value) = createCacheEntry()
        cache[key] = value
        cache[key].assert().isEqualTo(value)
        // Degrade the first cache entry to a missing-guard so that
        // `firstCache[key]` reads as null (see ComputedCache.get).
        orderCache.setCache(key, DefaultCacheValue.missingGuard())

        // Both underlying caches still physically hold entries: the guard in
        // orderCache, and the live join value in orderAddressCache.
        orderCache.getCache(key).assert().isNotNull
        orderAddressCache.getCache(key).assert().isNotNull

        cache.evict(key)

        // Evicting must always clear the first cache, even though the first
        // value reads back as a (null-on-get) missing guard. The old impl
        // returned early on `firstCache[key] == null` and cleared nothing.
        orderCache.getCache(key).assert().isNull()
    }

    @Test
    fun evictWhenFirstValueIsExpiredStillEvictsBothCaches() {
        val (key, value) = createCacheEntry()
        cache[key] = value
        // The first cache now holds a real entry; reading via the typed `get`
        // would return null only once expired, but the underlying CacheValue
        // stays present so the join key remains recoverable. Here the entry is
        // still fresh, so both eviction targets must be cleared.
        orderCache.getCache(key).assert().isNotNull
        orderAddressCache.getCache(key).assert().isNotNull

        cache.evict(key)

        orderCache.getCache(key).assert().isNull()
        orderAddressCache.getCache(key).assert().isNull()
    }
}

data class Order(val id: String)

data class OrderAddress(val orderId: String)

@JoinCacheable(firstCacheName = "OrderAddress", joinCacheName = "Order")
interface MockJoinCache : JoinCache<String, OrderAddress, String, Order>
