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
    private val orderCache = MapClientSideCache<Order>()
    private val orderAddressCache = MapClientSideCache<OrderAddress>()
    private val joinCaching: JoinCache<String, Order, String, OrderAddress> =
        SimpleJoinCaching(
            orderCache,
            orderAddressCache,
        ) { firstValue -> firstValue.id }

    @Test
    fun get() {
        val order = Order("1")
        val orderAddress = OrderAddress(order.id)
        orderCache.setCache(order.id, forever(order))
        orderAddressCache.setCache(order.id, forever(orderAddress))
        val joinValue = joinCaching[order.id]
        Assertions.assertNotNull(joinValue)
        Assertions.assertEquals(order, joinValue!!.firstValue)
        Assertions.assertEquals(order.id, joinValue.joinKey)
        Assertions.assertEquals(orderAddress, joinValue.secondValue)
    }

    data class Order(val id: String)

    data class OrderAddress(val orderId: String)
}
