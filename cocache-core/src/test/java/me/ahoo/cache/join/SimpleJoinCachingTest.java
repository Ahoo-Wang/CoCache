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

package me.ahoo.cache.join;

import me.ahoo.cache.CacheValue;
import me.ahoo.cache.client.MapClientSideCache;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

/**
 * SimpleJoinCachingTest .
 *
 * @author ahoo wang
 */
class SimpleJoinCachingTest {
    MapClientSideCache<Order> orderCache = new MapClientSideCache<>();
    MapClientSideCache<OrderAddress> orderAddressCache = new MapClientSideCache<>();
    JoinCache<String, Order, String, OrderAddress> joinCaching = new SimpleJoinCaching<>(orderCache, orderAddressCache, new ExtractJoinKey<Order, String>() {
        @Nonnull
        @Override
        public String extract(@Nonnull Order firstValue) {
            return firstValue.getId();
        }
    });

    @Test
    void get() {
        Order order = new Order();
        order.setId("1");
        OrderAddress orderAddress = new OrderAddress();
        orderAddress.setOrderId(order.getId());
        orderCache.setCache(order.getId(), CacheValue.forever(order));
        orderAddressCache.setCache(order.getId(), CacheValue.forever(orderAddress));
        JoinValue<Order, String, OrderAddress> joinValue = joinCaching.get(order.getId());
        Assertions.assertNotNull(joinValue);
        Assertions.assertEquals(order, joinValue.getFirstValue());
        Assertions.assertEquals(order.getId(), joinValue.getJoinKey());
        Assertions.assertEquals(orderAddress, joinValue.getJoinValue());
    }

    public static class Order {
        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    public static class OrderAddress {
        private String orderId;

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }
    }
}
