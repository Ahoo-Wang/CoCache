package me.ahoo.cache.spring.boot.starter

import me.ahoo.cache.CacheConfig
import me.ahoo.cache.CacheManager
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.GuavaCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.mock.MockDistributedCache
import org.junit.jupiter.api.BeforeEach

open class AbstractCoCacheEndpointTest {
    companion object {
        const val CACHE_NAME = "cacheName"
    }

    lateinit var cacheManager: CacheManager

    @BeforeEach
    open fun setup() {
        val keyConverter = ToStringKeyConverter<String>("")
        val clientSideCaching = MapClientSideCache<String>()
        val distributedCaching = MockDistributedCache<String>()
        cacheManager = CacheManager(GuavaCacheEvictedEventBus())
        cacheManager.getOrCreateCache(
            CacheConfig(
                cacheName = CACHE_NAME,
                clientId = "currentClientId",
                keyConverter = keyConverter,
                distributedCaching = distributedCaching,
                clientSideCaching = clientSideCaching,
            ),
        )
    }
}
