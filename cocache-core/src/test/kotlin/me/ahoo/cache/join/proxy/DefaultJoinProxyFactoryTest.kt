package me.ahoo.cache.join.proxy

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.CacheFactory
import me.ahoo.cache.annotation.joinCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinValue
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.join.DefaultJoinValue
import me.ahoo.cache.join.MockJoinCache
import me.ahoo.cache.join.Order
import me.ahoo.cache.join.OrderAddress
import me.ahoo.cache.test.CacheSpec
import java.util.UUID

class DefaultJoinProxyFactoryTest : CacheSpec<String, JoinValue<OrderAddress, String, Order>>() {

    override fun createCache(): JoinCache<String, OrderAddress, String, Order> {
        val cacheFactory = mockk<CacheFactory> {
            every { getCache<Cache<String, OrderAddress>>("OrderAddress") } returns MapClientSideCache()
            every { getCache<Cache<String, Order>>("Order") } returns MapClientSideCache()
        }

        val joinProxyFactory = DefaultJoinProxyFactory(cacheFactory)
        val metadata = joinCacheMetadata<MockJoinCache>()
        return joinProxyFactory.create<MockJoinCache>(metadata)
//        val orderAddress = OrderAddress(UUID.randomUUID().toString())
//        val extractKey = cache.extract(orderAddress)
//        assertEquals(extractKey, orderAddress.orderId)
    }

    override fun createCacheEntry(): Pair<String, JoinValue<OrderAddress, String, Order>> {
        val orderAddress = OrderAddress(UUID.randomUUID().toString())
        val order = Order(orderAddress.orderId)
        return UUID.randomUUID().toString() to DefaultJoinValue(orderAddress, orderAddress.orderId, order)
    }
}
