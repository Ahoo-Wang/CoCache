package me.ahoo.cache.proxy

import me.ahoo.cache.CacheManager
import me.ahoo.cache.CoherentCache
import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.DefaultClientSideCacheFactory
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
        internal fun <CACHE> createProxyCache(metadata: CoCacheMetadata = coCacheMetadata<MockCache>()): CACHE {
            val distributedCacheFactory = object : DistributedCacheFactory {
                override fun <V> create(cacheMetadata: CoCacheMetadata): DistributedCache<V> {
                    return MockDistributedCache()
                }
            }
            val cacheSourceFactory = object : CacheSourceFactory {
                override fun <K, V> create(cacheMetadata: CoCacheMetadata): CacheSource<K, V> {
                    return CacheSource.noOp()
                }
            }
            val cacheProxyFactory = DefaultCacheProxyFactory(
                cacheManager = CacheManager(NoOpCacheEvictedEventBus),
                clientIdGenerator = ClientIdGenerator.UUID,
                clientSideCacheFactory = DefaultClientSideCacheFactory,
                distributedCacheFactory = distributedCacheFactory,
                cacheSourceFactory = cacheSourceFactory
            )
            return cacheProxyFactory.create(metadata)
        }
    }

    @Test
    fun create() {
        val cache = createProxyCache<MockCache>()
        assertNull(cache.getCache("key"))
        assertTrue(cache.toString().startsWith(CoherentCache::class.java.simpleName))
        assertEquals(cache.delegate.cacheName, MockCache::class.java.simpleName)
        assertEquals(cache.clientId, cache.delegate.clientId)
        assertEquals(cache.cacheMetadata, coCacheMetadata<MockCache>())
    }

    @Test
    fun createWithKeyExpression() {
        val cache =
            createProxyCache<MockCacheWithKeyExpression>(
                metadata = coCacheMetadata<MockCacheWithKeyExpression>().copy(
                    keyExpression = "key"
                )
            )
        assertNull(cache.getCache("key"))
    }
}
