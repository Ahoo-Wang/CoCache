package me.ahoo.cache.proxy

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.DefaultClientSideCacheFactory
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.DefaultKeyConverterFactory
import me.ahoo.cache.distributed.DistributedCache
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.distributed.mock.MockDistributedCache
import me.ahoo.cache.source.CacheSourceFactory
import me.ahoo.cache.util.ClientIdGenerator
import me.ahoo.test.asserts.assert
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
                coherentCacheFactory = DefaultCoherentCacheFactory(NoOpCacheEvictedEventBus),
                clientIdGenerator = ClientIdGenerator.UUID,
                clientSideCacheFactory = DefaultClientSideCacheFactory,
                distributedCacheFactory = distributedCacheFactory,
                cacheSourceFactory = cacheSourceFactory,
                keyConverterFactory = DefaultKeyConverterFactory
            )
            return cacheProxyFactory.create(metadata)
        }
    }

    @Test
    fun create() {
        val cache = createProxyCache<MockCache>()
        cache.getCache("key").assert().isNull()
        cache.toString().assert().startsWith(CoherentCache::class.java.simpleName)
        cache.delegate.cacheName.assert().isEqualTo(MockCache::class.java.simpleName)
        cache.clientId.assert().isEqualTo(cache.delegate.clientId)
        cache.cacheMetadata.assert().isEqualTo(coCacheMetadata<MockCache>())
    }

    @Test
    fun createWithKeyExpression() {
        val cache =
            createProxyCache<MockCacheWithKeyExpression>(
                metadata = coCacheMetadata<MockCacheWithKeyExpression>().copy(
                    keyExpression = "key"
                )
            )
        cache.getCache("key").assert().isNull()
    }

    @Test
    fun defaultMethod() {
        val cache =
            createProxyCache<MockCacheWithKeyExpression>(
                metadata = coCacheMetadata<MockCacheWithKeyExpression>().copy(
                    keyExpression = "key"
                )
            )
        cache.defaultMethod().assert().isEqualTo("defaultMethod")
    }
}
