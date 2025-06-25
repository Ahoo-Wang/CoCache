package me.ahoo.cache.join.proxy

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.CacheFactory
import me.ahoo.cache.annotation.joinCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinKeyExtractor
import me.ahoo.cache.api.join.JoinValue
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.join.DefaultJoinValue
import me.ahoo.cache.join.JoinKeyExtractorFactory
import me.ahoo.cache.join.MockJoinCache
import me.ahoo.cache.join.Order
import me.ahoo.cache.join.OrderAddress
import me.ahoo.cache.test.CacheSpec
import java.util.UUID

class DefaultJoinCacheProxyFactoryTest : CacheSpec<String, JoinValue<OrderAddress, String, Order>>() {
    override val missingGuard: JoinValue<OrderAddress, String, Order>
        get() = DefaultJoinValue.missingGuardValue()
    override fun createCache(): JoinCache<String, OrderAddress, String, Order> {
        val cacheFactory = mockk<CacheFactory> {
            every { getCache<Cache<String, OrderAddress>>("OrderAddress") } returns MapClientSideCache()
            every { getCache<Cache<String, Order>>("Order") } returns MapClientSideCache()
        }
        val metadata = joinCacheMetadata<MockJoinCache>()
        val joinKeyExtractorFactory = mockk<JoinKeyExtractorFactory> {
            every { create<OrderAddress, String>(metadata) } returns JoinKeyExtractor { it.orderId }
        }

        val joinProxyFactory = DefaultJoinCacheProxyFactory(cacheFactory, joinKeyExtractorFactory)
        return joinProxyFactory.create<MockJoinCache>(metadata)
    }

    override fun createCacheEntry(): Pair<String, JoinValue<OrderAddress, String, Order>> {
        val orderAddress = OrderAddress(UUID.randomUUID().toString())
        val order = Order(orderAddress.orderId)
        return UUID.randomUUID().toString() to DefaultJoinValue(orderAddress, orderAddress.orderId, order)
    }
}
