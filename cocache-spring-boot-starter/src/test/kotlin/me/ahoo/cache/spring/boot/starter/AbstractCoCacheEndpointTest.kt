package me.ahoo.cache.spring.boot.starter

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.CacheFactory
import me.ahoo.cache.api.Cache
import me.ahoo.cache.client.MapClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.mock.MockDistributedCache
import org.junit.jupiter.api.BeforeEach

open class AbstractCoCacheEndpointTest {
    companion object {
        const val CACHE_NAME = "cacheName"
        const val NOT_FOUND = "NotFound"
    }

    lateinit var cacheFactory: CacheFactory

    @BeforeEach
    open fun setup() {
        val clientSideCaching = MapClientSideCache<String>()
        val distributedCaching = MockDistributedCache<String>()
        val mockCache = DefaultCoherentCache<String, String>(
            CoherentCacheConfiguration(
                CACHE_NAME,
                "clientId",
                ToStringKeyConverter("keyPrefix"),
                distributedCaching,
                clientSideCaching
            ),
            NoOpCacheEvictedEventBus
        )

        cacheFactory = mockk {
            every {
                caches
            } returns mapOf(
                CACHE_NAME to mockCache
            )
            every {
                getCache<Cache<String, String>>(CACHE_NAME)
            } returns mockCache
            every {
                getCache<Cache<String, String>>(CACHE_NAME)
            } returns mockCache
            every {
                getCache<CoherentCache<String, String>>(CACHE_NAME, CoherentCache::class.java)
            } returns mockCache
            every {
                getCache<CoherentCache<String, String>>(NOT_FOUND, any())
            } returns null
        }
    }
}
