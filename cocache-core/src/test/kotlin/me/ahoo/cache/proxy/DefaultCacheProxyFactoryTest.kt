package me.ahoo.cache.proxy

import me.ahoo.cache.CacheManager
import me.ahoo.cache.CoherentCache
import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.MapClientSideCacheFactory
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.source.CacheSourceFactory
import me.ahoo.cache.util.ClientIdGenerator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefaultCacheProxyFactoryTest {

    companion object {
        internal fun createProxyCache(): MockCache {
            val distributedCacheFactory = object : DistributedCacheFactory {
                override fun <V> create(cacheMetadata: CoCacheMetadata): DistributedCache<V> {
                    return MockDistributedCache()
                }
            }
            val cacheSourceFactory = object : CacheSourceFactory {
                override fun <V> create(cacheMetadata: CoCacheMetadata): CacheSource<String, V> {
                    return CacheSource.noOp()
                }
            }
            val cacheProxyFactory = DefaultCacheProxyFactory(
                cacheManager = CacheManager(NoOpCacheEvictedEventBus),
                clientIdGenerator = ClientIdGenerator.UUID,
                clientSideCacheFactory = MapClientSideCacheFactory,
                distributedCacheFactory = distributedCacheFactory,
                cacheSourceFactory = cacheSourceFactory
            )
            return cacheProxyFactory.create(coCacheMetadata<MockCache>())
        }
    }

    @Test
    fun create() {
        val cache = createProxyCache()
        assertNull(cache.getCache("key"))
        assertTrue(cache.toString().startsWith(CoherentCache::class.java.name))
    }
}
