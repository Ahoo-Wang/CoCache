package me.ahoo.cache.annotation

import me.ahoo.cache.api.annotation.JoinCacheable
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.join.MockJoinCache
import me.ahoo.cache.join.Order
import me.ahoo.cache.join.OrderAddress
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Assertions
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

    @Test
    fun parseIfNotInterface() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            joinCacheMetadata<NotJoinInterface>()
        }
    }

    @Test
    fun parseIfNotAnnotation() {
        val metadata = joinCacheMetadata<NotJoinAnnotation>()
        assertThat(metadata.proxyInterface, equalTo(NotJoinAnnotation::class))
        assertThat(metadata.name, equalTo(""))
        assertThat(metadata.cacheName, equalTo("NotJoinAnnotation"))
        assertThat(metadata.firstCacheName, equalTo(""))
        assertThat(metadata.joinCacheName, equalTo(""))
        assertThat(metadata.joinKeyExpression, equalTo(""))
        assertThat(metadata.firstKeyType, equalTo(String::class))
        assertThat(metadata.firstValueType, equalTo(String::class))
        assertThat(metadata.joinKeyType, equalTo(String::class))
        assertThat(metadata.joinValueType, equalTo(String::class))
    }

    @Test
    fun parseIfExpNotJoinStringKey() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            joinCacheMetadata<ExpNotJoinStringKey>()
        }
    }
}

abstract class NotJoinInterface : JoinCache<String, String, String, String>
interface NotJoinAnnotation : JoinCache<String, String, String, String>

@JoinCacheable(joinKeyExpression = "#{#root}")
interface ExpNotJoinStringKey : JoinCache<String, String, JoinCacheMetadataParserTest, String>
