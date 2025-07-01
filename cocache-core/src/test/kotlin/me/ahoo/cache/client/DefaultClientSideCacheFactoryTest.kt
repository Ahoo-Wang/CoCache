package me.ahoo.cache.client

import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.proxy.MockCache
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class DefaultClientSideCacheFactoryTest {

    @Test
    fun create() {
        val clientSideCache = DefaultClientSideCacheFactory.create<Any>(coCacheMetadata<MockCache>())
        clientSideCache.assert().isInstanceOf(MapClientSideCache::class.java)
    }

    @Test
    fun createIfDefaultGuavaCache() {
        val clientSideCache = DefaultClientSideCacheFactory.create<Any>(coCacheMetadata<MockDefaultGuavaClientCache>())
        clientSideCache.assert().isInstanceOf(GuavaClientSideCache::class.java)
    }

    @Test
    fun createIfCustomizeGuavaCache() {
        val clientSideCache =
            DefaultClientSideCacheFactory.create<Any>(coCacheMetadata<MockCustomizeGuavaClientCache>())
        clientSideCache.assert().isInstanceOf(GuavaClientSideCache::class.java)
    }

    @Test
    fun createIfDefaultCaffeineCache() {
        val clientSideCache =
            DefaultClientSideCacheFactory.create<Any>(coCacheMetadata<MockDefaultCaffeineClientCache>())
        clientSideCache.assert().isInstanceOf(CaffeineClientSideCache::class.java)
    }

    @Test
    fun createIfCustomizeCaffeineCache() {
        val clientSideCache =
            DefaultClientSideCacheFactory.create<Any>(coCacheMetadata<MockCustomizeCaffeineClientCache>())
        clientSideCache.assert().isInstanceOf(CaffeineClientSideCache::class.java)
    }
}
