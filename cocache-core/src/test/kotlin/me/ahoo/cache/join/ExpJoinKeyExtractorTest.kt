package me.ahoo.cache.join

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.UUID

class ExpJoinKeyExtractorTest {

    @Test
    fun extract() {
        val joinKeyExtractor = ExpJoinKeyExtractor<OrderAddress>("#{#root.orderId}")
        val orderAddress = OrderAddress(UUID.randomUUID().toString())
        val joinKey = joinKeyExtractor.extract(orderAddress)
        orderAddress.orderId.assert().isEqualTo(joinKey)
    }
}
