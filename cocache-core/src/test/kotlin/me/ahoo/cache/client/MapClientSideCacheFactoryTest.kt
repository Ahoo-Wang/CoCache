package me.ahoo.cache.client

import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.proxy.MockCache
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class MapClientSideCacheFactoryTest {

    @Test
    fun create() {
        val clientSideCache = MapClientSideCacheFactory.create<Any>(coCacheMetadata<MockCache>())
        assertThat(clientSideCache, instanceOf(MapClientSideCache::class.java))
    }
}
