package me.ahoo.cache.annotation

import me.ahoo.cache.ComputedCache
import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.CoCache
import me.ahoo.cache.proxy.MockCacheWithKeyExpression
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class CoCacheMetadataParserTest {

    @Test
    fun parse() {
        val metadata = coCacheMetadata<MockCache>()
        assertThat(metadata.proxyInterface, equalTo(MockCache::class))
        assertThat(metadata.name, equalTo(""))
        assertThat(metadata.keyPrefix, equalTo(""))
        assertThat(metadata.keyType, equalTo(String::class))
        assertThat(metadata.valueType, equalTo(CoCacheMetadataParserTest::class))
    }

    @Test
    fun parseComputedCache() {
        val metadata = coCacheMetadata<MockComputedCache>()
        assertThat(metadata.proxyInterface, equalTo(MockComputedCache::class))
        assertThat(metadata.name, equalTo(""))
        assertThat(metadata.keyPrefix, equalTo(""))
        assertThat(metadata.keyType, equalTo(String::class))
        assertThat(metadata.valueType, equalTo(CoCacheMetadataParserTest::class))
    }

    @Test
    fun parseWithKeyExp() {
        val metadata = coCacheMetadata<MockCacheWithKeyExpression>()
        assertThat(metadata.proxyInterface, equalTo(MockCacheWithKeyExpression::class))
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

    @CoCache
    interface MockCache : Cache<String, CoCacheMetadataParserTest>

    @CoCache
    interface MockComputedCache : ComputedCache<String, CoCacheMetadataParserTest>
    abstract class NotInterface : Cache<String, CoCacheMetadataParserTest>
}
