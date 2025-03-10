package me.ahoo.cache.join

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ExpJoinKeyExtractorTest {

    @Test
    fun extract() {
        val joinKeyExtractor = ExpJoinKeyExtractor<OrderAddress>("#{#root.orderId}")
        val orderAddress = OrderAddress(UUID.randomUUID().toString())
        val joinKey = joinKeyExtractor.extract(orderAddress)
        assertEquals(orderAddress.orderId, joinKey)
    }
}
