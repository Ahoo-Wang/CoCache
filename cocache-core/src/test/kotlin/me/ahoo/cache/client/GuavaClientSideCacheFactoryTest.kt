package me.ahoo.cache.client

import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.proxy.MockCache
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class GuavaClientSideCacheFactoryTest {

    @Test
    fun create() {
        val clientSideCache = GuavaClientSideCacheFactory.create<Any>(coCacheMetadata<MockCache>())
        assertThat(clientSideCache, instanceOf(GuavaClientSideCache::class.java))
    }
}
