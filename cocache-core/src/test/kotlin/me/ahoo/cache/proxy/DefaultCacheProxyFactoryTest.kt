/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cache.proxy

import me.ahoo.cache.annotation.CoCacheMetadata
import me.ahoo.cache.annotation.coCacheMetadata
import me.ahoo.cache.api.CacheValue
import me.ahoo.cache.api.distributed.DistributedCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.DefaultClientSideCacheFactory
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.DefaultCoherentCacheFactory
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.DefaultKeyConverterFactory
import me.ahoo.cache.distributed.DistributedCacheFactory
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.cache.source.CacheSourceFactory
import me.ahoo.cache.util.ClientIdGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class DefaultCacheProxyFactoryTest {

    companion object {
        internal fun <CACHE> createProxyCache(
            metadata: CoCacheMetadata = coCacheMetadata<MockCache>(),
            cacheSource: CacheSource<Any, Any> = CacheSource.noOp()
        ): CACHE {
            val distributedCacheFactory = object : DistributedCacheFactory {
                override fun <V> create(cacheMetadata: CoCacheMetadata): DistributedCache<V> {
                    return InMemoryDistributedCache()
                }
            }
            val cacheSourceFactory = object : CacheSourceFactory {
                @Suppress("UNCHECKED_CAST")
                override fun <K, V> create(cacheMetadata: CoCacheMetadata): CacheSource<K, V> {
                    return cacheSource as CacheSource<K, V>
                }
            }
            val cacheProxyFactory = DefaultCacheProxyFactory(
                coherentCacheFactory = DefaultCoherentCacheFactory(NoOpCacheEvictedEventBus),
                clientIdGenerator = ClientIdGenerator.UUID,
                clientSideCacheFactory = DefaultClientSideCacheFactory,
                distributedCacheFactory = distributedCacheFactory,
                cacheSourceFactory = cacheSourceFactory,
                keyConverterFactory = DefaultKeyConverterFactory.INSTANCE
            )
            return cacheProxyFactory.create(metadata)
        }
    }

    @Test
    fun create() {
        val cache = createProxyCache<MockCache>()
        cache["key"].assert().isNull()
        cache.toString().assert().startsWith(MockCache::class.java.simpleName)
        cache.delegate.cacheName.assert().isEqualTo(MockCache::class.java.simpleName)
        (cache as CoherentCache<*, *>).clientId.assert().isEqualTo(cache.delegate.clientId)
        cache.cacheMetadata.assert().isEqualTo(coCacheMetadata<MockCache>())
    }

    @Test
    fun proxyUsesIdentityEquality() {
        val cache = createProxyCache<MockCache>()
        val other = createProxyCache<MockCache>()
        (cache == cache).assert().isTrue()
        (cache == other).assert().isFalse()
        cache.hashCode().assert().isEqualTo(System.identityHashCode(cache))
    }

    @Test
    fun createWithKeyExpression() {
        val cache = createProxyCache<MockCacheWithKeyExpression>(coCacheMetadata<MockCacheWithKeyExpression>())
        cache["key"] = "value"
        cache["key"].assert().isEqualTo("value")
    }

    @Test
    fun defaultMethod() {
        val cache = createProxyCache<MockCacheWithKeyExpression>(coCacheMetadata<MockCacheWithKeyExpression>())
        cache.defaultMethod().assert().isEqualTo("defaultMethod")
    }

    @Test
    fun exceptionFromSourceIsNotWrapped() {
        val failure = IllegalStateException("source down")
        val cache = createProxyCache<MockCache>(cacheSource = CacheSource { throw failure })
        runCatching { cache["key"] }.exceptionOrNull().assert().isSameAs(failure)
    }

    @Test
    fun loadsFromSource() {
        val cache = createProxyCache<MockCache>(cacheSource = CacheSource { CacheValue.forever("value") })
        cache["key"].assert().isEqualTo("value")
    }
}
