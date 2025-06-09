package me.ahoo.cache.annotation

import me.ahoo.cache.api.annotation.JoinCacheable
import me.ahoo.cache.api.join.JoinCache
import me.ahoo.cache.join.MockJoinCache
import me.ahoo.cache.join.Order
import me.ahoo.cache.join.OrderAddress
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class JoinCacheMetadataParserTest {
    @Test
    fun parse() {
        val metadata = joinCacheMetadata<MockJoinCache>()
        metadata.proxyInterface.assert().isEqualTo(MockJoinCache::class)
        metadata.name.assert().isEqualTo("")
        metadata.cacheName.assert().isEqualTo("MockJoinCache")
        metadata.firstCacheName.assert().isEqualTo("OrderAddress")
        metadata.joinCacheName.assert().isEqualTo("Order")
        metadata.joinKeyExpression.assert().isEqualTo("")
        metadata.firstKeyType.classifier.assert().isEqualTo(String::class)
        metadata.firstValueType.classifier.assert().isEqualTo(OrderAddress::class)
        metadata.joinKeyType.classifier.assert().isEqualTo(String::class)
        metadata.joinValueType.classifier.assert().isEqualTo(Order::class)
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
        metadata.proxyInterface.assert().isEqualTo(NotJoinAnnotation::class)
        metadata.name.assert().isEqualTo("")
        metadata.cacheName.assert().isEqualTo("NotJoinAnnotation")
        metadata.firstCacheName.assert().isEqualTo("")
        metadata.joinCacheName.assert().isEqualTo("")
        metadata.joinKeyExpression.assert().isEqualTo("")
        metadata.firstKeyType.classifier.assert().isEqualTo(String::class)
        metadata.firstValueType.classifier.assert().isEqualTo(String::class)
        metadata.joinKeyType.classifier.assert().isEqualTo(String::class)
        metadata.joinValueType.classifier.assert().isEqualTo(String::class)
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
