package me.ahoo.cache.proxy

import me.ahoo.cache.CacheManager
import me.ahoo.cache.CacheSource
import me.ahoo.cache.CoherentCache
import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.util.ClientIdGenerator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefaultCacheProxyFactoryTest {

    @Test
    fun create() {
        val distributedCacheFactory = object : DistributedCacheFactory {
            override fun <V> create(cacheMetadata: CoCacheMetadata): DistributedCache<V> {
                return MockDistributedCache()
            }
        }
        val cacheSourceResolver = object : CacheSourceResolver {
            override fun <V> resolve(cacheMetadata: CoCacheMetadata): CacheSource<String, V> {
                return CacheSource.noOp()
            }
        }
        val cacheProxyFactory = DefaultCacheProxyFactory(
            cacheManager = CacheManager(NoOpCacheEvictedEventBus),
            clientIdGenerator = ClientIdGenerator.UUID,
            distributedCacheFactory = distributedCacheFactory,
            cacheSourceResolver = cacheSourceResolver
        )
        val cache = cacheProxyFactory.create<MockCache>(coCacheMetadata<MockCache>())
        assertNull(cache.getCache("key"))
        assertTrue(cache.toString().startsWith(CoherentCache::class.java.name))
    }
}
