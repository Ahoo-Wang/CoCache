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
import me.ahoo.cache.api.annotation.JoinCacheable
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.api.join.JoinValue
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.test.CacheSpec
import me.ahoo.cosid.jvm.UuidGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

/**
 * SimpleJoinCachingTest .
 *
 * @author ahoo wang
 */
internal class SimpleJoinCacheTest : CacheSpec<String, JoinValue<Order, String, OrderAddress>>() {

    override fun createCache(): Cache<String, JoinValue<Order, String, OrderAddress>> {
        val orderCache = MapClientSideCache<Order>()
        val orderAddressCache = MapClientSideCache<OrderAddress>()
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
        assertThat(cache[key], equalTo(value))
        val joinCache = cache as JoinCache<String, Order, String, OrderAddress>
        joinCache.evict(key, key)
        assertThat(cache[key], nullValue())
    }
}

data class Order(val id: String)

data class OrderAddress(val orderId: String)

@JoinCacheable(firstCacheName = "OrderAddress", joinCacheName = "Order")
interface MockJoinCache : JoinCache<String, OrderAddress, String, Order>
