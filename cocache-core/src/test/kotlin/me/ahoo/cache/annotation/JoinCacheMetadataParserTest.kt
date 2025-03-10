package me.ahoo.cache.annotation

import me.ahoo.cache.join.MockJoinCache
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class JoinCacheMetadataParserTest {
    @Test
    fun parse() {
        val metadata = joinCacheMetadata<MockJoinCache>()
        assertThat(metadata.type, equalTo(MockJoinCache::class))
        assertThat(metadata.name, equalTo(""))
    }
}
