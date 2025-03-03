package me.ahoo.cache.client

import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.proxy.MockCache
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class DefaultClientSideCacheFactoryTest {

    @Test
    fun create() {
        val clientSideCache = DefaultClientSideCacheFactory.create<Any>(coCacheMetadata<MockCache>())
        assertThat(clientSideCache, instanceOf(MapClientSideCache::class.java))
    }

    @Test
    fun createIfDefaultGuavaCache() {
        val clientSideCache = DefaultClientSideCacheFactory.create<Any>(coCacheMetadata<MockDefaultGuavaClientCache>())
        assertThat(clientSideCache, instanceOf(GuavaClientSideCache::class.java))
    }

    @Test
    fun createIfCustomizeGuavaCache() {
        val clientSideCache =
            DefaultClientSideCacheFactory.create<Any>(coCacheMetadata<MockCustomizeGuavaClientCache>())
        assertThat(clientSideCache, instanceOf(GuavaClientSideCache::class.java))
    }
}
