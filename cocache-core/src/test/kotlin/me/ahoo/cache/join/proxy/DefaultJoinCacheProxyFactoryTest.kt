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

package me.ahoo.cache.join.proxy

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.CacheFactory
import me.ahoo.cache.annotation.joinCacheMetadata
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinKeyExtractor
import me.ahoo.cache.api.join.JoinValue
import me.ahoo.cache.inMemoryCoherentCache
import me.ahoo.cache.join.JoinKeyExtractorFactory
import me.ahoo.cache.join.MockJoinCache
import me.ahoo.cache.join.Order
import me.ahoo.cache.join.OrderAddress
import me.ahoo.cache.proxy.CacheDelegated
import me.ahoo.cache.test.CacheSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.*

class DefaultJoinCacheProxyFactoryTest : CacheSpec<String, JoinValue<OrderAddress, String, Order>>() {
    private val metadata = joinCacheMetadata<MockJoinCache>()

    override fun createCache(): JoinCache<String, OrderAddress, String, Order> {
        val cacheFactory = mockk<CacheFactory> {
            every { getCache<Cache<String, OrderAddress>>("OrderAddress") } returns inMemoryCoherentCache()
            every { getCache<Cache<String, Order>>("Order") } returns inMemoryCoherentCache()
        }
        val joinKeyExtractorFactory = mockk<JoinKeyExtractorFactory> {
            every { create<OrderAddress, String>(metadata) } returns JoinKeyExtractor { it.orderId }
        }
        return DefaultJoinCacheProxyFactory(cacheFactory, joinKeyExtractorFactory).create<MockJoinCache>(metadata)
    }

    override fun createCacheEntry(): Pair<String, JoinValue<OrderAddress, String, Order>> {
        val orderAddress = OrderAddress(UUID.randomUUID().toString())
        return UUID.randomUUID().toString() to JoinValue(orderAddress, orderAddress.orderId, Order(orderAddress.orderId))
    }

    @Test
    fun exposesMetadataAndDelegate() {
        (cache as JoinCacheMetadataCapable).cacheMetadata.assert().isEqualTo(metadata)
        (cache as CacheDelegated<*>).delegate.assert().isNotNull()
    }

    @Test
    fun missingComponentCacheFailsFast() {
        val cacheFactory = mockk<CacheFactory> {
            every { getCache<Cache<String, OrderAddress>>(any<String>()) } returns null
        }
        runCatching {
            DefaultJoinCacheProxyFactory(cacheFactory, mockk()).create<MockJoinCache>(metadata)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
