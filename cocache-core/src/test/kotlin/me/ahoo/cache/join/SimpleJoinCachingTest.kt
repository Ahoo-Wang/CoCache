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

import me.ahoo.cache.CacheValue.Companion.forever
import me.ahoo.cache.client.MapClientSideCache
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * SimpleJoinCachingTest .
 *
 * @author ahoo wang
 */
internal class SimpleJoinCachingTest {
    var orderCache = MapClientSideCache<Order>()
    var orderAddressCache = MapClientSideCache<OrderAddress>()
    var joinCaching: JoinCache<String, Order, String, OrderAddress> =
        SimpleJoinCaching(
            orderCache,
            orderAddressCache
        ) { firstValue -> firstValue.id!! }

    @Test
    fun get() {
        val order = Order()
        order.id = "1"
        val orderAddress = OrderAddress()
        orderAddress.orderId = order.id
        orderCache.setCache(order.id!!, forever(order))
        orderAddressCache.setCache(order.id!!, forever(orderAddress))
        val joinValue = joinCaching[order.id!!]
        Assertions.assertNotNull(joinValue)
        Assertions.assertEquals(order, joinValue!!.firstValue)
        Assertions.assertEquals(order.id, joinValue.joinKey)
        Assertions.assertEquals(orderAddress, joinValue.joinValue)
    }

    class Order {
        var id: String? = null
    }

    class OrderAddress {
        var orderId: String? = null
    }
}
