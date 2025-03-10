package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.CoherentCacheConfiguration
import me.ahoo.cache.CoherentCacheFactory
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.GuavaCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.mock.MockDistributedCache
import org.junit.jupiter.api.BeforeEach

open class AbstractCoCacheEndpointTest {
    companion object {
        const val CACHE_NAME = "cacheName"
    }

    lateinit var cacheManager: CoherentCacheFactory

    @BeforeEach
    open fun setup() {
        val keyConverter = ToStringKeyConverter<String>("")
        val clientSideCaching = MapClientSideCache<String>()
        val distributedCaching = MockDistributedCache<String>()
        cacheManager = CoherentCacheFactory(GuavaCacheEvictedEventBus())
        cacheManager.getOrCreateCache(
            CoherentCacheConfiguration(
                cacheName = CACHE_NAME,
                clientId = "currentClientId",
                keyConverter = keyConverter,
                distributedCache = distributedCaching,
                clientSideCache = clientSideCaching,
            ),
        )
    }
}
