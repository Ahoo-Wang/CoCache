package me.ahoo.cache.annotation

import me.ahoo.cache.Cache
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
        assertThat(metadata.prefix, equalTo(""))
        assertThat(metadata.valueType, equalTo(CoCacheMetadataParserTest::class))
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

    interface NotCacheAnnotation : Cache<String, CoCacheMetadataParserTest>
}
