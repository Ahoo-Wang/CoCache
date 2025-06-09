package me.ahoo.cache.annotation

import me.ahoo.cache.ComputedCache
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.proxy.MockCacheWithKeyExpression
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class CoCacheMetadataParserTest {

    @Test
    fun parse() {
        val metadata = coCacheMetadata<MockCache>()
        metadata.proxyInterface.assert().isEqualTo(MockCache::class)
        metadata.name.assert().isEqualTo("")
        metadata.keyPrefix.assert().isEqualTo("")
        metadata.keyType.classifier.assert().isEqualTo(String::class)
        metadata.valueType.classifier.assert().isEqualTo(CoCacheMetadataParserTest::class)
    }

    @Test
    fun parseComputedCache() {
        val metadata = coCacheMetadata<MockComputedCache>()
        metadata.proxyInterface.assert().isEqualTo(MockComputedCache::class)
        metadata.name.assert().isEqualTo("")
        metadata.keyPrefix.assert().isEqualTo("")
        metadata.keyType.classifier.assert().isEqualTo(String::class)
        metadata.valueType.classifier.assert().isEqualTo(CoCacheMetadataParserTest::class)
    }

    @Test
    fun parseWithKeyExp() {
        val metadata = coCacheMetadata<MockCacheWithKeyExpression>()
        metadata.proxyInterface.assert().isEqualTo(MockCacheWithKeyExpression::class)
        metadata.keyType.classifier.assert().isEqualTo(String::class)
        metadata.valueType.classifier.assert().isEqualTo(String::class)
        metadata.keyExpression.assert().isEqualTo("#{#root}")
        metadata.keyPrefix.assert().isEqualTo("prefix:")
    }

    @Test
    fun parseIfNotInterface() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            coCacheMetadata<NotInterface>()
        }
    }

    @Test
    fun parseGenericValueCache() {
        val metadata = coCacheMetadata<MockGenericValueCache>()
        metadata.proxyInterface.assert().isEqualTo(MockGenericValueCache::class)
        metadata.name.assert().isEqualTo("")
        metadata.keyPrefix.assert().isEqualTo("")
        metadata.keyType.classifier.assert().isEqualTo(String::class)
        metadata.valueType.classifier.assert().isEqualTo(List::class)
        metadata.valueType.arguments[0].type!!.classifier.assert().isEqualTo(CoCacheMetadataParserTest::class)
    }

    @CoCache
    interface MockCache : Cache<String, CoCacheMetadataParserTest>

    @CoCache
    interface MockGenericValueCache : Cache<String, List<CoCacheMetadataParserTest>>

    @CoCache
    interface MockComputedCache : ComputedCache<String, CoCacheMetadataParserTest>
    abstract class NotInterface : Cache<String, CoCacheMetadataParserTest>
}
