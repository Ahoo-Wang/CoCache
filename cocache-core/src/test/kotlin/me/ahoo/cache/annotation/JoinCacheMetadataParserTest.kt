package me.ahoo.cache.annotation

import me.ahoo.cache.join.MockJoinCache
import me.ahoo.cache.join.Order
import me.ahoo.cache.join.OrderAddress
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class JoinCacheMetadataParserTest {
    @Test
    fun parse() {
        val metadata = joinCacheMetadata<MockJoinCache>()
        assertThat(metadata.proxyInterface, equalTo(MockJoinCache::class))
        assertThat(metadata.name, equalTo(""))
        assertThat(metadata.cacheName, equalTo("MockJoinCache"))
        assertThat(metadata.firstCacheName, equalTo("OrderAddress"))
        assertThat(metadata.joinCacheName, equalTo("Order"))
        assertThat(metadata.joinKeyExpression, equalTo(""))
        assertThat(metadata.firstKeyType, equalTo(String::class))
        assertThat(metadata.firstValueType, equalTo(OrderAddress::class))
        assertThat(metadata.joinKeyType, equalTo(String::class))
        assertThat(metadata.joinValueType, equalTo(Order::class))
    }
}
