package me.ahoo.cache.annotation

import me.ahoo.cache.ComputedCache
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.proxy.MockCacheWithKeyExpression
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class CoCacheMetadataParserTest {

    @Test
    fun parse() {
        val metadata = coCacheMetadata<MockCache>()
        assertThat(metadata.type, equalTo(MockCache::class))
        assertThat(metadata.name, equalTo(""))
        assertThat(metadata.keyPrefix, equalTo(""))
        assertThat(metadata.valueType, equalTo(CoCacheMetadataParserTest::class))
    }

    @Test
    fun parseWithKeyExp() {
        val metadata = coCacheMetadata<MockCacheWithKeyExpression>()
        assertThat(metadata.type, equalTo(MockCacheWithKeyExpression::class))
        assertThat(metadata.name, equalTo(""))
        assertThat(metadata.keyPrefix, equalTo("prefix:"))
        assertThat(metadata.keyExpression, equalTo("#{#root}"))
        assertThat(metadata.valueType, equalTo(String::class))
    }

    @Test
    fun parseIfNotInterface() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            coCacheMetadata<NotInterface>()
        }
    }

    @Test
    fun parseIfNotSubclassOfCache() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            coCacheMetadata<NotSubclassOfCache>()
        }
    }

    @Test
    fun parseIfNotCacheAnnotation() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            coCacheMetadata<NotCacheAnnotation>()
        }
    }

    @CoCache
    interface MockCache : Cache<String, CoCacheMetadataParserTest>

    class NotInterface

    interface NotSubclassOfCache

    interface NotCacheAnnotation : ComputedCache<String, CoCacheMetadataParserTest>
}
